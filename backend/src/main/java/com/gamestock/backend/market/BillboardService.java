package com.gamestock.backend.market;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 홈 상단 전광판. 투자자가 모의 자금을 내고 한 줄 문구를 산 즉시 정해진 시간 동안 띄운다.
 * 동시에 떠 있는 문구는 화면이 번갈아 보여 주고, 값은 admin_cash_adjustments에 음수로 남겨 수익률·주간 랭킹에서 뺀다.
 */
@Service
public class BillboardService {
    public static final long PRICE_PER_HOUR = 100_000L;
    public static final List<Integer> DURATIONS = List.of(1, 3, 6);
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 40;
    /** 10초씩 번갈아 보여도 한 바퀴가 너무 길어지지 않게 동시에 떠 있는 문구 수를 묶는다. */
    public static final int MAX_LIVE = 10;
    static final Pattern LINK = Pattern.compile("(?i)(https?://|www\\.|[a-z0-9-]+\\.(com|net|org|kr|io|gg|me|ly|co|xyz|site|app)\\b)");
    /** 공백·기호를 지운 뒤 찾는다. 막는 단어는 최소한만 두고, 나머지는 신고와 관리자 내림으로 다룬다. */
    static final List<String> BLOCKED = List.of("시발", "씨발", "ㅅㅂ", "병신", "ㅂㅅ", "좆", "개새끼", "니미", "fuck", "shit");
    private static final Set<String> OPEN = Set.of("BOOKED");

    public record Showing(long id, String nickname, String body, String startsAt, String endsAt) { }
    public record Board(List<Showing> live, long pricePerHour, List<Integer> durations, int minLength, int maxLength,
                        int maxLive, String nextOpening) { }
    public record Booking(long id, String body, int hours, long price, String startsAt, String endsAt,
                          String state, String hiddenReason, long refunded, String createdAt) { }
    public record Purchase(String body, Integer hours) { }
    public record ReportRequest(String detail) { }
    public record Hide(String reason, boolean refund) { }
    public record ActionResult(String message, Booking booking, MarketModels.Portfolio portfolio, long walletCash) { }

    private final JdbcTemplate db;
    private final MarketService market;
    private final AdminContentService content;
    private final AdminSupport support;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private Clock clock = Clock.systemUTC();

