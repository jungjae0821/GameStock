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
 * 접속 시간대 안에서만 일반 회원과 같은 {@link OrderService#submit} 경로로 주문을 낸다. 매수 일부는
 * 지금 가격의 지정가로 내서 수습 OJT의 지정가 매수 단계도 사람처럼 채운다.
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
    /** 매수 가운데 지정가로 내는 비율. */
    static final double LIMIT_BUY_CHANCE = 0.3;
    /** OJT 지정가 매수를 지금 가격보다 얼마나 올려 낼지. 호가 몇 틱 차이는 넉넉히 넘는다. */
    static final double OJT_LIMIT_PREMIUM = 0.02;
    /** OJT 매수 1주에 수수료를 더해도 현금이 모자라지 않게 둘 여유. */
    static final double OJT_CASH_MARGIN = 1.01;
    private static final Duration QUOTE_CACHE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final OrderService orders;
    private final EngagementService engagement;
    private final TitleService titles;
    private final AuthService auth;
    private final CareerService career;
    private final boolean enabled;
    private final int guardRank;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();
    private final Map<Long, LocalDate> choresDone = new ConcurrentHashMap<>();
    private volatile boolean accountsReady;
    private volatile Instant quotesAt;
    private volatile List<Quote> quotes = List.of();

    public PersonaTraderService(JdbcTemplate jdbc, MarketService market, OrderService orders, EngagementService engagement,
                                TitleService titles, AuthService auth, CareerService career,
                                @Value("${gamestock.persona.enabled:true}") boolean enabled,
                                @Value("${gamestock.persona.guard-rank:10}") int guardRank) {
        this.jdbc = jdbc;
        this.market = market;
        this.orders = orders;
        this.engagement = engagement;
        this.titles = titles;
        this.auth = auth;
        this.career = career;
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
        if (career.grade(id) == CareerGrade.TRAINEE && finishOjt(persona, id)) return;
        if (random.nextDouble() >= persona.actionChancePerMinute(Persona.dayOf(now))) return;
        trade(persona, id, random);
    }

    /**
     * 수습 봇의 OJT를 매 분 마저 채운다. 커리어 조회가 승진 판정을 겸하므로, 단계를 다 채운 봇은 다음 분에 사원이 된다.
     *
     * <p>평소 매매에만 맡기면 OJT가 끝나지 않는다. 실제 회원이 적으면 봇이 매수 보호 경계 안에 들어 매수가 막히고,
     * 지금 가격 지정가는 매도호가가 한두 틱 위면 체결되지 않고 다음 분에 취소된다. 그래서 남은 지정가 매수는
     * 매도호가에 닿는 가격으로 1주만 내고, 경계는 보지 않는다. 1주는 자산 순위를 거의 바꾸지 않는다.
     * 주문을 냈으면 true.
     */
    boolean finishOjt(Persona persona, long id) {
        CareerService.Career now = career.career(id);
        if (!CareerGrade.TRAINEE.name().equals(now.grade())) return false;
        Set<String> left = now.promotion().stream().filter(step -> !step.done()).map(CareerService.Step::id).collect(Collectors.toSet());
        for (String step : CareerService.CLIENT_STEPS) if (left.contains(step)) career.acknowledge(id, step);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='OPEN'", Integer.class, id);
        if (open != null && open > 0) {
            // 체결되지 않은 OJT 주문이 남았으면 거두고 다음 분에 다시 낸다.
            orders.cancelAll(id);
            return true;
        }
        List<Quote> prices = quotes();
        Order order = ojtOrder(left, persona.fandomCode(), account(id), prices);
        if (order == null) return false;
        submit(persona, id, order, order.side().equals("BUY") ? ojtLimitPrice(prices, order.code()) : null);
        return true;
    }

    /** 남은 OJT 매매 단계를 채울 주문. 지정가 매수가 먼저이고, 그다음 들고 있는 종목 1주 매도. 할 게 없으면 null. */
    static Order ojtOrder(Set<String> left, String fandomCode, Account account, List<Quote> quotes) {
        if (left.contains("LIMIT_BUY")) {
            Comparator<Quote> fandomFirst = Comparator.comparing((Quote q) -> !q.code().equals(fandomCode)).thenComparingLong(Quote::price);
            return quotes.stream().filter(q -> q.price() > 0 && ojtLimitPrice(quotes, q.code()) * OJT_CASH_MARGIN <= account.cash())
                    .min(fandomFirst).map(q -> new Order(q.code(), "BUY", 1, "OJT 지정가 매수")).orElse(null);
        }
        if (left.contains("SELL"))
            return account.holdings().stream().filter(h -> h.quantity() > 0).findFirst()
                    .map(h -> new Order(h.code(), "SELL", 1, "OJT 매도")).orElse(null);
        return null;
    }

    /**
     * OJT 지정가 매수 가격. 지금 가격보다 {@link #OJT_LIMIT_PREMIUM}만큼 올려 매도호가에 닿게 하되, 전날 종가 기준
     * 상한가는 넘지 않는다. 체결은 매도호가로 되므로 실제로 더 비싸게 사지는 않는다.
     */
    static long ojtLimitPrice(List<Quote> quotes, String code) {
        Quote quote = quotes.stream().filter(q -> q.code().equals(code)).findFirst().orElseThrow();
        long wanted = PriceLimitPolicy.ceilToTick(quote.price() * (1 + OJT_LIMIT_PREMIUM));
        long reference = quote.prevClose() > 0 ? quote.prevClose() : quote.price();
        return Math.max(PriceLimitPolicy.ceilToTick(quote.price()), Math.min(wanted, PriceLimitPolicy.dailyBand(reference).upperPrice()));
    }

    /** 그날 처음 접속했을 때: 출석, 수습 OJT 확인, 칭호 확인과 가끔 바꿔 달기, 마감 전이면 일일 예측. */
    private void chores(Persona persona, long id, ZonedDateTime now, Random random) {
        auth.grantAttendanceReward(id);
        // 서버가 직접 볼 수 없는 OJT 단계(종목 화면 보기, 손실한도 교육)는 앱이 알려 주는데, 봇은 앱이 없으니 직접 남긴다.
        for (String step : CareerService.CLIENT_STEPS) career.acknowledge(id, step);
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
        Long limit = chosen.side().equals("BUY") && random.nextDouble() < LIMIT_BUY_CHANCE ? limitPrice(quotes(), chosen.code()) : null;
        submit(persona, id, chosen, limit);
    }

    private void submit(Persona persona, long id, Order chosen, Long limit) {
        orders.submit(id, new OrderRequest(chosen.code(), chosen.side(), chosen.quantity(), limit == null ? "MARKET" : "LIMIT", limit))
                .whenComplete((result, error) -> {
                    if (error != null) log.debug("운영 봇 {} 주문 거절({} {} {}): {}", persona.username(), chosen.side(),
                            chosen.code(), chosen.quantity(), error.getMessage());
                    else log.debug("운영 봇 {} 주문 {} {} {} {} ({})", persona.username(), chosen.side(), chosen.code(),
                            chosen.quantity(), limit == null ? "시장가" : "지정가 " + limit, chosen.reason());
                });
    }

    /**
     * 지정가 매수 가격. 지금 가격을 호가 단위로 올려 잡아 대개 바로 체결되게 한다. 남으면 다음 차례에 취소된다.
     * 가격을 모르면 null을 돌려주고, 그때는 시장가로 낸다.
     */
    static Long limitPrice(List<Quote> quotes, String code) {
        return quotes.stream().filter(q -> q.code().equals(code) && q.price() > 0).findFirst()
                .map(q -> PriceLimitPolicy.ceilToTick(q.price())).orElse(null);
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
