package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * Public ranking and daily missions split out of {@link MarketService}. Not a Spring bean:
 * MarketService constructs it and keeps the public entry points, so {@code rewardMission}
 * still runs inside MarketService's {@code @Transactional} proxy and its {@code synchronized}
 * monitor. Clock and admin controls are read through MarketService on every call because
 * tests and setter injection replace them after construction.
 */
final class MarketRankingMissions {
    private static final long STARTING_CASH = 1_000_000L;
    private static final long MISSION_REWARD_CASH = 10_000L;
    static final List<String> MISSION_IDS = List.of(
            "market", "news", "watch", "ranking", "portfolio", "home", "settings",
            // "career" replaced "loan" when new loans stopped; the daily draw keeps the same size.
            "trade", "career", "titles",
            "stock-gov", "stock-uma", "stock-ba", "stock-gi", "stock-sr",
            "stock-zzz", "stock-crk", "stock-vr", "stock-ow", "stock-pw");
    static final int DAILY_MISSION_COUNT = 3;
    private static final Map<String, Long> MISSION_REWARDS = MISSION_IDS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> MISSION_REWARD_CASH));
    private final MarketService market;
    private final JdbcTemplate jdbc;

    MarketRankingMissions(MarketService market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    /** Grade order for the trader ranking; members not yet migrated rank with trainees. */
    private static final String GRADE_LEVEL = "FIELD(COALESCE(c.grade, 'TRAINEE'), 'TRAINEE', 'STAFF', 'ASSOCIATE', 'MANAGER', 'SENIOR_MANAGER', 'GENERAL_MANAGER', 'EXECUTIVE')";
    private static final String HUMAN_RANKED = """
            u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo' AND COALESCE(u.role, 'USER') <> 'ADMIN'
            """;

    /**
     * The trader ranking: higher grade first, then the return on the grade's base asset.
     * The trading account is the firm's money and grows with every promotion, so ranking by
     * its size would only restate the grade.
     */
    public List<RankingEntry> ranking() {
        Map<Long,Long> externalCash=market.adminRuntime()==null?Map.of():market.adminRuntime().externalCash();
        List<RankingEntry> entries = jdbc.query("""
                SELECT u.id, u.nickname, u.profile_image_url, u.cash, u.loan_balance,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0) AS unsettled_cash,
                       COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0) AS attendance_reward_cash,
                       COALESCE((SELECT SUM(mr.reward_cash) FROM mission_rewards mr WHERE mr.user_id = u.id), 0) AS mission_reward_cash,
                       COALESCE(SUM(CASE WHEN p.quantity > 0 THEN p.quantity * COALESCE(pm.mark_price,s.current_price) ELSE 0 END), 0) AS asset_value,
                       """ + AccountValuation.DERIVATIVE_ASSET + """
                       AS derivative_value,
                       """ + GRADE_LEVEL + """
                        AS grade_level, c.grade, c.grade_base, COALESCE(c.outside_cash, 0) AS outside_cash, COALESCE(c.legacy, FALSE) AND c.migrated_at IS NULL AS waiting,
                       COALESCE(c.personal_cash, 0) AS personal_cash,
                       (SELECT ut.title_id FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_title,
                       (SELECT ut.win_count FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_count
                FROM users u
                LEFT JOIN career_state c ON c.user_id = u.id
                LEFT JOIN portfolios p ON p.user_id = u.id
                LEFT JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE\s""" + HUMAN_RANKED + """
                GROUP BY u.id, u.nickname, u.profile_image_url, u.cash, u.loan_balance, c.grade, c.grade_base, c.outside_cash, c.legacy, c.migrated_at, c.personal_cash
                ORDER BY grade_level DESC,
                  (u.cash + reserved_cash + unsettled_cash + asset_value + derivative_value - u.loan_balance - COALESCE(c.outside_cash, 0) - COALESCE(c.grade_base, 1000000))
                    / GREATEST(COALESCE(c.grade_base, 1000000), 1) DESC,
                  u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            long assetValue = rs.getLong("asset_value");
            long cash = rs.getLong("cash");
            long reservedCash = rs.getLong("reserved_cash");
            long unsettledCash = rs.getLong("unsettled_cash");
            long attendanceRewardCash = rs.getLong("attendance_reward_cash");
            long missionRewardCash = rs.getLong("mission_reward_cash");
            long derivativeValue = rs.getLong("derivative_value");
            // 빌린 돈으로 순위가 오르지 않도록 랭킹 자산은 대출 잔액을 뺀 순자산이다.
            long totalAsset = cash + reservedCash + unsettledCash + assetValue + derivativeValue - rs.getLong("loan_balance");
            CareerGrade grade = CareerGrade.parse(rs.getString("grade"));
            long base = rs.getLong("grade_base");
            long outside = rs.getLong("outside_cash");
            boolean waiting = rs.getBoolean("waiting") || rs.getString("grade") == null;
            // 커리어 회원은 지금 직급의 기준자산 대비 수익률. 이전을 기다리는 회원은 예전처럼
            // 시작금 대비이고, 출석·미션 보상과 외부 입금은 투자 성과가 아니라서 뺀다.
            double changePercent = !waiting && base > 0 ? (totalAsset - outside - base) * 100.0 / base
                    : (totalAsset - STARTING_CASH - attendanceRewardCash - missionRewardCash - externalCash.getOrDefault(rs.getLong("id"),0L)) * 100.0 / STARTING_CASH;
            // Rows saved before image validation may hold arbitrary URLs; never expose them to other viewers.
            String image = com.gamestock.backend.auth.ProfileImages.sanitize(rs.getString("profile_image_url"));
            TitleService.TitleDefinition title = TitleService.definition(rs.getString("equipped_title"));
            return new RankingEntry(0, rs.getString("nickname"), image.isEmpty() ? null : image, totalAsset, assetValue, cash, changePercent,
                    title == null ? null : TitleService.displayName(title, rs.getInt("equipped_count")), title != null && title.weekly(),
                    waiting ? null : grade.name(), waiting ? null : grade.label, rs.getLong("personal_cash"));
        });
        List<RankingEntry> ranked = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            ranked.add(new RankingEntry(index + 1, entry.nickname(), entry.profileImageUrl(), entry.totalAsset(), entry.assetValue(), entry.cash(), entry.changePercent(),
                    entry.equippedTitle(), entry.equippedTitleWeekly(), entry.grade(), entry.gradeLabel(), entry.personalCash()));
        }
        return ranked;
    }

    /** The wealth ranking: the member's own money, which only bonuses and rewards fill. */
    public List<WealthEntry> wealthRanking() {
        List<WealthEntry> entries = jdbc.query("""
                SELECT u.nickname, u.profile_image_url, c.grade, COALESCE(c.legacy, FALSE) AND c.migrated_at IS NULL AS waiting,
                       c.personal_cash,
                       (SELECT ut.title_id FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_title,
                       (SELECT ut.win_count FROM user_titles ut WHERE ut.user_id = u.id AND ut.equipped = TRUE LIMIT 1) AS equipped_count
                FROM users u JOIN career_state c ON c.user_id = u.id
                WHERE\s""" + HUMAN_RANKED + """
                  AND c.personal_cash > 0
                ORDER BY c.personal_cash DESC, u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            String image = com.gamestock.backend.auth.ProfileImages.sanitize(rs.getString("profile_image_url"));
            TitleService.TitleDefinition title = TitleService.definition(rs.getString("equipped_title"));
            boolean waiting = rs.getBoolean("waiting");
            CareerGrade grade = CareerGrade.parse(rs.getString("grade"));
            return new WealthEntry(row + 1, rs.getString("nickname"), image.isEmpty() ? null : image, rs.getLong("personal_cash"),
                    waiting ? null : grade.name(), waiting ? null : grade.label,
                    title == null ? null : TitleService.displayName(title, rs.getInt("equipped_count")), title != null && title.weekly());
        });
        return entries;
    }

    public DailyMissionStatus dailyMissions(long userId) {
        return dailyMissionsAt(userId, market.clock().instant());
    }

    static List<String> dailyMissionIds(LocalDate day) {
        // Seeding by the Korean date gives every server and every user the same random draw
        // for the whole day, and a fresh draw at Korean midnight.
        List<String> shuffled = new ArrayList<>(MISSION_IDS);
        Collections.shuffle(shuffled, new Random(day.toEpochDay() * 0x9E3779B97F4A7C15L));
        return List.copyOf(shuffled.subList(0, DAILY_MISSION_COUNT));
    }

    private DailyMissionStatus dailyMissionsAt(long userId, Instant now) {
        LocalDate day = now.atZone(MarketService.MISSION_ZONE).toLocalDate();
        List<String> available = dailyMissionIds(day).stream()
                .filter(id -> market.adminRuntime()==null || market.adminRuntime().missionReward(id,MISSION_REWARD_CASH)>=0).toList();
        Set<String> completedToday = new HashSet<>(jdbc.queryForList(
                "SELECT mission_id FROM mission_rewards WHERE user_id = ? AND rewarded_on = ? ORDER BY mission_id",
                String.class, userId, java.sql.Date.valueOf(day)));
        List<String> completed = available.stream().filter(completedToday::contains).toList();
        return new DailyMissionStatus(day.toString(), day.plusDays(1).atStartOfDay(MarketService.MISSION_ZONE).toInstant().toString(),
                now.toString(), available, completed);
    }

    public MissionRewardResult rewardMission(String missionId, long userId) {
        if(market.adminRuntime()!=null)AdminSupport.lock(jdbc);
        Long reward = MISSION_REWARDS.get(missionId);
        if (reward == null) throw new IllegalArgumentException("존재하지 않는 미션입니다.");
        if(market.adminRuntime()!=null){reward=market.adminRuntime().missionReward(missionId,reward);if(reward<0)throw new IllegalArgumentException("중지된 미션입니다.");}
        LocalDate day = LocalDate.now(market.clock().withZone(MarketService.MISSION_ZONE));
        if (!dailyMissionIds(day).contains(missionId)) {
            throw new IllegalArgumentException("오늘의 미션에 포함되지 않은 미션입니다.");
        }
        // 페이지 확인형 미션은 서버가 실제 열람을 확인할 수 없어 랭킹 수익률에서 보상을 제외한다.
        // 관심종목 등록은 서버 상태로 확인할 수 있으므로 등록 여부를 검증한다.
        int minimumWatchlist=market.adminRuntime()==null?("watch".equals(missionId)?1:0):market.adminRuntime().minimumWatchlist(missionId);
        if (minimumWatchlist>0) {
            Long watched = jdbc.queryForObject("SELECT COUNT(*) FROM user_watchlists WHERE user_id = ?", Long.class, userId);
            if (watched == null || watched < minimumWatchlist)
                throw new IllegalArgumentException("관심종목을 "+minimumWatchlist+"개 이상 등록하면 보상을 받을 수 있습니다.");
        }
        // reward_cash stays 0: the reward goes to the personal wallet, and reward_cash means trading cash that returns leave out.
        int inserted = jdbc.update("INSERT IGNORE INTO mission_rewards (user_id, mission_id, rewarded_on, reward_cash, wallet_cash) VALUES (?, ?, ?, 0, ?)",
                userId, missionId, java.sql.Date.valueOf(day), reward);
        // Keyed by member, mission and day: an admin season reset deletes the reward rows but not the
        // wallet, so a row-based key would let the same day's mission pay twice.
        if (inserted > 0)
            PersonalWallet.credit(jdbc, userId, "mission:" + userId + ":" + missionId + ":" + day, PersonalWallet.MISSION, reward,
                    "오늘의 미션 보상");
        Portfolio portfolio = market.portfolioUnsafe(userId);
        return new MissionRewardResult(inserted > 0 ? reward : 0, inserted > 0, portfolio,
                dailyMissionsAt(userId, market.clock().instant()));
    }
}