    public BillboardService(JdbcTemplate db, MarketService market, AdminContentService content, AdminSupport support,
                            ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.db = db;
        this.market = market;
        this.content = content;
        this.support = support;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    void setClock(Clock clock) { this.clock = clock; }

    static void ensureTables(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS billboard_messages (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, nickname VARCHAR(40) NOT NULL,
              body VARCHAR(160) NOT NULL, hours INT NOT NULL, price BIGINT NOT NULL,
              starts_at TIMESTAMP(3) NOT NULL, ends_at TIMESTAMP(3) NOT NULL,
              status VARCHAR(12) NOT NULL DEFAULT 'BOOKED', hidden_reason VARCHAR(300) NULL, hidden_by BIGINT NULL,
              refunded BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              INDEX ix_billboard_time(status, ends_at), INDEX ix_billboard_user(user_id, id),
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        // 동시 노출 수 검사를 한 줄로 세우는 잠금. 체결 잠금(market_locks)을 막지 않으려고 따로 둔다.
        db.execute("CREATE TABLE IF NOT EXISTS billboard_lock (id INT PRIMARY KEY)");
        db.update("INSERT IGNORE INTO billboard_lock(id) VALUES(1)");
    }

    /** 공백을 한 칸으로 줄이고 검사를 통과한 문구. 통과하지 못하면 이유를 담아 던진다. */
    static String cleanBody(String value) {
        String text = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
        if (text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("문구에 쓸 수 없는 글자가 있어요.");
        int length = text.codePointCount(0, text.length());
        if (length < MIN_LENGTH) throw new IllegalArgumentException("문구는 " + MIN_LENGTH + "자 이상 적어 주세요.");
        if (length > MAX_LENGTH) throw new IllegalArgumentException("문구는 " + MAX_LENGTH + "자까지 적을 수 있어요.");
        if (LINK.matcher(text).find()) throw new IllegalArgumentException("문구에는 링크나 주소를 넣을 수 없어요.");
        String squeezed = text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·ㆍ_~]+", "");
        for (String word : BLOCKED)
            if (squeezed.contains(word)) throw new IllegalArgumentException("문구에 쓸 수 없는 표현이 있어요.");
        return text;
    }

    public Board board() {
        Instant now = clock.instant();
        List<Showing> live = db.query(
                "SELECT id, nickname, body, starts_at, ends_at FROM billboard_messages WHERE status = 'BOOKED' AND starts_at <= ? AND ends_at > ? ORDER BY starts_at, id LIMIT 60",
                (rs, row) -> new Showing(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant().toString(), rs.getTimestamp(5).toInstant().toString()),
                Timestamp.from(now), Timestamp.from(now));
        Instant opening = live.size() < MAX_LIVE ? null : nextOpening(now);
        return new Board(live, PRICE_PER_HOUR, DURATIONS, MIN_LENGTH, MAX_LENGTH, MAX_LIVE, opening == null ? null : opening.toString());
    }

    /** 가득 찼을 때 가장 먼저 자리가 나는 시각. */
    private Instant nextOpening(Instant now) {
        Timestamp end = db.queryForObject("SELECT MIN(ends_at) FROM billboard_messages WHERE status = 'BOOKED' AND ends_at > ?", Timestamp.class, Timestamp.from(now));
        return end == null ? null : end.toInstant();
    }

    public List<Booking> mine(long userId) {
        Instant now = clock.instant();
        return db.query("SELECT * FROM billboard_messages WHERE user_id = ? ORDER BY id DESC LIMIT 20",
                (rs, index) -> booking(rs.getLong("id"), rs.getString("body"), rs.getInt("hours"), rs.getLong("price"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), rs.getString("status"),
                        rs.getString("hidden_reason"), rs.getLong("refunded"), rs.getTimestamp("created_at").toInstant(), now), userId);
    }

    public ActionResult buy(long userId, String nickname, Purchase request) {
        String body = cleanBody(request == null ? null : request.body());
        int hours = request == null || request.hours() == null ? 0 : request.hours();
        if (!DURATIONS.contains(hours)) throw new IllegalArgumentException("노출 시간은 1시간, 3시간, 6시간 중에서 골라 주세요.");
        if (nickname == null || nickname.isBlank()) throw new IllegalArgumentException("닉네임을 정한 뒤에 문구를 올릴 수 있어요.");
        long price = PRICE_PER_HOUR * hours;
        long id = transaction.execute(state -> {
            Instant now = clock.instant();
            db.queryForObject("SELECT id FROM billboard_lock WHERE id = 1 FOR UPDATE", Integer.class);
            Integer open = db.queryForObject("SELECT COUNT(*) FROM billboard_messages WHERE user_id = ? AND status = 'BOOKED' AND ends_at > ?",
                    Integer.class, userId, Timestamp.from(now));
            if (open != null && open > 0) throw new IllegalArgumentException("올린 문구가 끝난 뒤에 새로 올릴 수 있어요.");
            if (PersonalWallet.balance(db, userId) < price)
                throw new IllegalArgumentException("내 지갑 잔액이 부족해요. " + String.format("%,d", price) + "원이 필요해요.");
            Integer live = db.queryForObject("SELECT COUNT(*) FROM billboard_messages WHERE status = 'BOOKED' AND ends_at > ?",
                    Integer.class, Timestamp.from(now));
            if (live != null && live >= MAX_LIVE) throw new IllegalArgumentException("전광판에 문구가 가득 찼어요. 앞 문구가 끝나면 다시 올릴 수 있어요.");
            db.update("INSERT INTO billboard_messages (user_id, nickname, body, hours, price, starts_at, ends_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    userId, nickname, body, hours, price, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofHours(hours))));
            long created = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            PersonalWallet.debit(db, userId, "billboard:" + created, PersonalWallet.BILLBOARD, price, "전광판 문구 #" + created);
            return created;
        });
        Booking booking = mine(userId).stream().filter(item -> item.id() == id).findFirst().orElseThrow();
        return new ActionResult("지금부터 " + hours + "시간 동안 홈에 문구가 보여요.", booking, market.portfolio(userId),
                PersonalWallet.balance(db, userId));
    }

    /** 신고는 기존 사용자 신고함으로 보낸다. 대상은 문구를 올린 사람이다. */
    public Map<String, Object> report(long reporter, long id, ReportRequest request) {
        var rows = db.queryForList("SELECT user_id, body FROM billboard_messages WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("신고할 문구를 찾을 수 없어요.");
        long author = ((Number) rows.get(0).get("user_id")).longValue();
        if (author == reporter) throw new IllegalArgumentException("내가 올린 문구는 신고할 수 없어요.");
        String detail = request == null || request.detail() == null ? "" : request.detail().trim();
        String summary = "[전광판 #" + id + "] " + rows.get(0).get("body") + (detail.isEmpty() ? "" : "\n" + detail);
        return content.report(reporter, new AdminContentService.Report(author, summary.length() > 1000 ? summary.substring(0, 1000) : summary));
    }

    public List<Map<String, Object>> adminList(long before, int limit) {
        AdminSupport.page(limit, before);
        return db.queryForList("SELECT * FROM billboard_messages WHERE (? = 0 OR id < ?) ORDER BY id DESC LIMIT ?", before, before, limit);
    }

    /** 관리자가 문구를 내린다. {@code refund}면 낸 값을 전부 돌려준다. */
    public Map<String, Object> hide(long actor, long id, Hide request) {
        String reason = AdminSupport.reason(request == null ? null : request.reason());
        boolean refund = request != null && request.refund();
        long[] user = new long[1];
        Map<String, Object> result = transaction.execute(state -> {
            var owner = db.queryForList("SELECT user_id FROM billboard_messages WHERE id = ?", Long.class, id);
            if (owner.isEmpty()) throw new IllegalArgumentException("문구를 찾을 수 없습니다.");
            user[0] = owner.get(0);
            db.queryForObject("SELECT id FROM billboard_lock WHERE id = 1 FOR UPDATE", Integer.class);
            var before = db.queryForList("SELECT * FROM billboard_messages WHERE id = ? FOR UPDATE", id).get(0);
            if (!OPEN.contains((String) before.get("status"))) throw new IllegalArgumentException("이미 취소했거나 내린 문구입니다.");
            long amount = refund ? ((Number) before.get("price")).longValue() : 0L;
            db.update("UPDATE billboard_messages SET status = 'HIDDEN', hidden_reason = ?, hidden_by = ?, refunded = ? WHERE id = ?", reason, actor, amount, id);
            if (amount > 0)
                PersonalWallet.credit(db, user[0], "billboard-refund:" + id, PersonalWallet.BILLBOARD, amount, "전광판 문구 #" + id + " 내림 환불");
            support.audit(actor, "BILLBOARD_HIDE", id, reason, before, Map.of("refunded", amount));
            return Map.<String, Object>of("id", id, "refunded", amount);
        });
        if (refund) events.publishEvent(new UserMarketEvent(user[0], "WALLET_CHANGED", Map.of("walletCash", PersonalWallet.balance(db, user[0]))));
        return result;
    }

    static String state(String status, Instant start, Instant end, Instant now) {
        if (!"BOOKED".equals(status)) return status;
        return now.isBefore(start) ? "UPCOMING" : now.isBefore(end) ? "LIVE" : "ENDED";
    }

    private static Booking booking(long id, String body, int hours, long price, Instant start, Instant end, String status,
                                   String hiddenReason, long refunded, Instant created, Instant now) {
        return new Booking(id, body, hours, price, start.toString(), end.toString(), state(status, start, end, now),
                hiddenReason, refunded, created.toString());
    }
}
