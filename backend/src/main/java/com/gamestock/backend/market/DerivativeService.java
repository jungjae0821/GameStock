package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

import static com.gamestock.backend.market.DerivativeModels.*;
import static com.gamestock.backend.market.DerivativePositionStore.PositionRow;
import static com.gamestock.backend.market.DerivativeQuotes.basketCode;

/**
 * 모의 ETF·선물·옵션 시장.
 *
 * <p>모든 상품은 기존 주식 계좌의 현금으로 결제한다. ETF는 NAV로 즉시 매매하고,
 * 선물은 레버리지 증거금을 잡은 뒤 평가손익을 반영하며, 옵션은 블랙-숄즈 모의
 * 프리미엄을 지불하고 만기에 내재가치로 현금결제한다. 실제 금융상품이 아니다.
 *
 * <p>이 클래스는 공개 진입점의 트랜잭션·모니터, 주문 검증, 계좌·포지션 잠금을 맡고, 나머지는
 * 생성자에서 만드는 package-private 협력 객체에 위임한다: 시세·신선도({@link DerivativeQuotes}),
 * 저장·잠금({@link DerivativePositionStore}), 계좌 조회({@link DerivativeAccountView}),
 * 신규 진입·한도({@link DerivativeOrderOpener}), 청산 체결({@link DerivativePositionCloser}),
 * 평가 갱신·만기·강제청산({@link DerivativeSettlement}), 공개 차트 집계({@link DerivativeChartQueries}).
 * 협력 객체는 Spring 빈이 아니므로 순환 의존이 생기지 않고, 같은 JdbcTemplate으로 이 서비스의
 * 트랜잭션에 참여한다. 시계 교체({@link #setClock})는 공급자를 통해 모두에게 즉시 반영된다.
 */
@Service
public class DerivativeService {
    private static final Logger log = LoggerFactory.getLogger(DerivativeService.class);
    private static final int MAX_ORDER_QUANTITY = 100;
    private static final int MAX_OPEN_POSITIONS = 60;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private final DerivativeIndexHistory indexHistory;
    private Instant nextHistoryRetention=Instant.EPOCH;
    void setClock(Clock clock) { this.clock = clock; }
    private final DerivativePriceOracle oracle;
    private final DerivativeBaskets baskets;
    private DerivativeMarketImpact marketImpact;
    private final DerivativeQuotes quotes;
    private final DerivativePositionStore store;
    private final DerivativeSettlement settlement;
    private final DerivativeChartQueries charts;
    private final DerivativeAccountView accounts;
    private final DerivativeOrderOpener opener;
    private final DerivativePositionCloser closer;

    @org.springframework.beans.factory.annotation.Autowired
    void configureMarketImpact(DerivativeMarketImpact impact) { this.marketImpact = impact; }

    public DerivativeService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.indexHistory = new DerivativeIndexHistory(jdbc);
        this.oracle = new DerivativePriceOracle(jdbc);
        this.baskets = new DerivativeBaskets(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
        this.quotes = new DerivativeQuotes(baskets, indexHistory, () -> this.clock);
        this.store = new DerivativePositionStore(jdbc, () -> this.clock);
        this.settlement = new DerivativeSettlement(jdbc, transactions, () -> this.clock, indexHistory, oracle, baskets,
                quotes, store, () -> this.marketImpact);
        this.charts = new DerivativeChartQueries(jdbc, () -> this.clock);
        this.accounts = new DerivativeAccountView(jdbc, () -> this.clock, indexHistory, quotes, store);
        this.opener = new DerivativeOrderOpener(quotes, store, accounts);
        this.closer = new DerivativePositionCloser(jdbc, () -> this.clock, indexHistory, quotes, store, accounts);
    }

    // Production migration is serialized by MarketSchema; tests may initialize explicitly.
    public void ensureTables() {
        ensureTables(jdbc);
    }

    /** 스키마 마이그레이션에서도 호출한다. 여러 번 실행해도 안전하다. */
    static void ensureTables(JdbcTemplate jdbc) {
        DerivativePositionStore.ensureTables(jdbc);
    }

    // ------------------------------------------------------------ 공개 시세

    public DerivativeSnapshot snapshot() {
        return quotes.snapshot();
    }

    // ------------------------------------------------------------ 계좌

    /** 계좌 평가와 최근 거래. 조회 내용은 {@link DerivativeAccountView}가 만든다. */
    @Transactional
    public Account account(long userId) {
        return accounts.account(userId);
    }

    // ------------------------------------------------------------ 주문

