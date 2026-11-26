package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 종목토론방. 종목마다 글과 한 단계 답글, 좋아요를 둔다.
 * 사람 계정만 쓸 수 있고(봇·트레이더 계정은 막는다), 첫 글이 올라온 지 일주일이 지난 글타래는
 * {@link DataRetentionService}가 답글·좋아요와 함께 지운다.
 */
@Service
public class StockTalkService {
    public static final int MAX_LENGTH = 300;
    public static final int PAGE_SIZE = 20;
    public static final Duration RETENTION = Duration.ofDays(7);
    /** 같은 사람이 글을 연달아 올리지 못하게 두는 간격. */
    static final Duration WRITE_INTERVAL = Duration.ofSeconds(20);
    /** 봇({@code BOT})과 전략 트레이더({@code TRADER})는 password_hash 자리에 표식을 둔다. */
    static final String HUMAN_AUTHOR = "u.password_hash NOT IN ('BOT','TRADER')";

    public record Post(long id, String nickname, String body, String createdAt, long likes, boolean liked, boolean mine,
                       Long priceAtPost, List<Post> replies) { }
    public record Page(List<Post> posts, boolean hasMore, int maxLength, int retentionDays) { }
    public record Write(String body, Long parentId) { }
    public record Like(long id, long likes, boolean liked) { }
    /** 홈 화면에 띄우는 종목 가리지 않은 새 글. 답글은 개수만 센다. */
    public record Recent(long id, String stockCode, String nickname, String body, String createdAt, long likes, long replies) { }
    public static final int RECENT_LIMIT = 10;

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private Clock clock = Clock.systemUTC();

    public StockTalkService(JdbcTemplate db, PlatformTransactionManager transactions) {
        this.db = db;
        this.transaction = new TransactionTemplate(transactions);
    }

    void setClock(Clock clock) { this.clock = clock; }

