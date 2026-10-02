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
    private static final long STARTING_CASH = 1_000_000L;
    private static final ZoneId MISSION_ZONE = ZoneId.of("Asia/Seoul");
    private static final Map<String, Long> MISSION_REWARDS = Map.of(
            "market", 50_000L,
            "news", 50_000L,
            "watch", 50_000L);
    /** GameStock charges a small, transparent 0.10% commission per side. */
    private static final double TRADING_FEE_RATE = 0.001;
    /** Per-headline news influence limits used to update fair value only. */
    private static final double NEWS_SINGLE_BASE_RATE = 0.02;
    private static final double NEWS_SINGLE_SPECIAL_RATE = 0.05;
    private static final double NEWS_MAJOR_INCIDENT_RATE = 0.10;
    private static final double NEWS_AGGREGATE_BASE_RATE = 0.05;
    private static final double NEWS_AGGREGATE_SPECIAL_RATE = 0.10;
    private static final String LP_USERNAME = "liquidity_provider";
    private static final long LP_STARTING_CASH = 50_000_000L;
    private static final int LP_INITIAL_INVENTORY = 400;
    private static final String TRADER_BOT_PASSWORD = "TRADER";
    private static final long TRADER_BOT_STARTING_CASH = 1_000_000L;
    private static final double NEWS_SPECIAL_IMPACT_THRESHOLD = 6.0;
    private static final double NEWS_MAJOR_INCIDENT_IMPACT_THRESHOLD = -8.0;
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
    private static final Pattern NEWS_SOURCE_PATTERN = Pattern.compile("출처:\\s*(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private MatchingEngine matching=MatchingEngine.direct();
    private MarketMetrics pipelineMetrics=new MarketMetrics();
    private MarketOwnership ownership;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configurePipeline(MatchingEngine matching,MarketMetrics metrics,MarketOwnership ownership){
        this.matching=matching;this.pipelineMetrics=metrics;this.ownership=ownership;
    }
    public boolean matchingEnabled(){return ownership==null||ownership.enabled();}
    public void requireMatchingOwner(){if(ownership!=null)ownership.requireOwner();}

    // Wall clock in production; replaceable with a synchronized Java/SQL clock in soak tests.
    private Clock clock = Clock.systemUTC();
    private final UserFeatureService userFeatures;
    private final TradingProtectionService protection;
    @Value("${market.seed:${gamestock.simulation.seed:}}")
    private String configuredSeed;
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
    @Value("${market.batch.detail-retention-minutes:15}")
    private int detailRetentionMinutes=15;
    private BotLedgerJournal botJournal;
    @Value("${market.batch.inline-liquidity:true}")
    private boolean inlineMarketMaker;
    public boolean inlineLiquidity(){return batchEnabled&&inlineMarketMaker;}
    @Value("${market.batch.adaptive:true}")
    private boolean adaptiveActivity;
    @Value("${market.batch.idle-target-per-symbol:30}")
    private int idleTargetPerSymbol=30;
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
    public void marketViewerConnected(String id){activityPolicy.connected(id,clock.millis());prepareVisitor();events.publishEvent(new MarketPresenceEvent());}
    public void marketViewerDisconnected(String id){activityPolicy.disconnected(id,clock.millis());}
    boolean activeSimulation(){return !adaptiveActivity||activityPolicy.active(clock.millis(),idleAfterMillis);}
    public long participantCadenceMillis(){return activeSimulation()?Math.max(50,Math.min(250,activeCadenceMillis)):activityPolicy.pulseOpen(clock.millis())?200:500;}
    int currentTargetPerSymbol(){return activeSimulation()?targetPerSymbol:Math.min(targetPerSymbol,Math.max(30,idleTargetPerSymbol));}
    boolean botWorkDue(String worker){return !adaptiveActivity||activityPolicy.workDue(worker,clock.millis(),activeSimulation());}
    boolean claimLegacyBotWork(String worker){return !adaptiveActivity||activityPolicy.claim(worker,clock.millis(),activeSimulation())>=0;}
    private void prepareVisitor() {
        if(simulationInitialized&&adaptiveActivity&&activityPolicy.maintenanceDue(clock.millis(),true))maintainScheduledMarket();
    }
    /** Idle heartbeats do not acquire a JDBC connection. Only due work opens a transaction. */
    public void maintainScheduledMarket() {
        if(!simulationInitialized||!matchingEnabled())return;
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
        this.userFeatures = userFeatures;
        this.protection = protection;
    }

    @PostConstruct
    @Transactional
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
            botJournal=new BotLedgerJournal(jdbc);
            demoUserId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,DEMO_USERNAME);
            simulationInitialized=true;return;
        }
        ensurePriceHistoryTable();
        ensureAuthenticationTables();
        ensureMarketEventColumns();
        ensureTradeTable();
        ensurePortfolioColumns();
        ensureSettlementTable();
        ensureDailySummaryTable();
        ensureSimulationState();
        ensureMarketLock();
        jdbc.update("""
                INSERT IGNORE INTO users (username, password_hash, nickname, cash)
                VALUES (?, ?, ?, ?)
                """, DEMO_USERNAME, "demo", "Demo User", 1_000_000L);

        renameExistingStock("NEXA", "UMA", "네사: 크로니클", "우마무스메 프리티더비");
        renameExistingStock("STAR", "BA", "스타라이트 아레나", "블루 아카이브");
        renameExistingStock("MOMO", "GOV", "모모 팜", "승리의 여신: 니케");
        removeExistingStock("VOID", "보이드 러너");

        StockCatalog.ensureListings(jdbc);
        MarketBookCache.ensureTables(jdbc);
        BotLedgerJournal.ensureTables(jdbc);
        botJournal=new BotLedgerJournal(jdbc);
        ensureTraderBots();
        userFeatures.ensureTables();
        userFeatures.ensureDefaultTags();
        seedPriceHistory();
        seedDailySummaries();
        protection.ensureTables();
        normalizeOpenOrderPrices();
        initializeSimulationBooks();
        priceMetrics.ensureTables();
        for(String column:List.of("created_at","expires_at")) {
            Integer precision=jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='orders' AND column_name=?",Integer.class,column);
            if(precision==null || precision<3)jdbc.execute("ALTER TABLE orders MODIFY COLUMN "+column+" TIMESTAMP(3) "+(column.equals("created_at")?"NOT NULL DEFAULT CURRENT_TIMESTAMP(3)":"NULL"));
        }
        refreshEnvironments();

        removeDefaultEvents();
        initializeNewsPriceState();

        demoUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, DEMO_USERNAME);
        // Convert any rows created by the former T+1 implementation to the
        // new immediate-settlement state during startup.
        settleDuePayments();
        if(batchEnabled)initializeActivityEngine();
        simulationInitialized=true;
    }

    private void initializeActivityEngine() {
        if(targetPerSymbol<30||targetPerSymbol>1000)throw new IllegalArgumentException("Per-symbol target must be 30..1000 fills/second");
        Map<String,Long> ids=new HashMap<>();
        jdbc.query("SELECT username,id FROM users WHERE password_hash='TRADER'",rs->{ids.put(rs.getString(1),rs.getLong(2));});
        List<Long> stocks=jdbc.queryForList("SELECT id FROM stocks ORDER BY id",Long.class);
        List<BotActivityEngine.Participant> participants=new ArrayList<>();
        for(int i=0;i<traderProfiles.size();i++) {
            BotProfile profile=traderProfiles.get(i);
            participants.add(new BotActivityEngine.Participant(ids.get(profile.username()),profile,stocks.get(i%stocks.size())));
        }
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
        if(inlineMarketMaker)for(long stock:book.stocks.keySet())if(!book.lpCashBudgets.containsKey(stock)) {
            long total=liquidityAllocatedCash(stock,activityLpId);
            long reserved=book.working(activityLpId,stock).stream().mapToLong(o->o.reservedCash).sum();
            book.lpCashBudgets.put(stock,total-reserved);
        }
        long loaded=System.nanoTime();
        if(lane.lastObservation==0 || now-lane.lastObservation>=500) {
            for(var stock:book.stocks.values()) {
                priceMetrics.project(stock.id,now);
                double fundamental=jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?",Double.class,stock.id);
                var observed=priceMetrics.read(stock.id,stock.last,now,0);
                lane.observations.put(stock.id,new BotActivityEngine.Observation(observed,environment(stock.code),fundamental,recentNewsBias(stock.code).rate(),.65));
                jdbc.update("UPDATE market_price_metrics SET mark_price=?,mark_updated_at=? WHERE stock_id=?",Math.round(observed.markPrice()),new Timestamp(now),stock.id);
            }
            lane.lastObservation=now;
            lane.risks.clear();
            jdbc.query("SELECT stock_id,target_inventory,max_inventory,risk_limit FROM lp_risk_books",rs->{lane.risks.put(rs.getLong(1),new MarketMakerEngine.RiskBook(0,0,rs.getInt(2),rs.getInt(3),rs.getLong(4)));});
        }
        Map<Long,Integer> recent=bookCacheEnabled?lane.cache.recent(now):repository.recentCounter(lane.stocks,now).recent(now);
        long observed=System.nanoTime();
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

    private void ensureAuthenticationTables() {
        addOrderColumnIfMissing();
        addUserColumnIfMissing("google_uid", "VARCHAR(128) NULL UNIQUE");
        addUserColumnIfMissing("email", "VARCHAR(255) NULL");
        addUserColumnIfMissing("profile_image_url", "VARCHAR(500) NULL");
        addUserColumnIfMissing("profile_completed", "BOOLEAN NOT NULL DEFAULT FALSE");
        addUserColumnIfMissing("role", "VARCHAR(20) NOT NULL DEFAULT 'USER'");
        addUserColumnIfMissing("account_reset_at", "TIMESTAMP NULL");
        addUserColumnIfMissing("reset_used_at", "TIMESTAMP NULL");
        addUserColumnIfMissing("nickname_changed_at", "TIMESTAMP NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_rewards (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, rewarded_on DATE NOT NULL,
                  streak_day INT NOT NULL, reward_cash BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_attendance_user_day (user_id, rewarded_on),
                  CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mission_rewards (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                  mission_id VARCHAR(40) NOT NULL, rewarded_on DATE NOT NULL, reward_cash BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_mission_reward_user_day (user_id, mission_id, rewarded_on),
                  CONSTRAINT fk_mission_reward_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        migrateDailyMissionRewards();
    }

    private void migrateDailyMissionRewards() {
        Integer columnCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND column_name = 'rewarded_on'", Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbc.execute("ALTER TABLE mission_rewards ADD COLUMN rewarded_on DATE NULL AFTER mission_id");
        }
        Integer nullableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND column_name = 'rewarded_on' AND is_nullable = 'YES'", Integer.class);
        if (nullableCount != null && nullableCount > 0) {
            // JDBC sessions use UTC. Keep each legacy payout on its original Korean date.
            jdbc.update("UPDATE mission_rewards SET rewarded_on = DATE(CONVERT_TZ(created_at, '+00:00', '+09:00')) WHERE rewarded_on IS NULL");
            jdbc.execute("ALTER TABLE mission_rewards MODIFY COLUMN rewarded_on DATE NOT NULL");
        }
        Integer dailyIndexCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND index_name = 'uq_mission_reward_user_day'", Integer.class);
        if (dailyIndexCount != null && dailyIndexCount == 0) {
            jdbc.execute("ALTER TABLE mission_rewards ADD UNIQUE KEY uq_mission_reward_user_day (user_id, mission_id, rewarded_on)");
        }
        Integer legacyIndexCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'mission_rewards' AND index_name = 'uq_mission_reward_user_mission'", Integer.class);
        if (legacyIndexCount != null && legacyIndexCount > 0) {
            // Add the daily index first so the user foreign key always has a supporting index.
            jdbc.execute("ALTER TABLE mission_rewards DROP INDEX uq_mission_reward_user_mission");
        }
    }

    private void addOrderColumnIfMissing() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'remaining_quantity'", Integer.class);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE orders ADD COLUMN remaining_quantity INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("reserved_quantity", "INT NOT NULL DEFAULT 0");
        addOrderColumnIfMissing("expires_at", "TIMESTAMP NULL");
        jdbc.update("UPDATE orders SET remaining_quantity = quantity WHERE remaining_quantity = 0 AND status = 'OPEN'");
        jdbc.execute("ALTER TABLE orders MODIFY COLUMN status ENUM('OPEN', 'FILLED', 'PARTIAL', 'CANCELLED') NOT NULL DEFAULT 'OPEN'");
    }

    private void addOrderColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE orders ADD COLUMN " + name + " " + definition);
    }

    private void addUserColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE users ADD COLUMN " + name + " " + definition);
    }

    private void ensureMarketEventColumns() {
        Integer sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'source'", Integer.class);
        if (sourceCount != null && sourceCount == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN source VARCHAR(40) NOT NULL DEFAULT '미디어 보도' AFTER event_type");
        jdbc.update("UPDATE market_events SET source = '미디어 보도' WHERE source IS NULL OR source = ''");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'published_at'", Integer.class);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN published_at TIMESTAMP NULL AFTER impact");
        Integer priceAtPublishCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_events' AND column_name = 'price_at_publish'", Integer.class);
        if (priceAtPublishCount != null && priceAtPublishCount == 0) jdbc.execute("ALTER TABLE market_events ADD COLUMN price_at_publish BIGINT NULL AFTER published_at");
        jdbc.update("UPDATE market_events SET published_at = created_at WHERE published_at IS NULL");
        // Existing events predate the persisted baseline. Reuse the closest
        // historical quote when available and fall back to the current quote
        // only for rows without enough history. Newly collected events receive
        // their collection-time price in NewsFeedService.
        jdbc.update("""
                UPDATE market_events e
                JOIN stocks s ON s.id = e.stock_id
                SET e.price_at_publish = COALESCE((
                    SELECT h.price FROM stock_price_history h
                    WHERE h.stock_id = e.stock_id
                      AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                    ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                ), s.current_price)
                WHERE e.event_type = 'NEWS' AND e.price_at_publish IS NULL
                """);
        // News is read by stock and type repeatedly during feed rendering and
        // price-driver calculation. These indexes are also safe for an
        // existing Railway database because they are created only once.
        ensureIndex("market_events", "ix_market_events_stock_type_time",
                "stock_id,event_type,published_at,created_at,id");
        ensureIndex("market_events", "ix_market_events_stock_title", "stock_id,title");
    }

    private void renameExistingStock(String oldCode, String newCode, String oldName, String newName) {
        jdbc.update("UPDATE games SET name = ? WHERE name = ?", newName, oldName);
        jdbc.update("UPDATE stocks SET stock_code = ? WHERE stock_code = ?", newCode, oldCode);
    }

    private void removeExistingStock(String code, String gameName) {
        jdbc.update("DELETE FROM market_events WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        // 체결 이력이 주문/종목을 외래 키로 참조하므로 주문보다 먼저 정리한다.
        jdbc.update("DELETE FROM trades WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM orders WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM portfolios WHERE stock_id IN (SELECT id FROM stocks WHERE stock_code = ?)", code);
        jdbc.update("DELETE FROM stocks WHERE stock_code = ?", code);
        jdbc.update("DELETE FROM games WHERE name = ? AND NOT EXISTS (SELECT 1 FROM stocks WHERE game_id = games.id)", gameName);
    }

    private void removeDefaultEvents() {
        jdbc.update("""
                DELETE FROM market_events
                WHERE title IN (?, ?, ?)
                """, "대규모 시즌 업데이트 적용", "경쟁작 출시 예고",
                "글로벌 누적 이용자 1,000만 달성");
    }

    private void ensurePriceHistoryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_price_history (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  price BIGINT NOT NULL,
                  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_price_history_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_price_history_stock_time (stock_id, recorded_at)
                )
                """);
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

    public synchronized List<Stock> stocks() {
        return jdbc.query("""
                SELECT stock_code, g.name, g.genre, current_price, previous_price, total_volume
                FROM stocks s JOIN games g ON g.id = s.game_id
                ORDER BY s.id
                """, (rs, row) -> new Stock(
                rs.getString("stock_code"),
                rs.getString("name"),
                rs.getString("genre"),
                rs.getLong("current_price"),
                changePercent(rs.getLong("current_price"), rs.getLong("previous_price")),
                rs.getLong("total_volume"), protection.restriction(rs.getString("stock_code"))));
    }

    public synchronized List<MarketEvent> marketEvents() {
        List<MarketEvent> relevant = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at,
                       s.current_price, s.previous_price,
                       COALESCE(e.price_at_publish, (
                           SELECT h.price FROM stock_price_history h
                           WHERE h.stock_id = e.stock_id
                             AND h.recorded_at <= COALESCE(e.published_at, e.created_at)
                           ORDER BY h.recorded_at DESC, h.id DESC LIMIT 1
                       ), s.current_price) AS price_at_publish
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
                """, this::toMarketEvent).stream()
                .filter(this::isRelevantNews)
                .toList();
        List<MarketEvent> updates = relevant.stream()
                .filter(event -> "업데이트 노트".equals(event.source()))
                .toList();
        List<MarketEvent> media = relevant.stream()
                .filter(event -> !"업데이트 노트".equals(event.source()))
                .toList();
        // 한 게임의 글이 최신이라는 이유로 다른 게임의 공식 글을
        // 전부 밀어내지 않도록, 종류별로 게임마다 최신 2개씩 확보한다.
        updates = takeLatestPerStock(updates, 2);
        media = takeLatestPerStock(media, 2);
        // 두 종류를 모두 확보하되, 화면에서는 게시 시각의 전체 흐름으로
        // 섞어서 보여준다. 업데이트 노트를 억지로 상단에 고정하지 않는다.
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

    /** GameStock은 장 마감 없이 24시간 주문을 접수하는 게임형 시장이다. */
    public synchronized MarketStatus marketStatus() {
        return protection.marketStatus();
    }

    public synchronized List<MarketEvent> stockNews(String code) {
        List<MarketEvent> candidates = jdbc.query("""
                SELECT s.stock_code, e.title, e.impact, e.source, e.description, e.published_at,
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
        return "업데이트 노트".equals(event.source())
                || event.stockCode() == null
                || NewsRelevance.isRelevant(event.stockCode(), event.title(), event.description());
    }

    private MarketEvent toMarketEvent(ResultSet rs, int rowNumber) throws SQLException {
        double rawImpact = rs.getDouble("impact");
        int impact = (int) Math.round(rawImpact);
        String sentiment = impact > 0 ? "positive" : impact < 0 ? "negative" : "neutral";
        long currentPrice = rs.getLong("current_price");
        long priceAtPublish = rs.getLong("price_at_publish");
        double priceChangePercent = changePercent(currentPrice, priceAtPublish);
        String priceDirection = priceChangePercent > 0 ? "up" : priceChangePercent < 0 ? "down" : "flat";
        String reason = priceReason(priceChangePercent, impact);
        var publishedAt = rs.getTimestamp("published_at");
        String published = publishedAt == null ? null : databaseInstant(publishedAt).toString();
        return new MarketEvent(rs.getString("stock_code"), rs.getString("title"), impact,
                sentiment, rs.getString("source"), rs.getString("description"), published, priceAtPublish, priceChangePercent,
                priceDirection, reason);
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

    @Transactional(readOnly = true)
    public synchronized List<RankingEntry> ranking() {
        List<RankingEntry> entries = jdbc.query("""
                SELECT u.nickname, u.profile_image_url, u.cash,
                       COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id = u.id AND o.status = 'OPEN'), 0) AS reserved_cash,
                       COALESCE((SELECT SUM(st.gross_amount - st.seller_fee) FROM settlements st WHERE st.seller_id = u.id AND st.status = 'PENDING'), 0) AS unsettled_cash,
                       COALESCE((SELECT SUM(ar.reward_cash) FROM attendance_rewards ar WHERE ar.user_id = u.id), 0) AS attendance_reward_cash,
                       COALESCE(SUM(CASE WHEN p.quantity > 0 THEN p.quantity * COALESCE(pm.mark_price,s.current_price) ELSE 0 END), 0) AS asset_value
                FROM users u
                LEFT JOIN portfolios p ON p.user_id = u.id
                LEFT JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo'
                  AND COALESCE(u.role, 'USER') <> 'ADMIN'
                GROUP BY u.id, u.nickname, u.profile_image_url, u.cash
                ORDER BY (u.cash + reserved_cash + asset_value) DESC, u.id ASC
                LIMIT 100
                """, (rs, row) -> {
            long assetValue = rs.getLong("asset_value");
            long cash = rs.getLong("cash");
            long reservedCash = rs.getLong("reserved_cash");
            long unsettledCash = rs.getLong("unsettled_cash");
            long attendanceRewardCash = rs.getLong("attendance_reward_cash");
            long totalAsset = cash + reservedCash + unsettledCash + assetValue;
            double changePercent = (totalAsset - STARTING_CASH - attendanceRewardCash) * 100.0 / STARTING_CASH;
            return new RankingEntry(0, rs.getString("nickname"), rs.getString("profile_image_url"), totalAsset, assetValue, cash, changePercent);
        });
        List<RankingEntry> ranked = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            ranked.add(new RankingEntry(index + 1, entry.nickname(), entry.profileImageUrl(), entry.totalAsset(), entry.assetValue(), entry.cash(), entry.changePercent()));
        }
        return ranked;
    }

    @Transactional(readOnly = true)
    public synchronized Portfolio portfolio(long userId) {
        return portfolioUnsafe(userId);
    }

    @Transactional(readOnly = true)
    public synchronized DailyMissionStatus dailyMissions(long userId) {
        return dailyMissionsAt(userId, clock.instant());
    }

    private DailyMissionStatus dailyMissionsAt(long userId, Instant now) {
        LocalDate day = now.atZone(MISSION_ZONE).toLocalDate();
        List<String> completed = jdbc.queryForList("SELECT mission_id FROM mission_rewards WHERE user_id = ? AND rewarded_on = ? ORDER BY mission_id",
                String.class, userId, java.sql.Date.valueOf(day));
        return new DailyMissionStatus(day.toString(), day.plusDays(1).atStartOfDay(MISSION_ZONE).toInstant().toString(),
                now.toString(), completed.stream().filter(MISSION_REWARDS::containsKey).toList());
    }

    @Transactional
    public synchronized MissionRewardResult rewardMission(String missionId, long userId) {
        Long reward = MISSION_REWARDS.get(missionId);
        if (reward == null) throw new IllegalArgumentException("존재하지 않는 미션입니다.");
        LocalDate day = LocalDate.now(clock.withZone(MISSION_ZONE));
        int inserted = jdbc.update("INSERT IGNORE INTO mission_rewards (user_id, mission_id, rewarded_on, reward_cash) VALUES (?, ?, ?, ?)",
                userId, missionId, java.sql.Date.valueOf(day), reward);
        if (inserted > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", reward, userId);
        Portfolio portfolio = portfolioUnsafe(userId);
        return new MissionRewardResult(inserted > 0 ? reward : 0, inserted > 0, portfolio,
                dailyMissionsAt(userId, clock.instant()));
    }

    @Transactional(readOnly = true)
    public synchronized List<ActiveOrder> activeOrders(long userId) {
        return jdbc.query("""
                SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                       o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                       COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
                FROM orders o JOIN stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND o.status = 'OPEN'
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 5
                """, (rs, row) -> new ActiveOrder(
                rs.getLong("id"), rs.getString("stock_code"), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
                rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"),
                rs.getInt("reserved_quantity"), databaseInstant(rs.getTimestamp("created_at")).toString(),
                rs.getTimestamp("expires_at") == null ? null : databaseInstant(rs.getTimestamp("expires_at")).toString()), userId);
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
                  FROM settlements st
                  WHERE (st.buyer_id = ? OR st.seller_id = ?) AND st.status = 'SETTLED'
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
                userId, userId, userId, userId, userId);
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

    @Transactional(readOnly = true)
    public synchronized List<OrderHistory> orderHistory(String code, long userId) {
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
    public synchronized List<PublicTrade> publicTrades(String code) {
        List<PublicTrade> result=new ArrayList<>(jdbc.query("""
                SELECT t.aggressor_side AS side, t.quantity, t.price, taker.order_type, t.created_at
                FROM trades t
                JOIN stocks s ON s.id = t.stock_id
                JOIN orders taker ON taker.id = t.taker_order_id
                LEFT JOIN settlements st ON st.trade_id = t.id
                WHERE s.stock_code = ? AND (st.id IS NULL OR st.status <> 'CANCELLED')
                ORDER BY t.created_at DESC, t.id DESC LIMIT 10
                """, (rs, row) -> new PublicTrade(rs.getString("side"), rs.getInt("quantity"),
                rs.getLong("price"), rs.getString("order_type"), databaseInstant(rs.getTimestamp("created_at")).toString()),
                code.toUpperCase(Locale.ROOT)));
        var ids=jdbc.queryForList("SELECT id FROM stocks WHERE stock_code=?",Long.class,code.toUpperCase(Locale.ROOT));
        if(botJournal!=null&&!ids.isEmpty())for(var print:botJournal.recent(ids.get(0)))
            result.add(new PublicTrade(print.side(),print.quantity(),print.price(),print.type(),Instant.ofEpochMilli(print.at()).toString()));
        return result.stream().sorted(Comparator.comparing((PublicTrade trade)->Instant.parse(trade.createdAt())).reversed()).limit(10).toList();
    }

    public synchronized List<PricePoint> priceHistory(String code) {
        return priceHistory(code, "24h");
    }

    /**
     * Returns the recorded price points inside one of the public chart windows.
     * The range is deliberately allow-listed so a client cannot inject SQL
     * interval fragments. A generous point cap keeps a week view lightweight;
     * the clients down-sample only when the canvas width requires it.
     */
    public synchronized List<PricePoint> priceHistory(String code, String range) {
        Instant cutoff = clock.instant().minus(historyWindow(range));
        String table=historyWindow(range).toMinutes()<=15?"bot_trade_seconds":"bot_trade_minutes";
        List<PricePoint> points = jdbc.query("""
                SELECT price,recorded_at FROM (
                  SELECT h.id,h.price,h.recorded_at FROM stock_price_history h JOIN stocks s ON s.id=h.stock_id
                  WHERE s.stock_code=? AND h.recorded_at>=?
                  UNION ALL
                  SELECT 0,b.close_price,FROM_UNIXTIME(b.last_at/1000.0) FROM %s b JOIN stocks s ON s.id=b.stock_id
                  WHERE s.stock_code=? AND b.last_at>=?
                ) points ORDER BY recorded_at DESC,id DESC
                LIMIT 2000
                """.formatted(table), (rs, row) -> new PricePoint(
                rs.getLong("price"),
                databaseInstant(rs.getTimestamp("recorded_at")).toString()),
                code.toUpperCase(Locale.ROOT), Timestamp.from(cutoff),code.toUpperCase(Locale.ROOT),cutoff.toEpochMilli());
        Collections.reverse(points);
        return points;
    }

    /** Returns a bounded OHLC series for the chart ranges shown by the client. */
    public synchronized List<ChartCandle> chartHistory(String code, String range) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        Duration window = chartWindow(range);
        long bucketSeconds = chartBucketSeconds(range);
        Instant cutoff = clock.instant().minus(window);
        String table=window.toDays()<=8?"bot_trade_minutes":"bot_trade_hours";
        return jdbc.query("""
                WITH points AS (
                  SELECT h.id,h.price open_price,h.price high_price,h.price low_price,h.price close_price,h.recorded_at,
                         UNIX_TIMESTAMP(h.recorded_at)*1000 first_at,UNIX_TIMESTAMP(h.recorded_at)*1000 last_at,1 point_count
                  FROM stock_price_history h JOIN stocks s ON s.id=h.stock_id WHERE s.stock_code=? AND h.recorded_at>=?
                  UNION ALL
                  SELECT 0,b.open_price,b.high_price,b.low_price,b.close_price,FROM_UNIXTIME(b.bucket_at),b.first_at,b.last_at,b.fills
                  FROM %s b JOIN stocks s ON s.id=b.stock_id WHERE s.stock_code=? AND b.bucket_at>=?
                ), bucketed AS (
                  SELECT h.*,
                         FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?) AS bucket,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.first_at ASC, h.id ASC
                         ) AS open_rank,
                         ROW_NUMBER() OVER (
                           PARTITION BY FLOOR(UNIX_TIMESTAMP(h.recorded_at) / ?)
                           ORDER BY h.last_at DESC, h.id DESC
                         ) AS close_rank
                  FROM points h
                )
                SELECT bucket * ? AS bucket_at,
                       MAX(CASE WHEN open_rank = 1 THEN open_price END) AS open_price,
                       MAX(high_price) AS high_price,
                       MIN(low_price) AS low_price,
                       MAX(CASE WHEN close_rank = 1 THEN close_price END) AS close_price,
                       SUM(point_count) AS point_count
                FROM bucketed
                GROUP BY bucket
                ORDER BY bucket ASC
                LIMIT 2000
                """.formatted(table), (rs, row) -> new ChartCandle(
                Instant.ofEpochSecond(rs.getLong("bucket_at")).toString(),
                rs.getLong("open_price"),
                rs.getLong("high_price"),
                rs.getLong("low_price"),
                rs.getLong("close_price"),
                rs.getLong("point_count")),
                normalized,Timestamp.from(cutoff),normalized,cutoff.getEpochSecond(),bucketSeconds,bucketSeconds,bucketSeconds,bucketSeconds);
    }

    private Duration chartWindow(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d" -> Duration.ofDays(1);
            case "1w" -> Duration.ofDays(7);
            case "1m" -> Duration.ofDays(30);
            case "1y" -> Duration.ofDays(365);
            default -> Duration.ofDays(1);
        };
    }

    private long chartBucketSeconds(String range) {
        return switch (range == null ? "1d" : range.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> 60;
            case "6h" -> 300;
            case "12h" -> 600;
            case "1d" -> 1800;
            case "1w" -> 7200;
            case "1m" -> 86400;
            case "1y" -> 604800;
            default -> 1800;
        };
    }

    private Duration historyWindow(String range) {
        return switch (range == null ? "24h" : range.trim().toLowerCase(Locale.ROOT)) {
            case "5m" -> Duration.ofMinutes(5);
            case "10m" -> Duration.ofMinutes(10);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "7d", "1w" -> Duration.ofDays(7);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(24);
        };
    }

    /**
     * Returns a transparent, public summary of the inputs that have shaped a
     * stock recently. This deliberately uses existing news, trade, and order
     * tables so the explanation is derived from the same data as the price.
     */
    public synchronized PriceDrivers priceDrivers(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        Stock stock = findStock(normalized);
        if (stock == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = stockId(normalized);
        Double impact = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.impact * GREATEST(0, LEAST(1,
                    1 - TIMESTAMPDIFF(SECOND, COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 86400.0))), 0)
                FROM market_events e
                WHERE e.stock_id = ? AND e.event_type = 'NEWS'
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Double.class, id);
        Integer newsCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                  AND COALESCE(published_at, created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, Integer.class, id);
        VolumeBreakdown volumes = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN buyer.password_hash NOT IN ('BOT','TRADER') THEN t.quantity ELSE 0 END), 0) AS user_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash NOT IN ('BOT','TRADER') THEN t.quantity ELSE 0 END), 0) AS user_sell,
                  COALESCE(SUM(CASE WHEN buyer.password_hash IN ('BOT','TRADER') THEN t.quantity ELSE 0 END), 0) AS bot_buy,
                  COALESCE(SUM(CASE WHEN seller.password_hash IN ('BOT','TRADER') THEN t.quantity ELSE 0 END), 0) AS bot_sell
                FROM trades t
                JOIN users buyer ON buyer.id = t.buyer_id
                JOIN users seller ON seller.id = t.seller_id
                WHERE t.stock_id = ?
                  AND t.created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("user_buy"), rs.getLong("user_sell"),
                rs.getLong("bot_buy"), rs.getLong("bot_sell")), id);
        long compactVolume=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM bot_trade_minutes WHERE stock_id=? AND bucket_at>=?",Long.class,id,clock.millis()/1000-86400);
        volumes=new VolumeBreakdown(volumes.userBuy(),volumes.userSell(),volumes.botBuy()+compactVolume,volumes.botSell()+compactVolume);
        VolumeBreakdown openOrders = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN side = 'BUY' THEN remaining_quantity ELSE 0 END), 0) AS open_buy,
                  COALESCE(SUM(CASE WHEN side = 'SELL' THEN remaining_quantity ELSE 0 END), 0) AS open_sell
                FROM orders
                WHERE stock_id = ? AND status = 'OPEN'
                """, (rs, row) -> new VolumeBreakdown(rs.getLong("open_buy"), rs.getLong("open_sell"), 0, 0), id);
        Timestamp latestNews = jdbc.queryForObject("""
                SELECT MAX(COALESCE(published_at, created_at)) FROM market_events
                WHERE stock_id = ? AND event_type = 'NEWS'
                """, Timestamp.class, id);
        Timestamp latestTrade = jdbc.queryForObject("SELECT MAX(created_at) FROM trades WHERE stock_id = ?", Timestamp.class, id);
        Timestamp compactLatest=jdbc.queryForObject("SELECT MAX(last_trade) FROM bot_ledger_totals WHERE stock_id=?",Timestamp.class,id);
        if(compactLatest!=null&&(latestTrade==null||compactLatest.after(latestTrade)))latestTrade=compactLatest;
        double newsImpact = Math.max(-10.0, Math.min(10.0, impact == null ? 0.0 : impact));
        long userNet = volumes.userBuy() - volumes.userSell();
        long botNet = volumes.botBuy() - volumes.botSell();
        long openNet = openOrders.userBuy() - openOrders.userSell();
        return new PriceDrivers(normalized, stock.price(), previousPrice(normalized), stock.changePercent(),
                newsImpact, newsCount == null ? 0 : newsCount, volumes.userBuy(), volumes.userSell(),
                volumes.botBuy(), volumes.botSell(), openOrders.userBuy(), openOrders.userSell(),
                latestNews == null ? null : databaseInstant(latestNews).toString(),
                latestTrade == null ? null : databaseInstant(latestTrade).toString(),
                priceDriversReason(stock.changePercent(), newsImpact, userNet, botNet, openNet));
    }

    private long previousPrice(String code) {
        Long previous = jdbc.queryForObject("SELECT previous_price FROM stocks WHERE stock_code = ?", Long.class, code);
        return previous == null ? 0 : previous;
    }

    private String priceDriversReason(double changePercent, double newsImpact, long userNet,
                                      long botNet, long openNet) {
        boolean newsUp = newsImpact > 0.2;
        boolean newsDown = newsImpact < -0.2;
        boolean flowUp = userNet > 0 || botNet > 0 || openNet > 0;
        boolean flowDown = userNet < 0 || botNet < 0 || openNet < 0;
        if (changePercent > 0 && newsUp && flowUp) return "최근 뉴스와 매수세가 함께 반영되어 가격이 상승했습니다.";
        if (changePercent < 0 && newsDown && flowDown) return "최근 뉴스와 매도세가 함께 반영되어 가격이 하락했습니다.";
        if (changePercent > 0 && newsDown && flowUp) return "하락 방향 뉴스가 있었지만 매수세가 우세해 가격이 상승했습니다.";
        if (changePercent < 0 && newsUp && flowDown) return "상승 방향 뉴스가 있었지만 매도세가 우세해 가격이 하락했습니다.";
        if (changePercent > 0 && flowUp) return "매수세가 매도세보다 커 가격이 상승했습니다.";
        if (changePercent < 0 && flowDown) return "매도세가 매수세보다 커 가격이 하락했습니다.";
        if (changePercent > 0 && newsUp) return "최근 뉴스 흐름이 상승 방향으로 반영되어 가격이 올랐습니다.";
        if (changePercent < 0 && newsDown) return "최근 뉴스 흐름이 하락 방향으로 반영되어 가격이 내렸습니다.";
        return "최근 뉴스와 거래 흐름이 뚜렷한 한 방향으로 모이지 않았습니다.";
    }

    public synchronized List<DailyCandle> dailySummaries(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        seedDailySummaries();
        return jdbc.query("""
                SELECT s.stock_code, d.trading_date, d.open_price, d.close_price, d.total_volume
                FROM daily_market_summaries d JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ?
                ORDER BY d.trading_date DESC
                LIMIT 90
                """, (rs, row) -> new DailyCandle(rs.getString("stock_code"),
                rs.getDate("trading_date").toLocalDate().toString(), rs.getLong("open_price"),
                rs.getLong("close_price"), rs.getLong("total_volume")), normalized);
    }

    @Transactional(readOnly = true)
    public synchronized OrderBook orderBook(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (findStock(normalized) == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        long id = stockId(normalized);
        List<OrderBookLevel> bids = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'BUY' AND status = 'OPEN'
                  AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3)) GROUP BY price ORDER BY price DESC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        List<OrderBookLevel> asks = jdbc.query("""
                SELECT price, SUM(remaining_quantity) quantity, COUNT(*) order_count FROM orders
                WHERE stock_id = ? AND side = 'SELL' AND status = 'OPEN'
                  AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3)) GROUP BY price ORDER BY price ASC LIMIT 10
                """, (rs, row) -> new OrderBookLevel(rs.getLong("price"), rs.getInt("quantity"), rs.getInt("order_count")), id);
        return new OrderBook(normalized, bids, asks);
    }

    @Transactional(readOnly = true)
    public synchronized MarketSnapshot snapshot() {
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
                order.status,portfolios.computeIfAbsent(command.userId(),this::portfolioUnsafe),execution.fee(),execution.status(),execution.settlementAt()),null));
        }
        publishBookEvents(book);
        events.publishEvent(bookEvent(book,now));
        return outcomes;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> symbolSnapshot(String code){
        var stock=findStock(code);
        if(stock==null)throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        var trades=publicTrades(code);
        return Map.of("symbol",code,"stock",stock,"orderbook",orderBook(code),"trades",trades,
            "chart",trades.stream().map(t->new PricePoint(t.price(),t.createdAt())).toList());
    }

    private MarketChangedEvent bookEvent(BatchOrderBook book,long now){
        Map<String,Object> updates=new HashMap<>();
        for(var stock:book.stocks.values()){
            List<PublicTrade> trades=new ArrayList<>();
            for(int i=book.fills.size()-1;i>=0&&trades.size()<20;i--){
                var fill=book.fills.get(i);if(fill.buy().stock==stock.id)
                    trades.add(new PublicTrade(fill.taker().side,fill.quantity(),fill.price(),fill.taker().type,Instant.ofEpochMilli(now).toString()));
            }
            var quote=new Stock(stock.code,stock.name,stock.genre,stock.last,changePercent(stock.last,stock.previous),stock.totalVolume,stock.continuous?null:protection.restriction(stock.code));
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
        if (!protection.marketStatus().open()) return;
        TradingRestriction restriction = protection.restriction(code);
        if (restriction != null && !restriction.limitOrdersAllowed()) return;
        long lp = ensureLiquidityProvider(code);
        // Measure live depth before withdrawing our quotes; replacement itself is not a liquidity crisis.
        PriceMetricService.Metrics metrics = metrics(code, 0);
        cancelBotOrders(lp, stockId(code));
        OrderBook externalBook=orderBook(code);
        PriceBand band=botPriceBand(code);
        MarketMakerEngine.QuoteConstraints constraints=new MarketMakerEngine.QuoteConstraints(band.lowerPrice(),band.upperPrice(),
                externalBook.bids().isEmpty()?0:externalBook.bids().get(0).price(),
                externalBook.asks().isEmpty()?0:externalBook.asks().get(0).price());
        MarketMakerEngine.RiskBook risk = liquidityRiskBook(code, lp);
        for (MarketMakerEngine.Quote quote : marketMaker.quotes(metrics, environment(code), risk,
                tickSize(Math.round(metrics.midPrice())), marketScale(code),constraints)) {
            insertLiquidityQuote(code, lp, quote);
        }
        MatchSummary matched = matchOrders(code);
        refreshMarkPrice(code);
        events.publishEvent(new MarketChangedEvent());
    }

    @Transactional
    public synchronized void participantBotAction(String username) {
        prepareBotAction(null);
        if (!protection.marketStatus().open()) return;
        BotProfile profile = traderProfiles.stream().filter(p -> p.username().equals(username)).findFirst().orElseThrow();
        List<String> codes = botStockCodes();
        if (codes.isEmpty()) return;
        long userId = traderBotId(username);
        BotObservation observation = selectBotObservation(profile,userId,codes);
        String code = observation.code();
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
        PriceBand band = dailyPriceBand(code);
        price = "BUY".equals(decision.side()) ? floorToTick(band.clamp(price)) : ceilToTick(band.clamp(price));
        int quantity = "SELL".equals(decision.side()) ? Math.min(decision.quantity(), availableQuantity(userId,id)) : decision.quantity();
        if (quantity <= 0) return;
        try {
            order(new OrderRequest(code, decision.side(), quantity, decision.market() ? "MARKET" : "LIMIT",
                    decision.market() ? null : price), userId);
            if (!decision.market()) jdbc.update("UPDATE orders SET expires_at=? WHERE user_id=? AND stock_id=? AND status='OPEN'",
                    Timestamp.from(clock.instant().plusMillis(decision.ttlMillis())), userId, id);
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
                double impact=Math.abs(recentNewsBias(code,profile.reactionLatency()).rate())*attention;
                if(impact>strongest){strongest=impact;catalyst=code;}
            }
            // News interrupts the normal research rotation, preserving the profile's reaction latency.
            if(catalyst!=null && !watched.contains(catalyst)) watched.add(0,catalyst);
        }
        List<BotObservation> observations=new ArrayList<>();
        List<BotOpportunitySelector.Opportunity> opportunities=new ArrayList<>();
        long equity=BotStrategyEngine.hasInventoryDemand(profile)?jdbc.queryForObject("""
                SELECT cash
                  + COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=u.id AND status='OPEN'),0)
                  + COALESCE((SELECT SUM(p.quantity*pm.mark_price) FROM portfolios p
                      JOIN market_price_metrics pm ON pm.stock_id=p.stock_id WHERE p.user_id=u.id),0)
                FROM users u WHERE id=?
                """,Long.class,userId):0;
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
        PriceMetricService.Metrics metrics=metrics(code,profile.reactionLatency());
        long id=stockId(code);
        List<BotOrderPolicy.RestingOrder> resting=jdbc.query("SELECT id,side,price,reserved_cash,created_at FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'",
                (rs,n)->new BotOrderPolicy.RestingOrder(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).getTime()),userId,id);
        HoldingState held = holdingState(id, userId);
        Long age = jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MAX(created_at), CURRENT_TIMESTAMP)/1000,0) FROM trades WHERE stock_id=? AND buyer_id=?",
                Long.class, id, userId);
        BotStrategyEngine.Position position = new BotStrategyEngine.Position(held.quantity(), availableCash(userId)+resting.stream().mapToLong(BotOrderPolicy.RestingOrder::cash).sum(),
                held.averagePrice(), age == null ? 0 : age,
                strategies.targetQuantity(profile,code,clock.millis(),equity,symbolCount,metrics.markPrice()));
        double estimate = metrics.markPrice();
        if (profile.strategy().family() == BotProfile.Family.VALUE) {
            double fundamental = jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?", Double.class, id);
            // Only value investors receive a noisy, slowly updated observation, never the hidden truth.
            estimate = strategies.estimate(profile, code, fundamental * (1 + tickRandom.nextGaussian() * .012));
        }
        double news = recentNewsBias(code, profile.reactionLatency()).rate();
        double perceived = news == 0 ? 0 : news * (1 + profile.valueError()*8)
                + tickRandom.nextGaussian() * Math.abs(news) * .25;
        BotStrategyEngine.Decision decision=strategies.decide(profile,metrics,environment(code),position,
                estimate,perceived,tickSize(Math.round(metrics.lastPrice())),marketScale(code));
        boolean replace=resting.stream().anyMatch(o -> botOrderPolicy.shouldReplace(profile,o,decision,
                clock.millis(),tickSize(o.price()),o.side().equals("BUY")?metrics.bestBid():metrics.bestAsk(),
                o.side().equals("BUY")?metrics.bestAsk():metrics.bestBid()));
        return new BotObservation(code,metrics,position,estimate,perceived,decision.score(),resting,decision,replace);
    }

    public synchronized long nextBotDelay(String username) {
        Random random = scheduleRandoms.computeIfAbsent(username, key -> new Random(simulationSeed ^ key.hashCode()));
        if (username.startsWith("liquidity:")) return 1000 + random.nextInt(2001);
        BotProfile p = traderProfiles.stream().filter(b -> b.username().equals(username)).findFirst().orElseThrow();
        return Math.max(500, (long)(p.decisionInterval() * (.65 + random.nextDouble()*.7)) + p.reactionLatency());
    }

    private PriceMetricService.Metrics metrics(String code,long latency) {
        return priceMetrics.read(stockId(code), findStock(code).price(), clock.millis(), latency);
    }

    private MarketEnvironment environment(String code) { return environments.getOrDefault(code, MarketEnvironment.neutral()); }

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

    /** Seed finite inventory once; a later sale is never replenished automatically. */
    private void seedLiquidityInventory(String code, long userId) {
        long id = stockId(code);
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
                    """, userId, id, LP_INITIAL_INVENTORY, LP_INITIAL_INVENTORY, findStock(code).price());
        }
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

    private void initializeSimulationBooks() {
        ensureOrderIndex("ix_orders_live_book", "stock_id,status,side,price,created_at,id");
        ensureOrderIndex("ix_orders_expiry", "status,expires_at");
        ensureOrderIndex("ix_orders_user_live", "user_id,status,stock_id");
        // Batch book loads scan OPEN rows in id order, while expiry cleanup
        // filters one stock by its deadline. Keep both paths selective as the
        // Railway order table grows.
        ensureOrderIndex("ix_orders_open_sequence", "status,id");
        ensureOrderIndex("ix_orders_stock_expiry", "stock_id,status,expires_at,id");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_price_metrics (
                    stock_id BIGINT PRIMARY KEY, mark_price BIGINT NOT NULL,
                    hidden_fundamental DOUBLE NOT NULL, anchor_price BIGINT NOT NULL,
                    last_news_id BIGINT NOT NULL DEFAULT 0, mark_updated_at TIMESTAMP(3) NULL)
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_price_metrics' AND column_name='mark_updated_at'",Integer.class)==0)
            jdbc.execute("ALTER TABLE market_price_metrics ADD COLUMN mark_updated_at TIMESTAMP(3) NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lp_risk_books (
                    stock_id BIGINT PRIMARY KEY, opening_cash BIGINT NOT NULL,
                    baseline_trade_id BIGINT NOT NULL, target_inventory INT NOT NULL,
                    max_inventory INT NOT NULL, risk_limit BIGINT NOT NULL)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS lp_cash_projection (stock_id BIGINT PRIMARY KEY,last_id BIGINT NOT NULL,cash BIGINT NOT NULL)");
        // PostConstruct is not intercepted by Spring's transaction proxy. Startup cancellation
        // and finite allocations therefore need an explicit transaction after the schema DDL.
        var startup=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        startup.executeWithoutResult(status->{
            acquireMarketLock();
            long lp = ensureLiquidityProvider("");
            cancelBotOrders(lp, null);
            List<String> codes = botStockCodes();
            long baseline = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades", Long.class);
            int existing = jdbc.queryForObject("SELECT COUNT(*) FROM lp_risk_books", Integer.class);
            if(batchEnabled)jdbc.update("UPDATE lp_risk_books SET target_inventory=100 WHERE target_inventory=400");
            long allocation = existing == 0 ? availableCash(lp) / Math.max(1,codes.size()) : 0;
            for (String code : codes) {
                long id = stockId(code);
                Stock stock = findStock(code);
                seedLiquidityInventory(code,lp);
                // No reset/refill on restart: persistent opening allocations and trade baselines are retained.
                jdbc.update("INSERT IGNORE INTO lp_risk_books VALUES (?,?,?,?,?,?)",
                        id,allocation,baseline,batchEnabled?100:400,1200,stock.price()*1200L);
                jdbc.update("INSERT IGNORE INTO market_price_metrics (stock_id,mark_price,hidden_fundamental,anchor_price) VALUES (?,?,?,?)",
                        id,stock.price(),stock.price(),stock.price());
                sectors.put(code,stock.genre());
            }
        });
    }

    private void ensureOrderIndex(String name,String columns) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name=?",Integer.class,name);
        if(count==null || count==0) jdbc.execute("CREATE INDEX "+name+" ON orders ("+columns+")");
    }

    private void ensureIndex(String table, String name, String columns) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, name);
        if (count == null || count == 0) jdbc.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
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
            NewsBias news = recentNewsBias(code);
            MarketEnvironment event = new MarketEnvironment(news.rate()*4,0,1+Math.abs(news.rate())*12,
                    1+Math.max(0,news.rate())*8,1+Math.max(0,-news.rate())*8,
                    1+Math.abs(news.rate())*5,1/(1+Math.abs(news.rate())*5),0,0,0,
                    1+Math.abs(news.rate())*5,1+Math.abs(news.rate())*5);
            environments.put(code,regimes.environment(code,sectors.getOrDefault(code,"OTHER"))
                    .combine(patterns.environment(code,now)).combine(event));
            updateFundamentalFromNews(code);
        }
    }

    private void updateFundamentalFromNews(String code) {
        long id = stockId(code);
        long lastNews = jdbc.queryForObject("SELECT last_news_id FROM market_price_metrics WHERE stock_id=?",Long.class,id);
        List<Long> ids = jdbc.queryForList("SELECT id FROM market_events WHERE stock_id=? AND event_type='NEWS' AND id>? AND COALESCE(published_at,created_at)<=CURRENT_TIMESTAMP ORDER BY id",Long.class,id,lastNews);
        double total = 0;
        for (Long newsId : ids) {
            RecentNews news = jdbc.queryForObject("SELECT title,description,impact FROM market_events WHERE id=?",
                    (rs,n)->new RecentNews(rs.getString(1),rs.getString(2),rs.getDouble(3),1),newsId);
            if (NewsRelevance.isRelevant(code,news.title(),news.description())) total += newsInfluence(news).rate();
        }
        if (!ids.isEmpty()) jdbc.update("""
                UPDATE market_price_metrics SET hidden_fundamental=GREATEST(anchor_price*.5,
                    LEAST(anchor_price*2,hidden_fundamental*?)),last_news_id=? WHERE stock_id=?
                """,1+MarketEnvironment.clamp(total,-.1,.1),ids.get(ids.size()-1),id);
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

    /** Human-only activity gives at most 50% extra scale; bots cannot amplify their own volume. */
    private double marketScale(String code) {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id), COALESCE(SUM(t.quantity),0)
                FROM trades t JOIN users u ON (u.id=t.buyer_id OR u.id=t.seller_id)
                WHERE t.stock_id=? AND t.created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE)
                  AND u.password_hash NOT IN ('BOT','TRADER') AND u.username<>'demo'
                  AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=t.id AND s.status='CANCELLED')
                """, (rs,n)->Math.min(1.5,.65+rs.getLong(1)*.04+Math.sqrt(rs.getLong(2))*.015),stockId(code));
    }

    private void advanceTradingProtections() {
        protection.refreshDay();
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

    private MatchSummary matchOrders(String code) {
        if(!protection.continuous(code))return MatchSummary.empty();
        long now=clock.millis(),id=stockId(code);
        var repository=new BatchMarketRepository(jdbc,compactLedger?botJournal:null);
        var book=repository.load(now,Set.of(id),Set.of());
        matching.compute(()->{book.matchExisting(id);return null;});
        repository.persist(book,now);
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        publishBookEvents(book);events.publishEvent(bookEvent(book,now));
        MatchSummary summary=MatchSummary.empty();
        for(var fill:book.fills)summary=summary.add(fill.quantity(),fill.price(),fill.taker().side);
        return summary;
    }

    private MatchRow topOrder(long stockId, String side) {
        return topOrder(stockId, side, 0L);
    }

    private MatchRow topOrder(long stockId, String side, long excludedUserId) {
        String priceOrder = "BUY".equals(side) ? "o.price DESC" : "o.price ASC";
        String userFilter = excludedUserId > 0 ? " AND o.user_id <> ? " : "";
        String sql = "SELECT o.id, o.user_id, COALESCE(o.price, 0), o.quantity, o.remaining_quantity, o.order_type, o.created_at, COALESCE(o.reserved_cash, 0), COALESCE(o.reserved_quantity, 0), o.side, (u.password_hash = 'BOT') AS is_bot "
                + "FROM orders o JOIN users u ON u.id = o.user_id WHERE o.stock_id = ? AND o.side = ? AND o.status = 'OPEN' AND o.remaining_quantity > 0 "
                + userFilter
                + "ORDER BY CASE WHEN o.order_type = 'MARKET' THEN 1 ELSE 0 END DESC, " + priceOrder + ", o.created_at ASC, o.id ASC LIMIT 1";
        List<MatchRow> rows = excludedUserId > 0
                ? jdbc.query(sql, (rs, row) -> new MatchRow(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10),
                rs.getBoolean(11)), stockId, side, excludedUserId)
                : jdbc.query(sql, (rs, row) -> new MatchRow(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), databaseInstant(rs.getTimestamp(7)), rs.getLong(8), rs.getInt(9), rs.getString(10),
                rs.getBoolean(11)), stockId, side);
        return rows.isEmpty() ? null : rows.get(0);
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

    private int affordableFill(long cash,long price,int requested) {
        int low=0,high=(int)Math.min(requested,cash/price);
        while(low<high) {
            int candidate=low+(high-low+1)/2;
            long gross=price*candidate;
            if(feeFor(gross)<=cash-gross) low=candidate; else high=candidate-1;
        }
        return low;
    }

    private void settleTrade(long stockId, MatchRow buy, MatchRow sell, MatchRow maker, MatchRow taker, int quantity, long tradePrice) {
        long amount = tradePrice * quantity;
        long buyerFee = feeFor(amount);
        long sellerFee = feeFor(amount);
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

    private void ensureTradeTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS trades (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  buy_order_id BIGINT NOT NULL,
                  sell_order_id BIGINT NOT NULL,
                  buyer_id BIGINT NOT NULL,
                  seller_id BIGINT NOT NULL,
                  maker_order_id BIGINT NOT NULL,
                  taker_order_id BIGINT NOT NULL,
                  aggressor_side ENUM('BUY', 'SELL') NOT NULL,
                  quantity INT NOT NULL,
                  price BIGINT NOT NULL,
                  buyer_fee BIGINT NOT NULL DEFAULT 0,
                  seller_fee BIGINT NOT NULL DEFAULT 0,
                  fee BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_trades_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders(id),
                  CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders(id),
                  CONSTRAINT fk_trades_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
                  CONSTRAINT fk_trades_seller FOREIGN KEY (seller_id) REFERENCES users(id),
                  INDEX ix_trades_stock_time (stock_id, created_at),
                  INDEX ix_trades_taker (taker_order_id),
                  INDEX ix_trades_maker (maker_order_id)
                )
                """);
        addTradeColumnIfMissing("buyer_fee", "BIGINT NOT NULL DEFAULT 0");
        addTradeColumnIfMissing("seller_fee", "BIGINT NOT NULL DEFAULT 0");
        addTradeColumnIfMissing("fee", "BIGINT NOT NULL DEFAULT 0");
        for(String side:List.of("buyer","seller")) {
            String name="ix_trades_"+side+"_fees";
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='trades' AND index_name=?",Integer.class,name)==0)
                jdbc.execute("CREATE INDEX "+name+" ON trades ("+side+"_id,created_at,"+side+"_fee)");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='trades' AND index_name='ix_trades_stock_sequence'",Integer.class)==0)
            jdbc.execute("CREATE INDEX ix_trades_stock_sequence ON trades (stock_id,id)");
    }

    private void addTradeColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'trades' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE trades ADD COLUMN " + name + " " + definition);
    }

    private void ensurePortfolioColumns() {
        boolean settledColumnAdded = addPortfolioColumnIfMissing("settled_quantity", "INT NOT NULL DEFAULT 0");
        addPortfolioColumnIfMissing("realized_profit_loss", "BIGINT NOT NULL DEFAULT 0");
        // Only rows from a pre-settlement schema are known to be settled. Do not
        // run this update on every restart; fresh rows are already settled.
        if (settledColumnAdded) jdbc.update("UPDATE portfolios SET settled_quantity = quantity WHERE quantity > 0");
    }

    private boolean addPortfolioColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'portfolios' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE portfolios ADD COLUMN " + name + " " + definition);
            return true;
        }
        return false;
    }

    private void ensureSettlementTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS settlements (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  trade_id BIGINT NOT NULL UNIQUE,
                  buyer_id BIGINT NOT NULL,
                  seller_id BIGINT NOT NULL,
                  stock_id BIGINT NOT NULL,
                  quantity INT NOT NULL,
                  gross_amount BIGINT NOT NULL,
                  buyer_fee BIGINT NOT NULL DEFAULT 0,
                  seller_fee BIGINT NOT NULL DEFAULT 0,
                  buyer_quantity_before INT NULL,
                  buyer_settled_quantity_before INT NULL,
                  buyer_average_price_before BIGINT NULL,
                  buyer_realized_profit_loss_before BIGINT NULL,
                  seller_quantity_before INT NULL,
                  seller_settled_quantity_before INT NULL,
                  seller_average_price_before BIGINT NULL,
                  seller_realized_profit_loss_before BIGINT NULL,
                  settlement_at TIMESTAMP NOT NULL,
                  status ENUM('PENDING', 'SETTLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
                  settled_at TIMESTAMP NULL,
                  cancelled_at TIMESTAMP NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_settlements_trade FOREIGN KEY (trade_id) REFERENCES trades(id),
                  CONSTRAINT fk_settlements_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
                  CONSTRAINT fk_settlements_seller FOREIGN KEY (seller_id) REFERENCES users(id),
                  CONSTRAINT fk_settlements_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_settlements_pending (status, settlement_at),
                  INDEX ix_settlements_buyer (buyer_id, status),
                  INDEX ix_settlements_seller (seller_id, status)
                )
                """);
        addSettlementColumnIfMissing("buyer_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("buyer_settled_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("buyer_average_price_before", "BIGINT NULL");
        addSettlementColumnIfMissing("buyer_realized_profit_loss_before", "BIGINT NULL");
        addSettlementColumnIfMissing("seller_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("seller_settled_quantity_before", "INT NULL");
        addSettlementColumnIfMissing("seller_average_price_before", "BIGINT NULL");
        addSettlementColumnIfMissing("seller_realized_profit_loss_before", "BIGINT NULL");
        addSettlementColumnIfMissing("cancelled_at", "TIMESTAMP NULL");
        jdbc.execute("ALTER TABLE settlements MODIFY COLUMN status ENUM('PENDING', 'SETTLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING'");
    }

    private void addSettlementColumnIfMissing(String name, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'settlements' AND column_name = ?", Integer.class, name);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE settlements ADD COLUMN " + name + " " + definition);
    }

    private void ensureDailySummaryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS daily_market_summaries (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  stock_id BIGINT NOT NULL,
                  trading_date DATE NOT NULL,
                  open_price BIGINT NOT NULL,
                  close_price BIGINT NOT NULL,
                  total_volume BIGINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uq_daily_summary_stock_date (stock_id, trading_date),
                  CONSTRAINT fk_daily_summary_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_daily_summary_date (trading_date)
                )
                """);
    }

    private void seedDailySummaries() {
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT id, CURRENT_DATE, current_price, current_price, 0
                FROM stocks s
                WHERE NOT EXISTS (
                    SELECT 1 FROM daily_market_summaries d
                    WHERE d.stock_id = s.id AND d.trading_date = CURRENT_DATE
                )
                """);
    }

    private void ensureSimulationState() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS simulation_state (
                  id TINYINT PRIMARY KEY,
                  tick BIGINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("INSERT IGNORE INTO simulation_state (id, tick) VALUES (1, 0)");
    }

    private void ensureNewsPriceState() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS news_price_state (
                  stock_id BIGINT PRIMARY KEY,
                  applied_bias DECIMAL(8,6) NOT NULL DEFAULT 0,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  CONSTRAINT fk_news_price_state_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
    }

    /** Seed only new rows so a backend restart never reapplies old headlines. */
    private void initializeNewsPriceState() {
        ensureNewsPriceState();
        List<String> codes = jdbc.queryForList("SELECT stock_code FROM stocks ORDER BY id", String.class);
        for (String code : codes) {
            long id = stockId(code);
            double currentBias = recentNewsBias(code).rate();
            jdbc.update("INSERT IGNORE INTO news_price_state (stock_id, applied_bias) VALUES (?, ?)", id, currentBias);
        }
    }

    private void ensureMarketLock() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS market_locks (
                  id TINYINT PRIMARY KEY
                )
                """);
        jdbc.update("INSERT IGNORE INTO market_locks (id) VALUES (1)");
    }

    /** Reject accidental double-clicks and abusive bursts from human users. */
    private void enforceOrderLimits(long userId, long stockId, String side, String orderType,
                                    int quantity, long price) {
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if ("BOT".equals(passwordHash)) return;
        Integer recentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + USER_ORDER_WINDOW_SECONDS + " SECOND)",
                Integer.class, userId);
        if (recentCount != null && recentCount >= USER_ORDER_LIMIT)
            throw new IllegalArgumentException("주문 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        Integer duplicateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND stock_id = ? AND side = ? AND order_type = ? "
                        + "AND quantity = ? AND COALESCE(price, 0) = ? "
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
            long rounded = "BUY".equals(order.side()) ? floorToTick(order.price()) : ceilToTick(order.price());
            long normalized = order.bot()
                    ? allowedBand.clamp(rounded)
                    : allowedBand.clamp(rounded);
            if ("BUY".equals(order.side())) {
                long required;
                try {
                    long normalizedGross = Math.multiplyExact(normalized, order.remainingQuantity());
                    required = normalizedGross + feeFor(normalizedGross);
                } catch (ArithmeticException error) {
                    if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
                    jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
                    continue;
                }
                long difference = order.reservedCash() - required;
                if (difference < 0 && jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ? AND cash >= ?", -difference, order.userId(), -difference) == 0) {
                    if (order.reservedCash() > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", order.reservedCash(), order.userId());
                    jdbc.update("UPDATE orders SET status = 'CANCELLED', remaining_quantity = 0, reserved_cash = 0, reserved_quantity = 0 WHERE id = ?", order.id());
                    continue;
                }
                if (difference > 0) jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", difference, order.userId());
                if (normalized != order.price() || order.reservedCash() != required)
                    jdbc.update("UPDATE orders SET price = ?, reserved_cash = ? WHERE id = ?", normalized, required, order.id());
            } else {
                if (normalized != order.price()) jdbc.update("UPDATE orders SET price = ? WHERE id = ?", normalized, order.id());
            }
        }
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
    private record MatchSummary(long lastTradePrice, long totalQuantity, long totalNotional, String aggressorSide,
                                long buyQuantity, long sellQuantity) {
        private static MatchSummary empty() { return new MatchSummary(0, 0, 0, null, 0, 0); }
        private boolean hasTrades() { return totalQuantity > 0; }
        private MatchSummary add(int quantity, long price, String side) {
            return new MatchSummary(price, totalQuantity + quantity, totalNotional + price * quantity, side,
                    buyQuantity + ("BUY".equals(side) ? quantity : 0), sellQuantity + ("SELL".equals(side) ? quantity : 0));
        }
        private MatchSummary merge(MatchSummary other) {
            if (!hasTrades()) return other;
            if (!other.hasTrades()) return this;
            return new MatchSummary(other.lastTradePrice, totalQuantity + other.totalQuantity,
                    totalNotional + other.totalNotional, other.aggressorSide,
                    buyQuantity + other.buyQuantity, sellQuantity + other.sellQuantity);
        }
        private long vwap() {
            return totalQuantity == 0 ? 0 : Math.round(totalNotional / (double) totalQuantity);
        }
    }
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
    private record VolumeBreakdown(long userBuy, long userSell, long botBuy, long botSell) { }
    private record RecentNews(String title, String description, double impact, double recency) { }
    private record NewsInfluence(double rate, boolean special) { }
    private record NewsBias(double rate, boolean special) { }
    private Stock findStock(String code) {
        List<Stock> rows = jdbc.query("""
                SELECT s.stock_code,g.name,g.genre,s.current_price,s.previous_price,s.total_volume
                FROM stocks s JOIN games g ON g.id=s.game_id WHERE s.stock_code=?
                """,(rs,n)->new Stock(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                changePercent(rs.getLong(4),rs.getLong(5)),rs.getLong(6),protection.restriction(code)),code);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** The JDBC timestamp already carries the normalized instant from the UTC DB session. */
    private Instant databaseInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private long stockId(String code) {
        return jdbc.queryForObject("SELECT id FROM stocks WHERE stock_code = ?", Long.class, code);
    }

    private long dailyReferencePrice(String code) {
        List<Long> references = jdbc.query("""
                SELECT d.open_price
                FROM daily_market_summaries d
                JOIN stocks s ON s.id = d.stock_id
                WHERE s.stock_code = ? AND d.trading_date = CURRENT_DATE
                """, (rs, row) -> rs.getLong(1), code);
        if (!references.isEmpty()) return references.get(0);
        jdbc.update("""
                INSERT IGNORE INTO daily_market_summaries (stock_id,trading_date,open_price,close_price,total_volume)
                SELECT id,CURRENT_DATE,current_price,current_price,0 FROM stocks WHERE stock_code=?
                """,code);
        return jdbc.queryForObject("SELECT d.open_price FROM daily_market_summaries d JOIN stocks s ON s.id=d.stock_id WHERE s.stock_code=? AND d.trading_date=CURRENT_DATE",Long.class,code);
    }

    private PriceBand dailyPriceBand(String code) {
        return dailyBand(dailyReferencePrice(code));
    }

    private PriceBand botPriceBand(String code) {
        return botBand(dailyReferencePrice(code));
    }

    private void moveToPrice(String code, long price, long volume) {
        PriceBand dailyBand = dailyPriceBand(code);
        long next = dailyBand.clamp(Math.max(100, price));
        jdbc.update("UPDATE stocks SET previous_price = current_price, current_price = ?, total_volume = total_volume + ? WHERE stock_code = ?", next, volume, code);
        jdbc.update("INSERT INTO stock_price_history (stock_id, price) SELECT id, ? FROM stocks WHERE stock_code = ?", next, code);
        recordDailyTrade(code, next, volume);
        refreshMarkPrice(code);
        // The scheduled market monitor evaluates the 60-second circuit-breaker condition.
        // A symbol fill must not take the market-wide state row's write lock.
    }

    private void recordDailyTrade(String code, long price, long volume) {
        jdbc.update("""
                INSERT INTO daily_market_summaries (stock_id, trading_date, open_price, close_price, total_volume)
                SELECT id, CURRENT_DATE, ?, ?, ? FROM stocks WHERE stock_code = ?
                ON DUPLICATE KEY UPDATE close_price = VALUES(close_price), total_volume = daily_market_summaries.total_volume + VALUES(total_volume)
                """, price, price, Math.max(0, volume), code);
    }

    private NewsBias recentNewsBias(String code) { return recentNewsBias(code, 0); }

    private NewsBias recentNewsBias(String code, long latency) {
        List<RecentNews> recentNews = jdbc.query("""
                SELECT e.title, e.description, e.impact,
                       GREATEST(0, LEAST(1, 1 - TIMESTAMPDIFF(SECOND,
                           COALESCE(e.published_at, e.created_at), CURRENT_TIMESTAMP) / 86400.0)) AS recency
                FROM market_events e JOIN stocks s ON s.id = e.stock_id
                WHERE e.event_type = 'NEWS' AND s.stock_code = ?
                  AND COALESCE(e.published_at, e.created_at) >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 24 HOUR)
                  AND COALESCE(e.published_at, e.created_at) <= ?
                ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.id DESC
                LIMIT 100
                """, (rs, row) -> new RecentNews(
                rs.getString("title"), rs.getString("description"), rs.getDouble("impact"),
                Math.max(0.0, Math.min(1.0, rs.getDouble("recency")))), code, Timestamp.from(clock.instant().minusMillis(latency))).stream()
                // Price formation uses the ten newest relevant headlines for
                // this stock, independently of the ten-item global feed.
                .filter(news -> NewsRelevance.isRelevant(code, news.title(), news.description()))
                .limit(10)
                .toList();
        double total = 0.0;
        boolean special = false;
        for (RecentNews news : recentNews) {
            NewsInfluence influence = newsInfluence(news);
            total += influence.rate();
            special |= influence.special();
        }
        double aggregateLimit = special ? NEWS_AGGREGATE_SPECIAL_RATE : NEWS_AGGREGATE_BASE_RATE;
        return new NewsBias(Math.max(-aggregateLimit, Math.min(aggregateLimit, total)), special);
    }

    private NewsInfluence newsInfluence(RecentNews news) {
        double absoluteImpact = Math.min(10.0, Math.abs(news.impact()));
        if (absoluteImpact == 0.0) return new NewsInfluence(0.0, false);
        boolean majorIncident = news.impact() <= NEWS_MAJOR_INCIDENT_IMPACT_THRESHOLD
                && NewsRelevance.hasIncidentContext(news.title(), news.description());
        boolean special = majorIncident || absoluteImpact >= NEWS_SPECIAL_IMPACT_THRESHOLD;
        double singleLimit = majorIncident ? NEWS_MAJOR_INCIDENT_RATE
                : special ? NEWS_SINGLE_SPECIAL_RATE : NEWS_SINGLE_BASE_RATE;
        double rate = singleLimit * (absoluteImpact / 10.0) * news.recency();
        return new NewsInfluence(Math.copySign(rate, news.impact()), special);
    }

    private Portfolio portfolioUnsafe(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<Position> positions = jdbc.query("""
                SELECT s.stock_code, p.quantity, COALESCE(p.settled_quantity, p.quantity) AS settled_quantity,
                       p.average_price, COALESCE(p.realized_profit_loss, 0) AS realized_profit_loss,
                       p.quantity * COALESCE(pm.mark_price,s.current_price) AS market_value,
                       p.quantity * (COALESCE(pm.mark_price,s.current_price) - p.average_price) AS profit_loss
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = ? AND p.quantity > 0
                ORDER BY p.id
                """, (rs, row) -> new Position(rs.getString("stock_code"),
                rs.getInt("quantity"), rs.getInt("settled_quantity"),
                Math.max(0, rs.getInt("quantity") - rs.getInt("settled_quantity")),
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
        long assetValue = positions.stream().mapToLong(Position::marketValue).sum();
        return new Portfolio(cash, assetValue, cash + reservedCash + unsettledCash + assetValue,
                positions, unsettledCash, unsettledAssetValue, totalFees, realizedProfitLoss, attendanceRewardCash);
    }

    private long currentPrice(String code) {
        return findStock(code).price();
    }

    private double changePercent(long current, long previous) {
        if (previous == 0) return 0;
        return Math.round((current - previous) * 10_000.0 / previous) / 100.0;
    }
}
