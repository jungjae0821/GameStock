package com.gamestock.backend.market;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * Batched participant-bot execution split out of {@link MarketService}: symbols are
 * sharded into up to four lanes, and each lane loads its order book, refreshes bot
 * observations, plans one micro-batch and persists it. Each lane is guarded by its own
 * monitor; MarketService keeps the transactional entry point, the owner check and every
 * configuration flag (tests set them by field name), and this class reads them per call.
 */
final class BotBatchLanes {
    // Bot indicators look 5 s to 5 min back; refreshing them every 2 s keeps that resolution at a quarter of the queries.
    private static final long OBSERVATION_INTERVAL_MS=2_000;
    private final MarketService market;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final LiquidityProviderAccounts liquidity;
    private final MarketNewsFeed newsFeed;
    private final TradingProtectionService protection;
    private final List<BotLane> lanes=new ArrayList<>();
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

    BotBatchLanes(MarketService market, JdbcTemplate jdbc, ApplicationEventPublisher events,
                  LiquidityProviderAccounts liquidity, MarketNewsFeed newsFeed, TradingProtectionService protection) {
        this.market = market;
        this.jdbc = jdbc;
        this.events = events;
        this.liquidity = liquidity;
        this.newsFeed = newsFeed;
        this.protection = protection;
    }

    int shardCount() {return lanes.size();}
    Map<String,Long> timings(int shard) {return lanes.get(shard).timings;}
    Map<String,Long> cacheStats(){return Map.of("hits",lanes.stream().mapToLong(l->l.cache.hits()).sum(),"misses",lanes.stream().mapToLong(l->l.cache.misses()).sum());}