    static void ensureTables(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS stock_talk_posts (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(12) NOT NULL, user_id BIGINT NOT NULL,
              parent_id BIGINT NULL, body VARCHAR(400) NOT NULL, price_at_post BIGINT NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              INDEX ix_talk_stock(stock_code, parent_id, id), INDEX ix_talk_parent(parent_id, id),
              INDEX ix_talk_user(user_id, id), INDEX ix_talk_root_time(parent_id, created_at),
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS stock_talk_likes (
              post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              PRIMARY KEY(post_id, user_id), INDEX ix_talk_like_user(user_id),
              FOREIGN KEY(post_id) REFERENCES stock_talk_posts(id) ON DELETE CASCADE,
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
    }

    /** 줄바꿈은 두 줄까지만 살리고 나머지 공백을 한 칸으로 줄인 본문. 통과하지 못하면 이유를 담아 던진다. */
    static String cleanBody(String value) {
        String text = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replaceAll("[\\t\\x0B\\f\\r ]+", " ").replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n").trim();
        if (text.codePoints().anyMatch(point -> point != '\n' && Character.isISOControl(point)))
            throw new IllegalArgumentException("글에 쓸 수 없는 글자가 있어요.");
        int length = text.codePointCount(0, text.length());
        if (length == 0) throw new IllegalArgumentException("내용을 적어 주세요.");
        if (length > MAX_LENGTH) throw new IllegalArgumentException("글은 " + MAX_LENGTH + "자까지 쓸 수 있어요.");
        if (BillboardService.LINK.matcher(text).find()) throw new IllegalArgumentException("글에는 링크나 주소를 넣을 수 없어요.");
        String squeezed = text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·ㆍ_~]+", "");
        for (String word : BillboardService.BLOCKED)
            if (squeezed.contains(word)) throw new IllegalArgumentException("글에 쓸 수 없는 표현이 있어요.");
        return text;
    }

    /** 최신 글부터 한 쪽. {@code viewer}가 0이면 비로그인이다. */
    public Page list(String code, long before, long viewer) {
        String stock = requireStock(code);
        List<Post> roots = db.query(postSql("p.stock_code = ? AND p.parent_id IS NULL AND (? = 0 OR p.id < ?)") + " ORDER BY p.id DESC LIMIT ?",
                (rs, row) -> post(rs, viewer, List.of()), viewer, viewer, stock, before, before, PAGE_SIZE + 1);
        boolean more = roots.size() > PAGE_SIZE;
        if (more) roots = roots.subList(0, PAGE_SIZE);
        return new Page(withReplies(roots, viewer), more, MAX_LENGTH, (int) RETENTION.toDays());
    }

    /** 모든 종목의 최신 원글. 로그인과 상관없이 같은 목록이라 보는 사람 정보는 채우지 않는다. */
    public List<Recent> recent(int limit) {
        int size = Math.max(1, Math.min(RECENT_LIMIT, limit));
        return db.query("SELECT p.id, p.stock_code, p.body, p.created_at, u.nickname, "
                        + "(SELECT COUNT(*) FROM stock_talk_likes l WHERE l.post_id = p.id) AS likes, "
                        + "(SELECT COUNT(*) FROM stock_talk_posts r WHERE r.parent_id = p.id) AS replies "
                        + "FROM stock_talk_posts p JOIN users u ON u.id = p.user_id WHERE " + HUMAN_AUTHOR
                        + " AND p.parent_id IS NULL ORDER BY p.id DESC LIMIT ?",
                (rs, row) -> new Recent(rs.getLong("id"), rs.getString("stock_code"), rs.getString("nickname"), rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant().toString(), rs.getLong("likes"), rs.getLong("replies")), size);
    }

    public Post write(long userId, String nickname, String code, Write request) {
        String stock = requireStock(code);
        String body = cleanBody(request == null ? null : request.body());
        Long parent = request == null ? null : request.parentId();
        if (nickname == null || nickname.isBlank()) throw new IllegalArgumentException("닉네임을 정한 뒤에 글을 쓸 수 있어요.");
        long id = transaction.execute(state -> {
            requireHuman(userId, true);
            Instant now = clock.instant();
            Timestamp last = db.queryForObject("SELECT MAX(created_at) FROM stock_talk_posts WHERE user_id = ?", Timestamp.class, userId);
            if (last != null && last.toInstant().plus(WRITE_INTERVAL).isAfter(now))
                throw new IllegalArgumentException("글은 " + WRITE_INTERVAL.toSeconds() + "초에 한 번씩 쓸 수 있어요.");
            if (parent != null) {
                var target = db.queryForList("SELECT stock_code, parent_id FROM stock_talk_posts WHERE id = ?", parent);
                if (target.isEmpty() || !stock.equals(target.get(0).get("stock_code")))
                    throw new IllegalArgumentException("답글을 달 글을 찾을 수 없어요. 지워졌을 수 있어요.");
                if (target.get(0).get("parent_id") != null) throw new IllegalArgumentException("답글에는 다시 답글을 달 수 없어요.");
            }
            var price = db.queryForList("SELECT current_price FROM stocks WHERE stock_code = ?", Long.class, stock);
            db.update("INSERT INTO stock_talk_posts (stock_code, user_id, parent_id, body, price_at_post, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    stock, userId, parent, body, price.isEmpty() ? null : price.get(0), Timestamp.from(now));
            return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
        return db.query(postSql("p.id = ?"), (rs, row) -> post(rs, userId, List.of()), userId, userId, id).get(0);
    }

    /** 내 글을 지운다. 첫 글이면 달린 답글도 함께 지운다. */
    public Map<String, Object> delete(long userId, long id) {
        transaction.executeWithoutResult(state -> {
            var owner = db.queryForList("SELECT user_id FROM stock_talk_posts WHERE id = ? FOR UPDATE", Long.class, id);
            if (owner.isEmpty()) throw new IllegalArgumentException("글을 찾을 수 없어요. 이미 지워졌을 수 있어요.");
            if (owner.get(0) != userId) throw new IllegalArgumentException("내가 쓴 글만 지울 수 있어요.");
            db.update("DELETE FROM stock_talk_posts WHERE parent_id = ?", id);
            db.update("DELETE FROM stock_talk_posts WHERE id = ?", id);
        });
        return Map.of("id", id);
    }

    /** 좋아요를 누르거나 거둔다. */
    public Like like(long userId, long id) {
        return transaction.execute(state -> {
            requireHuman(userId, false);
            if (db.queryForList("SELECT id FROM stock_talk_posts WHERE id = ?", Long.class, id).isEmpty())
                throw new IllegalArgumentException("글을 찾을 수 없어요. 지워졌을 수 있어요.");
            boolean liked = db.update("DELETE FROM stock_talk_likes WHERE post_id = ? AND user_id = ?", id, userId) == 0;
            if (liked) db.update("INSERT INTO stock_talk_likes (post_id, user_id, created_at) VALUES (?, ?, ?)", id, userId, Timestamp.from(clock.instant()));
            long likes = db.queryForObject("SELECT COUNT(*) FROM stock_talk_likes WHERE post_id = ?", Long.class, id);
            return new Like(id, likes, liked);
        });
    }

    /** 봇·트레이더 계정은 글도 좋아요도 남기지 못한다. HTTP로 들어오지 않더라도 서비스에서 한 번 더 막는다. */
    private void requireHuman(long userId, boolean writing) {
        var marker = db.queryForList("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if (marker.isEmpty()) throw new IllegalArgumentException("계정을 찾을 수 없어요.");
        if ("BOT".equals(marker.get(0)) || "TRADER".equals(marker.get(0)))
            throw new IllegalArgumentException(writing ? "봇 계정은 토론방에 글을 쓸 수 없어요." : "봇 계정은 좋아요를 누를 수 없어요.");
    }

    /** 관리자가 종목을 새로 올릴 수 있어 카탈로그가 아니라 stocks 표에서 찾는다. */
    private String requireStock(String code) {
        String stock = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (stock.isEmpty() || db.queryForList("SELECT id FROM stocks WHERE stock_code = ?", Long.class, stock).isEmpty())
            throw new IllegalArgumentException("종목을 찾을 수 없어요.");
        return stock;
    }

    private List<Post> withReplies(List<Post> roots, long viewer) {
        if (roots.isEmpty()) return roots;
        String marks = String.join(",", Collections.nCopies(roots.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(viewer, viewer));
        roots.forEach(root -> args.add(root.id()));
        Map<Long, List<Post>> byParent = new LinkedHashMap<>();
        db.query(postSql("p.parent_id IN (" + marks + ")") + " ORDER BY p.id", rs -> {
            byParent.computeIfAbsent(rs.getLong("parent_id"), key -> new ArrayList<>()).add(post(rs, viewer, List.of()));
        }, args.toArray());
        return roots.stream().map(root -> new Post(root.id(), root.nickname(), root.body(), root.createdAt(), root.likes(),
                root.liked(), root.mine(), root.priceAtPost(), byParent.getOrDefault(root.id(), List.of()))).toList();
    }

    /** 앞의 두 자리표시자는 보는 사람 id(좋아요 여부·내 글 여부), 그 뒤는 {@code where}의 값이다. */
    private static String postSql(String where) {
        return "SELECT p.id, p.parent_id, p.user_id, p.body, p.price_at_post, p.created_at, u.nickname, "
                + "(SELECT COUNT(*) FROM stock_talk_likes l WHERE l.post_id = p.id) AS likes, "
                + "EXISTS(SELECT 1 FROM stock_talk_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked, "
                + "(p.user_id = ?) AS mine "
                + "FROM stock_talk_posts p JOIN users u ON u.id = p.user_id WHERE " + HUMAN_AUTHOR + " AND " + where;
    }

    private static Post post(ResultSet rs, long viewer, List<Post> replies) throws SQLException {
        long stored = rs.getLong("price_at_post");
        Long price = rs.wasNull() ? null : stored;
        return new Post(rs.getLong("id"), rs.getString("nickname"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getLong("likes"),
                viewer != 0 && rs.getBoolean("liked"), viewer != 0 && rs.getBoolean("mine"), price, replies);
    }
}
