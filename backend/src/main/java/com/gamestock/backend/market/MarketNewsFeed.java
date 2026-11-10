package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gamestock.backend.market.MarketModels.*;

/**
 * News feed queries and the cached news bias that bots and fair value read, split out of
 * {@link MarketService}. Not a Spring bean: MarketService constructs it, keeps the public
 * entry points and the {@code NewsChangedEvent} listener, and shares its {@link NewsValuation}.
 * None of this code ran under the service monitor before the split and none does now; the
 * bias cache is a concurrent map as before.
 */
final class MarketNewsFeed {
    private static final Pattern NEWS_SOURCE_PATTERN = Pattern.compile("출처:\\s*(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final long NEWS_BIAS_TTL_MS=30_000;
    private final Map<String,double[]> observedNewsBias=new java.util.concurrent.ConcurrentHashMap<>();
    private final MarketService market;
    private final JdbcTemplate jdbc;
    private final NewsValuation newsValuation;

    MarketNewsFeed(MarketService market, JdbcTemplate jdbc, NewsValuation newsValuation) {
        this.market = market;
        this.jdbc = jdbc;
        this.newsValuation = newsValuation;
    }

    public List<MarketEvent> marketEvents() {
        Map<String, Long> prices = new HashMap<>();
        jdbc.query("SELECT stock_code, current_price FROM stocks", rs -> { prices.put(rs.getString(1), rs.getLong(2)); });
        return repriceNews(latestNews(), prices);
    }

    /**
     * The expensive half of {@link #marketEvents()}. Its rows change only when news or excerpts
     * are written, so the public cache keeps them and reprices with {@link #repriceNews}.
     * priceAtPublish is 0 where no publish-time price is known.
     */
    public List<MarketEvent> latestNews() {
        List<MarketEvent> relevant = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at, e.excerpt,
                       COALESCE(e.price_at_publish, (
                           SELECT h.price FROM stock_price_history h
                           WHERE h.stock_id = e.stock_id
                             AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                           ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                       )) AS price_at_publish
                FROM (
                    SELECT ranked.* FROM (
                        SELECT events.*, ROW_NUMBER() OVER (
                            PARTITION BY stock_id, source
                            ORDER BY COALESCE(published_at, created_at) DESC, id DESC
                        ) AS news_rank
                        FROM market_events events WHERE event_type = 'NEWS'
                    ) ranked WHERE ranked.news_rank <= 50
                ) e LEFT JOIN stocks s ON s.id = e.stock_id
            ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                """, this::newsRow).stream()
                .filter(this::isRelevantNews)
                .toList();
        List<MarketEvent> updates = relevant.stream()
                .filter(event -> "공식 발표".equals(event.source()))
                .toList();
        List<MarketEvent> media = relevant.stream()
                .filter(event -> !"공식 발표".equals(event.source()))
                .toList();
        // 한 게임의 글이 최신이라는 이유로 다른 게임의 공식 글을
        // 전부 밀어내지 않도록, 종류별로 게임마다 최신 2개씩 확보한다.
        updates = takeLatestPerStock(updates, 2);
        media = takeLatestPerStock(media, 2);
        // 두 종류를 모두 확보하되, 화면에서는 게시 시각의 전체 흐름으로
        // 섞어서 보여준다. 공식 발표를 억지로 상단에 고정하지 않는다.
        return java.util.stream.Stream.concat(updates.stream(), media.stream())
                .sorted(java.util.Comparator.comparing(this::publishedInstant,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    private List<MarketEvent> takeLatestPerStock(List<MarketEvent> events, int perStock) {
        Map<String, Integer> counts = new HashMap<>();
        return events.stream()
                .filter(event -> {
                    String stockCode = event.stockCode();
                    int count = counts.getOrDefault(stockCode, 0);
                    if (count >= perStock) return false;
                    counts.put(stockCode, count + 1);
                    return true;
                })
                .toList();
    }

    private Instant publishedInstant(MarketEvent event) {
        if (event.publishedAt() == null || event.publishedAt().isBlank()) return null;
        try {
            return Instant.parse(event.publishedAt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public List<MarketEvent> stockNews(String code) {
        List<MarketEvent> candidates = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at, e.excerpt,
                       s.current_price, s.previous_price,
                       COALESCE(e.price_at_publish, (
                           SELECT h.price FROM stock_price_history h
                           WHERE h.stock_id = e.stock_id
                             AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                           ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                       ), s.current_price) AS price_at_publish
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                LIMIT 100
                """, this::toMarketEvent, code.toUpperCase(Locale.ROOT));
        Set<String> seen = new HashSet<>();
        return candidates.stream()
                .filter(this::isRelevantNews)
                // Google News can publish the same article with minor source/title
                // variants. Count one canonical source only once toward the five.
                .filter(event -> seen.add(newsIdentity(event)))
                .limit(5)
                .toList();
    }