    @Transactional
    public OrderResult open(long userId, OrderRequest request) {
        if (request == null) throw new IllegalArgumentException("주문 내용을 확인해 주세요.");
        int quantity = request.quantity();
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        String kind = normalized(request.kind(), "상품 종류");
        String side = normalized(request.side(), "방향");
        store.requireTradingEnabled();
        store.lockUser(userId);
        int openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM derivative_positions WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
        if (openCount >= MAX_OPEN_POSITIONS && !("ETF".equals(kind) && "SELL".equals(side)))
            throw new IllegalArgumentException("동시에 보유할 수 있는 파생 포지션은 " + MAX_OPEN_POSITIONS + "개까지입니다.");

        DerivativeBaskets.Values prices = quotes.loadPrices();
        quotes.requireFresh(prices, basketCode(kind, request.symbol()));
        // Exposure always uses the higher current valuation, regardless of the new order's side.
        DerivativeBaskets.Values exposurePrices = prices.execution(true);
        boolean higher = "BUY".equals(side) || "LONG".equals(side) || "CALL".equals(side);
        prices = prices.execution(higher);
        double index = prices.index();
        Instant now = clock.instant();
        if ("ETF".equals(kind)) {
            if (!"BUY".equals(side) && !"SELL".equals(side))
                throw new IllegalArgumentException("ETF 방향은 BUY 또는 SELL입니다.");
            if ("SELL".equals(side)) return closer.closeEtfBySymbol(userId, request.symbol(), quantity, prices, index);
            return opener.openEtf(userId, request.symbol(), quantity, prices, index, exposurePrices);
        }
        if ("FUTURE".equals(kind)) return opener.openFuture(userId, side, quantity, request.leverage(), index, exposurePrices);
        if ("OPTION".equals(kind)) return opener.openOption(userId, request, quantity, index, now, exposurePrices);
        throw new IllegalArgumentException("상품 종류는 ETF, FUTURE, OPTION 중 하나입니다.");
    }

    @Transactional
    public OrderResult close(long userId, long positionId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        store.requireTradingEnabled();
        store.lockUser(userId);
        PositionRow row = store.lockPosition(userId, positionId);
        if (row == null) throw new IllegalArgumentException("청산할 포지션을 찾을 수 없습니다.");
        if (!"OPEN".equals(row.status())) throw new IllegalArgumentException("이미 종료된 포지션입니다.");
        if (quantity > row.quantity())
            throw new IllegalArgumentException("보유 수량 " + row.quantity() + "계약을 초과했습니다.");
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            settlement.expireOption(positionId);
            return new OrderResult("만기 옵션을 정산했습니다.", accounts.account(userId));
        }
        return closer.closeInternal(row, quantity, "CLOSE");
    }

    /**
     * A collateral sale rechecks its cash shortfall only after obtaining the account and position locks.
     * The loan service supplies the fee/slippage buffer and may accrue interest under the same lock.
     * Returns true only when a successful partial close leaves contracts for another attempt.
     */
    @Transactional
    public boolean closeForCollateral(long userId, long positionId, LongSupplier bufferedCashNeeded) {
        store.requireTradingEnabled();
        store.lockUser(userId);
        PositionRow row = store.lockPosition(userId, positionId);
        if (row == null || !"OPEN".equals(row.status()) || row.quantity() <= 0) return false;
        long needed = bufferedCashNeeded.getAsLong();
        if (needed <= 0) return false;
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            settlement.expireOption(positionId);
            return false;
        }
        // Marks estimate the size; closeInternal still uses the current protected execution price.
        // If proceeds fall short, the caller can retry the remaining contracts with their new mark.
        int quantity = Math.min(row.quantity(), MAX_ORDER_QUANTITY);
        if (row.markValue() > 0)
            quantity = (int) Math.min(quantity, Math.max(1, Math.ceil(needed * (double) row.quantity() / row.markValue())));
        closer.closeInternal(row, quantity, "CLOSE");
        return quantity < row.quantity();
    }

    // ------------------------------------------------------------ 만기·청산

    /**
     * 평가 갱신과 만기·강제청산. 여러 인스턴스가 실행해도 각 포지션을
     * 짧은 트랜잭션에서 잠그므로 같은 포지션을 두 번 결제하지 않는다.
     * 같은 인스턴스 안의 갱신은 이 서비스의 모니터({@code synchronized})로 직렬화한다.
     * 실제 갱신은 {@link DerivativeSettlement#refresh()}가 이 모니터 안에서 수행한다.
     */
    @Scheduled(fixedDelayString = "${gamestock.derivatives.refresh-ms:3000}",
            initialDelayString = "${gamestock.derivatives.initial-delay-ms:5000}")
    public synchronized void refreshMarks() {
        try {
            settlement.refresh();
            if(!clock.instant().isBefore(nextHistoryRetention)) {
                indexHistory.retain(clock.instant());
                nextHistoryRetention=clock.instant().plusSeconds(3600);
            }
        } catch (RuntimeException error) {
            log.warn("Derivative mark refresh failed: {}", error.toString());
        }
    }

    // ------------------------------------------------------------ 도우미

    /** 주문 입력 정규화. {@link DerivativeOrderOpener}의 옵션 진입 검증도 같이 쓴다. */
    static String normalized(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "를 확인해 주세요.");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 공개 차트

    /** 옵션 거래 흐름. 집계와 5초 캐시는 {@link DerivativeChartQueries}가 맡는다. */
    public OptionFlow optionFlow(String range, String interval) {
        return charts.optionFlow(range, interval);
    }

    /** 지수 선물 봉 차트. */
    public List<IndexCandle> futureChart(String range) {
        return charts.futureChart(range);
    }

    /** 한국시간 오늘 0시부터의 게임주 지수 10분 봉. */
    public List<IndexCandle> indexToday() {
        return charts.indexToday();
    }
}
