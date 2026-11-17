package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static com.gamestock.backend.market.MarketModels.OrderRequest;
import static com.gamestock.backend.market.MarketModels.OrderResult;

/**
 * 손절·익절 자동 매도. 체결가가 감시 가격에 닿으면 사람 주문과 같은 접수 경로로 시장가 매도를 낸다.
 *
 * <ul>
 *   <li>손절(STOP_LOSS): 현재가가 감시 가격 이하가 되면 판다. 등록할 때 감시 가격은 현재가보다 낮아야 한다.</li>
 *   <li>익절(TAKE_PROFIT): 현재가가 감시 가격 이상이 되면 판다. 등록할 때 감시 가격은 현재가보다 높아야 한다.</li>
 * </ul>
 * 시장가 매도라 실제 체결가는 감시 가격과 다를 수 있다. 같은 종목의 다른 감시 주문은 잔고가 0이 되면 함께 끝낸다.
 */
@Service
public class StopOrderService {
    private static final Logger log = LoggerFactory.getLogger(StopOrderService.class);
    /** 계정당 동시에 걸어 둘 수 있는 감시 주문 수. */
    static final int ACTIVE_LIMIT = 10;
    /** FIRING이 이보다 오래 남아 있으면 접수 중에 서버가 멈춘 것으로 보고 실제 주문 기록과 맞춘다. */
    static final int FIRING_STALE_SECONDS = 120;
    private static final String HUMAN_FILTER = "u.password_hash NOT IN ('BOT', 'TRADER') AND COALESCE(u.role, 'USER') <> 'ADMIN'";

