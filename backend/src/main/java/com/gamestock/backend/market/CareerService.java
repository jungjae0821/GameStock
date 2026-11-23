package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The prop desk career: grade, promotion, demotion, the daily loss limit and the daily
 * bonus settlement. The trading account is the existing {@code users.cash}; this class
 * only tops it up on grade changes (recorded as outside cash so returns leave it out)
 * and blocks new risk while a lock is on.
 *
 * <p>Two valuations of the same account are used. The mark asset values holdings at
 * the mark price and decides demotion and the daily loss limit. The confirmed asset
 * values each holding at the lower of mark and cost, so a gain counts only once it is
 * sold; it decides promotion and the bonus.
 */
@Service
public class CareerService {
    private static final Logger log = LoggerFactory.getLogger(CareerService.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final double DAY_LOSS_LIMIT = 0.05;
    static final Duration RETRAIN = Duration.ofHours(24);
    static final Duration NEWS_WINDOW = Duration.ofHours(2);
    static final int NEWS_FILLS = 5;
    static final int FUTURE_CLOSES = 3;
    static final int DISTINCT_SELLS = 5;
    static final Set<String> CLIENT_STEPS = Set.of("MARKET", "RISK");
    /** A fill counts toward promotion only if it moves at least this share of the grade's base. */
    static final double MIN_FILL_SHARE = 0.01;
    /** From staff up, one stock may hold at most this share of the grade's base. */
    static final double CONCENTRATION_LIMIT = 0.30;
    /** Share of a grade's weekly ranking that earns a point (at least one member), if their excess is positive. */
    static final double RANK_TOP_SHARE = 0.40;
    /** Days with a sized fill a member needs in the week to be ranked at all. */
    static final int WEEK_TRADING_DAYS = 2;
    /** Daily volatility below this counts as this, so a member who barely moved cannot divide by almost nothing. */
    static final double VOLATILITY_FLOOR = 0.005;

    static final String MARK_ASSET = "(" + AccountValuation.GROSS_ASSET + " - u.loan_balance)";
    static final String CONFIRMED_ASSET = """
            (u.cash - u.loan_balance
             + COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0)
             + COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0)
             + COALESCE((SELECT SUM(p.quantity * LEAST(COALESCE(pm.mark_price, s.current_price), GREATEST(p.average_price, 0)))
                 FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                 LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                 WHERE p.user_id = u.id AND p.quantity > 0), 0)
             + COALESCE((SELECT SUM(LEAST(GREATEST(dp.mark_value, 0), CASE WHEN dp.margin > 0 THEN dp.margin ELSE dp.entry_notional END))
                 FROM derivative_positions dp WHERE dp.user_id = u.id AND dp.status = 'OPEN'), 0))
            """;

    public record Step(String id, String label, String unit, long current, long target, boolean done) { }
    public record Event(String kind, String fromGrade, String toGrade, long assetAt, long topUp, String at) { }
    public record Career(boolean legacy, boolean exempt, String grade, String gradeLabel, int level, long allocation,
                         double bonusRate, String nextGrade, String nextGradeLabel, String gradeSince, long gradeBase,
                         long markAsset, long confirmedAsset, long highWaterMark, double returnPercent, long demotionAsset,
                         long dayOpenAsset, long dayLossLimit, String lockedUntil, String lockReason, long personalCash,
                         List<Step> promotion, List<Event> events, Week week) { }
    /**
     * This week's ranked race for associates and up: the return so far against the game index,
     * the trading days that make the member eligible and when the week is ranked. {@code last}
     * is the member's row from the most recent ranked week, if any.
     */
    public record Week(String since, String ranksAt, double returnPercent, double indexPercent, double excessPercent,
                       int tradingDays, int tradingDaysNeeded, double topShare, int points, int pointsNeeded, LastWeek last) { }
    public record LastWeek(String weekStart, int rank, int members, double excessPercent, boolean awarded) { }
    public record Wallet(long balance, List<PersonalWallet.Entry> entries) { }

    record State(long userId, CareerGrade grade, Instant gradeSince, long gradeBase, long outsideCash, int promotionPoints,
                 Instant weekOpenAt, Long weekOpenTraded, Double weekOpenIndex,
                 long highWaterMark, long personalCash,
                 Set<String> ojtSteps, LocalDate dayOpenOn, Long dayOpenAsset, Instant lockedUntil, String lockReason,
                 LocalDate lastSettledOn, boolean legacy, boolean migrated, boolean exempt, boolean trader) {
        boolean active() { return !exempt && (!legacy || migrated); }

        /** What the trading in this grade alone has made of {@code asset}: admin cash is taken back out. */
        long traded(long asset) { return asset - outsideCash; }
    }

    private record Block(String message) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private Clock clock = Clock.systemUTC();

    public CareerService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    void useClock(Clock clock) { this.clock = clock; }

    // ── 읽기 ────────────────────────────────────────────────

    /**
     * The member's career. One locked pass reviews (so a met promotion shows up as soon as they
     * look) and keeps the assets and promotion steps it computed for the answer, instead of
     * computing them all again afterwards.
     */
    public Career career(long userId) {
        List<UserMarketEvent> notices = new ArrayList<>();
        View view = transactions.execute(status -> {
            State state = load(userId, true);
            if (state.active()) return reviewLocked(state, clock.instant(), notices, true);
            return new View(state, markAsset(userId), confirmedAsset(userId), List.of());
        });
        notices.forEach(this::publish);
        State state = view.state();
        long mark = view.mark();
        long confirmed = view.confirmed();
        CareerGrade grade = state.grade();
        CareerGrade next = grade.next();
        double returnPercent = state.gradeBase() <= 0 ? 0 : (state.traded(confirmed) - state.gradeBase()) * 100.0 / state.gradeBase();
        long demotionAsset = grade.demotionLoss > 0 ? (long) Math.floor(state.gradeBase() * (1 - grade.demotionLoss)) + state.outsideCash() : 0;
        long dayLimit = grade.atLeast(CareerGrade.STAFF) ? (long) Math.floor(state.gradeBase() * DAY_LOSS_LIMIT) : 0;
        Instant now = clock.instant();
        boolean locked = state.lockedUntil() != null && state.lockedUntil().isAfter(now);
        return new Career(state.legacy() && !state.migrated(), state.exempt(), grade.name(), grade.label, grade.ordinal(),
                grade.allocation, grade.bonusRate, next == null ? null : next.name(), next == null ? null : next.label,
                state.gradeSince().toString(), state.gradeBase(), mark, confirmed, state.highWaterMark(), returnPercent,
                demotionAsset, state.dayOpenAsset() == null ? mark : state.dayOpenAsset(), dayLimit,
                locked ? state.lockedUntil().toString() : null, locked ? state.lockReason() : null,
                state.personalCash(), view.steps(), events(userId), grade.ranked() ? week(state, confirmed, now) : null);
    }

    /** The live view of this week's race. Costs three reads, only for ranked grades. */
    private Week week(State state, long confirmed, Instant now) {
        Instant since = state.weekOpenAt() != null ? state.weekOpenAt() : weekStart(now);
        double ret = state.weekOpenTraded() == null || state.weekOpenTraded() <= 0 ? 0
                : (double) state.traded(confirmed) / state.weekOpenTraded() - 1;
        Double index = indexAt(now);
        double indexReturn = index == null || state.weekOpenIndex() == null || state.weekOpenIndex() <= 0 ? 0 : index / state.weekOpenIndex() - 1;
        int days = (int) tradingDays(state.userId(), Timestamp.from(since), minFill(state));
        var last = jdbc.query("""
                SELECT week_start, rank_in_grade, ranked_members, return_permil - index_permil AS excess, awarded
                FROM career_week_results WHERE user_id = ? ORDER BY week_start DESC LIMIT 1
                """, (rs, row) -> new LastWeek(rs.getDate(1).toLocalDate().toString(), rs.getInt(2), rs.getInt(3),
                rs.getInt(4) / 10.0, rs.getBoolean(5)), state.userId());
        return new Week(since.toString(), weekStart(now).plus(Duration.ofDays(7)).toString(), ret * 100, indexReturn * 100,
                (ret - indexReturn) * 100, days, WEEK_TRADING_DAYS, RANK_TOP_SHARE, state.promotionPoints(),
                state.grade().promotionPoints, last.isEmpty() ? null : last.get(0));
    }

    public Wallet wallet(long userId) {
        return new Wallet(PersonalWallet.balance(jdbc, userId), PersonalWallet.recent(jdbc, userId, 30));
    }

    /** Whether the member may use a desk that opens at {@code required}. Legacy and staff accounts may. */
    public boolean reached(long userId, CareerGrade required) {
        State state = load(userId, false);
        return !state.active() || state.grade().atLeast(required);
    }

    public CareerGrade grade(long userId) { return load(userId, false).grade(); }

    public boolean active(long userId) { return load(userId, false).active(); }

    // ── OJT ─────────────────────────────────────────────────

    /**
     * Records an OJT step the server cannot see for itself (opening a stock, reading the risk
     * briefing). Returns null when nothing changed: not a trainee, or the step was already done.
     * The mobile app reports a stock view on every open, so that case must stay cheap.
     */
    public Career acknowledge(long userId, String step) {
        String id = step == null ? "" : step.trim().toUpperCase(Locale.ROOT);
        if (!CLIENT_STEPS.contains(id)) throw new IllegalArgumentException("알 수 없는 OJT 단계예요.");
        Boolean changed = transactions.execute(status -> {
            State state = load(userId, true);
            if (!state.active() || state.grade() != CareerGrade.TRAINEE || state.ojtSteps().contains(id)) return false;
            Set<String> steps = new LinkedHashSet<>(state.ojtSteps());
            steps.add(id);
            jdbc.update("UPDATE career_state SET ojt_steps = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?",
                    String.join(",", steps), userId);
            return true;
        });
        return Boolean.TRUE.equals(changed) ? career(userId) : null;
    }

    // ── 주문 전 확인 ────────────────────────────────────────

    /**
     * Called before a buy or a new derivative entry. Demotes first if the loss line is
     * crossed, then refuses while a retraining or daily-loss lock is on. Selling and
     * closing never come here.
     */
    public void requireNewRisk(long userId) { requireNewRisk(userId, null, 0, null); }

    /**
     * A buy or new entry. The loss checks first; for a stock buy ({@code stockCode} set) also the
     * concentration limit: from staff up, the stock's holding plus open buys plus this order may
     * not pass 30% of the grade's base, so a promotion cannot be won by putting the whole desk on
     * one coin flip. Both run on the one row read under the lock.
     */
    public void requireNewRisk(long userId, String stockCode, long quantity, Long limitPrice) {
        Block block = transactions.execute(status -> {
            State state = load(userId, true);
            if (!state.active()) return null;
            Instant now = clock.instant();
            long mark = markAsset(userId);
            Instant since = state.gradeSince();
            state = demoteIfBreached(state, mark, now);
            // A demotion may have topped the account up; the day must not start from the pre-top-up value.
            if (!state.gradeSince().equals(since)) mark = markAsset(userId);
            state = openDayIfNeeded(state, mark, now);
            if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) return new Block(lockMessage(state));
            CareerGrade grade = state.grade();
            if (grade.atLeast(CareerGrade.STAFF) && state.dayOpenAsset() != null) {
                long limit = (long) Math.floor(state.gradeBase() * DAY_LOSS_LIMIT);
                if (state.dayOpenAsset() - mark > limit) {
                    Instant until = today(now).plusDays(1).atStartOfDay(ZONE).toInstant();
                    jdbc.update("""
                            UPDATE career_state SET day_locked_until = ?, lock_reason = 'DAY_LIMIT', breach_count = breach_count + 1,
                              updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                            """, Timestamp.from(until), userId);
                    event(userId, "DAY_LIMIT", grade, grade, mark, 0);
                    return new Block("오늘 손실이 일일 손실한도(" + won(limit) + ")를 넘었어요. 내일 0시까지 새로 사거나 진입할 수 없어요. 파는 건 할 수 있어요.");
                }
            }
            return stockCode == null || !grade.atLeast(CareerGrade.STAFF) ? null : concentrationBlock(state, stockCode, quantity, limitPrice);
        });
        if (block != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, block.message());
    }

