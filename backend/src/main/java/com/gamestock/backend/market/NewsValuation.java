package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Durable economic news effects. Call project under the market transaction/lock. */
final class NewsValuation {
    private static final long DAY = 86_400;
    private static final double PERMANENT_SHARE = .7;
    private static final Pattern SOURCE = Pattern.compile("출처:\\s*(https?://\\S+)");
    private static final Pattern PUBLISHER = Pattern.compile("\\s+[-|]\\s+[^-|]+$");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D+");
    private static final Comparator<Article> ARTICLE_ORDER = Comparator.comparing(Article::published).thenComparingLong(Article::id);
    private static final double SIMILARITY = .72;
    record Article(long id, String title, String description, double impact, Instant published) { }
    record Event(List<Article> articles, double effect, Instant published) { }
    private final JdbcTemplate jdbc;
    private record Cached(List<Article> articles, List<Event> events) { }
    // Pure derived values only: a transaction rollback cannot poison the economic ledger.
    private final Map<String, Cached> cache = new LinkedHashMap<>(32, .75f, true);

    NewsValuation(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_fundamental_state (
                  stock_id BIGINT PRIMARY KEY, log_value DOUBLE NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_news_valuation (
                  news_id BIGINT PRIMARY KEY, stock_id BIGINT NOT NULL,
                  title TEXT NOT NULL, description TEXT NOT NULL, impact DOUBLE NOT NULL,
                  published_at TIMESTAMP(3) NOT NULL, archived BOOLEAN NOT NULL DEFAULT FALSE,
                  INDEX ix_news_valuation_active(stock_id,archived,published_at))
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_news_valuation' AND column_name='archived'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE market_news_valuation ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='market_news_valuation' AND index_name='ix_news_valuation_active'", Integer.class) == 0)
            jdbc.execute("CREATE INDEX ix_news_valuation_active ON market_news_valuation(stock_id,archived,published_at)");
        // Older versions discarded receipts after nine days. Seed compact tombstones from
        // retained old source rows for already-initialized stocks, without replaying value.
        jdbc.update("""
                INSERT IGNORE INTO market_news_valuation(news_id,stock_id,title,description,impact,published_at,archived)
                SELECT e.id,e.stock_id,'','',0,LEAST(COALESCE(e.published_at,e.created_at),e.created_at),TRUE
                FROM market_events e JOIN market_fundamental_state f ON f.stock_id=e.stock_id
                WHERE e.event_type='NEWS'
                  AND LEAST(COALESCE(e.published_at,e.created_at),e.created_at)<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 9 DAY)
                  AND NOT EXISTS (SELECT 1 FROM market_news_valuation v WHERE v.news_id=e.id)
                """);
    }

    List<Article> articles(String code, Instant now, long seconds) {
        return jdbc.query("""
                SELECT e.id,e.title,e.description,e.impact,COALESCE(e.published_at,e.created_at) AS published
                FROM market_events e JOIN stocks s ON s.id=e.stock_id
                WHERE s.stock_code=? AND e.event_type='NEWS'
                  AND COALESCE(e.published_at,e.created_at)>=? AND COALESCE(e.published_at,e.created_at)<=?
                  AND NOT EXISTS (SELECT 1 FROM market_news_valuation v WHERE v.news_id=e.id
                    AND (v.archived=TRUE OR v.published_at<?))
                ORDER BY published,e.id
                """, (rs, n) -> new Article(rs.getLong("id"), rs.getString("title"),
                Objects.toString(rs.getString("description"), ""), rs.getDouble("impact"),
                rs.getTimestamp("published").toInstant()), code, Timestamp.from(now.minusSeconds(seconds)),
                Timestamp.from(now), Timestamp.from(now.minusSeconds(9 * DAY)))
                .stream().filter(a -> official(a) || NewsRelevance.isRelevant(code, a.title(), a.description())).toList();
    }

    void project(long stock, String code, Instant now) {
        // Two extra days retain the complete duplicate group for corrections inside the seven-day window.
        Instant cutoff = now.minusSeconds(7 * DAY);
        // Keep the identity indefinitely even if an RSS feed republishes the same ID with
        // a new date. Large text is discarded; the active index keeps each tick bounded.
        jdbc.update("""
                UPDATE market_news_valuation SET archived=TRUE,title='',description='',impact=0
                WHERE stock_id=? AND archived=FALSE AND published_at<?
                """, stock, Timestamp.from(now.minusSeconds(9 * DAY)));
        List<Article> before = jdbc.query("""
                SELECT * FROM market_news_valuation WHERE stock_id=? AND archived=FALSE AND published_at>=? ORDER BY published_at,news_id
                """, (rs, n) -> new Article(rs.getLong("news_id"), rs.getString("title"), rs.getString("description"),
                rs.getDouble("impact"), rs.getTimestamp("published_at").toInstant()), stock, Timestamp.from(now.minusSeconds(9 * DAY)));
        List<Article> current = articles(code, now, 7 * DAY);
        Map<Long,Article> merged = new HashMap<>();
        before.stream().filter(a -> a.published().isBefore(cutoff)).forEach(a -> merged.put(a.id(), a));
        current.forEach(a -> merged.put(a.id(), a));
        List<Article> after = merged.values().stream().sorted(ARTICLE_ORDER).toList();
        var beforeEvents = cachedEvents(stock + ":before", before);
        var afterEvents = before.equals(after) ? beforeEvents : cachedEvents(stock + ":after", after);
        double weight = StockMarketProfile.of(code).movementWeight();
        double transientLog = (1 - PERMANENT_SHARE) * contribution(afterEvents, weight, now, true);
        List<Double> saved = jdbc.queryForList("SELECT log_value FROM market_fundamental_state WHERE stock_id=?", Double.class, stock);
        double permanentLog;
        if (saved.isEmpty()) {
            // Upgrade without a price jump or replaying already reflected headlines.
            double existing = jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?", Double.class, stock);
            permanentLog = Math.log(Math.max(PriceLimitPolicy.MIN_PRICE, existing)) - transientLog;
            jdbc.update("INSERT INTO market_fundamental_state(stock_id,log_value) VALUES(?,?)", stock, permanentLog);
        } else {
            permanentLog = saved.get(0) + PERMANENT_SHARE * (contribution(afterEvents, weight, now, false)
                    - contribution(beforeEvents, weight, now, false));
            // Keep numeric limits explicit even after years of simulated news.
            permanentLog = Math.max(Math.log(PriceLimitPolicy.MIN_PRICE), Math.min(Math.log(1_000_000_000), permanentLog));
            if (Double.compare(permanentLog, saved.get(0)) != 0)
                jdbc.update("UPDATE market_fundamental_state SET log_value=? WHERE stock_id=?", permanentLog, stock);
        }
        Map<Long, Article> old = new HashMap<>();
        before.forEach(a -> old.put(a.id(), a));
        List<Object[]> changed = current.stream().filter(a -> !a.equals(old.get(a.id())))
                .map(a -> new Object[]{a.id(),stock,a.title(),a.description(),a.impact(),Timestamp.from(a.published())}).toList();
        if (!changed.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO market_news_valuation(news_id,stock_id,title,description,impact,published_at) VALUES(?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE title=VALUES(title),description=VALUES(description),impact=VALUES(impact),published_at=VALUES(published_at)
                """, changed);
        Set<Long> ids = new HashSet<>(); current.forEach(a -> ids.add(a.id()));
        List<Object[]> removed = before.stream().filter(a -> !a.published().isBefore(cutoff) && !ids.contains(a.id()))
                .map(a -> new Object[]{a.id()}).toList();
        if (!removed.isEmpty()) jdbc.batchUpdate("DELETE FROM market_news_valuation WHERE news_id=?", removed);
        double value = Math.max(PriceLimitPolicy.MIN_PRICE, Math.min(1_000_000_000, Math.exp(permanentLog + transientLog)));
        jdbc.update("UPDATE market_price_metrics SET hidden_fundamental=? WHERE stock_id=?", value, stock);
    }

    static double effect(Article article) {
        double magnitude = Math.min(10, Math.abs(article.impact())) / 10;
        double limit = article.impact() < 0 && NewsRelevance.hasIncidentContext(article.title(), article.description()) ? .10 : .05;
        double weight = official(article) ? OfficialNoticeService.WEIGHT : 1;
        return Math.copySign(limit * magnitude * magnitude * weight, article.impact());
    }

    /** 게임사 공식 공지. 게임 이름이 없어도 그 게임의 글이고, 언론 기사보다 크게 반영한다. */
    static boolean official(Article article) {
        var match = SOURCE.matcher(article.description());
        return match.find() && OfficialNoticeService.isOfficialLink(match.group(1));
    }

    static double bias(List<Article> articles, Instant now) {
        return eventBias(events(articles), now);
    }

    double observedBias(String code, List<Article> articles, Instant now) {
        return eventBias(cachedEvents(code + ":bias", articles), now);
    }

    private static double eventBias(List<Event> events, Instant now) {
        double total = events.stream().mapToDouble(e -> e.effect() * recency(e.published(), now)).sum();
        return .10 * Math.tanh(total / .10);
    }

    private synchronized List<Event> cachedEvents(String key, List<Article> articles) {
        Cached saved = cache.get(key);
        if (saved != null && saved.articles().equals(articles)) return saved.events();
        List<Event> result = events(articles);
        cache.put(key, new Cached(List.copyOf(articles), result));
        while (cache.size() > 96) cache.remove(cache.keySet().iterator().next());
        return result;
    }

    private static double contribution(List<Event> events, double weight, Instant now, boolean decay) {
        return events.stream().mapToDouble(e -> Math.log1p(Math.max(-.8, e.effect() * weight))
                * (decay ? recency(e.published(), now) : 1)).sum();
    }

    private static double recency(Instant published, Instant now) {
        return Math.max(0, Math.min(1, 1 - (now.toEpochMilli() - published.toEpochMilli()) / 86_400_000.0));
    }

    static List<Event> events(List<Article> articles) {
        List<Prepared> prepared = articles.stream().sorted(ARTICLE_ORDER).map(Prepared::new).toList();
        Map<String, Integer> frequency = new HashMap<>();
        for (Prepared article : prepared) for (String token : article.tokens) frequency.merge(token, 1, Integer::sum);
        Comparator<String> tokenOrder = Comparator.comparingInt((String token) -> frequency.get(token)).thenComparing(Comparator.naturalOrder());
        Map<String, Deque<Group>> sources = new HashMap<>(), titles = new HashMap<>();
        Map<PrefixKey, Set<Group>> prefixes = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        Deque<Group> active = new ArrayDeque<>();
        for (Prepared article : prepared) {
            while (!active.isEmpty() && article.article.published().getEpochSecond()
                    - active.peekFirst().first.article.published().getEpochSecond() > 2 * DAY) {
                Group expired = active.removeFirst();
                remove(sources, expired.first.source, expired);
                remove(titles, expired.first.title, expired);
                for (PrefixKey key : expired.prefix) {
                    Set<Group> indexed = prefixes.get(key);
                    indexed.remove(expired);
                    if (indexed.isEmpty()) prefixes.remove(key);
                }
            }
            Group group = earlier(first(sources, article.source), first(titles, article.title));
            // Jaccard prefix filtering: a qualifying pair must share a token in these
            // prefixes under the same global ordering. Rare tokens avoid common-word scans.
            List<PrefixKey> prefix = article.prefix(tokenOrder);
            Set<Group> candidates = new HashSet<>();
            for (PrefixKey key : prefix) candidates.addAll(prefixes.getOrDefault(key, Set.of()));
            for (Group candidate : candidates) if ((group == null || candidate.order < group.order)
                    && similar(candidate.first, article)) group = candidate;
            if (group == null) {
                group = new Group(groups.size(), article, prefix);
                groups.add(group); active.addLast(group);
                if (!article.source.isEmpty()) sources.computeIfAbsent(article.source, key -> new ArrayDeque<>()).addLast(group);
                titles.computeIfAbsent(article.title, key -> new ArrayDeque<>()).addLast(group);
                for (PrefixKey key : prefix) prefixes.computeIfAbsent(key, ignored -> new HashSet<>()).add(group);
            }
            group.add(article);
        }
        return groups.stream().map(g -> new Event(List.copyOf(g.articles), g.strongest.effect, g.first.article.published())).toList();
    }

    static boolean duplicate(Article a, Article b) {
        Prepared left = new Prepared(a), right = new Prepared(b);
        return (!left.source.isEmpty() && left.source.equals(right.source)) || left.title.equals(right.title) || similar(left, right);
    }

    private static boolean similar(Prepared a, Prepared b) {
        if (a.title.length() < 12 || b.title.length() < 12 || !a.fuzzyKey.equals(b.fuzzyKey)) return false;
        int small = Math.min(a.tokens.size(), b.tokens.size()), large = Math.max(a.tokens.size(), b.tokens.size());
        if (small < SIMILARITY * large || large == 0) return false;
        Set<String> left = a.tokens.size() <= b.tokens.size() ? a.tokens : b.tokens;
        Set<String> right = left == a.tokens ? b.tokens : a.tokens;
        int intersection = 0;
        for (String token : left) if (right.contains(token)) intersection++;
        return intersection / (double) (small + large - intersection) >= SIMILARITY;
    }

    private record FuzzyKey(double sign, String numbers) { }
    private record PrefixKey(FuzzyKey key, String token) { }
    private static final class Prepared {
        final Article article;
        final String source, title;
        final Set<String> tokens;
        final FuzzyKey fuzzyKey;
        final double effect;
        Prepared(Article article) {
            this.article = article;
            var match = SOURCE.matcher(article.description());
            source = match.find() ? match.group(1) : "";
            title = normalized(article.title());
            tokens = bigrams(title);
            fuzzyKey = new FuzzyKey(Math.signum(article.impact()) == 0 ? 0 : Math.signum(article.impact()), NON_DIGITS.matcher(article.title()).replaceAll(" ").trim());
            effect = effect(article);
        }
        List<PrefixKey> prefix(Comparator<String> order) {
            if (title.length() < 12 || tokens.isEmpty()) return List.of();
            return tokens.stream().sorted(order).limit(tokens.size() - (long) Math.ceil(SIMILARITY * tokens.size()) + 1)
                    .map(token -> new PrefixKey(fuzzyKey, token)).toList();
        }
    }
    private static final class Group {
        final int order;
        final Prepared first;
        final List<PrefixKey> prefix;
        final List<Article> articles = new ArrayList<>();
        Prepared strongest;
        Group(int order, Prepared first, List<PrefixKey> prefix) { this.order = order; this.first = first; this.prefix = prefix; }
        void add(Prepared next) {
            articles.add(next.article);
            if (strongest == null || Math.abs(next.effect) > Math.abs(strongest.effect)
                    || (Math.abs(next.effect) == Math.abs(strongest.effect) && next.article.id() > strongest.article.id())) strongest = next;
        }
    }
    private static Group first(Map<String, Deque<Group>> index, String key) {
        Deque<Group> groups = index.get(key); return groups == null ? null : groups.peekFirst();
    }
    private static Group earlier(Group a, Group b) { return a == null ? b : b == null || a.order < b.order ? a : b; }
    private static void remove(Map<String, Deque<Group>> index, String key, Group group) {
        Deque<Group> groups = index.get(key);
        if (groups == null) return;
        groups.removeFirstOccurrence(group);
        if (groups.isEmpty()) index.remove(key);
    }

    private static String normalized(String title) {
        return PUNCTUATION.matcher(PUBLISHER.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("")).replaceAll("");
    }
    private static Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) result.add(text.substring(i, i + 2));
        return result;
    }
}