    public record StopOrder(long id, String stockCode, String kind, long triggerPrice, int quantity, String status,
                            String message, String createdAt, String triggeredAt) { }
    public record StopOrderRequest(String stockCode, String kind,
                                   @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictLongDeserializer.class) Long triggerPrice,
                                   @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.gamestock.backend.config.StrictIntegerDeserializer.class) Integer quantity) { }
    private record Due(long id, long userId, String code, int quantity) { }

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final OrderService orders;
    /** 이 인스턴스에서 지금 주문을 내고 있는 감시 주문. 접수가 오래 걸려도 복구 작업이 건드리지 않는다. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public StopOrderService(JdbcTemplate jdbc, MarketService market, OrderService orders) {
        this.jdbc = jdbc;
        this.market = market;
        this.orders = orders;
    }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stop_orders (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  kind VARCHAR(12) NOT NULL, trigger_price BIGINT NOT NULL, quantity INT NOT NULL,
                  status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE', message VARCHAR(200) NOT NULL DEFAULT '',
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), triggered_at TIMESTAMP(3) NULL,
                  KEY ix_stop_orders_watch (status, stock_id),
                  KEY ix_stop_orders_user (user_id, status, id),
                  CONSTRAINT fk_stop_orders_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_stop_orders_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
    }

    public List<StopOrder> list(long userId) {
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.kind, o.trigger_price, o.quantity, o.status, o.message, o.created_at, o.triggered_at
                FROM stop_orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? ORDER BY o.status = 'ACTIVE' DESC, o.id DESC LIMIT 30
                """, (rs, row) -> new StopOrder(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), iso(rs.getTimestamp(8)), iso(rs.getTimestamp(9))), userId);
    }

    public StopOrder create(long userId, StopOrderRequest request) {
        if (request == null || request.stockCode() == null || request.kind() == null)
            throw new IllegalArgumentException("종목과 감시 종류를 골라 주세요.");
        String code = request.stockCode().trim().toUpperCase(Locale.ROOT);
        String kind = request.kind().trim().toUpperCase(Locale.ROOT);
        if (!kind.equals("STOP_LOSS") && !kind.equals("TAKE_PROFIT")) throw new IllegalArgumentException("감시 종류는 손절 또는 익절만 고를 수 있습니다.");
        long trigger = request.triggerPrice() == null ? 0 : request.triggerPrice();
        int quantity = request.quantity() == null ? 0 : request.quantity();
        if (trigger <= 0) throw new IllegalArgumentException("감시 가격을 입력해 주세요.");
        if (quantity < 1 || quantity > 1000) throw new IllegalArgumentException("수량은 1주부터 1,000주까지 걸 수 있습니다.");
        Boolean human = jdbc.query("SELECT " + HUMAN_FILTER + " FROM users u WHERE u.id = ?", (rs, row) -> rs.getBoolean(1), userId)
                .stream().findFirst().orElse(false);
        if (!human) throw new IllegalArgumentException("자동 매도는 회원 계정에서만 쓸 수 있습니다.");
        List<long[]> stock = jdbc.query("SELECT id, current_price FROM stocks WHERE stock_code = ?",
                (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, code);
        if (stock.isEmpty()) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long stockId = stock.get(0)[0], price = stock.get(0)[1];
        if (kind.equals("STOP_LOSS") && trigger >= price)
            throw new IllegalArgumentException("손절 가격은 현재가(" + String.format("%,d", price) + "원)보다 낮아야 합니다.");
        if (kind.equals("TAKE_PROFIT") && trigger <= price)
            throw new IllegalArgumentException("익절 가격은 현재가(" + String.format("%,d", price) + "원)보다 높아야 합니다.");
        Integer held = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM portfolios WHERE user_id = ? AND stock_id = ?", Integer.class, userId, stockId);
        if (held == null || held < quantity)
            throw new IllegalArgumentException("보유 수량(" + (held == null ? 0 : held) + "주)보다 많이 걸 수 없습니다.");
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM stop_orders WHERE user_id = ? AND status = 'ACTIVE'", Integer.class, userId);
        if (active != null && active >= ACTIVE_LIMIT)
            throw new IllegalArgumentException("자동 매도는 한 번에 " + ACTIVE_LIMIT + "개까지 걸어 둘 수 있습니다.");
        jdbc.update("INSERT INTO stop_orders (user_id, stock_id, kind, trigger_price, quantity) VALUES (?, ?, ?, ?, ?)",
                userId, stockId, kind, trigger, quantity);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return list(userId).stream().filter(order -> order.id() == id).findFirst().orElseThrow();
    }

    public void cancel(long userId, long id) {
        int updated = jdbc.update("UPDATE stop_orders SET status = 'CANCELLED', message = '직접 취소했습니다.' WHERE id = ? AND user_id = ? AND status = 'ACTIVE'", id, userId);
        if (updated == 0) throw new IllegalArgumentException("이미 실행됐거나 없는 자동 매도입니다.");
    }

    /** 감시 가격에 닿은 주문을 찾아 시장가 매도로 바꾼다. 여러 인스턴스가 돌아도 상태 조건으로 한 번만 집는다. */
    @Scheduled(fixedDelayString = "${gamestock.stop-orders.check-ms:1000}", initialDelayString = "${gamestock.stop-orders.initial-delay-ms:30000}")
    public void watch() {
        if (!market.ownerAvailable()) return;
        try {
            List<Due> due = jdbc.query("""
                    SELECT o.id, o.user_id, s.stock_code, o.quantity
                    FROM stop_orders o JOIN stocks s ON s.id = o.stock_id
                    WHERE o.status = 'ACTIVE'
                      AND ((o.kind = 'STOP_LOSS' AND s.current_price <= o.trigger_price)
                        OR (o.kind = 'TAKE_PROFIT' AND s.current_price >= o.trigger_price))
                    ORDER BY o.id LIMIT 50
                    """, (rs, row) -> new Due(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)));
            for (Due order : due) fire(order);
        } catch (RuntimeException error) {
            log.warn("자동 매도 감시에 실패했습니다.", error);
        }
    }

    void fire(Due order) {
        inFlight.add(order.id());
        try {
            if (jdbc.update("UPDATE stop_orders SET status = 'FIRING', triggered_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = 'ACTIVE'", order.id()) == 0) return;
            submit(order);
        } finally {
            inFlight.remove(order.id());
        }
    }

    private void submit(Due order) {
        Integer available = jdbc.queryForObject("""
                SELECT COALESCE(MAX(COALESCE(p.settled_quantity, p.quantity)
                  - COALESCE((SELECT SUM(o.reserved_quantity) FROM orders o WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id AND o.side = 'SELL' AND o.status = 'OPEN'), 0)), 0)
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND s.stock_code = ?
                """, Integer.class, order.userId(), order.code());
        int quantity = Math.min(order.quantity(), available == null ? 0 : available);
        if (quantity <= 0) {
            finish(order.id(), "FAILED", "팔 수 있는 수량이 없어 실행하지 못했습니다.");
            return;
        }
        try {
            OrderResult result = orders.submit(order.userId(), new OrderRequest(order.code(), "SELL", quantity, "MARKET", null)).join();
            String note = quantity < order.quantity() ? " (보유 " + quantity + "주만 매도)" : "";
            finish(order.id(), "TRIGGERED", "시장가 매도 " + quantity + "주를 냈습니다" + note + ". 체결가 " + String.format("%,d", result.price()) + "원.");
            closeSiblingsIfEmpty(order.userId(), order.code());
        } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            String message = cause.getMessage() == null ? "주문이 접수되지 않았습니다." : cause.getMessage();
            finish(order.id(), "FAILED", message.length() > 180 ? message.substring(0, 180) : message);
        }
    }

    /**
     * 상태를 FIRING으로 바꾼 뒤 주문을 내거나 결과를 적기 전에 서버가 멈추거나 재배포되면 감시 주문이 FIRING에
     * 남는다. 그대로 두면 손절이 다시는 실행되지 않으므로, 오래된 FIRING을 실제 주문 기록과 맞춘다.
     * <ul>
     *   <li>FIRING 이후 그 종목의 매도 주문이 있으면 이미 낸 것으로 보고 TRIGGERED로 끝낸다(두 번 팔지 않는다).</li>
     *   <li>없으면 주문이 나가지 않은 것이므로 ACTIVE로 되돌려 다음 감시에서 다시 실행되게 한다.</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${gamestock.stop-orders.recover-ms:60000}", initialDelayString = "${gamestock.stop-orders.initial-delay-ms:30000}")
    public void recoverStuck() {
        if (!market.ownerAvailable()) return;
        try {
            List<Long> stuck = jdbc.queryForList("SELECT id FROM stop_orders WHERE status = 'FIRING' AND triggered_at < CURRENT_TIMESTAMP(3) - INTERVAL "
                    + FIRING_STALE_SECONDS + " SECOND ORDER BY id LIMIT 50", Long.class);
            for (long id : stuck) {
                if (inFlight.contains(id)) continue;
                // 주문 시각과 감시 시각을 다른 시계가 적었을 수 있어 몇 초 앞까지 본다. 애매하면 팔지 않는 쪽을 고른다.
                Integer sent = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM stop_orders s JOIN orders o
                          ON o.user_id = s.user_id AND o.stock_id = s.stock_id AND o.side = 'SELL'
                         AND o.created_at >= s.triggered_at - INTERVAL 5 SECOND
                        WHERE s.id = ?
                        """, Integer.class, id);
                if (sent != null && sent > 0)
                    jdbc.update("UPDATE stop_orders SET status = 'TRIGGERED', message = '시장가 매도를 냈습니다. 체결 결과는 주문 내역에서 확인해 주세요.' WHERE id = ? AND status = 'FIRING'", id);
                else
                    jdbc.update("UPDATE stop_orders SET status = 'ACTIVE', triggered_at = NULL, message = '' WHERE id = ? AND status = 'FIRING'", id);
            }
        } catch (RuntimeException error) {
            log.warn("자동 매도 복구에 실패했습니다.", error);
        }
    }

    private void finish(long id, String status, String message) {
        jdbc.update("UPDATE stop_orders SET status = ?, message = ? WHERE id = ?", status, message, id);
    }

    /** 잔고를 다 판 뒤 남은 감시 주문은 실행될 수 없으므로 함께 끝낸다. */
    private void closeSiblingsIfEmpty(long userId, String code) {
        jdbc.update("""
                UPDATE stop_orders o JOIN stocks s ON s.id = o.stock_id
                SET o.status = 'CANCELLED', o.message = '같은 종목의 다른 자동 매도가 실행돼 잔고가 없어 함께 끝냈습니다.'
                WHERE o.user_id = ? AND s.stock_code = ? AND o.status = 'ACTIVE'
                  AND COALESCE((SELECT SUM(p.quantity) FROM portfolios p WHERE p.user_id = o.user_id AND p.stock_id = o.stock_id), 0) = 0
                """, userId, code);
    }

    private static String iso(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
}
