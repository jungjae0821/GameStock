package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.time.Clock;
import java.util.Comparator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;
import java.sql.Timestamp;

import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

@Service
public class MarketService {
    static final ZoneId MISSION_ZONE = ZoneId.of("Asia/Seoul");
    static final List<String> MISSION_IDS = MarketRankingMissions.MISSION_IDS;
    /** GameStock charges a small, transparent 0.10% commission per side. */
    private static final double TRADING_FEE_RATE = 0.001;
    static final String TRADER_BOT_PASSWORD = "TRADER";
    private List<BotProfile> traderProfiles = List.of();
    private MarketRegimeEngine regimes;
    private PatternEngine patterns;
    private BotStrategyEngine strategies;
    private final MarketMakerEngine marketMaker = new MarketMakerEngine();
    private PriceMetricService priceMetrics;
    private final Map<String, MarketEnvironment> environments = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sectors = new HashMap<>();
    private final Map<String, Random> scheduleRandoms = new HashMap<>();
    private static final int USER_ORDER_WINDOW_SECONDS = 10;
    private static final int USER_ORDER_LIMIT = 20;
    private static final int DUPLICATE_ORDER_WINDOW_SECONDS = 2;
    static final String DEMO_USERNAME = "demo";

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private MatchingEngine matching=MatchingEngine.direct();
    private MarketMetrics pipelineMetrics=new MarketMetrics();
    private MarketOwnership ownership;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configurePipeline(MatchingEngine matching,MarketMetrics metrics,MarketOwnership ownership){
        this.matching=matching;this.pipelineMetrics=metrics;this.ownership=ownership;
    }
    private TitleService titles;
    private AdminRuntimeControl adminRuntime;
    AdminRuntimeControl adminRuntime(){return adminRuntime;}
    private DerivativeMarketImpact derivativeImpact;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureDerivativeImpact(DerivativeMarketImpact impact){this.derivativeImpact=impact;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureAdminRuntime(AdminRuntimeControl controls){this.adminRuntime=controls;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureTitles(TitleService titles){this.titles=titles;}
    public boolean matchingEnabled(){return ownership==null||ownership.enabled();}
    public boolean ownerAvailable(){return ownership==null||ownership.available();}
    public void requireMatchingOwner(){if(ownership!=null)ownership.requireOwner();}

    // Wall clock in production; replaceable with a synchronized Java/SQL clock in soak tests.
    private Clock clock = Clock.systemUTC();
    Clock clock(){return clock;}
    long simulationSeed(){return simulationSeed;}
    long simulationStartedAt(){return simulationStartedAt;}
    Random tickRandom(){return tickRandom;}
    BotStrategyEngine strategies(){return strategies;}
    BotResearch research(){return research;}
    private final UserFeatureService userFeatures;
    private final TradingProtectionService protection;
    @Value("${market.seed:${gamestock.simulation.seed:}}")
    private String configuredSeed;
    @Value("${gamestock.schema.migration-lock-timeout-seconds:60}")
    private int schemaMigrationLockTimeoutSeconds = 60;
    private long simulationSeed;
    private long simulationStartedAt;
    @Value("${market.batch.enabled:true}")
    private boolean batchEnabled;
    @Value("${market.batch.participants:6020}")
    private int batchParticipants=20;
    @Value("${market.batch.target-per-symbol:300}")
    private int targetPerSymbol=300;
    @Value("${market.batch.book-cache:true}")
    private boolean bookCacheEnabled=true;
    @Value("${market.batch.compact-ledger:true}")
    private boolean compactLedger;
    @Value("${market.batch.ledger-detail:false}")
    private boolean ledgerDetail;
    @Value("${market.batch.detail-retention-minutes:15}")
    private int detailRetentionMinutes=15;
    private BotLedgerJournal botJournal;
    BotLedgerJournal botJournal(){return botJournal;}
    @Value("${market.batch.inline-liquidity:true}")
    private boolean inlineMarketMaker;
    public boolean inlineLiquidity(){return batchEnabled&&inlineMarketMaker;}
    @Value("${market.batch.adaptive:true}")
    private boolean adaptiveActivity;
    @Value("${market.batch.idle-after-ms:120000}")
    private long idleAfterMillis=120000;
    @Value("${market.batch.active-cadence-ms:100}")
    private long activeCadenceMillis=100;
    @Value("${market.batch.idle-refresh-ms:1800000}")
    private long idleRefreshMillis=1800000;
    @Value("${market.batch.idle-refresh-batches:5}")
    private int idleRefreshBatches=5;
    private volatile boolean simulationInitialized;
    private final MarketActivityPolicy activityPolicy=new MarketActivityPolicy();
    public void recordHumanActivity(){activityPolicy.touch(clock.millis());prepareVisitor();events.publishEvent(new MarketPresenceEvent());}
    public void recordRemoteActivity(){activityPolicy.touch(clock.millis());prepareVisitor();}
    public void marketViewerConnected(String id){
        activityPolicy.connected(id,clock.millis());
        // Presence never adds simulation steps. Only an overdue scheduler needs recovery.
        prepareVisitor();
        events.publishEvent(new MarketPresenceEvent());
    }
    public void marketViewerDisconnected(String id){activityPolicy.disconnected(id,clock.millis());}
    // Market time is wall time, independent of connected viewers.
    boolean activeSimulation(){return true;}
    public long participantCadenceMillis(){
        if(adminRuntime!=null){Integer configured=adminRuntime.settings().cadenceMs();if(configured!=null)return configured;}
        return Math.max(50,Math.min(250,activeCadenceMillis));
    }
    int currentTargetPerSymbol(){
        if(adminRuntime!=null){Integer configured=adminRuntime.settings().targetPerSymbol();if(configured!=null)return configured;}
        return targetPerSymbol;
    }
    boolean botWorkDue(String worker){return !adaptiveActivity||activityPolicy.workDue(worker,clock.millis(),activeSimulation());}
    boolean claimLegacyBotWork(String worker){return !adaptiveActivity||activityPolicy.claim(worker,clock.millis(),activeSimulation())>=0;}
    private void prepareVisitor() {
        if(simulationInitialized&&adaptiveActivity&&activityPolicy.visitorMustWait(clock.millis(),true))maintainScheduledMarket();
    }
    /** Maintenance and participants share the same wall clock with or without viewers. */
    public void maintainScheduledMarket() {
        if(!simulationInitialized||!matchingEnabled()||!ownerAvailable())return;
        activityPolicy.maintain(clock.millis(),activeSimulation(),idleRefreshMillis,idleRefreshBatches,()->{
            var tx=new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.executeWithoutResult(status->maintainMarket());
        });
    }
    Map<String,Long> bookCacheStats(){return botBatches.cacheStats();}
    // Bot batch lanes read these per call; tests replace the fields by name, so they stay here.
    List<BotProfile> traderProfiles(){return traderProfiles;}
    int configuredTargetPerSymbol(){return targetPerSymbol;}
    boolean adaptiveActivity(){return adaptiveActivity;}
    MarketActivityPolicy activityPolicy(){return activityPolicy;}
    long idleAfterMillis(){return idleAfterMillis;}
    boolean bookCacheEnabled(){return bookCacheEnabled;}
    boolean inlineMarketMaker(){return inlineMarketMaker;}
    MarketMetrics pipelineMetrics(){return pipelineMetrics;}
    PriceMetricService priceMetrics(){return priceMetrics;}
    MatchingEngine matching(){return matching;}
    BatchMarketRepository newBatchRepository(){return new BatchMarketRepository(jdbc,compactLedger?botJournal:null);}
    private final NewsValuation newsValuation;
    private final MarketRankingMissions rankingMissions;
    private final MarketNewsFeed newsFeed;
    private final MarketChartQueries chartQueries;
    private final MarketBootstrap bootstrap;
    private final LiquidityProviderAccounts liquidity;
    private final BotResearch research;
    private final HoldingLedger holdings;
    private final SinglePriceAuction auction;
    private final OpenOrderPriceNormalizer priceNormalizer;
    private final AccountQueries accountQueries;
    private final BotBatchLanes botBatches;
    public int batchShardCount() {return botBatches.shardCount();}
    Map<String,Long> batchTimings(int shard) {return botBatches.timings(shard);}
    private Random tickRandom = new Random(20260910L);
    private long demoUserId;

    public MarketService(ApplicationEventPublisher events, JdbcTemplate jdbc, UserFeatureService userFeatures,
                         TradingProtectionService protection) {
        this.events = events;
        this.jdbc = jdbc;
        this.newsValuation = new NewsValuation(jdbc);
        this.rankingMissions = new MarketRankingMissions(this, jdbc);
        this.newsFeed = new MarketNewsFeed(this, jdbc, newsValuation);
        this.chartQueries = new MarketChartQueries(this, jdbc);
        this.bootstrap = new MarketBootstrap(this, jdbc);
        this.liquidity = new LiquidityProviderAccounts(this, jdbc);
        this.research = new BotResearch(jdbc, newsFeed);
        this.holdings = new HoldingLedger(jdbc);
        this.userFeatures = userFeatures;
        this.protection = protection;
        this.auction = new SinglePriceAuction(this, jdbc, holdings, protection);
        this.priceNormalizer = new OpenOrderPriceNormalizer(this, jdbc);
        this.accountQueries = new AccountQueries(this, jdbc);
        this.botBatches = new BotBatchLanes(this, jdbc, events, liquidity, newsFeed, protection);
    }

    /**
     * Startup runs in two phases. Schema changes come first because MySQL
     * commits every CREATE/ALTER implicitly. Data steps then run in autocommit
     * and are idempotent, so a restart after a failure resumes them. Only steps
     * that pair a cash write with another write use their own short transaction.
     * {@code @PostConstruct} bypasses the transaction proxy, so a method-level
     * {@code @Transactional} would never apply here.
     */
    @PostConstruct
    public void initializeData() {
        simulationSeed = configuredSeed == null || configuredSeed.isBlank() ? new java.security.SecureRandom().nextLong()
                : Long.parseLong(configuredSeed);
        simulationStartedAt = clock.millis();
        tickRandom = new Random(simulationSeed);
        traderProfiles = batchEnabled?BotProfile.activePopulation(simulationSeed,batchParticipants):BotProfile.defaults(simulationSeed);
        regimes = new MarketRegimeEngine(simulationSeed ^ 11);
        patterns = new PatternEngine(simulationSeed ^ 23);
        strategies = new BotStrategyEngine(simulationSeed ^ 37);
        research.reset();
        scheduleRandoms.clear();
        priceMetrics = new PriceMetricService(jdbc);
        if(!matchingEnabled()){
            botJournal=new BotLedgerJournal(jdbc,ledgerDetail);
            demoUserId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,DEMO_USERNAME);
            simulationInitialized=true;return;
        }
        bootstrap.migrateSchemaWithLock(schemaMigrationLockTimeoutSeconds, userFeatures, protection, priceMetrics, titles);
        bootstrap.ensureDemoUser();
        bootstrap.migrateLegacyListings();

        StockCatalog.ensureListings(jdbc);
        AdminSchema.restoreStockMetadata(jdbc);
        new StockDenominationMigration(jdbc).apply(StockCatalog.initialPrices(),this::settleDuePayments,clock.instant());
        new DerivativeBaskets(jdbc).rebalance();
        MarketBookCache.seedRevisions(jdbc);
        botJournal=new BotLedgerJournal(jdbc,ledgerDetail);
        bootstrap.ensureTraderBots(traderProfiles);
        userFeatures.ensureDefaultTags();
        bootstrap.seedPriceHistory();
        seedDailySummaries();
        protection.repairPricesToDailyBands();
        priceNormalizer.normalize();
        initializeSimulationBooks();
        priceMetrics.seedCursors();
        startupTransaction().executeWithoutResult(status -> {
            acquireMarketLock();
            refreshEnvironments();
        });

        bootstrap.removeDefaultEvents();
        initializeNewsPriceState();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
        // Convert any rows created by the former T+1 implementation to the
        // new immediate-settlement state during startup. Each pending row pays
        // the seller and marks itself settled; one transaction keeps a restart
        // from paying a row twice.
        startupTransaction().executeWithoutResult(status -> settleDuePayments());
        if(batchEnabled)initializeActivityEngine();
        simulationInitialized=true;
    }

    private void initializeActivityEngine() {botBatches.initialize();}

    public boolean batchBotsEnabled() {return batchEnabled;}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<BotActivityEngine.Activity> participantBatch(int shard) {
        return participantBatch(shard,new java.util.concurrent.atomic.AtomicReference<>());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<BotActivityEngine.Activity> participantBatch(int shard,java.util.concurrent.atomic.AtomicReference<BotBatchPlan> retryPlan) {
        requireMatchingOwner();
        return botBatches.run(shard,retryPlan);
    }

    // Locking policy: read-only queries below run without the service monitor; each
    // statement reads committed rows and they touch no mutable fields. Methods that
    // write or use in-memory simulation state (bots, environments, random) stay synchronized.
    public List<Stock> stocks() {
        List<Stock> rows = jdbc.query("""
                SELECT s.stock_code, g.name, g.genre, s.current_price,
                       COALESCE(p.day_reference, s.current_price) AS day_reference,
                       s.total_volume
                FROM stocks s
                JOIN games g ON g.id = s.game_id
                LEFT JOIN stock_protection_state p ON p.stock_id = s.id
                ORDER BY s.id
                """, (rs, row) -> new Stock(
                rs.getString("stock_code"),
                rs.getString("name"),
                rs.getString("genre"),
                rs.getLong("current_price"),
                changePercent(rs.getLong("current_price"), rs.getLong("day_reference")),
                rs.getLong("total_volume"), null,rs.getLong("day_reference")));
        // Restrictions are looked up after the stock query has released its connection.
        // Querying inside a row mapper holds two pooled connections per caller, which
        // exhausts the pool once several requests run in parallel.
        Map<String,TradingRestriction> restrictions=protection.restrictions();
        return rows.stream().map(stock->new Stock(stock.code(), stock.name(), stock.genre(), stock.price(), stock.changePercent(),
                stock.volume(), restrictions.get(stock.code()),stock.referencePrice())).toList();
    }

    public List<MarketEvent> marketEvents() {return newsFeed.marketEvents();}
    public List<MarketEvent> latestNews() {return newsFeed.latestNews();}

    /** GameStock은 장 마감 없이 24시간 주문을 접수하는 게임형 시장이다. */
    public MarketStatus marketStatus() {
        return protection.marketStatus();
    }

    public List<MarketEvent> stockNews(String code) {return newsFeed.stockNews(code);}
    public List<MarketEvent> repriceNews(List<MarketEvent> news, Map<String, Long> prices) {return newsFeed.repriceNews(news, prices);}

    @Transactional(readOnly = true)
    public List<RankingEntry> ranking() {return rankingMissions.ranking();}

    @Transactional(readOnly = true)
    public Portfolio portfolio(long userId) {
        return portfolioUnsafe(userId);
    }

    @Transactional(readOnly = true)
    public DailyMissionStatus dailyMissions(long userId) {return rankingMissions.dailyMissions(userId);}

    static List<String> dailyMissionIds(LocalDate day) {return MarketRankingMissions.dailyMissionIds(day);}

    @Transactional
    public synchronized MissionRewardResult rewardMission(String missionId, long userId) {return rankingMissions.rewardMission(missionId, userId);}

    @Transactional(readOnly = true)
    public List<ActiveOrder> activeOrders(long userId) {
        return activeOrders(userId, null);
    }

    @Transactional(readOnly = true)
    public List<ActiveOrder> activeOrders(long userId, String stockCode) {return accountQueries.activeOrders(userId, stockCode);}

    /** The user's recently completed fills. Cash and shares settle immediately. */
    @Transactional(readOnly = true)
    public List<SettlementEntry> settlements(long userId) {return accountQueries.settlements(userId);}

    /**
     * Compatibility rollback for legacy pending fills. New fills are settled
     * immediately and therefore cannot enter this path. If an older pending
     * row is cancelled, both participants' holdings are rebuilt from their
     * earliest saved snapshot and the remaining trade ledger.
     */
    @Transactional
    public synchronized void cancelSettlement(long settlementId, long userId) {
        acquireMarketLock();
        settleDuePayments();
        List<CancelableSettlement> rows = jdbc.query("""
                SELECT id, trade_id, buyer_id, seller_id, stock_id, quantity, gross_amount,
                       buyer_fee, seller_fee, created_at,
                       buyer_quantity_before, buyer_settled_quantity_before,
                       buyer_average_price_before, buyer_realized_profit_loss_before,
                       seller_quantity_before, seller_settled_quantity_before,
                       seller_average_price_before, seller_realized_profit_loss_before
                FROM settlements
                WHERE id = ? AND status = 'PENDING'
                FOR UPDATE
                """, (rs, row) -> new CancelableSettlement(
                rs.getLong("id"), rs.getLong("trade_id"), rs.getLong("buyer_id"),
                rs.getLong("seller_id"), rs.getLong("stock_id"), rs.getInt("quantity"),
                rs.getLong("gross_amount"), rs.getLong("buyer_fee"), rs.getLong("seller_fee"),
                databaseInstant(rs.getTimestamp("created_at")),
                (Integer) rs.getObject("buyer_quantity_before"),
                (Integer) rs.getObject("buyer_settled_quantity_before"),
                (Long) rs.getObject("buyer_average_price_before"),
                (Long) rs.getObject("buyer_realized_profit_loss_before"),
                (Integer) rs.getObject("seller_quantity_before"),
                (Integer) rs.getObject("seller_settled_quantity_before"),
                (Long) rs.getObject("seller_average_price_before"),
                (Long) rs.getObject("seller_realized_profit_loss_before")), settlementId);
        if (rows.isEmpty()) throw new IllegalArgumentException("취소할 수 있는 미결제 거래가 없습니다.");
        CancelableSettlement settlement = rows.get(0);
        if (settlement.buyerId() != userId && settlement.sellerId() != userId)
            throw new IllegalArgumentException("본인의 미결제 거래만 취소할 수 있습니다.");
        if (!settlement.hasSnapshot())
            throw new IllegalArgumentException("이 거래는 이전 버전에서 체결되어 취소할 수 없습니다.");
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?",
                settlement.grossAmount() + settlement.buyerFee(), settlement.buyerId());
        jdbc.update("UPDATE settlements SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP WHERE id = ?",
                settlement.id());
        Timestamp executionAt=jdbc.queryForObject("SELECT created_at FROM trades WHERE id=?",Timestamp.class,settlement.tradeId());
        new DerivativePriceOracle(jdbc).invalidate(settlement.stockId(),executionAt.toInstant());
        priceMetrics.invalidate(settlement.stockId());
        jdbc.update("DELETE FROM lp_cash_projection WHERE stock_id=?",settlement.stockId());
        holdings.rebuildFromLedger(settlement.stockId(), settlement.buyerId());
        if (settlement.sellerId() != settlement.buyerId())
            holdings.rebuildFromLedger(settlement.stockId(), settlement.sellerId());
        events.publishEvent(new MarketChangedEvent());
    }

    @Transactional
    public synchronized void cancelOrder(long orderId, long userId) {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        List<OrderReservation> matches = jdbc.query("""
                SELECT id, user_id, reserved_cash
                FROM orders
                WHERE id = ? AND user_id = ? AND status = 'OPEN'
                FOR UPDATE
                """, (rs, row) -> new OrderReservation(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("reserved_cash")), orderId, userId);
        if (matches.isEmpty()) throw new IllegalArgumentException("취소할 수 있는 미체결 주문이 없습니다.");
        OrderReservation reservation = matches.get(0);
        if (reservation.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservation.reservedCash(), userId);
        jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", orderId);
        events.publishEvent(new MarketChangedEvent());
        events.publishEvent(new UserMarketEvent(userId,"ORDER_CANCELLED",Map.of("orderId",orderId,"portfolio",portfolioUnsafe(userId))));
    }

    /** Cancel the complete account order set under the same admission lock as matching. */
    @Transactional
    public int cancelAllOrders(long userId) {
        requireMatchingOwner();
        acquireMarketLock();
        long refund = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        int cancelled = jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE user_id = ? AND status = 'OPEN'", userId);
        if (refund > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", refund, userId);
        events.publishEvent(new MarketChangedEvent());
        events.publishEvent(new UserMarketEvent(userId, "ORDER_CANCELLED", Map.of("cancelled", cancelled, "portfolio", portfolioUnsafe(userId))));
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<OrderHistory> orderHistory(String code, long userId) {return accountQueries.orderHistory(code, userId);}

    /** 모든 사용자의 익명 체결 내역. 개인별 주문 API와 분리해 공개한다. */
    @Transactional(readOnly = true)
    public List<PublicTrade> publicTrades(String code) {return chartQueries.publicTrades(code);}
    public List<PricePoint> priceHistory(String code) {return chartQueries.priceHistory(code);}
    public List<PricePoint> priceHistory(String code, String range) {return chartQueries.priceHistory(code, range);}
    public List<ChartCandle> chartHistory(String code, String range) {return chartQueries.chartHistory(code, range);}
    public PriceDrivers priceDrivers(String code) {return chartQueries.priceDrivers(code);}
    /** Keeps the service monitor as before: seeding today's summary rows is a write. */
    public synchronized List<DailyCandle> dailySummaries(String code) {return chartQueries.dailySummaries(code);}
    @Transactional(readOnly = true)
    public OrderBook orderBook(String code) {return chartQueries.orderBook(code);}

    @Transactional(readOnly = true)
    public MarketSnapshot snapshot() {
        return new MarketSnapshot(stocks(), portfolioUnsafe(demoUserId), marketEvents());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResult order(OrderRequest request, long userId) {
        var outcome=submitOrders(List.of(new OrderBatch.Command(userId,request))).get(0);
        if(outcome.error()!=null)throw new IllegalArgumentException(outcome.error());
        return outcome.result();
    }

    /** All human orders and bot orders use the same in-memory book and settlement rules.
     * Validation failures reject only their own command; valid commands retain FIFO order.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<OrderBatch.Outcome> submitOrders(List<OrderBatch.Command> commands) {
        requireMatchingOwner();
        if(commands.isEmpty())return List.of();
        if(commands.size()>200)throw new IllegalArgumentException("Order batch exceeds 200 commands");
        List<OrderBatch.Outcome> outcomes=new ArrayList<>(Collections.nCopies(commands.size(),null));
        Map<String,Long> symbols=new HashMap<>();
        Set<Long> users=new HashSet<>();
        for(int i=0;i<commands.size();i++)try{
            var command=commands.get(i);OrderService.validate(command.request());
            String code=command.request().stockCode().toUpperCase(Locale.ROOT);
            Long id=jdbc.query("SELECT id FROM stocks WHERE stock_code=?",(rs,n)->rs.getLong(1),code).stream().findFirst().orElse(null);
            if(id==null)throw new IllegalArgumentException("존재하지 않는 종목입니다.");
            symbols.put(code,id);users.add(command.userId());
        }catch(IllegalArgumentException error){outcomes.set(i,OrderBatch.Outcome.rejected(error.getMessage()));}
        if(symbols.isEmpty())return outcomes;
        long now=clock.millis();
        var repository=newBatchRepository();
        repository.lock(Set.copyOf(symbols.values()));
        java.util.SortedSet<Long> accounts=new java.util.TreeSet<>(users);
        for(var symbol:symbols.entrySet()){
            long stock=symbol.getValue();
            var rows=jdbc.query("SELECT id,user_id,side,COALESCE(price,0),remaining_quantity,reserved_quantity,order_type='MARKET',expires_at IS NOT NULL AND expires_at<=? FROM orders WHERE stock_id=? AND status='OPEN' ORDER BY created_at,id",
                (rs,n)->new MarketExecutionLocks.Resting(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getInt(5),rs.getInt(6),rs.getBoolean(7),rs.getBoolean(8)),new Timestamp(now),stock);
            long last=jdbc.queryForObject("SELECT current_price FROM stocks WHERE id=?",Long.class,stock);
            long reference=dailyReferencePrice(symbol.getKey());
            for(int i=0;i<commands.size();i++)if(outcomes.get(i)==null&&commands.get(i).request().stockCode().equalsIgnoreCase(symbol.getKey())){
                var command=commands.get(i);accounts.addAll(MarketExecutionLocks.accountsFor(command.request(),command.userId(),rows,reference,last));
            }
        }
        for(long account:accounts)jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,account);
        var book=repository.loadLocked(now,Set.copyOf(symbols.values()),users);
        if(adminRuntime!=null)adminRuntime.apply(book);
        List<Integer> valid=new ArrayList<>();
        for(int i=0;i<commands.size();i++)if(outcomes.get(i)==null)try{
            var command=commands.get(i);var request=command.request();
            String code=request.stockCode().toUpperCase(Locale.ROOT),side=request.side().toUpperCase(Locale.ROOT);
            String type=request.orderType()==null||request.orderType().isBlank()?"MARKET":request.orderType().toUpperCase(Locale.ROOT);
            protection.validateOrder(code,type);
            long price=type.equals("LIMIT")?(request.price()==null?0:request.price()):book.stocks.get(symbols.get(code)).last;
            if(price<=0)throw new IllegalArgumentException("지정가를 입력해 주세요.");
            if(type.equals("LIMIT")&&(price%tickSize(price)!=0||!dailyPriceBand(code).contains(price)))
                throw new IllegalArgumentException("호가 단위와 가격제한폭에 맞는 가격을 입력해 주세요.");
            Math.addExact(Math.multiplyExact(price,(long)request.quantity()),feeFor(Math.multiplyExact(price,(long)request.quantity())));
            enforceOrderLimits(command.userId(),symbols.get(code),side,type,request.quantity(),type.equals("LIMIT")?price:0);
            valid.add(i);
        }catch(IllegalArgumentException|ArithmeticException error){outcomes.set(i,OrderBatch.Outcome.rejected(error.getMessage()));}
        Map<Long,Integer> recentCounts=new HashMap<>();
        for(long user:users)recentCounts.put(user,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 10 SECOND)",Integer.class,user));
        Map<Integer,BatchOrderBook.Order> accepted=matching.compute(()->{
            Map<Integer,BatchOrderBook.Order> result=new java.util.LinkedHashMap<>();
            for(int i:valid){
                var command=commands.get(i);var r=command.request();String code=r.stockCode().toUpperCase(Locale.ROOT);
                String side=r.side().toUpperCase(Locale.ROOT),type=r.orderType()==null||r.orderType().isBlank()?"MARKET":r.orderType().toUpperCase(Locale.ROOT);
                long price=type.equals("LIMIT")?r.price():0,stock=symbols.get(code);
                if(!book.accounts.get(command.userId()).liquidityProvider&&recentCounts.get(command.userId())+book.accepted.stream().filter(o->o.user==command.userId()).count()>=USER_ORDER_LIMIT){
                    outcomes.set(i,OrderBatch.Outcome.rejected("주문 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));continue;
                }
                // SQL sees prior commits; this check includes earlier commands in this micro-batch.
                boolean duplicate=book.accepted.stream().anyMatch(o->o.user==command.userId()&&o.stock==stock&&o.side.equals(side)&&o.type.equals(type)&&o.quantity==r.quantity()&&(type.equals("MARKET")||o.price==price));
                if(duplicate){outcomes.set(i,OrderBatch.Outcome.rejected("동일한 주문이 이미 접수되었습니다."));continue;}
                var order=book.submit(command.userId(),stock,side,type,r.quantity(),price,now,Duration.ofHours(24).toMillis());
                if(order==null)outcomes.set(i,OrderBatch.Outcome.rejected("주문 가능한 현금 또는 보유 수량이 부족합니다."));
                else result.put(i,order);
            }
            return result;
        });
        repository.persist(book,now);
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        Map<Long,Portfolio> portfolios=new HashMap<>();
        for(var entry:accepted.entrySet()){
            int i=entry.getKey();var command=commands.get(i);var order=entry.getValue();String code=book.stocks.get(order.stock).code;
            var execution=accountQueries.executionSummary(order.id,order.side);
            TradingRestriction restriction=protection.restriction(code);
            String message=restriction!=null&&order.status.equals("OPEN")?restriction.label()+" 단일가 주문 접수":orderMessage(order.status);
            outcomes.set(i,new OrderBatch.Outcome(new OrderResult(message,code,order.side,order.quantity,order.price>0?order.price:book.stocks.get(order.stock).last,
                order.status,portfolios.computeIfAbsent(command.userId(),this::portfolioUnsafe),execution.fee(),execution.status(),execution.settlementAt(),order.id),null));
        }
        publishBookEvents(book);
        events.publishEvent(bookEvent(book,now));
        return outcomes;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> symbolSnapshot(String code){return chartQueries.symbolSnapshot(code);}

    MarketChangedEvent bookEvent(BatchOrderBook book,long now){
        Map<String,Object> updates=new HashMap<>();
        for(var stock:book.stocks.values()){
            List<PublicTrade> trades=new ArrayList<>();
            for(int i=book.fills.size()-1;i>=0&&trades.size()<20;i--){
                var fill=book.fills.get(i);if(fill.buy().stock==stock.id)
                    trades.add(new PublicTrade(fill.taker().side,fill.quantity(),fill.price(),fill.taker().type,Instant.ofEpochMilli(now).toString()));
            }
            var quote=new Stock(stock.code,stock.name,stock.genre,stock.last,changePercent(stock.last,stock.reference),stock.totalVolume,stock.continuous?null:protection.restriction(stock.code),stock.reference);
            updates.put(stock.code,Map.of("symbol",stock.code,"stock",quote,"orderbook",book.snapshot(stock.id),"trades",trades,
                "chart",trades.stream().map(t->new PricePoint(t.price(),t.createdAt())).toList()));
        }
        return new MarketChangedEvent(updates);
    }

    void publishBookEvents(BatchOrderBook book){
        Set<Long> people=new HashSet<>();
        book.changedOrders.forEach(o->{if(!book.accounts.get(o.user).bot)people.add(o.user);});
        book.changedHoldings.forEach(k->{if(!book.accounts.get(k.user()).bot)people.add(k.user());});
        for(long user:people){
            var orders=book.changedOrders.stream().filter(o->o.user==user).map(o->Map.of(
                "id",o.id,"stockCode",book.stocks.get(o.stock).code,"status",o.status,"remainingQuantity",o.remaining)).toList();
            events.publishEvent(new UserMarketEvent(user,"ACCOUNT_UPDATED",Map.of("orders",orders,"portfolio",portfolioUnsafe(user),"settlements",settlements(user))));
        }
    }

    @Transactional
    public synchronized void maintainMarket() {
        acquireMarketLock();
        settleDuePayments();
        expireOrders();
        advanceTradingProtections();
        if (protection.marketStatus().open()) refreshEnvironments();
        for (String code : botStockCodes()) refreshMarkPrice(code);
        if(botJournal!=null)botJournal.retain(clock.millis(),Math.max(15,detailRetentionMinutes));
        events.publishEvent(new MarketChangedEvent());
    }

    public synchronized List<String> botStockCodes() {
        return jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
    }

    public List<String> participantBotUsernames() {
        return traderProfiles.stream().map(BotProfile::username).toList();
    }

    private void prepareBotAction(String code) {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE",Integer.class);
        MarketBookCache.invalidate(jdbc,code==null?null:stockId(code));
        if(!batchEnabled) {
            settleDuePayments();
            expireOrders();
            advanceTradingProtections();
        }
        long tick = nextSimulationTick();
        tickRandom = new Random(simulationSeed ^ (tick * 0x9E3779B97F4A7C15L));
    }

    /** One task per symbol refreshes both sides atomically; all fills still use the existing ledger. */
    @Transactional
    public synchronized void liquidityBotAction(String code, String ignoredSide) {
        prepareBotAction(code);
        if(adminRuntime!=null&&adminRuntime.botsPaused())return;
        if (!protection.marketStatus().open()) return;
        TradingRestriction restriction = protection.restriction(code);
        if (restriction != null && !restriction.limitOrdersAllowed()) return;
        long lp = liquidity.ensureProvider();
        // Measure live depth before withdrawing our quotes; replacement itself is not a liquidity crisis.
        PriceMetricService.Metrics metrics = metrics(code, 0);
        cancelBotOrders(lp, stockId(code));
        liquidity.ensureInventory(code, lp);
        OrderBook externalBook=orderBook(code);
        double news=newsFeed.recentNewsBias(code).rate();
        long reference=dailyReferencePrice(code);
        PriceBand band=quoteBand(code,reference,news);
        MarketMakerEngine.QuoteConstraints constraints=new MarketMakerEngine.QuoteConstraints(band.lowerPrice(),band.upperPrice(),
                externalBook.bids().isEmpty()?0:externalBook.bids().get(0).price(),
                externalBook.asks().isEmpty()?0:externalBook.asks().get(0).price());
        MarketMakerEngine.RiskBook risk = liquidityRiskBook(code, lp);
        double fundamental=jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,stockId(code));
        fundamental=StockMarketProfile.valuationAnchor(code,reference,fundamental,news);
        double nearbyRange=Math.max(metrics.lastPrice()*.01,tickSize(Math.round(metrics.lastPrice()))*4.0);
        long externalBids=externalBook.bids().stream().filter(q->q.price()>=metrics.lastPrice()-nearbyRange).mapToLong(OrderBookLevel::quantity).sum();
        long externalAsks=externalBook.asks().stream().filter(q->q.price()<=metrics.lastPrice()+nearbyRange).mapToLong(OrderBookLevel::quantity).sum();
        double pressure=(externalBids-externalAsks)/(double)Math.max(1,externalBids+externalAsks);
        for (MarketMakerEngine.Quote quote : marketMaker.quotes(metrics, environment(code), risk,
                tickSize(Math.round(metrics.midPrice())), marketScale(code),constraints,fundamental,pressure)) {
            insertLiquidityQuote(code, lp, quote);
        }
        matchOrders(code);
        refreshMarkPrice(code);
        events.publishEvent(new MarketChangedEvent());
    }

    @Transactional
    public synchronized void participantBotAction(String username) {
        prepareBotAction(null);
        if(adminRuntime!=null) {
            double share=adminRuntime.settings().traderShare();
            if(share<=0 || share<1 && java.util.concurrent.ThreadLocalRandom.current().nextDouble()>=share)return;
        }
        if (!protection.marketStatus().open()) return;
        BotProfile profile = traderProfiles.stream().filter(p -> p.username().equals(username)).findFirst().orElseThrow();
        List<String> codes = botStockCodes();
        if (codes.isEmpty()) return;
        long userId = traderBotId(username);
        BotObservation observation = selectBotObservation(profile,userId,codes);
        String code = observation.code();
        if(adminRuntime!=null&&adminRuntime.restriction(code)!=null)return;
        PriceMetricService.Metrics metrics = observation.metrics();
        long id = stockId(code);
        // Execute the same funded intent that won selection; do not redraw its signal after choosing a symbol.
        BotStrategyEngine.Decision decision = observation.decision();
        List<BotOrderPolicy.RestingOrder> resting=observation.resting();
        if (!resting.isEmpty()) {
            // An unrelated symbol or a fresh random pricing draw must not reset an order's queue priority/TTL.
            if (!observation.replace()) return;
            cancelBotOrders(userId,id);
            events.publishEvent(new MarketChangedEvent());
        }
        if (decision.quantity() <= 0) return;
        long price = "BUY".equals(decision.side()) ? floorToTick(Math.round(decision.limitPrice()))
                : ceilToTick(Math.round(decision.limitPrice()));
        PriceBand band = quoteBand(code,dailyReferencePrice(code),observation.news());
        price = "BUY".equals(decision.side()) ? floorToTick(band.clamp(price)) : ceilToTick(band.clamp(price));
        int quantity = Math.min(2,"SELL".equals(decision.side()) ? Math.min(decision.quantity(), availableQuantity(userId,id)) : decision.quantity());
        if (quantity <= 0) return;
        try {
            order(new OrderRequest(code, decision.side(), quantity, decision.market() ? "MARKET" : "LIMIT",
                    decision.market() ? null : price), userId);
            if (!decision.market()) jdbc.update("UPDATE orders SET expires_at=? WHERE user_id=? AND stock_id=? AND status='OPEN'",
                    Timestamp.from(clock.instant().plusMillis(Math.min(30_000,decision.ttlMillis()))), userId, id);
        } catch (IllegalArgumentException unavailable) {
            // Insufficient resources, protection, or user-style rate limits mean HOLD.
        }
    }

    record BotObservation(String code, PriceMetricService.Metrics metrics, BotStrategyEngine.Position position,
                          double estimate, double news, double score, List<BotOrderPolicy.RestingOrder> resting,
                          BotStrategyEngine.Decision decision, boolean replace) {}

    /** Tests override and spy on this name to trace bot choices; the research itself lives in BotResearch. */
    BotObservation selectBotObservation(BotProfile profile,long userId,List<String> codes) {
        return research.select(this,profile,userId,codes);
    }


    public synchronized long nextBotDelay(String username) {
        if(adminRuntime!=null){Integer configured=adminRuntime.settings().cadenceMs();if(configured!=null)return configured;}
        Random random = scheduleRandoms.computeIfAbsent(username, key -> new Random(simulationSeed ^ key.hashCode()));
        if (username.startsWith("liquidity:")) return 1000 + random.nextInt(2001);
        BotProfile p = traderProfiles.stream().filter(b -> b.username().equals(username)).findFirst().orElseThrow();
        return Math.max(500, (long)(p.decisionInterval() * (.65 + random.nextDouble()*.7)) + p.reactionLatency());
    }

    PriceMetricService.Metrics metrics(String code,long latency) {
        return priceMetrics.read(stockId(code), findStock(code).price(), clock.millis(), latency);
    }

    MarketEnvironment environment(String code) {
        MarketEnvironment base = environments.getOrDefault(code, MarketEnvironment.neutral());
        if (derivativeImpact == null) return base;
        MarketEnvironment derivative = derivativeImpact.environment(code, clock.instant());
        return derivative.equals(MarketEnvironment.neutral()) ? base : base.combine(StockMarketProfile.applyTo(code, derivative));
    }

    private void cancelBotOrders(long userId, Long stockId) {
        List<Long> ids = stockId == null
                ? jdbc.query("SELECT id FROM orders WHERE user_id=? AND status='OPEN'", (rs,n)->rs.getLong(1),userId)
                : jdbc.query("SELECT id FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'", (rs,n)->rs.getLong(1),userId,stockId);
        ids.forEach(this::cancelOrderInternal);
    }

    private void insertLiquidityQuote(String code,long lp,MarketMakerEngine.Quote quote) {
        boolean buy = "BUY".equals(quote.side());
        PriceBand band = botPriceBand(code);
        long price = buy ? floorToTick(Math.round(quote.price())) : ceilToTick(Math.round(quote.price()));
        if (!band.contains(price)) return;
        OrderBook book = orderBook(code);
        if (buy && !book.asks().isEmpty() && price >= book.asks().get(0).price()
                || !buy && !book.bids().isEmpty() && price <= book.bids().get(0).price()) return;
        long id = stockId(code);
        MarketMakerEngine.RiskBook risk = liquidityRiskBook(code,lp);
        int quantity = buy ? Math.min(quote.quantity(), (int)Math.min(Integer.MAX_VALUE,
                risk.cashBudget()/Math.max(1,price*1.001))) : Math.min(quote.quantity(),availableQuantity(lp,id));
        if (quantity<=0) return;
        long reserved = buy ? price*quantity+feeFor(price*quantity) : 0;
        if (buy && jdbc.update("UPDATE users SET cash=cash-? WHERE id=? AND cash>=?",reserved,lp,reserved)==0) return;
        jdbc.update("""
                INSERT INTO orders (user_id,stock_id,side,order_type,price,quantity,remaining_quantity,reserved_cash,reserved_quantity,status,expires_at,compact_origin)
                VALUES (?,?,?,'LIMIT',?,?,?,?,?,'OPEN',?,?)
                """,lp,id,quote.side(),price,quantity,quantity,reserved,buy?0:quantity,
                Timestamp.from(clock.instant().plusMillis(4000)),compactLedger);
    }

    private long traderBotId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND password_hash = ?",
                Long.class, username, TRADER_BOT_PASSWORD);
    }

    /** PostConstruct bypasses the transaction proxy, so startup writes that must be atomic use this. */
    org.springframework.transaction.support.TransactionTemplate startupTransaction() {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** Tests read the provider's budget through this name, and quoting uses it on every refresh. */
    private MarketMakerEngine.RiskBook liquidityRiskBook(String code,long lp) {
        return liquidity.riskBook(code,lp);
    }

    LiquidityProviderAccounts liquidity() {return liquidity;}

    private void initializeSimulationBooks() {
        // Startup cancellation and finite allocations must commit together.
        startupTransaction().executeWithoutResult(status->{
            acquireMarketLock();
            long lp = liquidity.ensureProvider();
            cancelBotOrders(lp, null);
            List<String> codes = botStockCodes();
            long baseline = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades", Long.class);
            int existing = jdbc.queryForObject("SELECT COUNT(*) FROM lp_risk_books", Integer.class);
            long allocation = existing == 0 ? availableCash(lp) / Math.max(1,codes.size()) : 0;
            for (String code : codes) {
                long id = stockId(code);
                Stock stock = findStock(code);
                liquidity.ensureInventory(code,lp);
                // Non-zero allocations and trade baselines are retained across restarts.
                jdbc.update("INSERT IGNORE INTO lp_risk_books VALUES (?,?,?,?,?,?)",
                        id,allocation,baseline,batchEnabled?100:400,1200,stock.price()*1200L);
                int target=StockMarketProfile.initialLiquidityInventory(code,LiquidityProviderAccounts.INITIAL_INVENTORY);
                // A profile migration may increase the budget; an ordinary
                // restart must not inflate it just because the last price rose.
                jdbc.update("UPDATE lp_risk_books SET target_inventory=?,max_inventory=GREATEST(max_inventory,?),risk_limit=GREATEST(risk_limit,?) WHERE stock_id=? AND (target_inventory<>? OR max_inventory<?)",
                        target,target*4,stock.price()*target*4L,id,target,target*4);
                jdbc.update("INSERT IGNORE INTO market_price_metrics (stock_id,mark_price,hidden_fundamental,anchor_price) VALUES (?,?,?,?)",
                        id,stock.price(),stock.price(),stock.price());
                sectors.put(code,stock.genre());
            }
            liquidity.ensureCashReserve(lp,codes);
        });
    }

    private void refreshEnvironments() {
        long now = clock.millis();
        regimes.advance(now,sectors);
        for (String code : botStockCodes()) {
            MarketNewsFeed.NewsBias news = newsFeed.recentNewsBias(code);
            MarketEnvironment event = new MarketEnvironment(news.rate()*4,0,1+Math.abs(news.rate())*12,
                    1+Math.max(0,news.rate())*8,1+Math.max(0,-news.rate())*8,
                    1+Math.abs(news.rate())*5,1/(1+Math.abs(news.rate())*5),0,0,0,
                    1+Math.abs(news.rate())*5,1+Math.abs(news.rate())*5);
            MarketEnvironment raw = regimes.environment(code,sectors.getOrDefault(code,"OTHER"))
                    .combine(patterns.environment(code,now)).combine(event);
            environments.put(code,StockMarketProfile.applyTo(code,raw));
            updateFundamentalFromNews(code);
        }
    }

    private void updateFundamentalFromNews(String code) {
        newsValuation.project(stockId(code), code, clock.instant());
    }

    private void refreshMarkPrice(String code) {
        if (priceMetrics == null) return;
        long id=stockId(code);
        Timestamp previous=jdbc.queryForObject("SELECT mark_updated_at FROM market_price_metrics WHERE stock_id=?",Timestamp.class,id);
        long now=clock.millis();
        // Valuation may coalesce for 250 ms; actual fills/last price/history are always synchronous.
        // Persist the watermark in the same transaction so rollback/restart cannot leave a stale cache.
        if(previous!=null && now>=previous.getTime() && now-previous.getTime()<250) return;
        priceMetrics.project(id,now);
        long mark = Math.max(1,Math.round(metrics(code,0).markPrice()));
        jdbc.update("UPDATE market_price_metrics SET mark_price=?,mark_updated_at=? WHERE stock_id=?",mark,new Timestamp(now),id);
    }

    /** Human activity and virtual market size determine liquidity; bots cannot amplify their own scale. */
    double marketScale(String code) {
        double humanScale = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id), COALESCE(SUM(t.quantity),0)
                FROM trades t JOIN users u ON (u.id=t.buyer_id OR u.id=t.seller_id)
                WHERE t.stock_id=? AND t.created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE)
                  AND u.password_hash NOT IN ('BOT','TRADER') AND u.username<>'demo'
                  AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                """, (rs,n)->Math.min(1.5,.65+rs.getLong(1)*.04+Math.sqrt(rs.getLong(2))*.015),stockId(code));
        return StockMarketProfile.liquidityScale(code,humanScale);
    }

    private void advanceTradingProtections() {
        boolean dayChanged = protection.refreshDay();
        List<String> repaired = protection.repairPricesToDailyBands();
        if (dayChanged || !repaired.isEmpty()) priceNormalizer.normalize();
        protection.advanceMarketPhase();
        if (protection.marketAuctionDue()) {
            List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
            for (String code : codes) {
                auction.close(code);
                protection.finishAuction(code, findStock(code).price());
            }
            protection.finishMarketAuction();
        }
        for (String code : protection.dueViAuctions()) {
            if (protection.marketStatus().restriction() != null) break;
            auction.close(code);
            protection.finishAuction(code, findStock(code).price());
        }
        protection.observeMarket();
    }

    private void matchOrders(String code) {
        if(!protection.continuous(code))return;
        long now=clock.millis(),id=stockId(code);
        var repository=newBatchRepository();
        var book=repository.load(now,Set.of(id),Set.of());
        matching.compute(()->{book.matchExisting(id);return null;});
        repository.persist(book,now);
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        publishBookEvents(book);events.publishEvent(bookEvent(book,now));
    }

    private void cancelRemainingMarket(long orderId) {
        OrderState order = findOrder(orderId);
        if (order.remainingQuantity() <= 0) return;
        String status = order.quantity() == order.remainingQuantity() ? "CANCELLED" : "PARTIAL";
        jdbc.update("UPDATE orders SET status = ?, remaining_quantity = 0 WHERE id = ?", status, orderId);
    }

    private String orderMessage(String status) {
        return switch (status) {
            case "FILLED" -> "주문이 체결되었습니다.";
            case "PARTIAL" -> "일부 체결 후 잔량은 취소되었습니다.";
            case "CANCELLED" -> "체결할 호가가 없어 주문이 취소되었습니다.";
            default -> "지정가 주문이 호가창에 접수되었습니다.";
        };
    }

    void seedDailySummaries() {
        LocalDate tradingDate = LocalDate.now(clock.withZone(MISSION_ZONE));
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT s.id, ?, p.day_reference, p.day_reference, 0
                FROM stocks s JOIN stock_protection_state p ON p.stock_id = s.id
                WHERE NOT EXISTS (
                    SELECT 1 FROM daily_market_summaries d
                    WHERE d.stock_id = s.id AND d.trading_date = ?
                )
                """, tradingDate, tradingDate);
    }

    /** Seed only new rows so a backend restart never reapplies old headlines. */
    private void initializeNewsPriceState() {
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        for (String code : codes) {
            long id = stockId(code);
            double currentBias = newsFeed.recentNewsBias(code).rate();
            jdbc.update("INSERT IGNORE INTO news_price_state (stock_id, applied_bias) VALUES (?, ?)", id, currentBias);
        }
    }

    /** Reject accidental double-clicks and abusive bursts from human users. */
    private void enforceOrderLimits(long userId, long stockId, String side, String orderType,
                                    int quantity, long price) {
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if ("BOT".equals(passwordHash) || "TRADER".equals(passwordHash)) return;
        Integer recentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + USER_ORDER_WINDOW_SECONDS + " SECOND)",
                Integer.class, userId);
        if (recentCount != null && recentCount >= USER_ORDER_LIMIT)
            throw new IllegalArgumentException("주문 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        Integer duplicateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND stock_id = ? AND side = ? AND order_type = ? "
                        + "AND quantity = ? AND (order_type = 'MARKET' OR COALESCE(price, 0) = ?) "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + DUPLICATE_ORDER_WINDOW_SECONDS + " SECOND)",
                Integer.class, userId, stockId, side, orderType, quantity, price);
        if (duplicateCount != null && duplicateCount > 0)
            throw new IllegalArgumentException("동일한 주문이 이미 접수되었습니다.");
    }

    private long nextSimulationTick() {
        Long current = jdbc.queryForObject("SELECT tick FROM simulation_state WHERE id = 1 FOR UPDATE", Long.class);
        long next = (current == null ? 0 : current) + 1;
        jdbc.update("UPDATE simulation_state SET tick = ? WHERE id = 1", next);
        return next;
    }

    /** Serialize order matching across multiple backend instances. */
    void acquireMarketLock() {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE", Integer.class);
        MarketBookCache.invalidate(jdbc,null);
    }

    /** Finish any legacy pending deliveries left by an older T+1 database. */
    private void settleDuePayments() {
        List<PendingSettlement> due = jdbc.query("""
                SELECT id, buyer_id, seller_id, stock_id, quantity, gross_amount, seller_fee
                FROM settlements
                WHERE status = 'PENDING'
                ORDER BY id ASC
                FOR UPDATE
                """, (rs, row) -> new PendingSettlement(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getInt(5), rs.getLong(6), rs.getLong(7)));
        for (PendingSettlement settlement : due) {
            long sellerNet = settlement.grossAmount() - settlement.sellerFee();
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", sellerNet, settlement.sellerId());
            jdbc.update("""
                    UPDATE portfolios
                    SET settled_quantity = LEAST(quantity, COALESCE(settled_quantity, quantity) + ?)
                    WHERE user_id = ? AND stock_id = ?
                    """, settlement.quantity(), settlement.buyerId(), settlement.stockId());
            jdbc.update("UPDATE settlements SET status = 'SETTLED', settled_at = CURRENT_TIMESTAMP WHERE id = ?", settlement.id());
        }
    }

    private void expireOrders() {
        expireOrders(null,null);
    }

    private void expireOrders(Long stockId,Timestamp acceptedAt) {
        Timestamp cutoff=acceptedAt==null?Timestamp.from(clock.instant()):acceptedAt;
        String humanExpiry="SELECT DISTINCT o.user_id FROM orders o JOIN users u ON u.id=o.user_id WHERE o.status='OPEN' AND o.expires_at<=? AND u.password_hash NOT IN ('BOT','TRADER')"+(stockId==null?"":" AND o.stock_id=?");
        List<Long> expiredPeople=jdbc.queryForList(humanExpiry,Long.class,stockId==null?new Object[]{cutoff}:new Object[]{cutoff,stockId});
        if(stockId!=null) {
            // Symbol admission already protects this book. Primary-key writes avoid taking
            // an empty expiry range lock that could stall an unrelated symbol's first order.
            var expired=jdbc.query("SELECT id,user_id,reserved_cash FROM orders FORCE INDEX (ix_orders_live_book) WHERE stock_id=? AND status='OPEN' AND expires_at<=?",
                    (rs,n)->new OrderReservation(rs.getLong(1),rs.getLong(2),rs.getLong(3)),stockId,cutoff);
            for(var order:expired) {
                if(order.reservedCash()>0)jdbc.update("UPDATE users SET cash=cash+? WHERE id=?",order.reservedCash(),order.userId());
                jdbc.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE id=?",order.id());
            }
            publishExpirations(expiredPeople);return;
        }
        String where="status='OPEN' AND expires_at IS NOT NULL AND expires_at<=?"+(stockId==null?"":" AND stock_id=?");
        Object[] args=stockId==null?new Object[]{cutoff}:new Object[]{cutoff,stockId};
        // The gate/symbol admission lock holds the same expiry set stable for both statements.
        // Aggregate refunds once per account instead of issuing two SQL round trips per order.
        jdbc.update("UPDATE users u JOIN (SELECT user_id,SUM(reserved_cash) refund FROM orders WHERE "+where
                +" GROUP BY user_id) r ON r.user_id=u.id SET u.cash=u.cash+r.refund",args);
        jdbc.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE "+where,args);
    }

    private void publishExpirations(List<Long> people){
        for(long user:people)events.publishEvent(new UserMarketEvent(user,"ORDER_EXPIRED",Map.of("portfolio",portfolioUnsafe(user))));
    }

    void cancelOrderInternal(long orderId) {
        OrderState order = findOrder(orderId);
        if (!"OPEN".equals(order.status())) return;
        if ("MARKET".equals(order.orderType())) { cancelRemainingMarket(orderId); return; }
        if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
        jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", orderId);
    }

    long availableCash(long userId) {
        return jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
    }

    int availableQuantity(long userId, long stockId) {
        int owned = jdbc.query("SELECT COALESCE(settled_quantity, quantity) FROM portfolios WHERE user_id = ? AND stock_id = ?", (rs, row) -> rs.getInt(1), userId, stockId).stream().findFirst().orElse(0);
        Integer reserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_quantity), 0) FROM orders WHERE user_id = ? AND stock_id = ? AND side = 'SELL' AND status = 'OPEN'", Integer.class, userId, stockId);
        return owned - (reserved == null ? 0 : reserved);
    }

    private OrderState findOrder(long orderId) {
        return jdbc.query("""
                SELECT id, user_id, COALESCE(price, 0), quantity, remaining_quantity, status, order_type,
                       COALESCE(reserved_cash, 0), COALESCE(reserved_quantity, 0)
                FROM orders WHERE id = ?
                """, (rs, row) -> new OrderState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getInt(9)), orderId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));
    }

    HoldingState holdingState(long stockId, long userId) {
        return holdings.state(stockId, userId);
    }

    static long feeFor(long grossAmount) {
        if (grossAmount <= 0) return 0;
        return Math.max(1L, Math.round(grossAmount * TRADING_FEE_RATE));
    }

    private record OrderState(long id, long userId, long price, int quantity, int remainingQuantity, String status,
                              String orderType, long reservedCash, int reservedQuantity) { }
    private record OrderReservation(long id, long userId, long reservedCash) { }
    record HoldingState(int quantity, int settledQuantity, long averagePrice, long realizedProfitLoss) { }
    private record PendingSettlement(long id, long buyerId, long sellerId, long stockId, int quantity,
                                     long grossAmount, long sellerFee) { }
    private record CancelableSettlement(long id, long tradeId, long buyerId, long sellerId, long stockId,
                                        int quantity, long grossAmount, long buyerFee, long sellerFee,
                                        Instant createdAt, Integer buyerQuantityBefore,
                                        Integer buyerSettledQuantityBefore, Long buyerAveragePriceBefore,
                                        Long buyerRealizedProfitLossBefore, Integer sellerQuantityBefore,
                                        Integer sellerSettledQuantityBefore, Long sellerAveragePriceBefore,
                                        Long sellerRealizedProfitLossBefore) {
        private boolean hasSnapshot() {
            return buyerQuantityBefore != null && buyerSettledQuantityBefore != null
                    && buyerAveragePriceBefore != null && buyerRealizedProfitLossBefore != null
                    && sellerQuantityBefore != null && sellerSettledQuantityBefore != null
                    && sellerAveragePriceBefore != null && sellerRealizedProfitLossBefore != null;
        }
    }
    Stock findStock(String code) {
        List<Stock> rows = jdbc.query("""
                SELECT s.stock_code,g.name,g.genre,s.current_price,
                       COALESCE(p.day_reference, s.current_price) AS day_reference,
                       s.total_volume
                FROM stocks s
                JOIN games g ON g.id=s.game_id
                LEFT JOIN stock_protection_state p ON p.stock_id=s.id
                WHERE s.stock_code=?
                """,(rs,n)->new Stock(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                changePercent(rs.getLong(4),rs.getLong(5)),rs.getLong(6),null,rs.getLong(5)),code);
        if(rows.isEmpty())return null;
        // Looked up after the stock query has released its connection, as in stocks().
        Stock stock=rows.get(0);
        return new Stock(stock.code(),stock.name(),stock.genre(),stock.price(),stock.changePercent(),
                stock.volume(),protection.restriction(stock.code()),stock.referencePrice());
    }

    /** The JDBC timestamp already carries the normalized instant from the UTC DB session. */
    Instant databaseInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    long stockId(String code) {
        return jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code = ?", Long.class, code);
    }

    long dailyReferencePrice(String code) {
        // TradingProtectionService owns the active market date in Asia/Seoul.
        // Do not derive the execution band from MySQL CURRENT_DATE: the JDBC
        // session is deliberately UTC, so that value is still yesterday during
        // the first nine hours of a Korean trading day.
        List<Long> protectionReferences = jdbc.query("""
                SELECT p.day_reference
                FROM stock_protection_state p
                JOIN stocks s ON s.id = p.stock_id
                WHERE s.stock_code = ?
                """, (rs, row) -> rs.getLong(1), code);
        if (!protectionReferences.isEmpty()) return protectionReferences.get(0);

        LocalDate tradingDate = LocalDate.now(clock.withZone(MISSION_ZONE));
        List<Long> references = jdbc.query("""
                SELECT d.open_price
                FROM daily_market_summaries d
                JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ? AND d.trading_date = ?
                """, (rs, row) -> rs.getLong(1), code, tradingDate);
        if (!references.isEmpty()) return references.get(0);
        Long currentPrice = jdbc.queryForObject("SELECT current_price FROM stocks WHERE stock_code=?", Long.class, code);
        jdbc.update("""
                INSERT IGNORE INTO daily_market_summaries (stock_id,trading_date,open_price,close_price,total_volume)
                SELECT id,?,?,?,0 FROM stocks WHERE stock_code=?
                """, tradingDate, currentPrice, currentPrice, code);
        return jdbc.queryForObject("SELECT d.open_price FROM daily_market_summaries d JOIN stocks s ON s.id=d.stock_id WHERE s.stock_code=? AND d.trading_date=?",Long.class,code,tradingDate);
    }

    PriceBand dailyPriceBand(String code) {
        return dailyBand(code,dailyReferencePrice(code));
    }

    PriceBand botPriceBand(String code) {
        return botBand(code,dailyReferencePrice(code));
    }

    void moveToPrice(String code, long price, long volume) {
        PriceBand dailyBand = dailyPriceBand(code);
        long next = dailyBand.clamp(Math.max(PriceLimitPolicy.MIN_PRICE, price));
        jdbc.update("UPDATE stocks SET previous_price = current_price, current_price = ?, total_volume = total_volume + ? WHERE stock_code = ?", next, volume, code);
        jdbc.update("INSERT INTO stock_price_history (stock_id, price) SELECT id, ? FROM stocks WHERE stock_code = ?", next, code);
        recordDailyTrade(code, next, volume);
        refreshMarkPrice(code);
        // The scheduled market monitor evaluates the 60-second circuit-breaker condition.
        // A symbol fill must not take the market-wide state row's write lock.
    }

    private void recordDailyTrade(String code, long price, long volume) {
        LocalDate tradingDate = LocalDate.now(clock.withZone(MISSION_ZONE));
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT s.id, ?, p.day_reference, ?, ?
                FROM stocks s JOIN stock_protection_state p ON p.stock_id = s.id
                WHERE s.stock_code = ?
                ON DUPLICATE KEY UPDATE close_price = VALUES(close_price), total_volume = daily_market_summaries.total_volume + VALUES(total_volume)
                """, tradingDate, price, Math.max(0, volume), code);
    }

    @org.springframework.context.event.EventListener
    public void newsChanged(NewsChangedEvent event) { newsFeed.clearObservedBias(); }

    Portfolio portfolioUnsafe(long userId) {return accountQueries.portfolio(userId);}

    double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
