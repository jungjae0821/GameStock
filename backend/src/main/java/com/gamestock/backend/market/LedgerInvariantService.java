package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 중 장부가 깨졌는지 주기적으로 확인한다. 감사 때 로컬에서만 쟀던 대사 결과를 운영에서도 보이게 하려는 것이다.
 *
 * 무료 DB(1 ECPU)를 쓰므로 표 전체를 훑는 무거운 대사(체결↔정산 짝 맞추기 등)는 넣지 않았다. 계좌·보유 표처럼
 * 작은 표와, 인덱스로 좁혀지는 미체결 주문만 본다. 위반이 있으면 ERROR 로그를 남기고 /api/health의
 * ledgerHealthy를 false로 바꿔 외부 감시(backend/ops/monitor.py)가 Discord로 알리게 한다.
 */
@Service
public class LedgerInvariantService {
    private static final Logger log = LoggerFactory.getLogger(LedgerInvariantService.class);

    record Check(String name, String sql) { }

    static final List<Check> CHECKS = List.of(
            new Check("negative_cash", "SELECT COUNT(*) FROM users WHERE cash < 0"),
            new Check("negative_loan", "SELECT COUNT(*) FROM users WHERE loan_balance < 0"),
            new Check("invalid_holdings", "SELECT COUNT(*) FROM portfolios WHERE quantity < 0 OR settled_quantity < 0 OR settled_quantity > quantity"),
            new Check("invalid_open_orders", """
                    SELECT COUNT(*) FROM orders
                    WHERE status = 'OPEN'
                      AND (remaining_quantity <= 0 OR remaining_quantity > quantity OR reserved_cash < 0 OR reserved_quantity < 0)
                    """));

    private final JdbcTemplate jdbc;
    private final MarketMetrics metrics;
    /** 마지막으로 끝난 점검의 위반 수. 아직 점검 전이거나 점검이 실패하면 바꾸지 않는다. */
    private volatile Map<String, Long> lastViolations = Map.of();

    public LedgerInvariantService(JdbcTemplate jdbc, MarketMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${gamestock.ledger-check.interval-ms:600000}", initialDelayString = "${gamestock.ledger-check.initial-delay-ms:120000}")
    public void check() {
        Map<String, Long> violations = new LinkedHashMap<>();
        try {
            for (Check check : CHECKS) {
                Long count = jdbc.queryForObject(check.sql(), Long.class);
                if (count != null && count > 0) violations.put(check.name(), count);
            }
        } catch (RuntimeException error) {
            // DB가 잠깐 느리거나 끊긴 것은 장부 위반이 아니다. 다음 점검에서 다시 본다.
            log.warn("장부 점검을 끝내지 못했습니다 ({})", error.getClass().getSimpleName());
            metrics.add("ledger.check.failed", 1);
            return;
        }
        lastViolations = Map.copyOf(violations);
        metrics.gauge("ledger.violations", violations.values().stream().mapToLong(Long::longValue).sum());
        if (violations.isEmpty()) return;
        log.error("장부 불변식 위반: {}", violations);
    }

    /** 마지막 점검에서 위반이 없었는가. 공개 health에는 이 값만 내보내고 건수와 이름은 로그에만 남긴다. */
    public boolean healthy() {
        return lastViolations.isEmpty();
    }

    Map<String, Long> lastViolations() {
        return lastViolations;
    }
}
