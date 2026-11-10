package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;
import java.time.Clock;
import java.util.Comparator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import static com.gamestock.backend.market.MarketModels.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

@Service
public class MarketService {
    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    static final ZoneId MISSION_ZONE = ZoneId.of("Asia/Seoul");
    static final List<String> MISSION_IDS = MarketRankingMissions.MISSION_IDS;
    /** GameStock charges a small, transparent 0.10% commission per side. */
    private static final double TRADING_FEE_RATE = 0.001;
    /** Per-headline news influence limits used to update fair value only. */
    private static final String LP_USERNAME = "liquidity_provider";
    private static final long LP_STARTING_CASH = 50_000_000L;
    private static final long LP_ONE_TIME_RESERVE = 50_000_000L;
    private static final int LP_RESERVE_VERSION = 1;
    private static final int LP_INITIAL_INVENTORY = 100;
    private static final String TRADER_BOT_PASSWORD = "TRADER";
    private static final long TRADER_BOT_STARTING_CASH = 1_000_000L;
    private static final String SCHEMA_MIGRATION_LOCK = "gamestock:schema-migration";
    private List<BotProfile> traderProfiles = List.of();
    private MarketRegimeEngine regimes;
    private PatternEngine patterns;
    private BotStrategyEngine strategies;
    private final MarketMakerEngine marketMaker = new MarketMakerEngine();
    private PriceMetricService priceMetrics;
    private final Map<String, MarketEnvironment> environments = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sectors = new HashMap<>();
    private final Map<String, Random> scheduleRandoms = new HashMap<>();
    private final Map<String, Integer> researchCursors = new HashMap<>();
    private BotOpportunitySelector opportunitySelector = new BotOpportunitySelector();
    private final BotOrderPolicy botOrderPolicy = new BotOrderPolicy();
    private static final int USER_ORDER_WINDOW_SECONDS = 10;
    private static final int USER_ORDER_LIMIT = 20;
    private static final int DUPLICATE_ORDER_WINDOW_SECONDS = 2;
    private static final String DEMO_USERNAME = "demo";

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
    Map<String,Long> bookCacheStats(){return Map.of("hits",botLanes.stream().mapToLong(l->l.cache.hits()).sum(),"misses",botLanes.stream().mapToLong(l->l.cache.misses()).sum());}
    private final List<BotLane> botLanes=new ArrayList<>();
    private long activityLpId;
    // Bot indicators look 5 s to 5 min back; refreshing them every 2 s keeps that resolution at a quarter of the queries.
    private static final long OBSERVATION_INTERVAL_MS=2_000;
    private final NewsValuation newsValuation;
    private final MarketRankingMissions rankingMissions;
    private final MarketNewsFeed newsFeed;
    private final MarketChartQueries chartQueries;
    private static final class BotLane {
        final Set<Long> stocks,users;
        final BotActivityEngine engine;
        final MarketBookCache cache=new MarketBookCache();
        final Map<Long,MarketMakerEngine.RiskBook> risks=new HashMap<>();
        final Map<Long,BotActivityEngine.Observation> observations=new HashMap<>();
        long lastActivity,lastObservation,epoch=-1;
        volatile Map<String,Long> timings=Map.of();
        BotLane(Set<Long> stocks,Set<Long> users,BotActivityEngine engine) {this.stocks=stocks;this.users=users;this.engine=engine;}
    }
    public int batchShardCount() {return botLanes.size();}
    Map<String,Long> batchTimings(int shard) {return botLanes.get(shard).timings;}
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
        this.userFeatures = userFeatures;
        this.protection = protection;
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
        researchCursors.clear();
        opportunitySelector = new BotOpportunitySelector();
        scheduleRandoms.clear();
        priceMetrics = new PriceMetricService(jdbc);
        if(!matchingEnabled()){
            botJournal=new BotLedgerJournal(jdbc,ledgerDetail);
            demoUserId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,DEMO_USERNAME);
            simulationInitialized=true;return;
        }
        migrateSchemaWithLock();

        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, DEMO_USERNAME, "demo", "Demo User", 1_000_000L);

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        retireLegacyStock("VOID");

        StockCatalog.ensureListings(jdbc);
        AdminSchema.restoreStockMetadata(jdbc);
        new StockDenominationMigration(jdbc).apply(StockCatalog.initialPrices(),this::settleDuePayments,clock.instant());
        new DerivativeBaskets(jdbc).rebalance();
        MarketBookCache.seedRevisions(jdbc);
        botJournal=new BotLedgerJournal(jdbc,ledgerDetail);
        ensureTraderBots();
        userFeatures.ensureDefaultTags();
        seedPriceHistory();
        seedDailySummaries();
        protection.repairPricesToDailyBands();
        normalizeOpenOrderPrices();
        initializeSimulationBooks();
        priceMetrics.seedCursors();
        startupTransaction().executeWithoutResult(status -> {
            acquireMarketLock();
            refreshEnvironments();
        });

        removeDefaultEvents();
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

    /**
     * Keep schema changes exclusive across accidental overlapping starts. The
     * lock is held on a dedicated JDBC connection while the migration uses its
     * normal pool connections, and is released even when a DDL step fails.
     */
    private void migrateSchemaWithLock() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            boolean locked = false;
            int timeout = Math.max(0, schemaMigrationLockTimeoutSeconds);
            log.info("Waiting for database schema migration lock (timeout={}s)", timeout);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, SCHEMA_MIGRATION_LOCK);
                statement.setInt(2, timeout);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new IllegalStateException("Could not acquire database schema migration lock within " + timeout + " seconds");
                    }
                }
            }
            locked = true;
            long started = System.nanoTime();
            try {
                new MarketSchema(jdbc).migrate(userFeatures, protection, priceMetrics, titles);
                log.info("Database schema migration completed in {} ms",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return null;
            } finally {
                if (locked) {
                    try (var release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        release.setString(1, SCHEMA_MIGRATION_LOCK);
                        release.execute();
                    } catch (SQLException releaseError) {
                        log.warn("Could not release database schema migration lock", releaseError);
                    }
                }
            }
        });
    }

    private void initializeActivityEngine() {
        if(targetPerSymbol<30||targetPerSymbol>1000)throw new IllegalArgumentException("Per-symbol target must be 30..1000 fills/second");
        Map<String,Long> ids=new HashMap<>();
        jdbc.query("SELECT username,id FROM users WHERE password_hash='TRADER'",rs->{ids.put(rs.getString(1),rs.getLong(2));});
        List<Long> stocks=jdbc.queryForList("SELECT id FROM stocks ORDER BY id",Long.class);
        Map<Long,Set<Long>> heldStocks=new HashMap<>();
        jdbc.query("SELECT p.user_id,p.stock_id FROM portfolios p JOIN users u ON u.id=p.user_id WHERE u.password_hash='TRADER' AND p.quantity>0",
                rs->{heldStocks.computeIfAbsent(rs.getLong(1),key->new HashSet<>()).add(rs.getLong(2));});
        List<BotActivityEngine.Participant> participants=BotParticipantAssignments.assign(traderProfiles,ids,stocks,heldStocks);
        activityLpId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,LP_USERNAME);
        botLanes.clear();
        int count=Math.min(4,stocks.size());
        for(int shard=0;shard<count;shard++) {
            Set<Long> symbols=new HashSet<>();for(int i=shard;i<stocks.size();i+=count)symbols.add(stocks.get(i));
            var group=participants.stream().filter(p->symbols.contains(p.stock())).toList();
            Set<Long> users=new HashSet<>();group.forEach(p->users.add(p.id()));users.add(activityLpId);
            botLanes.add(new BotLane(Set.copyOf(symbols),Set.copyOf(users),new BotActivityEngine(group,simulationSeed^(shard*71L),targetPerSymbol)));
        }
    }

    public boolean batchBotsEnabled() {return batchEnabled;}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<BotActivityEngine.Activity> participantBatch(int shard) {
        return participantBatch(shard,new java.util.concurrent.atomic.AtomicReference<>());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<BotActivityEngine.Activity> participantBatch(int shard,java.util.concurrent.atomic.AtomicReference<BotBatchPlan> retryPlan) {
        requireMatchingOwner();
        BotLane lane=botLanes.get(shard);
        synchronized(lane) {
        long now=clock.millis();
        if(adaptiveActivity) {
            long epoch=activityPolicy.claim("batch:"+shard,now,activeSimulation());
            if(epoch<0){lane.timings=Map.of();return List.of();}
            if(lane.epoch!=epoch || lane.lastActivity>0&&now-lane.lastActivity>idleAfterMillis) {
                lane.engine.resume();lane.lastActivity=0;lane.lastObservation=0;lane.epoch=epoch;
            }
        }
        long started=System.nanoTime();
        BatchMarketRepository repository=new BatchMarketRepository(jdbc,compactLedger?botJournal:null);
        BatchOrderBook book=bookCacheEnabled?lane.cache.borrow(repository,now,lane.stocks,lane.users):repository.load(now,lane.stocks,lane.users);
        if(adminRuntime!=null){
            var controls=adminRuntime.settings();if(controls.botsPaused()||controls.marketPaused())return List.of();
            lane.engine.traderShare(controls.traderShare());adminRuntime.apply(book);
        }
        if(inlineMarketMaker)for(var stock:book.stocks.values()) {
            book.restoreLiquidityInventory(activityLpId,stock.id,
                    StockMarketProfile.initialLiquidityInventory(stock.code,LP_INITIAL_INVENTORY),stock.last);
            if(!book.lpCashBudgets.containsKey(stock.id)) {
                long total=liquidityAllocatedCash(stock.id,activityLpId);
                long reserved=book.working(activityLpId,stock.id).stream().mapToLong(o->o.reservedCash).sum();
                book.lpCashBudgets.put(stock.id,total-reserved);
            }
        }
        if(inlineMarketMaker) {
            Map<Long,Long> minimumCash=new HashMap<>();
            for(var stock:book.stocks.values())
                minimumCash.put(stock.id,MarketMakerEngine.emergencyCashRequirement(botBand(stock.code,stock.reference).upperPrice()));
            long moved=book.rebalanceLiquidityCash(activityLpId,minimumCash);
            if(moved>0)pipelineMetrics.add("bot.liquidity.cashRebalanced",moved);
        }
        long loaded=System.nanoTime();
        if(lane.lastObservation==0 || now-lane.lastObservation>=OBSERVATION_INTERVAL_MS) {
            Map<Long,Double> fundamentals=new HashMap<>();
            jdbc.query("SELECT stock_id,hidden_fundamental FROM market_price_metrics WHERE stock_id IN ("
                    +book.stocks.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")",
                    rs->{fundamentals.put(rs.getLong(1),rs.getDouble(2));});
            for(var stock:book.stocks.values()) {
                priceMetrics.project(stock.id,now);
                Double fundamental=fundamentals.get(stock.id);
                if(fundamental==null)throw new org.springframework.dao.EmptyResultDataAccessException(1);
                var observed=priceMetrics.read(stock.id,stock.last,now,0);
                lane.observations.put(stock.id,new BotActivityEngine.Observation(observed,environment(stock.code),fundamental,newsFeed.observedNewsRate(stock.code,now),
                        StockMarketProfile.liquidityScale(stock.code,.65)));
                jdbc.update("UPDATE market_price_metrics SET mark_price=?,mark_updated_at=? WHERE stock_id=?",Math.round(observed.markPrice()),new Timestamp(now),stock.id);
            }
            lane.lastObservation=now;
            lane.risks.clear();
            jdbc.query("SELECT stock_id,target_inventory,max_inventory,risk_limit FROM lp_risk_books",rs->{lane.risks.put(rs.getLong(1),new MarketMakerEngine.RiskBook(0,0,rs.getInt(2),rs.getInt(3),rs.getLong(4)));});
        }
        Map<Long,Integer> recent=bookCacheEnabled?lane.cache.recent(now):repository.recentCounter(lane.stocks,now).recent(now);
        long observed=System.nanoTime();
        // Reload on a new decision so restart, ownership transfer and a failed transaction all
        // continue from the committed checkpoint. A retry reuses its already chosen plan/state.
        if (retryPlan.get() == null) {
            Map<Long,MarketConsensusValue.Checkpoint> saved = new HashMap<>();
            jdbc.query("SELECT stock_id,value,observed_at,random_state FROM market_consensus_state WHERE stock_id IN ("
                    +lane.stocks.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")",
                    rs -> { saved.put(rs.getLong(1),new MarketConsensusValue.Checkpoint(rs.getDouble(2),rs.getLong(3),rs.getLong(4))); });
            lane.engine.restoreConsensus(saved);
        }
        List<BotActivityEngine.Activity> activity;
        if(retryPlan.get()!=null)activity=matching.compute(()->retryPlan.get().apply(book,now));
        else{
            book.capture();
            activity=matching.compute(()->lane.engine.microBatches(book,lane.observations,recent,now,lane.lastActivity==0?50:now-lane.lastActivity,lane.risks,activityLpId,currentTargetPerSymbol()));
            retryPlan.set(book.captured());
        }
        int acceptedCount=book.accepted.size();
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){pipelineMetrics.add("bot.orders",acceptedCount);}
            });
        else pipelineMetrics.add("bot.orders",acceptedCount);
        long planned=System.nanoTime();
        repository.persist(book,now);
        repository.insert("market_consensus_state","stock_id,value,observed_at,random_state",
                lane.engine.consensusCheckpoint().entrySet().stream().map(e -> new Object[]{e.getKey(),e.getValue().value(),e.getValue().at(),e.getValue().randomState()}).toList(), false,
                " ON DUPLICATE KEY UPDATE value=VALUES(value),observed_at=VALUES(observed_at),random_state=VALUES(random_state)");
        long persisted=System.nanoTime();
        pipelineMetrics.timing("db.persist",persisted-planned);pipelineMetrics.gauge("db.batch.orders",book.accepted.size());
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        if(bookCacheEnabled)lane.cache.saved(now);
        publishBookEvents(book);
        lane.lastActivity=now;
        if(!book.accepted.isEmpty()||!book.changedOrders.isEmpty())events.publishEvent(bookEvent(book,now));
        Map<String,Long> timings=new HashMap<>(repository.timings);
        timings.putAll(Map.of("load",loaded-started,"observe",observed-loaded,"decide",planned-observed,"persist",persisted-planned));
        lane.timings=Map.copyOf(timings);
        return activity;
        }
    }

    private void ensureTraderBots() {
        for(int start=0;start<traderProfiles.size();start+=200) {
            var group=traderProfiles.subList(start,Math.min(start+200,traderProfiles.size()));
            List<Object> args=new ArrayList<>();
            for(BotProfile profile:group) {
                args.add(profile.username());args.add(TRADER_BOT_PASSWORD);args.add(profile.nickname());args.add(TRADER_BOT_STARTING_CASH);
            }
            // Existing funds are never reset. New participants receive finite initial capital once.
            jdbc.update("INSERT INTO users(username,password_hash,nickname,cash) VALUES "
                    +String.join(",",Collections.nCopies(group.size(),"(?,?,?,?)"))
                    +" ON DUPLICATE KEY UPDATE nickname=IF(password_hash='TRADER',VALUES(nickname),nickname)",args.toArray());
        }
    }

    private void renameExistingStock(String oldCode, String newCode, String oldName, String newName) {
        jdbc.update("UPDATE games SET name = ? WHERE name = ?", newName, oldName);
        jdbc.update("UPDATE stocks SET stock_code = ? WHERE stock_code = ?", newCode, oldCode);
    }

    private void retireLegacyStock(String code) {
        startupTransaction().executeWithoutResult(status -> {
            acquireMarketLock();
            var baskets = new DerivativeBaskets(jdbc);
            baskets.lock();
            // Keep holdings, news and financial history, including all foreign-key targets.
            // An explicit later admin decision must survive subsequent restarts.
            jdbc.update("""
                    INSERT IGNORE INTO admin_stock_state(stock_id,state,reason)
                    SELECT id,'DELISTED','구형 종목 운영 종료' FROM stocks WHERE stock_code=?
                    """, code);
            var orders = jdbc.queryForList("""
                    SELECT o.id,o.user_id,o.reserved_cash FROM orders o JOIN stocks s ON s.id=o.stock_id
                    JOIN admin_stock_state a ON a.stock_id=s.id
                    WHERE s.stock_code=? AND a.state<>'ACTIVE' AND o.status='OPEN'
                    ORDER BY o.user_id,o.id FOR UPDATE
                    """, code);
            for (var order : orders) {
                long refund = ((Number) order.get("reserved_cash")).longValue();
                if (refund > 0) jdbc.update("UPDATE users SET cash=cash+? WHERE id=?", refund, order.get("user_id"));
                jdbc.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE id=?", order.get("id"));
            }
            // Cancellation/refunds, retirement and value-preserving reconstitution commit together.
            baskets.rebalance();
        });
    }

    private void removeDefaultEvents() {
        jdbc.update("""
                DELETE FROM market_events
                WHERE title IN (?, ?, ?)
                """, "대규모 시즌 업데이트 적용", "경쟁작 출시 예고",
                "글로벌 누적 이용자 1,000만 달성");
    }

    private void seedPriceHistory() {
        jdbc.update("""
                INSERT INTO stock_price_history (stock_id, price)
                SELECT s.id, s.current_price FROM stocks s
                WHERE NOT EXISTS (
                    SELECT 1 FROM stock_price_history h WHERE h.stock_id = s.id
                )
                """);
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
    public List<ActiveOrder> activeOrders(long userId, String stockCode) {
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                       o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                       COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND o.status = 'OPEN'
                  AND (? IS NULL OR s.stock_code = ?)
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 5
                """, (rs, row) -> new ActiveOrder(
                rs.getLong("id"), rs.getString("stock_code"), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
                rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"),
                rs.getInt("reserved_quantity"), databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getTimestamp("expires_at") == null ? null : databaseInstant(rs.getTimestamp("expires_at")).toString()), userId, stockCode, stockCode);
    }

    /** The user's recently completed fills. Cash and shares settle immediately. */
    @Transactional(readOnly = true)
    public List<SettlementEntry> settlements(long userId) {
        return jdbc.query("""
                SELECT x.id, x.side, s.stock_code, x.quantity, x.gross_amount, x.fee,
                       x.net_amount, x.status, x.settlement_at, x.created_at, x.cancellable
                FROM (
                  SELECT st.id, st.stock_id, st.quantity, st.gross_amount,
                         CASE WHEN st.buyer_id = ? THEN 'BUY' ELSE 'SELL' END AS side,
                         CASE WHEN st.buyer_id = ? THEN st.buyer_fee ELSE st.seller_fee END AS fee,
                         CASE WHEN st.buyer_id = ? THEN st.gross_amount + st.buyer_fee
                              ELSE st.gross_amount - st.seller_fee END AS net_amount,
                         st.status, st.settlement_at, st.created_at,
                         CASE WHEN st.status = 'PENDING'
                                    AND st.buyer_quantity_before IS NOT NULL
                                    AND st.buyer_settled_quantity_before IS NOT NULL
                                    AND st.buyer_average_price_before IS NOT NULL
                                    AND st.buyer_realized_profit_loss_before IS NOT NULL
                                    AND st.seller_quantity_before IS NOT NULL
                                    AND st.seller_settled_quantity_before IS NOT NULL
                                    AND st.seller_average_price_before IS NOT NULL
                                    AND st.seller_realized_profit_loss_before IS NOT NULL
                              THEN TRUE ELSE FALSE END AS cancellable
                  FROM settlements st JOIN users owner ON owner.id = ?
                  WHERE (st.buyer_id = ? OR st.seller_id = ?) AND st.status = 'SETTLED'
                    AND st.created_at > COALESCE(owner.account_reset_at, '1970-01-01')
                ) x JOIN stocks s ON s.id = x.stock_id
                ORDER BY x.created_at DESC, x.id DESC
                LIMIT 5
                """, (rs, row) -> new SettlementEntry(
                rs.getLong("id"), rs.getString("side"), rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getLong("gross_amount"), rs.getLong("fee"),
                rs.getLong("net_amount"), rs.getString("status"),
                databaseInstant(rs.getTimestamp("settlement_at")).toString(),
                databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getBoolean("cancellable")),
                userId, userId, userId, userId, userId, userId);
    }

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
        rebuildHoldingFromLedger(settlement.stockId(), settlement.buyerId());
        if (settlement.sellerId() != settlement.buyerId())
            rebuildHoldingFromLedger(settlement.stockId(), settlement.sellerId());
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
    public List<OrderHistory> orderHistory(String code, long userId) {
        return jdbc.query("""
                SELECT o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at,
                       COALESCE(SUM(CASE WHEN o.side = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                LEFT JOIN trades t ON t.buy_order_id = o.id OR t.sell_order_id = o.id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE o.user_id = ? AND s.stock_code = ?
                  AND o.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                GROUP BY o.id, o.side, o.quantity, o.price, o.status, o.order_type, o.remaining_quantity, o.created_at
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 20
                """, (rs, row) -> new OrderHistory(
                rs.getString("side"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("status"),
                rs.getString("order_type"),
                rs.getInt("remaining_quantity"),
                databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                userId, code.toUpperCase(Locale.ROOT), userId);
    }

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
        var repository=new BatchMarketRepository(jdbc,compactLedger?botJournal:null);
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
            var execution=executionSummary(order.id,order.side);
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

    private MarketChangedEvent bookEvent(BatchOrderBook book,long now){
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

    private void publishBookEvents(BatchOrderBook book){
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
        long lp = ensureLiquidityProvider(code);
        // Measure live depth before withdrawing our quotes; replacement itself is not a liquidity crisis.
        PriceMetricService.Metrics metrics = metrics(code, 0);
        cancelBotOrders(lp, stockId(code));
        ensureLiquidityInventory(code, lp);
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

    BotObservation selectBotObservation(BotProfile profile,long userId,List<String> codes) {
        int cursor=researchCursors.getOrDefault(profile.username(),Math.floorMod(profile.username().hashCode()^(int)simulationSeed,codes.size()));
        int count=Math.min(3,codes.size());
        List<String> watched=new ArrayList<>();
        for(int i=0;i<count;i++) watched.add(codes.get((cursor+i)%codes.size()));
        Map<String,Long> quiet=new HashMap<>();
        jdbc.query("""
                SELECT s.stock_code,(SELECT MAX(t.created_at) FROM trades t WHERE t.stock_id=s.id
                  AND NOT EXISTS (SELECT 1 FROM settlements x WHERE x.trade_id=t.id AND x.status='CANCELLED')) last_trade
                FROM stocks s LEFT JOIN stock_protection_state p ON p.stock_id=s.id WHERE p.vi_type IS NULL
                ORDER BY s.id
                """,rs->{
            Timestamp last=rs.getTimestamp(2);
            quiet.put(rs.getString(1),Math.max(0,clock.millis()-profile.reactionLatency()
                    -(last==null?simulationStartedAt:last.getTime())));
        });
        long attentionAfter=30_000+Math.floorMod(profile.username().hashCode()^(int)simulationSeed,20_000);
        // Add one neglected symbol without replacing the normal research rotation or forcing an order.
        String neglected=codes.stream().filter(c->quiet.getOrDefault(c,0L)>attentionAfter)
                .max(Comparator.comparingLong(c->quiet.getOrDefault(c,0L))).orElse(null);
        if(neglected!=null && !watched.contains(neglected)) watched.add(neglected);
        boolean patient=profile.strategy().family()==BotProfile.Family.VALUE || profile.strategy().family()==BotProfile.Family.SWING;
        List<String> working=jdbc.queryForList("""
                SELECT s.stock_code FROM orders o JOIN stocks s ON s.id=o.stock_id
                WHERE o.user_id=? AND o.status='OPEN' AND o.created_at<=?
                  AND (o.expires_at IS NULL OR o.expires_at>CURRENT_TIMESTAMP(3))
                GROUP BY s.id ORDER BY MIN(o.created_at),s.id LIMIT 1
                """,String.class,userId,Timestamp.from(clock.instant().minusMillis(patient?15_000:2_000)));
        if(!working.isEmpty() && !watched.contains(working.get(0))) watched.add(working.get(0));
        if(profile.strategy().family()==BotProfile.Family.NEWS) {
            String catalyst=null;double strongest=0;
            for(String code:codes) {
                double attention=.6+new Random(simulationSeed^profile.username().hashCode()^((long)code.hashCode()<<32)).nextDouble()*.8;
                double impact=Math.abs(newsFeed.recentNewsBias(code,profile.reactionLatency()).rate())*attention;
                if(impact>strongest){strongest=impact;catalyst=code;}
            }
            // News interrupts the normal research rotation, preserving the profile's reaction latency.
            if(catalyst!=null && !watched.contains(catalyst)) watched.add(0,catalyst);
        }
        List<BotObservation> observations=new ArrayList<>();
        List<BotOpportunitySelector.Opportunity> opportunities=new ArrayList<>();
        long equity=jdbc.queryForObject("""
                SELECT cash
                  + COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=u.id AND status='OPEN'),0)
                  + COALESCE((SELECT SUM(p.quantity*pm.mark_price) FROM portfolios p
                      JOIN market_price_metrics pm ON pm.stock_id=p.stock_id WHERE p.user_id=u.id),0)
                FROM users u WHERE id=?
                """,Long.class,userId);
        for(String code:watched) {
            BotObservation candidate=observeBot(profile,userId,code,equity,codes.size());
            observations.add(candidate);
            double strength=Math.abs(candidate.score())/strategies.threshold(profile,candidate.metrics());
            // Keeping an existing quote is valid but must not consume the only actionable research slot.
            boolean actionable=candidate.resting().isEmpty()?candidate.decision().quantity()>0:candidate.replace();
            opportunities.add(new BotOpportunitySelector.Opportunity(code,strength,actionable,quiet.getOrDefault(code,0L)));
        }
        researchCursors.put(profile.username(),BotOpportunitySelector.nextCursor(cursor,count,codes.size()));
        String selected=opportunitySelector.select(profile.username(),opportunities);
        return observations.stream().filter(o->o.code().equals(selected)).findFirst().orElseThrow();
    }

    private BotObservation observeBot(BotProfile profile,long userId,String code,long equity,int symbolCount) {
        PriceMetricService.Metrics metrics=BotActivityEngine.stabilizeQuietBook(metrics(code,profile.reactionLatency()));
        long id=stockId(code);
        List<BotOrderPolicy.RestingOrder> resting=jdbc.query("SELECT id,side,price,reserved_cash,created_at FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'",
                (rs,n)->new BotOrderPolicy.RestingOrder(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).getTime()),userId,id);
        HoldingState held = holdingState(id, userId);
        Long age = jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MAX(created_at), CURRENT_TIMESTAMP)/1000,0) FROM trades WHERE stock_id=? AND buyer_id=?",
                Long.class, id, userId);
        BotStrategyEngine.Position position = new BotStrategyEngine.Position(held.quantity(), availableCash(userId)+resting.stream().mapToLong(BotOrderPolicy.RestingOrder::cash).sum(),
                held.averagePrice(), age == null ? 0 : age,
                strategies.targetQuantity(profile,code,clock.millis(),equity,symbolCount,metrics.markPrice()));
        double news = newsFeed.recentNewsBias(code, profile.reactionLatency()).rate();
        long reference = dailyReferencePrice(code);
        double fundamental = jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?", Double.class, id);
        fundamental=StockMarketProfile.valuationAnchor(code,reference,fundamental,news);
        double noise=.012*(StockMarketProfile.isLargeCap(code)?StockMarketProfile.of(code).movementWeight():1);
        double estimate = strategies.estimate(profile, code, fundamental * (1 + tickRandom.nextGaussian() * noise));
        double perceived = news == 0 ? 0 : news * (1 + profile.valueError()*8)
                + tickRandom.nextGaussian() * Math.abs(news) * .25;
        BotStrategyEngine.Decision decision=strategies.decide(profile,metrics,environment(code),position,
                estimate,perceived,tickSize(Math.round(metrics.lastPrice())),marketScale(code),code,reference);
        boolean replace=resting.size()>1 || resting.stream().anyMatch(o -> clock.millis()-o.createdAt()>=30_000 || botOrderPolicy.shouldReplace(profile,o,decision,
                clock.millis(),tickSize(o.price()),o.side().equals("BUY")?metrics.bestBid():metrics.bestAsk(),
                o.side().equals("BUY")?metrics.bestAsk():metrics.bestBid()));
        return new BotObservation(code,metrics,position,estimate,perceived,decision.score(),resting,decision,replace);
    }

    public synchronized long nextBotDelay(String username) {
        if(adminRuntime!=null){Integer configured=adminRuntime.settings().cadenceMs();if(configured!=null)return configured;}
        Random random = scheduleRandoms.computeIfAbsent(username, key -> new Random(simulationSeed ^ key.hashCode()));
        if (username.startsWith("liquidity:")) return 1000 + random.nextInt(2001);
        BotProfile p = traderProfiles.stream().filter(b -> b.username().equals(username)).findFirst().orElseThrow();
        return Math.max(500, (long)(p.decisionInterval() * (.65 + random.nextDouble()*.7)) + p.reactionLatency());
    }

    private PriceMetricService.Metrics metrics(String code,long latency) {
        return priceMetrics.read(stockId(code), findStock(code).price(), clock.millis(), latency);
    }

    private MarketEnvironment environment(String code) {
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

    /**
     * Seed a new LP lot and top up a nearly depleted lot within the finite supply bound.
     *
     * A few remaining shares must not block an otherwise eligible refill.
     */
    private void ensureLiquidityInventory(String code, long userId) {
        long id = stockId(code);
        int initialInventory = StockMarketProfile.initialLiquidityInventory(code,LP_INITIAL_INVENTORY);
        List<HoldingState> holdings = jdbc.query("""
                SELECT quantity, settled_quantity, average_price, realized_profit_loss
                FROM portfolios WHERE user_id = ? AND stock_id = ? FOR UPDATE
                """, (rs, row) -> new HoldingState(
                rs.getInt("quantity"),
                rs.getInt("settled_quantity"),
                rs.getLong("average_price"),
                rs.getLong("realized_profit_loss")), userId, id);
        if (holdings.isEmpty()) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """, userId, id, initialInventory, initialInventory, findStock(code).price());
            recordLiquidityIssuance(id, initialInventory);
        } else if (holdings.get(0).quantity() <= 4) {
            // The startup/legacy path obeys the same finite supply bound as inline quotes.
            long circulating=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM portfolios WHERE stock_id=?",Long.class,id);
            long nearbyAsks=jdbc.queryForObject("SELECT COALESCE(SUM(remaining_quantity),0) FROM orders WHERE stock_id=? AND user_id<>? AND side='SELL' AND status='OPEN' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AND price<=?",Long.class,id,userId,findStock(code).price()*1.02);
            int refill=(int)Math.min(32,Math.max(0,Math.max(512L,initialInventory*4L)-circulating));
            if(refill==0 || nearbyAsks>=4)return;
            HoldingState current=holdings.get(0);
            int nextQuantity=current.quantity()+refill;
            long average=Math.round((current.averagePrice()*(double)current.quantity()
                    +findStock(code).price()*(double)refill)/nextQuantity);
            jdbc.update("""
                    UPDATE portfolios
                    SET quantity = ?, settled_quantity = ?, average_price = ?
                    WHERE user_id = ? AND stock_id = ?
                    """, nextQuantity, current.settledQuantity()+refill, average, userId, id);
            recordLiquidityIssuance(id, refill);
        }
    }

    private void recordLiquidityIssuance(long stockId, int quantity) {
        jdbc.update("INSERT INTO market_inventory_issuance(stock_id,quantity) VALUES(?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity)", stockId, quantity);
    }

    private long traderBotId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND password_hash = ?",
                Long.class, username, TRADER_BOT_PASSWORD);
    }

    private long ensureLiquidityProvider(String code) {
        jdbc.update("INSERT IGNORE INTO users (username, password_hash, nickname, cash) VALUES (?, 'BOT', ?, ?)",
                LP_USERNAME, "유동성 공급자", LP_STARTING_CASH);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, LP_USERNAME);
    }

    /** PostConstruct bypasses the transaction proxy, so startup writes that must be atomic use this. */
    private org.springframework.transaction.support.TransactionTemplate startupTransaction() {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private void initializeSimulationBooks() {
        // Startup cancellation and finite allocations must commit together.
        startupTransaction().executeWithoutResult(status->{
            acquireMarketLock();
            long lp = ensureLiquidityProvider("");
            cancelBotOrders(lp, null);
            List<String> codes = botStockCodes();
            long baseline = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades", Long.class);
            int existing = jdbc.queryForObject("SELECT COUNT(*) FROM lp_risk_books", Integer.class);
            long allocation = existing == 0 ? availableCash(lp) / Math.max(1,codes.size()) : 0;
            for (String code : codes) {
                long id = stockId(code);
                Stock stock = findStock(code);
                ensureLiquidityInventory(code,lp);
                // Non-zero allocations and trade baselines are retained across restarts.
                jdbc.update("INSERT IGNORE INTO lp_risk_books VALUES (?,?,?,?,?,?)",
                        id,allocation,baseline,batchEnabled?100:400,1200,stock.price()*1200L);
                int target=StockMarketProfile.initialLiquidityInventory(code,LP_INITIAL_INVENTORY);
                // A profile migration may increase the budget; an ordinary
                // restart must not inflate it just because the last price rose.
                jdbc.update("UPDATE lp_risk_books SET target_inventory=?,max_inventory=GREATEST(max_inventory,?),risk_limit=GREATEST(risk_limit,?) WHERE stock_id=? AND (target_inventory<>? OR max_inventory<?)",
                        target,target*4,stock.price()*target*4L,id,target,target*4);
                jdbc.update("INSERT IGNORE INTO market_price_metrics (stock_id,mark_price,hidden_fundamental,anchor_price) VALUES (?,?,?,?)",
                        id,stock.price(),stock.price(),stock.price());
                sectors.put(code,stock.genre());
            }
            ensureLiquidityCashReserve(lp,codes);
        });
    }

    /** Apply one finite simulation-only LP reserve and keep its per-symbol projection consistent. */
    private void ensureLiquidityCashReserve(long lp,List<String> codes) {
        jdbc.update("INSERT IGNORE INTO market_liquidity_state(id,reserve_version) VALUES (1,0)");
        if(jdbc.update("UPDATE market_liquidity_state SET reserve_version=? WHERE id=1 AND reserve_version=0",LP_RESERVE_VERSION)!=1)return;
        jdbc.update("UPDATE users SET cash=cash+? WHERE id=?",LP_ONE_TIME_RESERVE,lp);
        long share=LP_ONE_TIME_RESERVE/Math.max(1,codes.size()),remainder=LP_ONE_TIME_RESERVE%Math.max(1,codes.size());
        for(int i=0;i<codes.size();i++) {
            long delta=share+(i<remainder?1:0),id=stockId(codes.get(i));
            jdbc.update("UPDATE lp_risk_books SET opening_cash=opening_cash+? WHERE stock_id=?",delta,id);
            jdbc.update("UPDATE lp_cash_projection SET cash=cash+? WHERE stock_id=?",delta,id);
        }
    }

    private MarketMakerEngine.RiskBook liquidityRiskBook(String code,long lp) {
        long id = stockId(code);
        refreshLiquidityProjection(id,lp);
        return jdbc.queryForObject("""
                SELECT c.cash + COALESCE((SELECT lp_cash_delta FROM bot_ledger_totals b WHERE b.stock_id=r.stock_id),0) - COALESCE((SELECT SUM(o.reserved_cash) FROM orders o
                    WHERE o.user_id=? AND o.stock_id=r.stock_id AND o.status='OPEN'),0) AS budget,
                    r.target_inventory,r.max_inventory,r.risk_limit
                FROM lp_risk_books r JOIN lp_cash_projection c ON c.stock_id=r.stock_id WHERE r.stock_id=?
                """, (rs,n)->new MarketMakerEngine.RiskBook(Math.max(0,Math.min(availableCash(lp),rs.getLong(1))),
                        holdingState(id,lp).quantity(),rs.getInt(2),rs.getInt(3),rs.getLong(4)),lp,id);
    }

    private long liquidityAllocatedCash(long id,long lp) {
        refreshLiquidityProjection(id,lp);
        return jdbc.queryForObject("SELECT c.cash+COALESCE(b.lp_cash_delta,0) FROM lp_cash_projection c LEFT JOIN bot_ledger_totals b ON b.stock_id=c.stock_id WHERE c.stock_id=?",Long.class,id);
    }

    private void refreshLiquidityProjection(long id,long lp) {
        jdbc.update("INSERT IGNORE INTO lp_cash_projection SELECT stock_id,baseline_trade_id,opening_cash FROM lp_risk_books WHERE stock_id=?",id);
        long cursor=jdbc.queryForObject("SELECT last_id FROM lp_cash_projection WHERE stock_id=?",Long.class,id);
        long end=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades WHERE stock_id=?",Long.class,id);
        if(end>cursor) {
            long delta=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE WHEN t.seller_id=? THEN t.quantity*t.price-t.seller_fee ELSE 0 END
                        - CASE WHEN t.buyer_id=? THEN t.quantity*t.price+t.buyer_fee ELSE 0 END),0)
                    FROM trades t WHERE t.stock_id=? AND t.id>? AND t.id<=?
                      AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                    """,Long.class,lp,lp,id,cursor,end);
            jdbc.update("UPDATE lp_cash_projection SET cash=cash+?,last_id=? WHERE stock_id=?",delta,end,id);
        }
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
    private double marketScale(String code) {
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
        if (dayChanged || !repaired.isEmpty()) normalizeOpenOrderPrices();
        protection.advanceMarketPhase();
        if (protection.marketAuctionDue()) {
            List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
            for (String code : codes) {
                closeSinglePriceAuction(code);
                protection.finishAuction(code, findStock(code).price());
            }
            protection.finishMarketAuction();
        }
        for (String code : protection.dueViAuctions()) {
            if (protection.marketStatus().restriction() != null) break;
            closeSinglePriceAuction(code);
            protection.finishAuction(code, findStock(code).price());
        }
        protection.observeMarket();
    }

    /** Maximise executable volume, then minimise imbalance and distance from the quote. */
    private void closeSinglePriceAuction(String code) {
        if(adminRuntime!=null&&adminRuntime.restriction(code)!=null)return;
        long stockId = stockId(code);
        long reference = findStock(code).price();
        PriceBand daily = dailyPriceBand(code);
        PriceBand bots = botPriceBand(code);
        List<MatchRow> orders = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.status = 'OPEN' AND o.order_type = 'LIMIT'
                  AND o.remaining_quantity > 0 ORDER BY o.created_at, o.id
                """, this::auctionRow, stockId);
        java.util.SortedSet<Long> candidates = new java.util.TreeSet<>();
        candidates.add(floorToTick(reference));
        for (MatchRow order : orders) {
            if (daily.contains(order.price()) && order.price() % tickSize(order.price()) == 0) candidates.add(order.price());
        }
        long price = reference;
        long bestVolume = 0;
        long bestImbalance = Long.MAX_VALUE;
        for (long candidate : candidates) {
            if (!daily.contains(candidate)) continue;
            long buys = 0;
            long sells = 0;
            for (MatchRow order : orders) {
                if (order.bot() && !bots.contains(candidate)) continue;
                if ("BUY".equals(order.side()) && order.price() >= candidate) buys += order.remainingQuantity();
                if ("SELL".equals(order.side()) && order.price() <= candidate) sells += order.remainingQuantity();
            }
            long volume = Math.min(buys, sells);
            long imbalance = Math.abs(buys - sells);
            if (volume > bestVolume || (volume == bestVolume && volume > 0 &&
                    (imbalance < bestImbalance || (imbalance == bestImbalance
                            && Math.abs(candidate - reference) < Math.abs(price - reference))))) {
                price = candidate;
                bestVolume = volume;
                bestImbalance = imbalance;
            }
        }
        if (bestVolume == 0) return;
        long volume = 0;
        while (true) {
            MatchRow buy = topAuctionOrder(stockId, "BUY", price, bots.contains(price));
            MatchRow sell = topAuctionOrder(stockId, "SELL", price, bots.contains(price));
            if (buy == null || sell == null) break;
            if (buy.userId() == sell.userId()) {
                MatchRow alternativeSell = topAuctionOrder(stockId, "SELL", price, bots.contains(price), buy.userId());
                if (alternativeSell == null) break;
                sell = alternativeSell;
            }
            int quantity = Math.min(buy.remainingQuantity(), sell.remainingQuantity());
            if (!canSettle(buy, sell, quantity, price, stockId)) {
                if (!canPayForFill(buy,quantity,price)) cancelOrderInternal(buy.id());
                else cancelOrderInternal(sell.id());
                continue;
            }
            MatchRow maker = earlier(buy, sell) ? buy : sell;
            MatchRow taker = maker.id() == buy.id() ? sell : buy;
            settleTrade(stockId, buy, sell, maker, taker, quantity, price);
            volume += quantity;
        }
        if (volume > 0) moveToPrice(code, price, volume);
    }

    private MatchRow topAuctionOrder(long stockId, String side, long price, boolean botsAllowed) {
        return topAuctionOrder(stockId, side, price, botsAllowed, 0L);
    }

    private MatchRow topAuctionOrder(long stockId, String side, long price, boolean botsAllowed,
                                     long excludedUserId) {
        String comparison = "BUY".equals(side) ? ">=" : "<=";
        String priority = "BUY".equals(side) ? "DESC" : "ASC";
        String excluded = excludedUserId > 0 ? " AND o.user_id <> ? " : "";
        List<MatchRow> rows = jdbc.query("""
                SELECT o.id, o.user_id, o.price, o.quantity, o.remaining_quantity, o.order_type,
                       o.created_at, o.reserved_cash, o.reserved_quantity, o.side,
                       (u.password_hash = 'BOT') AS is_bot
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN'
                  AND o.order_type = 'LIMIT' AND o.remaining_quantity > 0 AND o.price
                """ + comparison + " ? " + (botsAllowed ? "" : "AND u.password_hash <> 'BOT' ")
                + excluded
                + "ORDER BY o.price " + priority + ", o.created_at, o.id LIMIT 1",
                this::auctionRow, excludedUserId > 0
                        ? new Object[]{stockId, side, price, excludedUserId}
                        : new Object[]{stockId, side, price});
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MatchRow auctionRow(ResultSet rs, int row) throws SQLException {
        return new MatchRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10), rs.getBoolean(11));
    }

    private void matchOrders(String code) {
        if(!protection.continuous(code))return;
        long now=clock.millis(),id=stockId(code);
        var repository=new BatchMarketRepository(jdbc,compactLedger?botJournal:null);
        var book=repository.load(now,Set.of(id),Set.of());
        matching.compute(()->{book.matchExisting(id);return null;});
        repository.persist(book,now);
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        publishBookEvents(book);events.publishEvent(bookEvent(book,now));
    }

    private boolean canSettle(MatchRow buy, MatchRow sell, int quantity, long tradePrice, long stockId) {
        if (!canPayForFill(buy,quantity,tradePrice)) return false;
        return sell.reservedQuantity() > 0 || availableQuantity(sell.userId(), stockId) >= quantity;
    }

    private long releasedReservation(MatchRow buy,int quantity) {
        if (buy.reservedCash()==0) return 0;
        long remainingGross=buy.price()*(buy.remainingQuantity()-(long)quantity);
        return buy.reservedCash()-remainingGross-feeFor(remainingGross);
    }

    private boolean canPayForFill(MatchRow buy,int quantity,long price) {
        long gross=price*quantity;
        return availableCash(buy.userId())+releasedReservation(buy,quantity)>=gross+feeFor(gross);
    }

    private void settleTrade(long stockId, MatchRow buy, MatchRow sell, MatchRow maker, MatchRow taker, int quantity, long tradePrice) {
        long amount = tradePrice * quantity;
        boolean internal=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (?,?) AND password_hash IN ('BOT','TRADER')",Integer.class,buy.userId(),sell.userId())==2;
        long buyerFee = internal?0:feeFor(amount);
        long sellerFee = buyerFee;
        HoldingState buyerBefore = holdingState(stockId, buy.userId());
        HoldingState sellerBefore = holdingState(stockId, sell.userId());
        if (buy.reservedCash() > 0) {
            // Use the actual change in the remaining reservation: rounded per-fill fees are not additive.
            long reservedChunk = releasedReservation(buy,quantity);
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reservedChunk - amount - buyerFee, buy.userId());
        } else {
            jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", amount + buyerFee, buy.userId());
        }
        adjustQuantity(stockId, buy.userId(), quantity, tradePrice, buyerFee, true, buyerBefore);
        adjustQuantity(stockId, sell.userId(), quantity, tradePrice, sellerFee, false, sellerBefore);
        // GameStock settles fills immediately; the seller receives cash and
        // the buyer's shares become settled in the same transaction.
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", amount - sellerFee, sell.userId());
        jdbc.update("""
                INSERT INTO trades (stock_id, buy_order_id, sell_order_id, buyer_id, seller_id,
                                    maker_order_id, taker_order_id, aggressor_side, quantity, price,
                                    buyer_fee, seller_fee, fee)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, buy.id(), sell.id(), buy.userId(), sell.userId(), maker.id(), taker.id(),
                taker.side(), quantity, tradePrice, buyerFee, sellerFee, buyerFee + sellerFee);
        long tradeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO settlements (trade_id, buyer_id, seller_id, stock_id, quantity, gross_amount,
                                         buyer_fee, seller_fee, settlement_at, status,
                                         buyer_quantity_before, buyer_settled_quantity_before,
                                         buyer_average_price_before, buyer_realized_profit_loss_before,
                                         seller_quantity_before, seller_settled_quantity_before,
                                         seller_average_price_before, seller_realized_profit_loss_before, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'SETTLED',
                        ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tradeId, buy.userId(), sell.userId(), stockId, quantity, amount, buyerFee, sellerFee,
                buyerBefore.quantity(), buyerBefore.settledQuantity(), buyerBefore.averagePrice(),
                buyerBefore.realizedProfitLoss(), sellerBefore.quantity(), sellerBefore.settledQuantity(),
                sellerBefore.averagePrice(), sellerBefore.realizedProfitLoss());
        updateMatchedOrder(buy, quantity, tradePrice);
        updateMatchedOrder(sell, quantity, tradePrice);
        protection.recordTrade(stockId, tradePrice);
    }

    private void updateMatchedOrder(MatchRow order, int filledQuantity, long tradePrice) {
        int remaining = order.remainingQuantity() - filledQuantity;
        int filledBefore = order.quantity() - order.remainingQuantity();
        long nextPrice = "MARKET".equals(order.orderType())
                ? weightedAverage(order.price(), filledBefore, tradePrice, filledQuantity)
                : order.price();
        long reservedCash = order.reservedCash();
        if (reservedCash > 0 && "BUY".equals(order.side())) {
            // Recompute the reservation from the remaining quantity so fee
            // rounding does not accumulate a one-won drift over many fills.
            long remainingGross = order.price() * (long) remaining;
            reservedCash = remaining == 0 ? 0 : remainingGross + feeFor(remainingGross);
        }
        int reservedQuantity = order.reservedQuantity();
        if (reservedQuantity > 0 && "SELL".equals(order.side())) reservedQuantity = Math.max(0, reservedQuantity - filledQuantity);
        jdbc.update("UPDATE orders SET price = ?, remaining_quantity = ?, reserved_cash = ?, reserved_quantity = ?, status = ? WHERE id = ?",
                nextPrice, remaining, reservedCash, reservedQuantity, remaining == 0 ? "FILLED" : "OPEN", order.id());
    }

    private long weightedAverage(long previousAverage, int filledBefore, long tradePrice, int filledQuantity) {
        int filled = filledBefore + filledQuantity;
        return filled == 0 ? 0 : Math.round(((double) previousAverage * filledBefore + (double) tradePrice * filledQuantity) / filled);
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
    private void acquireMarketLock() {
        jdbc.queryForObject("SELECT id FROM market_locks WHERE id = 1 FOR UPDATE", Integer.class);
        MarketBookCache.invalidate(jdbc,null);
    }

    /** 기존 호가도 새 호가 단위 규칙을 따르도록 보정한다. 예약 현금 차액은 함께 정산한다. */
    private void normalizeOpenOrderPrices() {
        List<OpenLimitOrder> orders = jdbc.query("""
                SELECT o.id, o.user_id, s.stock_code, o.side, o.price, o.remaining_quantity,
                       COALESCE(o.reserved_cash, 0), (u.password_hash = 'BOT') AS is_bot
                FROM orders o
                JOIN stocks s ON s.id = o.stock_id
                JOIN users u ON u.id = o.user_id
                WHERE o.status = 'OPEN' AND o.order_type = 'LIMIT' AND o.price IS NOT NULL
                """, (rs, row) -> new OpenLimitOrder(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getBoolean(8)));
        for (OpenLimitOrder order : orders) {
            PriceBand allowedBand = order.bot() ? botPriceBand(order.stockCode()) : dailyPriceBand(order.stockCode());
            if (!allowedBand.contains(order.price())) {
                startupTransaction().executeWithoutResult(status -> cancelOutOfBandOrder(order));
                continue;
            }
            long rounded = "BUY".equals(order.side()) ? floorToTick(order.price()) : ceilToTick(order.price());
            long normalized = allowedBand.clamp(rounded);
            Long required = null;
            if ("BUY".equals(order.side())) {
                try {
                    long normalizedGross = Math.multiplyExact(normalized, order.remainingQuantity());
                    required = normalizedGross + feeFor(normalizedGross);
                } catch (ArithmeticException error) {
                    // required stays null: the order is cancelled and refunded below.
                }
                if (required != null && normalized == order.price() && order.reservedCash() == required) continue;
            } else if (normalized == order.price()) continue;
            // A cash adjustment and its order update must commit together, or a restart
            // would apply the same difference again. Unchanged orders skip the transaction.
            Long reserved = required;
            startupTransaction().executeWithoutResult(status -> applyNormalizedPrice(order, normalized, reserved));
        }
    }

    private void cancelOutOfBandOrder(OpenLimitOrder order) {
        int cancelled = jdbc.update("""
                UPDATE orders
                SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0
                WHERE id = ? AND status = 'OPEN'
                """, order.id());
        if (cancelled > 0 && order.reservedCash() > 0) {
            jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
        }
    }

    /** {@code required} is the new buy reservation, or null when it overflowed. */
    private void applyNormalizedPrice(OpenLimitOrder order, long normalized, Long required) {
        if (!"BUY".equals(order.side())) {
            jdbc.update("UPDATE orders SET price = ? WHERE id = ?", normalized, order.id());
            return;
        }
        if (required == null) {
            if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
            jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
            return;
        }
        long difference = order.reservedCash() - required;
        if (difference < 0 && jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", -difference, order.userId(), -difference) == 0) {
            if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
            jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
            return;
        }
        if (difference > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", difference, order.userId());
        jdbc.update("UPDATE orders SET price = ?, reserved_cash = ? WHERE id = ?", normalized, required, order.id());
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

    private void cancelOrderInternal(long orderId) {
        OrderState order = findOrder(orderId);
        if (!"OPEN".equals(order.status())) return;
        if ("MARKET".equals(order.orderType())) { cancelRemainingMarket(orderId); return; }
        if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
        jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", orderId);
    }

    private long availableCash(long userId) {
        return jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
    }

    private int availableQuantity(long userId, long stockId) {
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

    private boolean earlier(MatchRow first, MatchRow second) {
        int compared = first.createdAt().compareTo(second.createdAt());
        return compared < 0 || (compared == 0 && first.id() < second.id());
    }

    private void adjustQuantity(long stockId, long userId, int amount, long price, long fee, boolean buy, HoldingState state) {
        int next = buy ? state.quantity() + amount : state.quantity() - amount;
        if (next < 0) throw new IllegalArgumentException("보유 수량이 부족합니다.");
        long gross = price * (long) amount;
        long nextAverage = buy && next > 0
                ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / next)
                : (next == 0 ? 0 : state.averagePrice());
        int nextSettled = buy ? Math.min(next, state.settledQuantity() + amount)
                : Math.max(0, state.settledQuantity() - amount);
        long nextRealized = buy ? state.realizedProfitLoss()
                : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) amount;
        jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE quantity=VALUES(quantity), settled_quantity=VALUES(settled_quantity),
                        average_price=VALUES(average_price), realized_profit_loss=VALUES(realized_profit_loss)
                    """, userId, stockId, next, nextSettled, nextAverage, nextRealized);
    }

    private HoldingState holdingState(long stockId, long userId) {
        return jdbc.query("""
                SELECT quantity, COALESCE(settled_quantity, quantity), average_price,
                       COALESCE(realized_profit_loss, 0)
                FROM portfolios WHERE user_id = ? AND stock_id = ?
                """, (rs, row) -> new HoldingState(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)), userId, stockId)
                .stream().findFirst().orElse(new HoldingState(0, 0, 0, 0));
    }

    private void restoreHolding(long stockId, long userId, int quantity, int settledQuantity,
                                long averagePrice, long realizedProfitLoss) {
        int updated = jdbc.update("""
                UPDATE portfolios SET quantity = ?, settled_quantity = ?, average_price = ?, realized_profit_loss = ?
                WHERE user_id = ? AND stock_id = ?
                """, quantity, settledQuantity, averagePrice, realizedProfitLoss, userId, stockId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO portfolios (user_id, stock_id, quantity, settled_quantity, average_price, realized_profit_loss)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, stockId, quantity, settledQuantity, averagePrice, realizedProfitLoss);
        }
    }

    private void rebuildHoldingFromLedger(long stockId, long userId) {
        List<HoldingSnapshot> snapshots = jdbc.query("""
                SELECT settlements.trade_id, settlements.buyer_id, buyer_quantity_before, buyer_settled_quantity_before,
                       buyer_average_price_before, buyer_realized_profit_loss_before,
                       seller_quantity_before, seller_settled_quantity_before,
                       seller_average_price_before, seller_realized_profit_loss_before
                FROM settlements
                JOIN trades t0 ON t0.id = settlements.trade_id
                WHERE settlements.stock_id = ? AND (settlements.buyer_id = ? OR settlements.seller_id = ?)
                  AND t0.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND buyer_quantity_before IS NOT NULL
                  AND buyer_settled_quantity_before IS NOT NULL
                  AND buyer_average_price_before IS NOT NULL
                  AND buyer_realized_profit_loss_before IS NOT NULL
                  AND seller_quantity_before IS NOT NULL
                  AND seller_settled_quantity_before IS NOT NULL
                  AND seller_average_price_before IS NOT NULL
                  AND seller_realized_profit_loss_before IS NOT NULL
                ORDER BY trade_id ASC
                LIMIT 1
                """, (rs, row) -> {
            long tradeId = rs.getLong("trade_id");
            boolean buyer = rs.getLong("buyer_id") == userId;
            HoldingState state = buyer
                    ? new HoldingState(rs.getInt("buyer_quantity_before"), rs.getInt("buyer_settled_quantity_before"),
                    rs.getLong("buyer_average_price_before"), rs.getLong("buyer_realized_profit_loss_before"))
                    : new HoldingState(rs.getInt("seller_quantity_before"), rs.getInt("seller_settled_quantity_before"),
                    rs.getLong("seller_average_price_before"), rs.getLong("seller_realized_profit_loss_before"));
            return new HoldingSnapshot(tradeId, state);
        }, stockId, userId, userId, userId);
        if (snapshots.isEmpty()) throw new IllegalArgumentException("거래 원장을 복구할 기준을 찾을 수 없습니다.");
        HoldingState state = snapshots.get(0).state();
        List<LedgerTrade> trades = jdbc.query("""
                SELECT t.id, t.buyer_id, t.seller_id, t.quantity, t.price,
                       t.buyer_fee, t.seller_fee, st.status
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.stock_id = ? AND (t.buyer_id = ? OR t.seller_id = ?)
                  AND t.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id = ?), '1970-01-01')
                  AND t.id >= ? AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.id ASC
                """, (rs, row) -> new LedgerTrade(rs.getLong("id"), rs.getLong("buyer_id"),
                rs.getLong("seller_id"), rs.getInt("quantity"), rs.getLong("price"),
                rs.getLong("buyer_fee"), rs.getLong("seller_fee"), rs.getString("status")),
                stockId, userId, userId, userId, snapshots.get(0).tradeId());
        for (LedgerTrade trade : trades) {
            boolean buy = trade.buyerId() == userId;
            long fee = buy ? trade.buyerFee() : trade.sellerFee();
            long gross = trade.price() * (long) trade.quantity();
            int nextQuantity = buy ? state.quantity() + trade.quantity() : state.quantity() - trade.quantity();
            if (nextQuantity < 0) throw new IllegalArgumentException("거래 원장을 복구할 수 없습니다.");
            long nextAverage = buy && nextQuantity > 0
                    ? Math.round(((double) state.averagePrice() * state.quantity() + gross + fee) / nextQuantity)
                    : (nextQuantity == 0 ? 0 : state.averagePrice());
            int nextSettled = buy ? state.settledQuantity()
                    : Math.max(0, state.settledQuantity() - trade.quantity());
            long nextRealized = buy ? state.realizedProfitLoss()
                    : state.realizedProfitLoss() + gross - fee - state.averagePrice() * (long) trade.quantity();
            if (buy && (trade.settlementStatus() == null || "SETTLED".equals(trade.settlementStatus())))
                nextSettled = Math.min(nextQuantity, nextSettled + trade.quantity());
            state = new HoldingState(nextQuantity, nextSettled, nextAverage, nextRealized);
        }
        restoreHolding(stockId, userId, state.quantity(), state.settledQuantity(),
                state.averagePrice(), state.realizedProfitLoss());
    }

    private ExecutionSummary executionSummary(long orderId, String side) {
        List<ExecutionSummary> rows = jdbc.query("""
                SELECT COALESCE(SUM(CASE WHEN ? = 'BUY' THEN t.buyer_fee ELSE t.seller_fee END), 0) AS fee,
                       CASE WHEN COUNT(t.id) = 0 THEN 'NONE'
                            WHEN SUM(CASE WHEN st.status = 'CANCELLED' THEN 1 ELSE 0 END) > 0 THEN 'CANCELLED'
                            WHEN SUM(CASE WHEN st.status = 'PENDING' THEN 1 ELSE 0 END) > 0 THEN 'PENDING'
                            ELSE 'SETTLED' END AS settlement_status,
                       MIN(CASE WHEN st.status = 'PENDING' THEN st.settlement_at ELSE NULL END) AS settlement_at
                FROM trades t
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE t.buy_order_id = ? OR t.sell_order_id = ?
                """, (rs, row) -> new ExecutionSummary(rs.getLong("fee"), rs.getString("settlement_status"),
                rs.getTimestamp("settlement_at") == null ? null : databaseInstant(rs.getTimestamp("settlement_at")).toString()),
                side, orderId, orderId);
        return rows.isEmpty() ? new ExecutionSummary(0, "NONE", null) : rows.get(0);
    }

    private long feeFor(long grossAmount) {
        if (grossAmount <= 0) return 0;
        return Math.max(1L, Math.round(grossAmount * TRADING_FEE_RATE));
    }

    private record MatchRow(long id, long userId, long price, int quantity, int remainingQuantity, String orderType,
                            Instant createdAt, long reservedCash, int reservedQuantity, String side, boolean bot) { }
    private record OrderState(long id, long userId, long price, int quantity, int remainingQuantity, String status,
                              String orderType, long reservedCash, int reservedQuantity) { }
    private record OrderReservation(long id, long userId, long reservedCash) { }
    private record OpenLimitOrder(long id, long userId, String stockCode, String side, long price,
                                  int remainingQuantity, long reservedCash, boolean bot) { }
    private record HoldingState(int quantity, int settledQuantity, long averagePrice, long realizedProfitLoss) { }
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
    private record HoldingSnapshot(long tradeId, HoldingState state) { }
    private record LedgerTrade(long id, long buyerId, long sellerId, int quantity, long price,
                               long buyerFee, long sellerFee, String settlementStatus) { }
    private record ExecutionSummary(long fee, String status, String settlementAt) { }
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

    private long dailyReferencePrice(String code) {
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

    private PriceBand dailyPriceBand(String code) {
        return dailyBand(code,dailyReferencePrice(code));
    }

    private PriceBand botPriceBand(String code) {
        return botBand(code,dailyReferencePrice(code));
    }

    private void moveToPrice(String code, long price, long volume) {
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

    Portfolio portfolioUnsafe(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, COALESCE(p.settled_quantity, p.quantity) AS settled_quantity,
                       COALESCE((SELECT SUM(o.reserved_quantity) FROM orders o
                                 WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id
                                   AND o.side = 'SELL' AND o.status = 'OPEN'), 0) AS reserved_quantity,
                       p.average_price, COALESCE(p.realized_profit_loss, 0) AS realized_profit_loss,
                       p.quantity * COALESCE(pm.mark_price,s.current_price) AS market_value,
                       GREATEST(0, COALESCE(p.settled_quantity,p.quantity) - COALESCE((
                           SELECT SUM(o.reserved_quantity) FROM orders o
                           WHERE o.user_id = p.user_id AND o.stock_id = p.stock_id AND o.status = 'OPEN' AND o.side = 'SELL'
                       ),0)) AS available_quantity,
                       p.quantity * (COALESCE(pm.mark_price,s.current_price) - p.average_price) AS profit_loss
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getInt("settled_quantity"),
                Math.max(0, rs.getInt("quantity") - rs.getInt("settled_quantity")),
                Math.max(0, rs.getInt("settled_quantity") - rs.getInt("reserved_quantity")),
                rs.getLong("average_price"), rs.getLong("market_value"), rs.getLong("profit_loss"),
                rs.getLong("average_price") == 0 ? 0 :
                        Math.round(rs.getLong("profit_loss") * 10000.0 /
                                (rs.getInt("quantity") * rs.getLong("average_price"))) / 100.0,
                rs.getLong("realized_profit_loss")), userId);
        long reservedCash = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cash), 0) FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId);
        long unsettledCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(gross_amount - seller_fee), 0)
                FROM settlements WHERE seller_id = ? AND status = 'PENDING'
                """, Long.class, userId);
        long unsettledAssetValue = positions.stream()
                .filter(position -> position.unsettledQuantity() > 0)
                .mapToLong(position -> (long) position.unsettledQuantity() * currentPrice(position.stockCode()))
                .sum();
        long totalFees = jdbc.queryForObject("""
                SELECT COALESCE(SUM(f.fee),0) FROM (
                  SELECT id,buyer_fee fee FROM trades WHERE buyer_id=?
                    AND created_at>COALESCE((SELECT account_reset_at FROM users WHERE id=?),'1970-01-01')
                  UNION ALL
                  SELECT id,seller_fee fee FROM trades WHERE seller_id=?
                    AND created_at>COALESCE((SELECT account_reset_at FROM users WHERE id=?),'1970-01-01')
                ) f WHERE NOT EXISTS (SELECT 1 FROM settlements st WHERE st.trade_id=f.id AND st.status='CANCELLED')
                """, Long.class, userId, userId, userId, userId);
        totalFees+=jdbc.queryForObject("SELECT COALESCE(SUM(fees),0) FROM bot_account_fees WHERE user_id=?",Long.class,userId);
        long realizedProfitLoss = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(realized_profit_loss, 0)), 0)
                FROM portfolios WHERE user_id = ?
                """, Long.class, userId);
        long attendanceRewardCash = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reward_cash), 0)
                FROM attendance_rewards
                WHERE user_id = ?
                """, Long.class, userId);
        // Like attendance, mission payouts are not trading performance; clients subtract both from P&L.
        long missionRewardCash = jdbc.queryForObject(
                "SELECT COALESCE(SUM(reward_cash), 0) FROM mission_rewards WHERE user_id = ?", Long.class, userId);
        // 대출 잔액은 빌린 돈이라 화면에서 순자산과 손익을 계산할 때 뺀다.
        long loanBalance = jdbc.queryForObject("SELECT loan_balance FROM users WHERE id = ?", Long.class, userId);
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        // ETF·선물·옵션 평가액도 계좌 총자산에 포함한다. 저장된 마지막 평가값을 쓴다.
        long derivativeAssetValue = AccountValuation.derivatives(jdbc,userId);
        long derivativeRealized = jdbc.queryForObject("""
                SELECT COALESCE(SUM(realized_pnl), 0) FROM derivative_positions WHERE user_id = ?
                """, Long.class, userId);
        return new Portfolio(cash, assetValue, cash + reservedCash + unsettledCash + assetValue + derivativeAssetValue,
                positions, unsettledCash, unsettledAssetValue, totalFees, realizedProfitLoss, attendanceRewardCash,
                missionRewardCash, loanBalance,adminRuntime==null?0:adminRuntime.externalCash(userId),
                derivativeAssetValue, derivativeRealized);
    }

    private long currentPrice(String code) {
        return findStock(code).price();
    }

    double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
