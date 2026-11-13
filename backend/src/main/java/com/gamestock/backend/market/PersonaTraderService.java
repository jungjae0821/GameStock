package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.gamestock.backend.market.MarketModels.OrderRequest;
import static com.gamestock.backend.market.PersonaDecisions.*;

/**
 * {@link Persona} 운영 봇들을 1분마다 움직인다. 접속한 날에는 출석·칭호·일일 예측을 챙기고,
 * 접속 시간대 안에서만 일반 회원과 같은 {@link OrderService#submit} 경로로 시장가 주문을 낸다.
 *
 * <p>시즌·주간·자산 랭킹 중 하나라도 {@code guardRank}에 가까우면 새로 사지 않는다. 랭킹에 실제 회원이
 * 적으면 그 경계도 실제 회원 수까지로 줄어든다. 그래도
 * 보상 순위에 들면 시즌 종료와 주간 칭호 지급이 이들을 건너뛴다.
 * 관리자가 봇이나 트레이더 봇, 시장을 멈추면 함께 멈춘다.
 */
@Service
public class PersonaTraderService {
    private static final Logger log = LoggerFactory.getLogger(PersonaTraderService.class);
    /** 경계선보다 이만큼 아래 순위부터 미리 매수를 멈춘다. */
    static final int GUARD_MARGIN = 3;
    private static final Duration QUOTE_CACHE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final OrderService orders;
    private final EngagementService engagement;
    private final TitleService titles;
    private final AuthService auth;
    private final boolean enabled;
    private final int guardRank;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();
    private final Map<Long, LocalDate> choresDone = new ConcurrentHashMap<>();
    private volatile boolean accountsReady;
    private volatile Instant quotesAt;
    private volatile List<Quote> quotes = List.of();

    public PersonaTraderService(JdbcTemplate jdbc, MarketService market, OrderService orders, EngagementService engagement,
                                TitleService titles, AuthService auth,
                                @Value("${gamestock.persona.enabled:true}") boolean enabled,
                                @Value("${gamestock.persona.guard-rank:10}") int guardRank) {
        this.jdbc = jdbc;
        this.market = market;
        this.orders = orders;
        this.engagement = engagement;
        this.titles = titles;
        this.auth = auth;
        this.enabled = enabled;
        this.guardRank = guardRank;
    }

    @Scheduled(fixedDelayString = "${gamestock.persona.tick-ms:60000}", initialDelayString = "${gamestock.persona.initial-ms:120000}")
    public void tick() {
        if (!enabled || !market.matchingEnabled() || !market.ownerAvailable()) return;
        AdminRuntimeControl runtime = market.adminRuntime();
        if (runtime != null && (runtime.settings().tradersStopped() || runtime.settings().marketPaused())) return;
        try {
            ensureAccounts();
        } catch (RuntimeException error) {
            log.warn("운영 봇 계정을 준비하지 못했습니다. 다음 주기에 다시 시도합니다.", error);
            return;
        }
        ZonedDateTime now = market.clock().instant().atZone(Persona.ZONE);
        for (Persona persona : Persona.ALL) {
            Long id = ids.get(persona.username());
            if (id == null || !persona.onlineAt(now)) continue;
            try {
                act(persona, id, now, ThreadLocalRandom.current());
            } catch (RuntimeException error) {
                log.warn("운영 봇 {} 활동에 실패했습니다.", persona.username(), error);
            }
        }
    }

    // ── 계정 ────────────────────────────────────────────────