    private Block concentrationBlock(State state, String stockCode, long quantity, Long limitPrice) {
        long userId = state.userId();
        var stock = jdbc.queryForList("""
                SELECT s.id, COALESCE(pm.mark_price, s.current_price) AS mark FROM stocks s
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id WHERE s.stock_code = ?
                """, stockCode.trim().toUpperCase(Locale.ROOT));
        if (stock.isEmpty()) return null;
        long stockId = number(stock.get(0).get("id"));
        long mark = number(stock.get(0).get("mark"));
        long held = count("SELECT COALESCE(SUM(quantity), 0) FROM portfolios WHERE user_id = ? AND stock_id = ?", userId, stockId) * mark;
        long pending = count("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND stock_id = ? AND status = 'OPEN' AND side = 'BUY'",
                userId, stockId);
        long price = limitPrice != null && limitPrice > 0 ? limitPrice : mark;
        long cap = (long) Math.floor((state.gradeBase() + state.outsideCash()) * CONCENTRATION_LIMIT);
        long after = held + pending + quantity * price;
        if (after <= cap) return null;
        return new Block("한 종목에는 기준자산의 " + Math.round(CONCENTRATION_LIMIT * 100) + "%(" + won(cap)
                + ")까지만 담을 수 있어요. 이 주문까지 " + won(after) + "이 돼요. 수량을 줄여 주세요.");
    }

