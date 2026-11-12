package com.gamestock.backend.market;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Advanced features open step by step as a member trades.
 *
 * <p>Futures open after a few stock fills, options after a few futures, quant after
 * thirty fills and tap after three daily predictions. Any account older than
 * {@link #AGE_UNLOCK} has everything, so an occasional visitor is never locked out for
 * good. Accounts that existed before this system ({@code unlock_all}) and admin or demo
 * accounts are always unlocked. Closing an existing position is never gated.
 */
@Service
public class FeatureUnlockService {
    public enum Feature { FUTURES, OPTIONS, QUANT, TAP }

    static final Duration AGE_UNLOCK = Duration.ofDays(14);
    static final int FUTURES_BUYS = 3;
    static final int FUTURES_SELLS = 1;
    static final int OPTIONS_FUTURES = 3;
    static final int QUANT_FILLS = 30;
    static final int TAP_PREDICTIONS = 3;

    public record Step(String label, long current, long target, boolean done) { }
    public record FeatureProgress(String id, String name, boolean unlocked, List<Step> steps, String hint) { }
    public record Progress(boolean allUnlocked, String reason, String ageUnlockAt, List<FeatureProgress> features) { }

    private final JdbcTemplate jdbc;
    private Clock clock = Clock.systemUTC();

    public FeatureUnlockService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void useClock(Clock clock) { this.clock = clock; }

    public Progress progress(long userId) {
        var rows = jdbc.queryForList("""
                SELECT u.created_at, u.username, COALESCE(u.role,'USER') AS role, COALESCE(e.unlock_all,FALSE) AS unlock_all
                FROM users u LEFT JOIN user_engagement e ON e.user_id=u.id WHERE u.id=?
                """, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("계정을 찾을 수 없습니다.");
        var row = rows.get(0);
        Instant created = row.get("created_at") instanceof Timestamp t ? t.toInstant() : clock.instant();
        Instant ageAt = created.plus(AGE_UNLOCK);
        String reason = null;
        if (truthy(row.get("unlock_all"))) reason = "EARLY_MEMBER";
        else if ("ADMIN".equalsIgnoreCase(String.valueOf(row.get("role"))) || "demo".equals(row.get("username"))) reason = "STAFF";
        else if (!clock.instant().isBefore(ageAt)) reason = "ACCOUNT_AGE";

        long buys = count("SELECT COUNT(*) FROM trades WHERE buyer_id=?", userId);
        long sells = count("SELECT COUNT(*) FROM trades WHERE seller_id=?", userId);
        long futures = count("SELECT COUNT(*) FROM derivative_positions WHERE user_id=? AND kind='FUTURE'", userId);
        long predictions = count("SELECT COUNT(*) FROM daily_predictions WHERE user_id=?", userId);
        boolean all = reason != null;

        var futureSteps = List.of(step("주식 매수 체결", buys, FUTURES_BUYS), step("주식 매도 체결", sells, FUTURES_SELLS));
        boolean futuresOpen = all || futureSteps.stream().allMatch(Step::done);
        var optionSteps = List.of(step("선물 열기", futuresOpen ? 1 : 0, 1), step("선물 거래", futures, OPTIONS_FUTURES));
        boolean optionsOpen = all || optionSteps.stream().allMatch(Step::done);
        var quantSteps = List.of(step("주식 체결", buys + sells, QUANT_FILLS));
        var tapSteps = List.of(step("일일 예측 참여", predictions, TAP_PREDICTIONS));

        var features = List.of(
                feature(Feature.FUTURES, "선물", futuresOpen, futureSteps),
                feature(Feature.OPTIONS, "옵션", optionsOpen, optionSteps),
                feature(Feature.QUANT, "퀀트", all || quantSteps.get(0).done(), quantSteps),
                feature(Feature.TAP, "탭 베팅", all || tapSteps.get(0).done(), tapSteps));
        return new Progress(all, reason, ageAt.toString(), features);
    }

    public boolean unlocked(long userId, Feature feature) {
        return progress(userId).features().stream()
                .filter(item -> item.id().equals(feature.name())).findFirst().map(FeatureProgress::unlocked).orElse(false);
    }

    /** Throws 403 with the remaining condition when the feature is still locked. */
    public void require(long userId, Feature feature) {
        var item = progress(userId).features().stream().filter(f -> f.id().equals(feature.name())).findFirst().orElseThrow();
        if (!item.unlocked())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, item.name() + "은(는) 아직 잠겨 있어요. " + item.hint());
    }

    /** Derivative kinds that need an unlock; ETFs trade like stocks and stay open. */
    public void requireForDerivative(long userId, String kind) {
        if ("FUTURE".equalsIgnoreCase(kind)) require(userId, Feature.FUTURES);
        else if ("OPTION".equalsIgnoreCase(kind)) require(userId, Feature.OPTIONS);
    }

    private FeatureProgress feature(Feature id, String name, boolean unlocked, List<Step> steps) {
        String hint = unlocked ? "열렸어요." : steps.stream().filter(s -> !s.done()).findFirst()
                .map(s -> s.label() + " " + (s.target() - s.current()) + "번 더 하면 열려요.").orElse("");
        return new FeatureProgress(id.name(), name, unlocked, steps, hint);
    }

    private static Step step(String label, long current, long target) {
        return new Step(label, Math.min(current, target), target, current >= target);
    }

    private long count(String sql, long userId) {
        Long value = jdbc.queryForObject(sql, Long.class, userId);
        return value == null ? 0 : value;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
