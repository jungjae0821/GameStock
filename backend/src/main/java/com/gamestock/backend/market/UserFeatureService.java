package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

import static com.gamestock.backend.market.UserFeatureModels.*;

/** Persistent per-user/detail-page features shared by web and mobile clients. */
@Service
public class UserFeatureService {
    private final JdbcTemplate jdbc;

    public UserFeatureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_watchlists (
                  user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, stock_id),
                  CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_watchlist_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS price_alerts (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  target_price BIGINT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, triggered_at TIMESTAMP NULL,
                  CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_alert_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_alert_user_active (user_id, active)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_comments (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  comment_text VARCHAR(240) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_comment_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_comment_stock_time (stock_id, created_at)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_tags (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT NOT NULL, tag VARCHAR(40) NOT NULL,
                  created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_stock_tag (stock_id, tag),
                  CONSTRAINT fk_tag_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  CONSTRAINT fk_tag_user FOREIGN KEY (created_by) REFERENCES users(id)
                )
                """);
    }

    public void ensureDefaultTags() {
        insertDefault("UMA", "육성"); insertDefault("UMA", "서브컬처"); insertDefault("UMA", "라이브서비스");
        insertDefault("BA", "학원"); insertDefault("BA", "수집형"); insertDefault("BA", "라이브서비스");
        insertDefault("GOV", "RPG"); insertDefault("GOV", "수집형"); insertDefault("GOV", "액션");
    }

    private void insertDefault(String code, String tag) {
        jdbc.update("INSERT IGNORE INTO stock_tags (stock_id, tag) SELECT id, ? FROM stocks WHERE stock_code = ?", tag, code);
    }

    public List<WatchlistEntry> watchlist(long userId) {
        return jdbc.query("SELECT s.stock_code, w.created_at FROM user_watchlists w JOIN stocks s ON s.id = w.stock_id WHERE w.user_id = ? ORDER BY w.created_at DESC",
                (rs, row) -> new WatchlistEntry(rs.getString(1), iso(rs.getTimestamp(2))), userId);
    }

    @Transactional
    public void addWatchlist(long userId, String code) {
        long stockId = stockId(code);
        jdbc.update("INSERT IGNORE INTO user_watchlists (user_id, stock_id) VALUES (?, ?)", userId, stockId);
    }

    @Transactional
    public void removeWatchlist(long userId, String code) {
        jdbc.update("DELETE w FROM user_watchlists w JOIN stocks s ON s.id = w.stock_id WHERE w.user_id = ? AND s.stock_code = ?", userId, normalize(code));
    }

    public List<PriceAlert> alerts(long userId) {
        return jdbc.query("SELECT a.id, s.stock_code, a.target_price, a.active, a.created_at, a.triggered_at FROM price_alerts a JOIN stocks s ON s.id = a.stock_id WHERE a.user_id = ? ORDER BY a.created_at DESC LIMIT 50",
                (rs, row) -> new PriceAlert(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), iso(rs.getTimestamp(5)), iso(rs.getTimestamp(6))), userId);
    }

    @Transactional
    public PriceAlert addAlert(long userId, PriceAlertRequest request) {
        long stockId = stockId(request.stockCode());
        if (request.targetPrice() <= 0) throw new IllegalArgumentException("알림 가격은 1원 이상이어야 합니다.");
        jdbc.update("INSERT INTO price_alerts (user_id, stock_id, target_price) VALUES (?, ?, ?)", userId, stockId, request.targetPrice());
        return jdbc.queryForObject("SELECT a.id, s.stock_code, a.target_price, a.active, a.created_at, a.triggered_at FROM price_alerts a JOIN stocks s ON s.id = a.stock_id WHERE a.id = ?",
                (rs, row) -> new PriceAlert(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), iso(rs.getTimestamp(5)), iso(rs.getTimestamp(6))), jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @Transactional
    public void deleteAlert(long userId, long alertId) {
        jdbc.update("DELETE FROM price_alerts WHERE id = ? AND user_id = ?", alertId, userId);
    }

    public List<StockComment> comments(String code) {
        return jdbc.query("SELECT c.id, s.stock_code, u.nickname, c.comment_text, c.created_at FROM stock_comments c JOIN stocks s ON s.id = c.stock_id JOIN users u ON u.id = c.user_id WHERE s.stock_code = ? ORDER BY c.created_at DESC LIMIT 50",
                (rs, row) -> new StockComment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), iso(rs.getTimestamp(5))), normalize(code));
    }

    @Transactional
    public StockComment addComment(long userId, String code, CommentRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        if (text.length() < 1 || text.length() > 240) throw new IllegalArgumentException("댓글은 1~240자로 입력해 주세요.");
        long stockId = stockId(code);
        jdbc.update("INSERT INTO stock_comments (user_id, stock_id, comment_text) VALUES (?, ?, ?)", userId, stockId, text);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return jdbc.queryForObject("SELECT c.id, s.stock_code, u.nickname, c.comment_text, c.created_at FROM stock_comments c JOIN stocks s ON s.id = c.stock_id JOIN users u ON u.id = c.user_id WHERE c.id = ?",
                (rs, row) -> new StockComment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), iso(rs.getTimestamp(5))), id);
    }

    public List<StockTag> tags(String code) {
        return jdbc.query("SELECT s.stock_code, t.tag, t.created_at FROM stock_tags t JOIN stocks s ON s.id = t.stock_id WHERE s.stock_code = ? ORDER BY t.created_at, t.tag",
                (rs, row) -> new StockTag(rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3))), normalize(code));
    }

    @Transactional
    public StockTag addTag(long userId, String code, TagRequest request) {
        String tag = request.tag() == null ? "" : request.tag().trim().replaceFirst("^#", "");
        if (tag.length() < 1 || tag.length() > 40) throw new IllegalArgumentException("태그는 1~40자로 입력해 주세요.");
        long stockId = stockId(code);
        jdbc.update("INSERT IGNORE INTO stock_tags (stock_id, tag, created_by) VALUES (?, ?, ?)", stockId, tag, userId);
        return jdbc.queryForObject("SELECT s.stock_code, t.tag, t.created_at FROM stock_tags t JOIN stocks s ON s.id = t.stock_id WHERE t.stock_id = ? AND t.tag = ?",
                (rs, row) -> new StockTag(rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3))), stockId, tag);
    }

    @Transactional
    public void deleteUserData(long userId) {
        jdbc.update("DELETE FROM price_alerts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_comments WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_tags WHERE created_by = ?", userId);
        jdbc.update("DELETE FROM user_watchlists WHERE user_id = ?", userId);
    }

    private long stockId(String code) {
        Long id = jdbc.query("SELECT id FROM stocks WHERE stock_code = ?", (rs, row) -> rs.getLong(1), normalize(code)).stream().findFirst().orElse(null);
        if (id == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        return id;
    }

    private String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(Locale.ROOT); }
    private String iso(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }
}
