package com.gamestock.backend.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.market.DerivativeModels;
import com.gamestock.backend.market.DerivativeService;
import com.gamestock.backend.market.MarketModels;
import com.gamestock.backend.market.MarketService;
import com.gamestock.backend.market.PersistenceWorker;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.gamestock.backend.openapi.OpenApiModels.*;

/**
 * The receipt, balance changes and matching/position writes commit in one transaction.
 * The primary-key insert serializes concurrent retries across processes, not just threads.
 * Work goes through the existing bounded persistence pool and the existing trading engines.
 */
@Service
public class OpenApiTradingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PersistenceWorker persistence;
    private final OpenApiKeyService keys;
    private final MarketService market;
    private final DerivativeService derivatives;
    private com.gamestock.backend.market.FeatureUnlockService unlocks;

    /** Optional so tests that build this class by hand keep working without the unlock gate. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureUnlocks(com.gamestock.backend.market.FeatureUnlockService unlocks) { this.unlocks = unlocks; }

    private com.gamestock.backend.market.CareerService career;

    /** Optional for the same reason: hand-built tests run without the career's loss limits. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureCareer(com.gamestock.backend.market.CareerService career) { this.career = career; }

    public OpenApiTradingService(JdbcTemplate jdbc, ObjectMapper json, PersistenceWorker persistence,
                                 OpenApiKeyService keys, MarketService market, DerivativeService derivatives) {
        this.jdbc = jdbc; this.json = json; this.persistence = persistence; this.keys = keys;
        this.market = market; this.derivatives = derivatives;
    }

    public CompletableFuture<Receipt> order(Principal principal, String requestId, Order request) {
        request.validate();
        // Opening futures or options needs the same unlock as the web; closing never does.
        boolean closingOption = request.product() == Product.OPTION && request.side() == Side.SELL;
        if (unlocks != null && !closingOption) unlocks.requireForDerivative(principal.userId(), request.product().name());
        // Buying stock or an ETF, opening a future either way and buying an option add risk; sells and closes do not.
        boolean addsRisk = request.product() == Product.FUTURE || request.side() == Side.BUY;
        if (career != null && addsRisk) {
            if (request.product() == Product.STOCK)
                career.requireNewRisk(principal.userId(), request.symbol(), request.quantity(),
                        request.effectiveOrderType() == OrderType.LIMIT ? request.price() : null);
            else career.requireNewRisk(principal.userId());
        }
        return execute(principal, requestId, "ORDER", request, () -> {
            if (request.product() == Product.STOCK)
                return market.order(new MarketModels.OrderRequest(request.symbol(), request.side().name(),
                        request.quantity(), request.effectiveOrderType().name(), request.price()), principal.userId());
            if (request.product() == Product.OPTION && request.side() == Side.SELL) {
                requirePosition(principal.userId(), request.positionId(), "OPTION");
                return derivatives.close(principal.userId(), request.positionId(), request.quantity());
            }
            String side = switch (request.product()) {
                case FUTURE -> request.side() == Side.BUY ? "LONG" : "SHORT";
                case OPTION -> request.optionType().name();
                default -> request.side().name();
            };
            return derivatives.open(principal.userId(), new DerivativeModels.OrderRequest(request.product().name(),
                    side, request.quantity(), request.symbol(), request.leverage(),
                    request.cycleId(), request.strike(), request.expiryEpoch()));
        });
    }

    public CompletableFuture<Receipt> close(Principal principal, String requestId, long positionId, Close request) {
        if (positionId <= 0 || request.quantity() < 1 || request.quantity() > 100)
            throw new IllegalArgumentException("포지션 번호와 수량(1~100)을 확인해 주세요.");
        return execute(principal, requestId, "CLOSE:" + positionId, request, () -> {
            requirePosition(principal.userId(), positionId, null);
            return derivatives.close(principal.userId(), positionId, request.quantity());
        });
    }

    public CompletableFuture<Receipt> cancel(Principal principal, String requestId, long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("주문 번호를 확인해 주세요.");
        return execute(principal, requestId, "CANCEL:" + orderId, null, () -> {
            market.requireMatchingOwner();
            market.cancelOrder(orderId, principal.userId());
            return new Cancellation(orderId, "CANCELLED");
        });
    }

    private void requirePosition(long userId, long positionId, String kind) {
        var rows = jdbc.query("SELECT kind FROM derivative_positions WHERE id = ? AND user_id = ? AND status = 'OPEN'",
                (rs, row) -> rs.getString(1), positionId, userId);
        if (rows.isEmpty() || (kind != null && !kind.equals(rows.get(0))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보유 포지션을 찾을 수 없습니다.");
    }

    private CompletableFuture<Receipt> execute(Principal principal, String requestId, String operation,
                                               Object request, Supplier<Object> action) {
        validateRequestId(requestId);
        String fingerprint = OpenApiKeyService.hash(operation + "\n" + encode(request));
        // A fresh internal batch ID avoids the shorter internal receipt retention
        // affecting this API's seven-day idempotency contract.
        return persistence.submit(UUID.randomUUID().toString(), Receipt.class, 1, () -> {
            keys.requireTrading(principal);
            int inserted = jdbc.update("""
                    INSERT IGNORE INTO open_api_requests (user_id, request_id, request_hash, payload)
                    VALUES (?, ?, ?, '')
                    """, principal.userId(), requestId, fingerprint);
            if (inserted == 0) {
                Stored stored = stored(principal.userId(), requestId);
                if (!fingerprint.equals(stored.hash()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 Idempotency-Key의 요청 내용이 다릅니다.");
                return decode(stored.payload());
            }
            Object result = action.get();
            if (result instanceof DerivativeModels.OrderResult derivative)
                result = new DerivativeModels.OrderResult(derivative.message(), OpenApiAccounts.account(derivative.account()));
            Receipt receipt = new Receipt(requestId, operation, json.valueToTree(result));
            jdbc.update("UPDATE open_api_requests SET payload = ? WHERE user_id = ? AND request_id = ?",
                    encode(receipt), principal.userId(), requestId);
            return receipt;
        });
    }

    public Receipt receipt(long userId, String requestId) {
        validateRequestId(requestId);
        return decode(stored(userId, requestId).payload());
    }

    private Stored stored(long userId, String requestId) {
        return jdbc.query("SELECT request_hash, payload FROM open_api_requests WHERE user_id = ? AND request_id = ?",
                        (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), userId, requestId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "완료된 요청을 찾을 수 없습니다. 처리 중인 요청은 같은 키와 본문으로 재시도할 수 있습니다."));
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Idempotency-Key는 영문·숫자·점·밑줄·하이픈 1~64자로 지정해 주세요.");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode API receipt", error); }
    }
    private Receipt decode(String value) {
        try { return json.readValue(value, Receipt.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot decode API receipt", error); }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void pruneReceipts() {
        try {
            jdbc.update("DELETE FROM open_api_requests WHERE created_at < CURRENT_TIMESTAMP - INTERVAL 7 DAY ORDER BY created_at LIMIT 1000");
        } catch (org.springframework.dao.DataAccessException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("OpenAPI receipt cleanup deferred ({})", error.getClass().getSimpleName());
        }
    }
    private record Stored(String hash, String payload) { }
}