    void initialize() {
        if(market.configuredTargetPerSymbol()<30||market.configuredTargetPerSymbol()>1000)throw new IllegalArgumentException("Per-symbol target must be 30..1000 fills/second");
        Map<String,Long> ids=new HashMap<>();
        jdbc.query("SELECT username,id FROM users WHERE password_hash='TRADER'",rs->{ids.put(rs.getString(1),rs.getLong(2));});
        List<Long> stocks=jdbc.queryForList("SELECT id FROM stocks ORDER BY id",Long.class);
        Map<Long,Set<Long>> heldStocks=new HashMap<>();
        jdbc.query("SELECT p.user_id,p.stock_id FROM portfolios p JOIN users u ON u.id=p.user_id WHERE u.password_hash='TRADER' AND p.quantity>0",
                rs->{heldStocks.computeIfAbsent(rs.getLong(1),key->new HashSet<>()).add(rs.getLong(2));});
        List<BotActivityEngine.Participant> participants=BotParticipantAssignments.assign(market.traderProfiles(),ids,stocks,heldStocks);
        activityLpId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,LiquidityProviderAccounts.USERNAME);
        lanes.clear();
        int count=Math.min(4,stocks.size());
        for(int shard=0;shard<count;shard++) {
            Set<Long> symbols=new HashSet<>();for(int i=shard;i<stocks.size();i+=count)symbols.add(stocks.get(i));
            var group=participants.stream().filter(p->symbols.contains(p.stock())).toList();
            Set<Long> users=new HashSet<>();group.forEach(p->users.add(p.id()));users.add(activityLpId);
            lanes.add(new BotLane(Set.copyOf(symbols),Set.copyOf(users),new BotActivityEngine(group,market.simulationSeed()^(shard*71L),market.configuredTargetPerSymbol())));
        }
    }

    List<BotActivityEngine.Activity> run(int shard,java.util.concurrent.atomic.AtomicReference<BotBatchPlan> retryPlan) {
        AdminRuntimeControl adminRuntime=market.adminRuntime();
        BotLane lane=lanes.get(shard);
        synchronized(lane) {
        long now=market.clock().millis();
        if(market.adaptiveActivity()) {
            long epoch=market.activityPolicy().claim("batch:"+shard,now,market.activeSimulation());
            if(epoch<0){lane.timings=Map.of();return List.of();}
            if(lane.epoch!=epoch || lane.lastActivity>0&&now-lane.lastActivity>market.idleAfterMillis()) {
                lane.engine.resume();lane.lastActivity=0;lane.lastObservation=0;lane.epoch=epoch;
            }
        }
        long started=System.nanoTime();
        BatchMarketRepository repository=market.newBatchRepository();
        BatchOrderBook book=market.bookCacheEnabled()?lane.cache.borrow(repository,now,lane.stocks,lane.users):repository.load(now,lane.stocks,lane.users);
        if(adminRuntime!=null){
            var controls=adminRuntime.settings();if(controls.botsPaused()||controls.marketPaused())return List.of();
            lane.engine.traderShare(controls.traderShare());adminRuntime.apply(book);
        }
        if(market.inlineMarketMaker())for(var stock:book.stocks.values()) {
            book.restoreLiquidityInventory(activityLpId,stock.id,
                    StockMarketProfile.initialLiquidityInventory(stock.code,LiquidityProviderAccounts.INITIAL_INVENTORY),stock.last);
            if(!book.lpCashBudgets.containsKey(stock.id)) {
                long total=liquidity.allocatedCash(stock.id,activityLpId);
                long reserved=book.working(activityLpId,stock.id).stream().mapToLong(o->o.reservedCash).sum();
                book.lpCashBudgets.put(stock.id,total-reserved);
            }
        }
        if(market.inlineMarketMaker()) {
            Map<Long,Long> minimumCash=new HashMap<>();
            for(var stock:book.stocks.values())
                minimumCash.put(stock.id,MarketMakerEngine.emergencyCashRequirement(botBand(stock.code,stock.reference).upperPrice()));
            long moved=book.rebalanceLiquidityCash(activityLpId,minimumCash);
            if(moved>0)market.pipelineMetrics().add("bot.liquidity.cashRebalanced",moved);
        }
        long loaded=System.nanoTime();
        if(lane.lastObservation==0 || now-lane.lastObservation>=OBSERVATION_INTERVAL_MS) {
            Map<Long,Double> fundamentals=new HashMap<>();
            jdbc.query("SELECT stock_id,hidden_fundamental FROM market_price_metrics WHERE stock_id IN ("
                    +book.stocks.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")",
                    rs->{fundamentals.put(rs.getLong(1),rs.getDouble(2));});
            for(var stock:book.stocks.values()) {
                market.priceMetrics().project(stock.id,now);
                Double fundamental=fundamentals.get(stock.id);
                if(fundamental==null)throw new org.springframework.dao.EmptyResultDataAccessException(1);
                var observed=market.priceMetrics().read(stock.id,stock.last,now,0);
                lane.observations.put(stock.id,new BotActivityEngine.Observation(observed,market.environment(stock.code),fundamental,newsFeed.observedNewsRate(stock.code,now),
                        StockMarketProfile.liquidityScale(stock.code,.65)));
                jdbc.update("UPDATE market_price_metrics SET mark_price=?,mark_updated_at=? WHERE stock_id=?",Math.round(observed.markPrice()),new Timestamp(now),stock.id);
            }
            lane.lastObservation=now;
            lane.risks.clear();
            jdbc.query("SELECT stock_id,target_inventory,max_inventory,risk_limit FROM lp_risk_books",rs->{lane.risks.put(rs.getLong(1),new MarketMakerEngine.RiskBook(0,0,rs.getInt(2),rs.getInt(3),rs.getLong(4)));});
        }
        Map<Long,Integer> recent=market.bookCacheEnabled()?lane.cache.recent(now):repository.recentCounter(lane.stocks,now).recent(now);
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
        if(retryPlan.get()!=null)activity=market.matching().compute(()->retryPlan.get().apply(book,now));
        else{
            book.capture();
            activity=market.matching().compute(()->lane.engine.microBatches(book,lane.observations,recent,now,lane.lastActivity==0?50:now-lane.lastActivity,lane.risks,activityLpId,market.currentTargetPerSymbol()));
            retryPlan.set(book.captured());
        }
        int acceptedCount=book.accepted.size();
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){market.pipelineMetrics().add("bot.orders",acceptedCount);}
            });
        else market.pipelineMetrics().add("bot.orders",acceptedCount);
        long planned=System.nanoTime();
        repository.persist(book,now);
        repository.insert("market_consensus_state","stock_id,value,observed_at,random_state",
                lane.engine.consensusCheckpoint().entrySet().stream().map(e -> new Object[]{e.getKey(),e.getValue().value(),e.getValue().at(),e.getValue().randomState()}).toList(), false,
                " ON DUPLICATE KEY UPDATE value=VALUES(value),observed_at=VALUES(observed_at),random_state=VALUES(random_state)");
        long persisted=System.nanoTime();
        market.pipelineMetrics().timing("db.persist",persisted-planned);market.pipelineMetrics().gauge("db.batch.orders",book.accepted.size());
        for(var stock:book.stocks.values())if(stock.viTrigger>0)protection.beforeTrade(stock.code,stock.viTrigger);
        if(market.bookCacheEnabled())lane.cache.saved(now);
        market.publishBookEvents(book);
        lane.lastActivity=now;
        if(!book.accepted.isEmpty()||!book.changedOrders.isEmpty())events.publishEvent(market.bookEvent(book,now));
        Map<String,Long> timings=new HashMap<>(repository.timings);
        timings.putAll(Map.of("load",loaded-started,"observe",observed-loaded,"decide",planned-observed,"persist",persisted-planned));
        lane.timings=Map.copyOf(timings);
        return activity;
        }
    }
}
