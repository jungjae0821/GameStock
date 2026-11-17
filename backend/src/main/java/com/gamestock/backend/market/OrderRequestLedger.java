package com.gamestock.backend.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * 웹·앱 주문의 Idempotency-Key 장부.
 *
 * 응답이 늦어 화면이 요청을 포기해도 서버는 그 주문을 계속 처리한다. 사용자가 다시 누르면
 * 같은 주문이 한 번 더 들어가므로, 화면은 같은 주문을 다시 보낼 때 같은 키를 붙이고 서버는 그 키를
 * 한 번만 실행한다. 매칭 엔진은 사람 주문을 묶음 트랜잭션으로 처리하므로 엔진 안이 아니라 주문
 * 접수 앞에서 키를 먼저 잡는다.
 *
 * - 처음 보는 키: 행을 만들고 주문을 넘긴다. 성공하면 결과를 저장한다.
 * - 거절이 확실한 실패(입력 오류·잔고 부족 등): 행을 지워 같은 키로 다시 보낼 수 있게 한다.
 * - 결과를 모르는 실패(서버 과부하 등): 행을 남긴다. 같은 키는 409를 받고, 화면은 새 키로 다시 주문한다.
 * - 이미 저장된 키: 주문을 다시 실행하지 않고 저장한 결과를 돌려준다.
 *
 * 키가 없는 요청(예전 앱)은 지금처럼 바로 처리한다.
 */
@Service
public class OrderRequestLedger {
    private static final Logger log = LoggerFactory.getLogger(OrderRequestLedger.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderRequestLedger(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 시작 시 마이그레이션 잠금 안에서 부른다. */
    static void ensureTable(JdbcTemplate jdbc) {
        // users 외래 키를 두지 않는다. 주문 접수가 종목 잠금보다 먼저 회원 행을 잠그면 안 된다.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS order_requests (
                  user_id BIGINT NOT NULL,
                  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  payload MEDIUMTEXT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (user_id, request_id),
                  INDEX ix_order_requests_created (created_at)
                )
                """);
    }

    public CompletableFuture<Object> submit(long userId, String requestId, OrderRequest request,
                                            Supplier<CompletableFuture<OrderResult>> action) {
        if (requestId == null || requestId.isBlank()) return action.get().thenApply(result -> result);
        validateRequestId(requestId);
        String hash = sha256(encode(request));
        int inserted = jdbc.update("INSERT IGNORE INTO order_requests (user_id, request_id, request_hash) VALUES (?, ?, ?)",
                userId, requestId, hash);
        if (inserted == 0) return CompletableFuture.completedFuture(replay(userId, requestId, hash));

        CompletableFuture<OrderResult> future;
        try {
            future = action.get();
        } catch (RuntimeException error) {
            if (definitive(error)) forget(userId, requestId);
            throw error;
        }
        // 결과 기록은 주문 큐 스레드를 붙잡지 않게 따로 돈다.
        future.whenCompleteAsync((result, error) -> {
            if (error == null) remember(userId, requestId, result);
            else if (definitive(error)) forget(userId, requestId);
        });
        return future.thenApply(result -> result);
    }

    private Object replay(long userId, String requestId, String hash) {
        List<Stored> rows = jdbc.query("SELECT request_hash, payload FROM order_requests WHERE user_id = ? AND request_id = ?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), userId, requestId);
        if (rows.isEmpty() || rows.get(0).payload() == null) {
            if (!rows.isEmpty() && !hash.equals(rows.get(0).hash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 주문 요청 번호의 내용이 다릅니다.");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "같은 주문을 이미 처리하고 있습니다. 주문 내역을 확인한 뒤 다시 주문해 주세요.");
        }
        Stored stored = rows.get(0);
        if (!hash.equals(stored.hash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 주문 요청 번호의 내용이 다릅니다.");
        try {
            return json.readTree(stored.payload());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode stored order result", error);
        }
    }

    private void remember(long userId, String requestId, OrderResult result) {
        try {
            jdbc.update("UPDATE order_requests SET payload = ? WHERE user_id = ? AND request_id = ?",
                    encode(result), userId, requestId);
        } catch (RuntimeException error) {
            // 기록하지 못해도 행은 남아 같은 키의 재실행을 막는다.
            log.warn("Order receipt not stored ({})", error.getClass().getSimpleName());
        }
    }

    private void forget(long userId, String requestId) {
        try {
            jdbc.update("DELETE FROM order_requests WHERE user_id = ? AND request_id = ? AND payload IS NULL", userId, requestId);
        } catch (RuntimeException error) {
            log.warn("Order request key not released ({})", error.getClass().getSimpleName());
        }
    }

    /** 주문이 실행되지 않았다고 확신할 수 있는 실패. 그 밖의 실패는 체결됐을 수도 있다고 본다. */
    static boolean definitive(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof IllegalArgumentException || cause instanceof ResponseStatusException;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void prune() {
        try {
            jdbc.update("DELETE FROM order_requests WHERE created_at < CURRENT_TIMESTAMP - INTERVAL 1 DAY ORDER BY created_at LIMIT 1000");
        } catch (DataAccessException error) {
            log.warn("Order request cleanup deferred ({})", error.getClass().getSimpleName());
        }
    }

    static void validateRequestId(String requestId) {
        if (!requestId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Idempotency-Key는 영문·숫자·점·밑줄·하이픈 1~64자로 지정해 주세요.");
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode order request", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Stored(String hash, String payload) { }
}