    private String newsIdentity(MarketEvent event) {
        Matcher matcher = NEWS_SOURCE_PATTERN.matcher(event.description() == null ? "" : event.description());
        return matcher.find() ? matcher.group(1) : event.title();
    }

    private boolean isRelevantNews(MarketEvent event) {
        return "공식 발표".equals(event.source())
                || event.stockCode() == null
                || NewsRelevance.isRelevant(event.stockCode(), event.title(), event.description());
    }

    private MarketEvent toMarketEvent(ResultSet rs, int rowNumber) throws SQLException {
        return pricedNews(newsRow(rs, rowNumber), rs.getLong("current_price"));
    }

    /** A stored news row before its price comparison is filled in. */
    private MarketEvent newsRow(ResultSet rs, int rowNumber) throws SQLException {
        double rawImpact = rs.getDouble("impact");
        int impact = (int) Math.round(rawImpact);
        String sentiment = impact > 0 ? "positive" : impact < 0 ? "negative" : "neutral";
        var publishedAt = rs.getTimestamp("published_at");
        String published = publishedAt == null ? null : market.databaseInstant(publishedAt).toString();
        NewsExcerptService.Excerpt excerpt = NewsExcerptService.readStored(rs.getString("excerpt"));
        return new MarketEvent(rs.getString("stock_code"), rs.getString("title"), impact,
                sentiment, rs.getString("source"), rs.getString("description"), published, rs.getLong("price_at_publish"), 0,
                "flat", null, excerpt == null ? List.of() : excerpt.paragraphs(), excerpt == null ? null : excerpt.url());
    }

    /** Compares each event with the current quote; one without a publish-time price compares with itself. */
    public List<MarketEvent> repriceNews(List<MarketEvent> news, Map<String, Long> prices) {
        return news.stream().map(event -> pricedNews(event, prices.getOrDefault(event.stockCode(), 0L))).toList();
    }

    private MarketEvent pricedNews(MarketEvent event, long currentPrice) {
        long priceAtPublish = event.priceAtPublish() == 0 ? currentPrice : event.priceAtPublish();
        double priceChangePercent = market.changePercent(currentPrice, priceAtPublish);
        String priceDirection = priceChangePercent > 0 ? "up" : priceChangePercent < 0 ? "down" : "flat";
        return new MarketEvent(event.stockCode(), event.title(), event.impact(), event.sentiment(), event.source(),
                event.description(), event.publishedAt(), priceAtPublish, priceChangePercent, priceDirection,
                priceReason(priceChangePercent, event.impact()), event.excerpt(), event.articleUrl());
    }

    private String priceReason(double priceChangePercent, int impact) {
        if (priceChangePercent > 0 && impact > 0)
            return "뉴스 영향과 최근 상승 흐름이 함께 나타나 가격이 오르고 있습니다.";
        if (priceChangePercent < 0 && impact < 0)
            return "뉴스 영향과 최근 하락 흐름이 함께 나타나 가격이 내리고 있습니다.";
        if (priceChangePercent > 0 && impact < 0)
            return "하락 요인으로 해석되는 뉴스가 있지만 최근 거래 흐름은 상승세입니다.";
        if (priceChangePercent < 0 && impact > 0)
            return "상승 요인으로 해석되는 뉴스가 있지만 최근 거래 흐름은 하락세입니다.";
        if (priceChangePercent > 0)
            return "최근 거래 흐름을 따라 가격이 상승하고 있습니다.";
        if (priceChangePercent < 0)
            return "최근 거래 흐름을 따라 가격이 하락하고 있습니다.";
        if (impact > 0) return "상승 요인으로 해석되는 뉴스가 등록됐지만 가격은 보합입니다.";
        if (impact < 0) return "하락 요인으로 해석되는 뉴스가 등록됐지만 가격은 보합입니다.";
        return "최근 가격과 뉴스 영향도가 보합 상태입니다.";
    }

    NewsBias recentNewsBias(String code) { return recentNewsBias(code, 0); }

    /**
     * Batch bots observe every listing each OBSERVATION_INTERVAL_MS, but headlines arrive every ten
     * minutes and their weight decays over a day. A rate read within NEWS_BIAS_TTL_MS is reused until
     * news is stored again.
     */
    double observedNewsRate(String code, long now) {
        double[] cached = observedNewsBias.get(code);
        if (cached != null && now >= cached[1] && now - cached[1] < NEWS_BIAS_TTL_MS) return cached[0];
        double rate = recentNewsBias(code).rate();
        observedNewsBias.put(code, new double[]{rate, now});
        return rate;
    }

    void clearObservedBias() { observedNewsBias.clear(); }

    NewsBias recentNewsBias(String code, long latency) {
        Instant observed = market.clock().instant().minusMillis(latency);
        var news = newsValuation.articles(code, observed, 3 * 86400);
        return new NewsBias(newsValuation.observedBias(code, news, observed), false);
    }

    record NewsBias(double rate, boolean special) { }
}
