package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.gamestock.backend.market.MarketModels.OrderRequest;

/**
 * 모의 자금 대출. 로그인한 사람 계정만 빌리고 갚을 수 있으며, 봇 계정은 서비스 안에서도 거절한다.
 *
 * <ul>
 *   <li>한도: 순자산(총 자산 − 대출 잔액)의 50%</li>
 *   <li>이자: 한국시간 하루 0.1%, 자정이 지나면 잔액에 붙는다</li>
 *   <li>담보비율(총 자산 ÷ 대출 잔액)이 140% 밑이면 경고하고 새 대출을 막는다</li>
 *   <li>120% 밑이면 미체결 주문을 취소하고 주식·파생자산을 청산해 140%까지 갚는다(반대매매)</li>
 * </ul>
 * 대출 잔액은 투자 성과가 아니므로 수익률·랭킹·칭호 계산에서 모두 뺀다.
 */
@Service
public class LoanService {
    private static final Logger log = LoggerFactory.getLogger(LoanService.class);
    private static final ZoneId LOAN_ZONE = ZoneId.of("Asia/Seoul");
    static final double LIMIT_RATIO = 0.5;
    static final double DAILY_INTEREST_RATE = 0.001;
    static final double WARNING_RATIO = 1.4;
    static final double LIQUIDATION_RATIO = 1.2;
    static final long MIN_AMOUNT = 10_000L;
    /** 계정당 1분에 허용하는 대출·상환 요청 수. 스크립트로 반복 호출하는 것을 막는다. */
    private static final int ACTIONS_PER_MINUTE = 10;
    /** 반대매매 매도 수량은 체결 미끄러짐과 수수료를 생각해 조금 넉넉히 잡는다. */
    private static final double LIQUIDATION_BUFFER = 1.03;

    /** 봇·시연용·관리자 계정은 대출 대상이 아니다. 랭킹의 투자자 기준과 같다. */
    private static final String HUMAN_FILTER = "u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo' AND COALESCE(u.role, 'USER') <> 'ADMIN'";
    private static final String GROSS_ASSET = AccountValuation.GROSS_ASSET;