    // ── 승진·강등 ───────────────────────────────────────────

    /** Applies a crossed loss line or a met promotion. Safe to call any time. */
    public void review(long userId) {
        List<UserMarketEvent> notices = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            State state = load(userId, true);
            if (!state.active()) return;
            reviewLocked(state, clock.instant(), notices, false);
        });
        notices.forEach(this::publish);
    }

    /** What one locked review saw: the state after any grade change, the two valuations and, if asked for, the steps. */
    private record View(State state, long mark, long confirmed, List<Step> steps) { }

    /**
     * Demotes past the loss line, then promotes when every step is met. With {@code withSteps}
     * the promotion steps come back for display; without, they are only counted when a
     * promotion is possible at all, which skips the fill counts for most reviews.
     */
    private View reviewLocked(State state, Instant now, List<UserMarketEvent> notices, boolean withSteps) {
        long userId = state.userId();
        long mark = markAsset(userId);
        CareerGrade before = state.grade();
        Instant since = state.gradeSince();
        state = demoteIfBreached(state, mark, now);
        boolean demoted = !state.gradeSince().equals(since);
        if (demoted) {
            notices.add(notice(userId, before, state.grade()));
            mark = markAsset(userId);
        }
        boolean hasNext = state.grade().next() != null;
        boolean check = hasNext && !demoted && mayPromote(state);
        if (!withSteps && !check) return new View(state, mark, 0, List.of());
        long confirmed = confirmedAsset(userId);
        List<Step> steps = hasNext && (withSteps || check) ? promotionSteps(state, confirmed) : List.of();
        if (check && !steps.isEmpty() && steps.stream().allMatch(Step::done)) {
            state = promote(state, mark, now);
            notices.add(notice(userId, before, state.grade()));
            mark = markAsset(userId);
            confirmed = confirmedAsset(userId);
            steps = withSteps && state.grade().next() != null ? promotionSteps(state, confirmed) : List.of();
        }
        return new View(state, mark, confirmed, steps);
    }

    /**
     * Whether a promotion could happen now. Ranked grades need their weekly points first, so until
     * then no other step is worth counting; the OJT and staff steps are counted every time.
     */
    private static boolean mayPromote(State state) {
        CareerGrade grade = state.grade();
        // AI traders finish the OJT like members but stay staff: the staff steps are only activity, which a
        // bot meets every day, and they are never settled or ranked, so a higher desk would only hand them more money.
        if (state.trader() && grade != CareerGrade.TRAINEE) return false;
        return !grade.ranked() || state.promotionPoints() >= grade.promotionPoints;
    }

    private State promote(State state, long mark, Instant now) {
        CareerGrade from = state.grade();
        CareerGrade to = from.next();
        // Gains made in the old grade are paid now at the old grade's rate, not later at the new one.
        long confirmed = confirmedAsset(state.userId());
        long gain = Math.max(0, confirmed - state.highWaterMark());
        long bonus = (long) Math.floor(gain * from.bonusRate);
        if (bonus > 0)
            PersonalWallet.credit(jdbc, state.userId(), "bonus:" + state.userId() + ":promote:" + to.name() + ":" + now.toEpochMilli(),
                    PersonalWallet.BONUS, bonus, from.label + " 마지막 성과급");
        long topUp = Math.max(0, to.allocation - mark);
        topUp(state.userId(), topUp, "promote:" + to.name(), to.label + " 발령 운용한도 충전");
        jdbc.update("""
                UPDATE career_state SET grade = ?, grade_since = ?, grade_base = ?, outside_cash = 0, return_streak = 0, high_water_mark = GREATEST(high_water_mark, ?) + ?,
                  day_open_asset = CASE WHEN day_open_asset IS NULL THEN NULL ELSE day_open_asset + ? END,
                  updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, to.name(), Timestamp.from(now), mark + topUp, confirmed, topUp, topUp, state.userId());
        openWeek(state.userId(), now, confirmed + topUp, true);
        event(state.userId(), "PROMOTE", from, to, mark, topUp);
        log.info("직급 승진 user={} {} -> {} 충전={}", state.userId(), from, to, topUp);
        return load(state.userId(), true);
    }

    private State demoteIfBreached(State state, long mark, Instant now) {
        CareerGrade grade = state.grade();
        if (grade.demotionLoss <= 0) return state;
        long line = (long) Math.floor(state.gradeBase() * (1 - grade.demotionLoss));
        if (state.traded(mark) >= line) return state;
        long confirmed = confirmedAsset(state.userId());
        if (grade == CareerGrade.STAFF) {
            long topUp = Math.max(0, CareerGrade.STAFF.allocation - mark);
            topUp(state.userId(), topUp, "retrain", "재교육 뒤 운용한도 충전");
            jdbc.update("""
                    UPDATE career_state SET grade_since = ?, grade_base = ?, outside_cash = 0, return_streak = 0, high_water_mark = GREATEST(high_water_mark, ?) + ?,
                      day_locked_until = ?,
                      lock_reason = 'RETRAIN', breach_count = breach_count + 1,
                      day_open_asset = CASE WHEN day_open_asset IS NULL THEN NULL ELSE day_open_asset + ? END,
                      updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                    """, Timestamp.from(now), mark + topUp, confirmed, topUp, Timestamp.from(now.plus(RETRAIN)), topUp, state.userId());
            openWeek(state.userId(), now, confirmed + topUp, true);
            event(state.userId(), "RETRAIN", grade, grade, mark, topUp);
            log.info("재교육 user={} 평가자산={} 충전={}", state.userId(), mark, topUp);
            return load(state.userId(), true);
        }
        CareerGrade to = grade.previous();
        long topUp = Math.max(0, to.allocation - mark);
        topUp(state.userId(), topUp, "demote:" + to.name(), to.label + " 강등 운용한도 충전");
        // The high-water mark survives a demotion: winning back a loss the firm carried is not new gain,
        // otherwise losing big and recovering would pay a bonus the firm never earned.
        jdbc.update("""
                UPDATE career_state SET grade = ?, grade_since = ?, grade_base = ?, outside_cash = 0, return_streak = 0,
                  high_water_mark = GREATEST(high_water_mark, ?) + ?,
                  breach_count = breach_count + 1,
                  day_open_asset = CASE WHEN day_open_asset IS NULL THEN NULL ELSE day_open_asset + ? END,
                  updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, to.name(), Timestamp.from(now), mark + topUp, confirmed, topUp, topUp, state.userId());
        openWeek(state.userId(), now, confirmed + topUp, true);
        event(state.userId(), "DEMOTE", grade, to, mark, topUp);
        log.info("직급 강등 user={} {} -> {} 충전={}", state.userId(), grade, to, topUp);
        return load(state.userId(), true);
    }

    List<Step> promotionSteps(State state, long confirmed) {
        CareerGrade grade = state.grade();
        long userId = state.userId();
        Timestamp since = Timestamp.from(state.gradeSince());
        List<Step> steps = new ArrayList<>();
        if (grade == CareerGrade.TRAINEE) {
            Long fandom = jdbc.queryForList("SELECT fandom_stock_id FROM user_engagement WHERE user_id = ?", Long.class, userId)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            steps.add(check("FANDOM", "주력 게임 고르기", fandom != null));
            steps.add(check("MARKET", "종목 화면에서 호가·뉴스 보기", state.ojtSteps().contains("MARKET")));
            long limitBuys = count("""
                    SELECT COUNT(*) FROM trades t JOIN orders o ON o.id = t.buy_order_id
                    WHERE t.buyer_id = ? AND t.created_at >= ? AND o.order_type = 'LIMIT'
                    """, userId, since);
            steps.add(counted("LIMIT_BUY", "지정가 매수 체결", limitBuys, 1));
            long sells = count("SELECT COUNT(*) FROM trades WHERE seller_id = ? AND created_at >= ?", userId, since);
            steps.add(counted("SELL", "매도 체결", sells, 1));
            steps.add(check("RISK", "손실한도·성과급 교육 확인", state.ojtSteps().contains("RISK")));
            return steps;
        }
        long minFill = minFill(state);
        String sized = " (" + won(minFill) + " 이상)";
        if (grade == CareerGrade.STAFF) {
            steps.add(counted("DAYS", "거래한 날" + sized, tradingDays(userId, since, minFill), grade.promotionTradingDays));
            steps.add(counted("NEWS", "뉴스 발행 2시간 안에 그 종목 체결" + sized, newsFills(userId, since, minFill), grade.promotionNewsFills));
            steps.add(check("CLEAN", "이 직급에서 일일 손실한도 위반 없음", dayLimitBreaches(userId, since) == 0));
            long floor = Math.round(CareerGrade.STAFF_RETURN_FLOOR * 1000);
            long permil = returnPermil(state, confirmed);
            steps.add(check("FLOOR", "확정 수익률 " + Math.round(CareerGrade.STAFF_RETURN_FLOOR * 100) + "% 이상 유지", permil >= floor));
            return steps;
        }
        if (grade.ranked())
            steps.add(counted("POINTS", "주간 순위 상위 " + Math.round(RANK_TOP_SHARE * 100) + "% (게임주 지수보다 나은 수익)",
                    state.promotionPoints(), grade.promotionPoints));
        switch (grade) {
            case ASSOCIATE -> steps.add(counted("NEWS", "뉴스 발행 2시간 안에 그 종목 체결" + sized, newsFills(userId, since, minFill), NEWS_FILLS));
            // A forced liquidation is not a close the trader chose, so only CLOSE counts.
            case MANAGER -> steps.add(counted("FUTURES", "선물 청산", count("""
                    SELECT COUNT(*) FROM derivative_trades
                    WHERE user_id = ? AND kind = 'FUTURE' AND action = 'CLOSE' AND created_at >= ?
                    """, userId, since), FUTURE_CLOSES));
            case SENIOR_MANAGER -> steps.add(counted("SPREAD", "서로 다른 종목 매도" + sized, count("""
                    SELECT COUNT(DISTINCT stock_id) FROM trades WHERE seller_id = ? AND created_at >= ? AND quantity * price >= ?
                    """, userId, since, minFill), DISTINCT_SELLS));
            case GENERAL_MANAGER -> steps.add(check("CLEAN", "이 직급에서 일일 손실한도 위반 없음", dayLimitBreaches(userId, since) == 0));
            default -> { }
        }
        return steps;
    }

    // ── 매일 정산 ───────────────────────────────────────────

    /**
     * Settles every member not yet settled for today's Korean date. Runs often so a missed
     * midnight catches up; each member is settled at most once a day under a row lock.
     */
    @Scheduled(fixedDelayString = "${gamestock.career.settle-ms:600000}", initialDelayString = "${gamestock.career.settle-initial-ms:120000}")
    public void settleDue() {
        LocalDate today = today(clock.instant());
        List<Long> due = jdbc.queryForList("""
                SELECT c.user_id FROM career_state c JOIN users u ON u.id = c.user_id
                WHERE (c.last_settled_on IS NULL OR c.last_settled_on < ?) AND (c.legacy = FALSE OR c.migrated_at IS NOT NULL)
                  AND u.password_hash NOT IN ('BOT', 'TRADER')
                ORDER BY c.user_id LIMIT 2000
                """, Long.class, Date.valueOf(today));
        for (long userId : due) {
            try {
                settle(userId, today);
            } catch (RuntimeException error) {
                log.warn("커리어 정산 실패 user={}: {}", userId, error.getMessage());
            }
        }
    }

    /** One member's daily settlement: bonus over the high-water mark, the day's opening asset, then review. */
    void settle(long userId, LocalDate today) {
        List<UserMarketEvent> notices = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            State state = load(userId, true);
            if (!state.active() || state.lastSettledOn() != null && !state.lastSettledOn().isBefore(today)) return;
            long confirmed = confirmedAsset(userId);
            long mark = markAsset(userId);
            long gain = Math.max(0, confirmed - state.highWaterMark());
            long bonus = (long) Math.floor(gain * state.grade().bonusRate);
            LocalDate day = today.minusDays(1);
            if (bonus > 0 && PersonalWallet.credit(jdbc, userId, "bonus:" + userId + ":" + day, PersonalWallet.BONUS, bonus,
                    day + " 성과급 (" + state.grade().label + ")"))
                notices.add(new UserMarketEvent(userId, "CAREER_BONUS", Map.of("amount", bonus, "day", day.toString())));
            recordMissedDayLimit(state, mark, today);
            // The day's closing mark feeds the weekly volatility. A grade change resets the week, so
            // marks before it are ignored there.
            jdbc.update("INSERT IGNORE INTO career_daily_marks (user_id, day, traded_mark) VALUES (?, ?, ?)",
                    userId, Date.valueOf(day), state.traded(mark));
            if (state.grade().ranked() && state.weekOpenAt() == null) openWeek(userId, clock.instant(), state.traded(confirmed), false);
            // A buy after midnight may already have opened today; keep that baseline so earlier losses still count.
            boolean opened = today.equals(state.dayOpenOn());
            jdbc.update("""
                    UPDATE career_state SET high_water_mark = GREATEST(high_water_mark, ?),
                      day_open_on = ?, day_open_asset = CASE WHEN ? THEN day_open_asset ELSE ? END,
                      last_settled_on = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                    """, confirmed, Date.valueOf(today), opened, mark, Date.valueOf(today), userId);
            reviewLocked(load(userId, true), clock.instant(), notices, false);
        });
        notices.forEach(this::publish);
    }

    // ── 주간 순위 ───────────────────────────────────────────

    /** One ranked member's week, as it is stored in {@code career_week_results}. */
    record Ranked(long userId, CareerGrade grade, double ret, double indexReturn, double volatility, double score) {
        double excess() { return ret - indexReturn; }
    }

    /**
     * Ranks the week that just ended once the Korean Monday has begun. Runs with the daily
     * settlement's cadence; the {@code career_weeks} row makes it run once per week even with
     * several instances or a restart in the middle.
     */
    @Scheduled(fixedDelayString = "${gamestock.career.settle-ms:600000}", initialDelayString = "${gamestock.career.week-initial-ms:180000}")
    public void settleWeekDue() {
        try {
            settleWeek(weekStart(clock.instant()));
        } catch (RuntimeException error) {
            log.warn("커리어 주간 순위 정산 실패: {}", error.getMessage());
        }
    }

    /**
     * Ranks every eligible associate-and-up member for the week ending at {@code end} (a Korean
     * Monday 0시), gives the top share with a positive excess a point, and starts everyone's next
     * week from now. Members whose week began at or after {@code end} are not ranked. Returns the
     * ranked rows; empty if the week was already settled.
     */
    List<Ranked> settleWeek(Instant end) {
        LocalDate weekStart = end.atZone(ZONE).toLocalDate();
        List<Long> awarded = new ArrayList<>();
        List<Ranked> ranked = transactions.execute(status -> {
            if (jdbc.update("INSERT IGNORE INTO career_weeks (week_start) VALUES (?)", Date.valueOf(weekStart.minusDays(7))) == 0)
                return List.<Ranked>of();
            Double indexNow = indexAt(end);
            List<Long> members = jdbc.queryForList("""
                    SELECT c.user_id FROM career_state c JOIN users u ON u.id = c.user_id
                    WHERE c.grade IN ('ASSOCIATE', 'MANAGER', 'SENIOR_MANAGER', 'GENERAL_MANAGER')
                      AND (c.legacy = FALSE OR c.migrated_at IS NOT NULL)
                      AND u.password_hash NOT IN ('BOT', 'TRADER') AND COALESCE(u.role, 'USER') <> 'ADMIN' AND u.username <> 'demo'
                    ORDER BY c.user_id FOR UPDATE OF c
                    """, Long.class);
            List<Ranked> rows = new ArrayList<>();
            for (long userId : members) {
                State state = load(userId, false);
                long confirmed = confirmedAsset(userId);
                if (state.weekOpenAt() != null && state.weekOpenAt().isBefore(end) && state.weekOpenTraded() != null && state.weekOpenTraded() > 0
                        && tradingDays(userId, Timestamp.from(state.weekOpenAt()), minFill(state)) >= WEEK_TRADING_DAYS) {
                    double ret = (double) state.traded(confirmed) / state.weekOpenTraded() - 1;
                    double indexReturn = indexNow == null || state.weekOpenIndex() == null || state.weekOpenIndex() <= 0
                            ? 0 : indexNow / state.weekOpenIndex() - 1;
                    double volatility = Math.max(VOLATILITY_FLOOR, weekVolatility(state, markAsset(userId)));
                    rows.add(new Ranked(userId, state.grade(), ret, indexReturn, volatility, (ret - indexReturn) / volatility));
                }
                // A member whose week already restarted after Monday 0시 (a grade change) keeps that start.
                if (state.weekOpenAt() == null || state.weekOpenAt().isBefore(end)) openWeek(userId, end, state.traded(confirmed), false);
            }
            for (CareerGrade grade : CareerGrade.values()) {
                List<Ranked> pool = rows.stream().filter(row -> row.grade() == grade)
                        .sorted(java.util.Comparator.comparingDouble(Ranked::score).reversed().thenComparingLong(Ranked::userId)).toList();
                int top = Math.max(1, (int) Math.ceil(pool.size() * RANK_TOP_SHARE));
                for (int i = 0; i < pool.size(); i++) {
                    Ranked row = pool.get(i);
                    boolean point = i < top && row.excess() > 0;
                    if (point) {
                        jdbc.update("UPDATE career_state SET promotion_points = promotion_points + 1, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?",
                                row.userId());
                        awarded.add(row.userId());
                    }
                    jdbc.update("""
                            INSERT INTO career_week_results (week_start, user_id, grade, return_permil, index_permil, volatility_permil,
                              score, rank_in_grade, ranked_members, awarded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, Date.valueOf(weekStart.minusDays(7)), row.userId(), grade.name(), permil(row.ret()), permil(row.indexReturn()),
                            permil(row.volatility()), row.score(), i + 1, pool.size(), point);
                }
            }
            log.info("커리어 주간 순위 {}: 순위 {}명, 포인트 {}명", weekStart.minusDays(7), rows.size(), awarded.size());
            return rows;
        });
        // Each point may complete a promotion; review outside the ranking transaction like any other check.
        for (long userId : awarded) review(userId);
        return ranked == null ? List.of() : ranked;
    }

    /** Root-mean-square daily log return of the member's traded mark since the week opened, ending at {@code markNow}. */
    private double weekVolatility(State state, long markNow) {
        LocalDate openDay = state.weekOpenAt().atZone(ZONE).toLocalDate();
        List<Long> marks = new ArrayList<>(jdbc.queryForList(
                "SELECT traded_mark FROM career_daily_marks WHERE user_id = ? AND day >= ? ORDER BY day", Long.class,
                state.userId(), Date.valueOf(openDay)));
        marks.add(state.traded(markNow));
        double sum = 0;
        int n = 0;
        for (int i = 1; i < marks.size(); i++) {
            if (marks.get(i - 1) <= 0 || marks.get(i) <= 0) continue;
            double r = Math.log((double) marks.get(i) / marks.get(i - 1));
            sum += r * r;
            n++;
        }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    /**
     * Starts the member's ranked week at {@code at}, from {@code traded} (confirmed asset less outside cash)
     * and the index then. A grade change also clears the points, which belong to the grade they were won in.
     */
    private void openWeek(long userId, Instant at, long traded, boolean resetPoints) {
        jdbc.update("""
                UPDATE career_state SET promotion_points = CASE WHEN ? THEN 0 ELSE promotion_points END,
                  week_open_at = ?, week_open_traded = ?, week_open_index = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, resetPoints, Timestamp.from(at), traded, indexAt(at), userId);
    }

    /** The game index at or just before {@code at}; null when no observation exists (a fresh install). */
    Double indexAt(Instant at) {
        try {
            List<Double> found = jdbc.queryForList("""
                    SELECT index_value FROM derivative_index_observations
                    WHERE observed_at <= ? AND invalidated = FALSE ORDER BY observed_at DESC LIMIT 1
                    """, Double.class, Timestamp.from(at));
            return found.isEmpty() ? null : found.get(0);
        } catch (org.springframework.dao.DataAccessException missing) {
            return null;
        }
    }

    /** The Korean Monday 0시 that starts the week containing {@code now}. */
    static Instant weekStart(Instant now) {
        LocalDate day = now.atZone(ZONE).toLocalDate();
        return day.minusDays(day.getDayOfWeek().getValue() - 1L).atStartOfDay(ZONE).toInstant();
    }

    private static int permil(double ratio) { return (int) Math.round(ratio * 1000); }

    /**
     * A day that ended beyond the loss limit counts as a breach even if the member never
     * tried to buy again that day, so "no breaches" cannot be kept by simply waiting.
     */
    private void recordMissedDayLimit(State state, long mark, LocalDate today) {
        if (!state.grade().atLeast(CareerGrade.STAFF) || state.dayOpenAsset() == null || state.dayOpenOn() == null
                || !state.dayOpenOn().isBefore(today)) return;
        long limit = (long) Math.floor(state.gradeBase() * DAY_LOSS_LIMIT);
        if (state.dayOpenAsset() - mark <= limit) return;
        Timestamp dayStart = Timestamp.from(state.dayOpenOn().atStartOfDay(ZONE).toInstant());
        if (count("SELECT COUNT(*) FROM career_events WHERE user_id = ? AND kind = 'DAY_LIMIT' AND created_at >= ?",
                state.userId(), dayStart) > 0) return;
        jdbc.update("UPDATE career_state SET breach_count = breach_count + 1, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?",
                state.userId());
        event(state.userId(), "DAY_LIMIT", state.grade(), state.grade(), mark, 0);
    }

    // ── 리셋 ────────────────────────────────────────────────

    /** Life reset: back to a trainee with an empty wallet. Runs inside the reset's transaction. */
    public static void resetAccount(JdbcTemplate jdbc, long userId) {
        PersonalWallet.ensureRow(jdbc, userId);
        jdbc.update("DELETE FROM personal_ledger WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM career_events WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE career_state SET grade = 'TRAINEE', grade_since = CURRENT_TIMESTAMP(3), grade_base = ?, outside_cash = 0, return_streak = 0, high_water_mark = ?,
                  promotion_points = 0, week_open_at = NULL, week_open_traded = NULL, week_open_index = NULL,
                  personal_cash = 0, ojt_steps = '', day_open_on = NULL, day_open_asset = NULL, day_locked_until = NULL,
                  lock_reason = NULL, breach_count = 0, last_settled_on = NULL, legacy = FALSE,
                  updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, CareerGrade.TRAINEE.allocation, CareerGrade.TRAINEE.allocation, userId);
    }

    /**
     * Admin cash moved in or out of the trading account. It is counted in {@code outside_cash},
     * which returns and the loss line take back out, and the high-water mark and the day's
     * opening asset move by the same amount, so it never becomes bonus, promotion gain or a
     * daily loss. Runs inside the admin change's transaction.
     */
    public static void shiftForOutsideCash(JdbcTemplate jdbc, long userId, long amount) {
        if (amount == 0) return;
        jdbc.update("""
                UPDATE career_state SET outside_cash = outside_cash + ?, high_water_mark = high_water_mark + ?,
                  day_open_asset = CASE WHEN day_open_asset IS NULL THEN NULL ELSE day_open_asset + ? END,
                  updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, amount, amount, amount, userId);
    }

    /** After an admin season reset puts the trading account back to the starting cash, re-base the grade on it. */
    public static void rebase(JdbcTemplate jdbc, long userId, long asset) {
        jdbc.update("""
                UPDATE career_state SET grade_since = CURRENT_TIMESTAMP(3), grade_base = ?, outside_cash = 0, return_streak = 0, high_water_mark = ?,
                  week_open_at = NULL, week_open_traded = NULL, week_open_index = NULL,
                  day_open_asset = NULL, day_open_on = NULL, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?
                """, asset, asset, userId);
    }

    // ── 도움 ────────────────────────────────────────────────

    State load(long userId, boolean lock) {
        PersonalWallet.ensureRow(jdbc, userId);
        var row = jdbc.queryForList("""
                SELECT c.*, u.username, COALESCE(u.role, 'USER') AS role, u.password_hash = 'TRADER' AS trader
                FROM career_state c JOIN users u ON u.id = c.user_id
                WHERE c.user_id = ?""" + (lock ? " FOR UPDATE" : ""), userId).get(0);
        Set<String> ojt = new LinkedHashSet<>();
        String raw = (String) row.get("ojt_steps");
        if (raw != null && !raw.isBlank()) ojt.addAll(Arrays.asList(raw.split(",")));
        boolean exempt = "ADMIN".equalsIgnoreCase(String.valueOf(row.get("role"))) || "demo".equals(row.get("username"));
        return new State(userId, CareerGrade.parse((String) row.get("grade")), instant(row.get("grade_since")),
                number(row.get("grade_base")), number(row.get("outside_cash")), (int) number(row.get("promotion_points")),
                row.get("week_open_at") instanceof Timestamp w ? w.toInstant() : null,
                row.get("week_open_traded") == null ? null : number(row.get("week_open_traded")),
                row.get("week_open_index") instanceof Number i ? i.doubleValue() : null,
                number(row.get("high_water_mark")), number(row.get("personal_cash")), ojt,
                row.get("day_open_on") instanceof Date d ? d.toLocalDate() : null,
                row.get("day_open_asset") == null ? null : number(row.get("day_open_asset")),
                row.get("day_locked_until") instanceof Timestamp t ? t.toInstant() : null, (String) row.get("lock_reason"),
                row.get("last_settled_on") instanceof Date s ? s.toLocalDate() : null,
                truthy(row.get("legacy")), row.get("migrated_at") != null, exempt, truthy(row.get("trader")));
    }

    long markAsset(long userId) {
        return jdbc.queryForObject("SELECT " + MARK_ASSET + " FROM users u WHERE u.id = ?", Long.class, userId);
    }

    long confirmedAsset(long userId) {
        return jdbc.queryForObject("SELECT " + CONFIRMED_ASSET + " FROM users u WHERE u.id = ?", Long.class, userId);
    }

    private State openDayIfNeeded(State state, long mark, Instant now) {
        LocalDate today = today(now);
        if (today.equals(state.dayOpenOn())) return state;
        jdbc.update("UPDATE career_state SET day_open_on = ?, day_open_asset = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?",
                Date.valueOf(today), mark, state.userId());
        return load(state.userId(), true);
    }

    /** Firm money into the trading account, recorded as outside cash so weekly and season returns leave it out. */
    private void topUp(long userId, long amount, String what, String reason) {
        if (amount <= 0) return;
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount, userId);
        jdbc.update("INSERT INTO admin_cash_adjustments (request_key, admin_id, user_id, amount, reason) VALUES (?, 0, ?, ?, ?)",
                "career:" + userId + ":" + what + ":" + clock.instant().toEpochMilli(), userId, amount, reason);
    }

    private void event(long userId, String kind, CareerGrade from, CareerGrade to, long asset, long topUp) {
        jdbc.update("INSERT INTO career_events (user_id, kind, from_grade, to_grade, asset_at, top_up, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, kind, from == null ? null : from.name(), to == null ? null : to.name(), asset, topUp, Timestamp.from(clock.instant()));
    }

    private List<Event> events(long userId) {
        return jdbc.query("""
                SELECT kind, from_grade, to_grade, asset_at, top_up, created_at FROM career_events
                WHERE user_id = ? ORDER BY id DESC LIMIT 10
                """, (rs, row) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getTimestamp(6).toInstant().toString()), userId);
    }

    private UserMarketEvent notice(long userId, CareerGrade from, CareerGrade to) {
        return new UserMarketEvent(userId, "CAREER_CHANGED", Map.of("from", from.name(), "to", to.name(), "label", to.label));
    }

    private void publish(UserMarketEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException error) {
            log.debug("커리어 알림을 보내지 못했습니다: {}", error.getMessage());
        }
    }

    /** Return on the grade's base in tenths of a percent, in integers so 3,300,000 on 3,000,000 is exactly +10.0%. */
    private static long returnPermil(State state, long confirmed) {
        return state.gradeBase() <= 0 ? 0 : Math.floorDiv((state.traded(confirmed) - state.gradeBase()) * 1000, state.gradeBase());
    }

    /** The smallest fill that counts toward promotion, so one-share trades cannot tick the boxes. */
    private static long minFill(State state) {
        return Math.max(1, (long) Math.ceil(state.gradeBase() * MIN_FILL_SHARE));
    }

    private long tradingDays(long userId, Timestamp since, long minFill) {
        return count("""
                SELECT COUNT(DISTINCT d) FROM (
                  SELECT DATE(CONVERT_TZ(created_at, '+00:00', '+09:00')) AS d FROM trades
                    WHERE buyer_id = ? AND created_at >= ? AND quantity * price >= ?
                  UNION SELECT DATE(CONVERT_TZ(created_at, '+00:00', '+09:00')) FROM trades
                    WHERE seller_id = ? AND created_at >= ? AND quantity * price >= ?
                ) days
                """, userId, since, minFill, userId, since, minFill);
    }

    private long dayLimitBreaches(long userId, Timestamp since) {
        return count("SELECT COUNT(*) FROM career_events WHERE user_id = ? AND kind = 'DAY_LIMIT' AND created_at >= ?", userId, since);
    }

    private long newsFills(long userId, Timestamp since, long minFill) {
        String recentNews = """
                EXISTS (SELECT 1 FROM market_news_valuation n WHERE n.stock_id = t.stock_id
                  AND n.published_at <= t.created_at AND n.published_at >= t.created_at - INTERVAL %d MINUTE)
                """.formatted(NEWS_WINDOW.toMinutes());
        return count("SELECT COUNT(*) FROM trades t WHERE t.buyer_id = ? AND t.created_at >= ? AND t.quantity * t.price >= ? AND " + recentNews,
                userId, since, minFill)
                + count("SELECT COUNT(*) FROM trades t WHERE t.seller_id = ? AND t.created_at >= ? AND t.buyer_id <> t.seller_id"
                        + " AND t.quantity * t.price >= ? AND " + recentNews, userId, since, minFill);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static Step check(String id, String label, boolean done) {
        return new Step(id, label, "CHECK", done ? 1 : 0, 1, done);
    }

    private static Step counted(String id, String label, long current, long target) {
        return new Step(id, label, "COUNT", Math.min(current, target), target, current >= target);
    }

    private String lockMessage(State state) {
        String until = state.lockedUntil().atZone(ZONE).toLocalDateTime().toString().replace('T', ' ');
        if (until.length() > 16) until = until.substring(0, 16);
        return "RETRAIN".equals(state.lockReason())
                ? "손실한도를 넘어 재교육 중이에요. " + until + "까지 새로 사거나 진입할 수 없어요. 파는 건 할 수 있어요."
                : "일일 손실한도를 넘었어요. " + until + "까지 새로 사거나 진입할 수 없어요. 파는 건 할 수 있어요.";
    }

    private LocalDate today(Instant now) { return now.atZone(ZONE).toLocalDate(); }

    private static String won(long amount) { return String.format(Locale.ROOT, "%,d원", amount); }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }

    private static Instant instant(Object value) { return value instanceof Timestamp t ? t.toInstant() : Instant.EPOCH; }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