    /**
     * 처음 한 번 계정과 팬덤·공개 프로필을 만든다. 닉네임을 이미 실제 회원이 쓰고 있으면 그 봇은
     * 만들지 않는다. 기존 계정의 자산과 칭호는 건드리지 않는다.
     */
    void ensureAccounts() {
        if (accountsReady) return;
        for (Persona persona : Persona.ALL) {
            Long id = personaId(persona);
            if (id == null) {
                Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname=? OR username=?", Integer.class,
                        persona.nickname(), persona.username());
                if (taken != null && taken > 0) {
                    log.warn("운영 봇 {}의 닉네임이나 아이디를 이미 다른 계정이 쓰고 있어 만들지 않습니다.", persona.username());
                    continue;
                }
                jdbc.update("""
                        INSERT INTO users (username, password_hash, nickname, google_uid, profile_completed, persona, cash)
                        VALUES (?, ?, ?, ?, TRUE, ?, 1000000)
                        """, persona.username(), Persona.PASSWORD, persona.nickname(), persona.googleUid(), persona.style().name());
                id = personaId(persona);
                log.info("운영 봇 {}({})를 만들었습니다.", persona.nickname(), persona.style());
            }
            if (id == null) continue;
            if (engagement.me(id).fandomCode() == null) engagement.setFandom(id, new EngagementService.FandomRequest(persona.fandomCode()));
            engagement.setPublic(id, new EngagementService.PublicRequest(true));
            ids.put(persona.username(), id);
        }
        accountsReady = true;
    }

    private Long personaId(Persona persona) {
        return jdbc.queryForList("SELECT id FROM users WHERE username=? AND password_hash=?", Long.class,
                persona.username(), Persona.PASSWORD).stream().findFirst().orElse(null);
    }

    // ── 하루 활동 ────────────────────────────────────────────

    void act(Persona persona, long id, ZonedDateTime now, Random random) {
        // 출석과 일일 예측은 자정에 바뀌므로 달력 날짜로, 매매 빈도는 밤을 넘기는 봇의 하루로 센다.
        LocalDate day = now.toLocalDate();
        if (!day.equals(choresDone.get(id))) {
            chores(persona, id, now, random);
            choresDone.put(id, day);
        }
        if (random.nextDouble() >= persona.actionChancePerMinute(Persona.dayOf(now))) return;
        trade(persona, id, random);
    }

    /** 그날 처음 접속했을 때: 출석, 칭호 확인과 가끔 바꿔 달기, 마감 전이면 일일 예측. */
    private void chores(Persona persona, long id, ZonedDateTime now, Random random) {
        auth.grantAttendanceReward(id);
        var status = titles.check(id);
        var owned = status.titles().stream().filter(TitleService.UserTitle::owned).toList();
        boolean wearing = owned.stream().anyMatch(TitleService.UserTitle::equipped);
        if (!owned.isEmpty() && (!wearing || random.nextDouble() < 0.15))
            titles.equip(id, owned.get(random.nextInt(owned.size())).id());
        if (now.getHour() < EngagementService.PREDICTION_CUTOFF_HOUR && random.nextDouble() < 0.8) {
            String pick = predictionPick(persona, quotes());
            if (pick != null) {
                try {
                    engagement.predict(id, new EngagementService.PredictionRequest(pick));
                } catch (IllegalArgumentException alreadyPredicted) {
                    log.debug("운영 봇 {} 예측 건너뜀: {}", persona.username(), alreadyPredicted.getMessage());
                }
            }
        }
    }

    static String predictionPick(Persona persona, List<Quote> quotes) {
        Comparator<Quote> order = switch (persona.style()) {
            case SCALP -> Comparator.comparingDouble(Quote::momentum);
            case LONG -> Comparator.comparingDouble(Quote::drawdown);
            case NEWBIE -> null;
        };
        if (order == null) return persona.fandomCode();
        return quotes.stream().filter(q -> q.price() > 0).max(order).map(Quote::code).orElse(persona.fandomCode());
    }

    private void trade(Persona persona, long id, Random random) {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'", Integer.class, id);
        if (open != null && open > 0) {
            // 체결되지 않고 남은 주문은 사람처럼 한 번 취소하고 이번 차례는 넘긴다.
            orders.cancelAll(id);
            return;
        }
        Optional<Order> order = decide(persona, account(id), quotes(), false, random);
        if (order.isEmpty()) return;
        if (order.get().side().equals("BUY") && nearReward(id, persona.nickname())) {
            log.debug("운영 봇 {}가 보상 순위 가까이에 있어 매수를 쉬어 갑니다.", persona.username());
            return;
        }
        Order chosen = order.get();
        orders.submit(id, new OrderRequest(chosen.code(), chosen.side(), chosen.quantity(), "MARKET", null))
                .whenComplete((result, error) -> {
                    if (error != null) log.debug("운영 봇 {} 주문 거절({} {} {}): {}", persona.username(), chosen.side(),
                            chosen.code(), chosen.quantity(), error.getMessage());
                    else log.debug("운영 봇 {} 주문 {} {} {} ({})", persona.username(), chosen.side(), chosen.code(),
                            chosen.quantity(), chosen.reason());
                });
    }

    // ── 보상 순위 경계 ───────────────────────────────────────

    /** 진행 중인 시즌, 이번 주 수익률, 자산 랭킹 가운데 하나라도 경계선 근처면 true. */
    boolean nearReward(long id, String nickname) {
        var ranking = market.ranking();
        int limit = guardLimit(guardRank, realMembers(ranking));
        if (limit == 0) return false;
        var season = engagement.season(id);
        if (season != null && "ACTIVE".equals(season.status()) && season.me() != null && season.me().rank() <= limit) return true;
        var weekly = titles.weeklyRanking(id);
        if (weekly != null && weekly.me() != null && weekly.me().rank() <= limit) return true;
        for (int i = 0; i < Math.min(limit, ranking.size()); i++)
            if (nickname.equals(ranking.get(i).nickname())) return true;
        return false;
    }

    /**
     * 매수를 멈추는 순위 경계. 원래는 {@code guardRank + GUARD_MARGIN}위까지지만, 랭킹에 실제 회원이 그보다
     * 적으면 실제 회원 수까지로 줄인다. 회원이 적을 때 랭킹 전체가 경계 안에 들어가 운영 봇이 아무것도
     * 못 사는 일을 막는다. 보상은 정산이 운영 봇을 건너뛰므로 실제 회원 몫을 가져가지 않는다.
     */
    static int guardLimit(int guardRank, int realMembers) {
        return Math.max(0, Math.min(guardRank + GUARD_MARGIN, realMembers));
    }

    /** 자산 랭킹에서 운영 봇을 뺀 실제 회원 수. */
    static int realMembers(List<MarketModels.RankingEntry> ranking) {
        Set<String> personas = Persona.ALL.stream().map(Persona::nickname).collect(Collectors.toSet());
        return (int) ranking.stream().filter(entry -> !personas.contains(entry.nickname())).count();
    }

    // ── 계좌와 시세 ──────────────────────────────────────────

    private Account account(long id) {
        Long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id=?", Long.class, id);
        Long net = jdbc.queryForObject("SELECT (" + AccountValuation.GROSS_ASSET + ") - u.loan_balance FROM users u WHERE u.id=?", Long.class, id);
        Instant now = market.clock().instant();
        List<Holding> holdings = jdbc.query("""
                SELECT s.stock_code, p.quantity, p.average_price, COALESCE(pm.mark_price, s.current_price) AS price,
                       (SELECT MAX(t.created_at) FROM trades t WHERE t.buyer_id=p.user_id AND t.stock_id=p.stock_id) AS bought_at
                FROM portfolios p JOIN stocks s ON s.id=p.stock_id LEFT JOIN market_price_metrics pm ON pm.stock_id=s.id
                WHERE p.user_id=? AND p.quantity>0
                """, (rs, row) -> {
            Timestamp bought = rs.getTimestamp("bought_at");
            long held = bought == null ? 0 : Math.max(0, Duration.between(bought.toInstant(), now).toMinutes());
            return new Holding(rs.getString("stock_code"), rs.getLong("quantity"), rs.getLong("average_price"),
                    rs.getLong("price"), held);
        }, id);
        return new Account(cash == null ? 0 : cash, net == null ? 0 : net, holdings);
    }

    /** 모든 종목의 지금 가격, 최근 7거래일 종가 최고, 전날 종가, 30분 전 가격. 30초 동안 재사용한다. */
    List<Quote> quotes() {
        Instant now = market.clock().instant();
        if (quotesAt != null && quotesAt.plus(QUOTE_CACHE).isAfter(now)) return quotes;
        LocalDate today = now.atZone(Persona.ZONE).toLocalDate();
        List<Quote> fresh = new ArrayList<>(jdbc.query("""
                SELECT s.stock_code, COALESCE(pm.mark_price, s.current_price) AS price,
                       (SELECT MAX(d.close_price) FROM daily_market_summaries d WHERE d.stock_id=s.id AND d.trading_date>=?) AS week_high,
                       (SELECT d.close_price FROM daily_market_summaries d WHERE d.stock_id=s.id AND d.trading_date<?
                         ORDER BY d.trading_date DESC LIMIT 1) AS prev_close,
                       (SELECT h.price FROM stock_price_history h WHERE h.stock_id=s.id AND h.recorded_at<=?
                         ORDER BY h.recorded_at DESC LIMIT 1) AS recent
                FROM stocks s LEFT JOIN market_price_metrics pm ON pm.stock_id=s.id ORDER BY s.id
                """, (rs, row) -> {
            long price = rs.getLong("price");
            long weekHigh = Math.max(price, rs.getLong("week_high"));
            long prevClose = rs.getObject("prev_close") == null ? price : rs.getLong("prev_close");
            long recent = rs.getObject("recent") == null ? price : rs.getLong("recent");
            return new Quote(rs.getString("stock_code"), price, weekHigh, prevClose, recent);
        }, Date.valueOf(today.minusDays(7)), Date.valueOf(today), Timestamp.from(now.minus(Duration.ofMinutes(30)))));
        quotes = List.copyOf(fresh);
        quotesAt = now;
        return quotes;
    }
}