    public record LoanEntry(long id, String kind, long amount, long balanceAfter, String createdAt) { }
    public record LoanStatus(long balance, long limit, long available, long grossAsset, long netAsset, long cash,
                             Double collateralRatio, boolean warning, boolean borrowBlocked,
                             double limitRatio, double dailyInterestRate, double warningRatio, double liquidationRatio,
                             long minAmount, List<LoanEntry> history, boolean borrowingOpen) { }
    public record LoanRequest(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictLongDeserializer.class) Long amount) { }
    public record LoanActionResult(String message, LoanStatus loan, MarketModels.Portfolio portfolio) { }

    private record Account(long cash, long balance, long grossAsset, boolean human) { }
    private record Holding(String code, int available, long price) { }

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final OrderService orders;
    private final DerivativeService derivatives;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Map<Long, Deque<Long>> recentActions = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public LoanService(JdbcTemplate jdbc, MarketService market, OrderService orders, DerivativeService derivatives, ApplicationEventPublisher events,
                       PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.market = market;
        this.orders = orders;
        this.derivatives = derivatives;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    void setClock(Clock clock) { this.clock = clock; }

    /**
     * New loans stopped with the prop desk career: the trading account is the firm's money.
     * Existing balances can still be repaid and keep accruing interest. Tests reopen it to
     * keep the old lending path covered.
     */
    private boolean borrowing = false;

    void allowBorrowing(boolean open) { this.borrowing = open; }

    public LoanStatus status(long userId) {
        Account account = account(userId, false);
        long net = account.grossAsset() - account.balance();
        long limit = Math.max(0, (long) Math.floor(net * LIMIT_RATIO));
        Double ratio = account.balance() > 0 ? (double) account.grossAsset() / account.balance() : null;
        boolean warning = ratio != null && ratio < WARNING_RATIO;
        long available = warning || !account.human() || !borrowing ? 0 : Math.max(0, limit - account.balance());
        List<LoanEntry> history = jdbc.query("""
                SELECT id, kind, amount, balance_after, created_at FROM loan_ledger
                WHERE user_id = ? ORDER BY id DESC LIMIT 30
                """, (rs, row) -> new LoanEntry(rs.getLong("id"), rs.getString("kind"), rs.getLong("amount"),
                rs.getLong("balance_after"), rs.getTimestamp("created_at").toInstant().toString()), userId);
        return new LoanStatus(account.balance(), limit, available, account.grossAsset(), net, account.cash(),
                ratio, warning, available < MIN_AMOUNT, LIMIT_RATIO, DAILY_INTEREST_RATE, WARNING_RATIO,
                LIQUIDATION_RATIO, MIN_AMOUNT, history, borrowing);
    }

    public LoanActionResult borrow(long userId, Long requested) {
        if (!borrowing) throw new IllegalArgumentException("신규 대출은 중단됐어요. 운용계좌는 회사 돈이라 직급에 따른 운용한도로만 굴려요. 이미 빌린 돈은 갚을 수 있어요.");
        long amount = validAmount(requested);
        throttle(userId);
        transaction.executeWithoutResult(state -> {
            Account account = account(userId, true);
            if (!account.human()) throw new IllegalArgumentException("대출은 회원 계정에서만 이용할 수 있습니다.");
            long net = account.grossAsset() - account.balance();
            if (account.balance() > 0 && (double) account.grossAsset() / account.balance() < WARNING_RATIO)
                throw new IllegalArgumentException("담보비율이 140% 밑이라 새로 빌릴 수 없습니다. 먼저 일부를 갚아 주세요.");
            long available = Math.max(0, (long) Math.floor(net * LIMIT_RATIO) - account.balance());
            if (amount > available)
                throw new IllegalArgumentException("대출 한도를 넘었습니다. 지금은 최대 " + String.format("%,d", available) + "원까지 빌릴 수 있습니다.");
            // 처음 빌린 날에는 이자를 붙이지 않는다. 다음 한국시간 자정부터 하루씩 붙는다.
            int updated = jdbc.update("""
                    UPDATE users SET cash = cash + ?, loan_balance = loan_balance + ?,
                           loan_interest_on = COALESCE(loan_interest_on, ?)
                    WHERE id = ? AND loan_balance = ?
                    """, amount, amount, Date.valueOf(today()), userId, account.balance());
            if (updated == 0) throw new IllegalArgumentException("대출 잔액이 방금 바뀌었습니다. 다시 시도해 주세요.");
            record(userId, "BORROW", amount, account.balance() + amount);
        });
        return result(userId, String.format("%,d원을 빌렸습니다.", amount));
    }

    public LoanActionResult repay(long userId, Long requested) {
        long amount = validRepayAmount(requested);
        throttle(userId);
        long[] repaid = new long[1];
        transaction.executeWithoutResult(state -> {
            Account account = account(userId, true);
            if (account.balance() == 0) throw new IllegalArgumentException("갚을 대출이 없습니다.");
            long pay = Math.min(amount, account.balance());
            if (pay > account.cash()) throw new IllegalArgumentException("보유 현금이 부족합니다. 지금은 최대 " + String.format("%,d", Math.min(account.cash(), account.balance())) + "원까지 갚을 수 있습니다.");
            settle(userId, pay, account.balance(), "REPAY");
            repaid[0] = pay;
        });
        return result(userId, String.format("%,d원을 갚았습니다.", repaid[0]));
    }

    /** 자정이 지난 대출에 밀린 날수만큼 복리로 이자를 붙인다. 여러 인스턴스가 돌아도 날짜 조건으로 한 번만 붙는다. */
    @Scheduled(fixedDelayString = "${gamestock.loans.interest-check-ms:60000}", initialDelayString = "${gamestock.loans.initial-delay-ms:45000}")
    public void accrueInterest() {
        try {
            LocalDate today = today();
            List<long[]> due = jdbc.query("""
                    SELECT id, loan_balance, DATEDIFF(?, loan_interest_on) FROM users
                    WHERE loan_balance > 0 AND loan_interest_on IS NOT NULL AND loan_interest_on < ?
                    """, (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, Date.valueOf(today), Date.valueOf(today));
            for (long[] row : due)
                transaction.executeWithoutResult(state -> applyInterest(row[0], row[1], row[2], today));
        } catch (RuntimeException error) {
            log.warn("대출 이자 정산에 실패했습니다. 다음 주기에 다시 시도합니다.", error);
        }
    }

    /** 날짜 조건으로 이미 붙은 이자를 다시 붙이지 않는다. 잔액이 그사이 바뀌었으면 건너뛴다. */
    private void applyInterest(long userId, long balance, long days, LocalDate today) {
        long interest = interestFor(balance, days);
        int updated = jdbc.update("""
                UPDATE users SET loan_balance = loan_balance + ?, loan_interest_on = ?
                WHERE id = ? AND loan_balance = ? AND loan_interest_on < ?
                """, interest, Date.valueOf(today), userId, balance, Date.valueOf(today));
        if (updated > 0 && interest > 0) record(userId, "INTEREST", interest, balance + interest);
    }

    /**
     * 잔액을 바꾸기 전에 밀린 이자를 먼저 붙인다. 자정 뒤 스케줄러보다 먼저 빌리면
     * 새 대출금에 지난날 이자가 붙고, 먼저 다 갚으면 밀린 이자가 사라지기 때문이다.
     * 사용자 행을 잠근 트랜잭션 안에서만 부른다.
     */
    private void accrueDueInterest(long userId) {
        LocalDate today = today();
        jdbc.query("""
                SELECT loan_balance, DATEDIFF(?, loan_interest_on) FROM users
                WHERE id = ? AND loan_balance > 0 AND loan_interest_on IS NOT NULL AND loan_interest_on < ?
                """, (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, Date.valueOf(today), userId, Date.valueOf(today))
                .forEach(row -> applyInterest(userId, row[0], row[1], today));
    }

    static long interestFor(long balance, long days) {
        if (balance <= 0 || days <= 0) return 0;
        return (long) Math.ceil(balance * (Math.pow(1 + DAILY_INTEREST_RATE, days) - 1));
    }

    /** 담보비율이 120% 밑으로 내려간 계정을 반대매매한다. 주문은 사람 주문과 같은 접수 경로로 낸다. */
    @Scheduled(fixedDelayString = "${gamestock.loans.margin-check-ms:30000}", initialDelayString = "${gamestock.loans.initial-delay-ms:45000}")
    public void enforceMargin() {
        if (!market.ownerAvailable()) return;
        try {
            List<Long> users = jdbc.query("SELECT u.id, (" + GROSS_ASSET + ") AS gross, u.loan_balance FROM users u WHERE u.loan_balance > 0 AND " + HUMAN_FILTER,
                    (rs, row) -> rs.getLong("gross") < rs.getLong("loan_balance") * LIQUIDATION_RATIO ? rs.getLong("id") : null)
                    .stream().filter(java.util.Objects::nonNull).toList();
            for (long userId : users) {
                try {
                    liquidate(userId);
                } catch (RuntimeException error) {
                    log.warn("사용자 {} 반대매매에 실패했습니다. 다음 주기에 다시 시도합니다.", userId, error);
                }
            }
        } catch (RuntimeException error) {
            log.warn("담보비율 점검에 실패했습니다.", error);
        }
    }

    void liquidate(long userId) {
        // The scheduler's candidate list can be stale after a deposit, repayment or price recovery.
        // Recheck before touching orders, and again between asynchronous cancellations.
        if (!requiresLiquidation(account(userId, false))) return;
        List<Long> open = jdbc.queryForList("SELECT id FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        for (long orderId : open) {
            if (!requiresLiquidation(account(userId, false))) return;
            try {
                orders.cancel(userId, orderId).join();
            } catch (RuntimeException ignored) {
                // 이미 체결·만료된 주문이면 넘어간다.
            }
        }
        Account account = account(userId, false);
        if (!requiresLiquidation(account)) return;
        long need = cashNeeded(account);
        if (need > 0) {
            List<Holding> holdings = jdbc.query("""
                    SELECT s.stock_code,
                           COALESCE(p.settled_quantity, p.quantity)
                             - COALESCE((SELECT SUM(o.reserved_quantity) FROM orders o WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id AND o.side = 'SELL' AND o.status = 'OPEN'), 0) AS available,
                           COALESCE(pm.mark_price, s.current_price) AS price
                    FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                    LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                    WHERE p.user_id = ? AND p.quantity > 0
                    """, (rs, row) -> new Holding(rs.getString("stock_code"), rs.getInt("available"), rs.getLong("price")), userId)
                    .stream().filter(h -> h.available() > 0 && h.price() > 0)
                    .sorted(Comparator.comparingLong((Holding h) -> h.available() * h.price()).reversed()).toList();
            for (Holding holding : holdings) {
                need = cashNeeded(account(userId, false));
                if (need <= 0) break;
                int quantity = (int) Math.min(Math.min(holding.available(), 1000),
                        (long) Math.ceil(need * LIQUIDATION_BUFFER / holding.price()));
                try {
                    orders.submit(userId, new OrderRequest(holding.code(), "SELL", quantity, "MARKET", null)).join();
                    // A submitted/cancelled order is not cash. Re-read settled cash and current collateral.
                    need = cashNeeded(account(userId, false));
                } catch (RuntimeException error) {
                    log.info("사용자 {} 반대매매 매도({})가 접수되지 않았습니다: {}", userId, holding.code(), error.getMessage());
                }
            }
        }
        if (need > 0) {
            // Derivatives count as collateral, so accounts holding only derivatives must also
            // be able to repay. Use the same fees, immutable expiry fixing and user/position locks.
            List<Long> positions = jdbc.queryForList("""
                    SELECT id FROM derivative_positions
                    WHERE user_id=? AND status='OPEN' AND quantity>0 ORDER BY mark_value DESC,id
                    """, Long.class, userId);
            for (long position : positions) {
                need = cashNeeded(account(userId, false));
                if (need <= 0) break;
                // Every successful iteration reduces this position's quantity. Recompute the
                // shortfall under the account lock, including a repayment made while waiting.
                while (need > 0) {
                    try {
                        boolean remaining = Boolean.TRUE.equals(transaction.execute(state -> derivatives.closeForCollateral(
                                userId, position, () -> (long) Math.ceil(cashNeeded(account(userId, true)) * LIQUIDATION_BUFFER))));
                        if (!remaining) break;
                        need = cashNeeded(account(userId, false));
                    } catch (RuntimeException error) {
                        log.info("사용자 {} 반대매매 파생 청산({})이 보류됐습니다: {}", userId, position, error.getMessage());
                        break;
                    }
                }
            }
        }
        long paid = transaction.execute(state -> {
            Account current = account(userId, true);
            long pay = Math.min(Math.min(current.cash(), current.balance()), Math.max(repayTarget(current.grossAsset(), current.balance()), 0));
            if (pay > 0) settle(userId, pay, current.balance(), "LIQUIDATION");
            return pay;
        });
        if (paid > 0) {
            events.publishEvent(new UserMarketEvent(userId, "LOAN_LIQUIDATED", Map.of("portfolio", market.portfolio(userId))));
            log.info("사용자 {} 반대매매를 실행했습니다.", userId);
        }
    }

    private static boolean requiresLiquidation(Account account) {
        return account.balance() > 0 && account.grossAsset() < account.balance() * LIQUIDATION_RATIO;
    }

    private static long cashNeeded(Account account) {
        return Math.max(0, repayTarget(account.grossAsset(), account.balance()) - account.cash());
    }

    /** 갚고 나면 담보비율이 140%가 되는 금액. 총 자산이 잔액보다 작으면 전액이다. */
    static long repayTarget(long gross, long balance) {
        if (gross <= balance) return balance;
        // (1.4·잔액 − 총자산) ÷ 0.4 를 정수로 계산해 반올림 오차를 없앤다.
        long numerator = 14 * balance - 10 * gross;
        long needed = numerator <= 0 ? 0 : (numerator + 3) / 4;
        return Math.min(balance, needed);
    }

    private void settle(long userId, long pay, long balance, String kind) {
        int updated = jdbc.update("""
                UPDATE users SET cash = cash - ?, loan_balance = loan_balance - ?,
                       loan_interest_on = CASE WHEN loan_balance = 0 THEN NULL ELSE loan_interest_on END
                WHERE id = ? AND cash >= ? AND loan_balance = ?
                """, pay, pay, userId, pay, balance);
        if (updated == 0) throw new IllegalArgumentException("잔액이 방금 바뀌었습니다. 다시 시도해 주세요.");
        record(userId, kind, pay, balance - pay);
    }

    private Account account(long userId, boolean lock) {
        if (lock) {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
            accrueDueInterest(userId);
        }
        return jdbc.query("SELECT u.cash, u.loan_balance, (" + GROSS_ASSET + ") AS gross, (" + HUMAN_FILTER + ") AS human FROM users u WHERE u.id = ?",
                        (rs, row) -> new Account(rs.getLong("cash"), rs.getLong("loan_balance"), rs.getLong("gross"), rs.getBoolean("human")), userId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("계정을 찾을 수 없습니다."));
    }

    private void record(long userId, String kind, long amount, long balanceAfter) {
        jdbc.update("INSERT INTO loan_ledger (user_id, kind, amount, balance_after) VALUES (?, ?, ?, ?)", userId, kind, amount, balanceAfter);
    }

    private LoanActionResult result(long userId, String message) {
        return new LoanActionResult(message, status(userId), market.portfolio(userId));
    }

    private static long validAmount(Long amount) {
        if (amount == null || amount < MIN_AMOUNT) throw new IllegalArgumentException("대출은 10,000원부터 신청할 수 있습니다.");
        if (amount > 1_000_000_000_000L) throw new IllegalArgumentException("금액을 확인해 주세요.");
        return amount;
    }

    private static long validRepayAmount(Long amount) {
        if (amount == null || amount < 1) throw new IllegalArgumentException("갚을 금액을 입력해 주세요.");
        if (amount > 1_000_000_000_000L) throw new IllegalArgumentException("금액을 확인해 주세요.");
        return amount;
    }

    private void throttle(long userId) {
        long now = clock.millis();
        Deque<Long> times = recentActions.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= ACTIONS_PER_MINUTE) throw new IllegalArgumentException("요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
            times.addLast(now);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(LOAN_ZONE));
    }

    /** 오래된 요청 기록을 비운다. */
    @Scheduled(fixedDelay = 300_000)
    public void pruneThrottle() {
        long now = clock.millis();
        recentActions.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || now - entry.getValue().peekLast() > 60_000;
            }
        });
    }
}
