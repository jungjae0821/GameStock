package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.gamestock.backend.market.PriceLimitPolicy.*;

/**
 * How a participant bot researches the market before one action, split out of
 * {@link MarketService}: which symbols it watches this turn, what it observes on each,
 * and which funded intent wins. Not a Spring bean and holds only the research rotation
 * state. The market is passed on every call instead of kept in a field: strategies, the
 * per-tick random and the clock are replaced after construction, and a Mockito spy of
 * MarketService must be the instance these reads go through.
 * {@link MarketService#selectBotObservation} stays the entry point tests override.
 */
final class BotResearch {
    private final JdbcTemplate jdbc;
    private final MarketNewsFeed newsFeed;
    private final Map<String, Integer> researchCursors = new HashMap<>();
    private BotOpportunitySelector opportunitySelector = new BotOpportunitySelector();
    private final BotOrderPolicy botOrderPolicy = new BotOrderPolicy();

    BotResearch(JdbcTemplate jdbc, MarketNewsFeed newsFeed) {
        this.jdbc = jdbc;
        this.newsFeed = newsFeed;
    }

    /** A restart with a new seed begins every bot's research rotation again. */
    void reset() {
        researchCursors.clear();
        opportunitySelector = new BotOpportunitySelector();
    }

    Map<String, Integer> researchCursors() {return researchCursors;}

    MarketService.BotObservation select(MarketService market,BotProfile profile,long userId,List<String> codes) {
        int cursor=researchCursors.getOrDefault(profile.username(),Math.floorMod(profile.username().hashCode()^(int)market.simulationSeed(),codes.size()));
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
            quiet.put(rs.getString(1),Math.max(0,market.clock().millis()-profile.reactionLatency()
                    -(last==null?market.simulationStartedAt():last.getTime())));
        });
        long attentionAfter=30_000+Math.floorMod(profile.username().hashCode()^(int)market.simulationSeed(),20_000);
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
                """,String.class,userId,Timestamp.from(market.clock().instant().minusMillis(patient?15_000:2_000)));
        if(!working.isEmpty() && !watched.contains(working.get(0))) watched.add(working.get(0));
        if(profile.strategy().family()==BotProfile.Family.NEWS) {
            String catalyst=null;double strongest=0;
            for(String code:codes) {
                double attention=.6+new Random(market.simulationSeed()^profile.username().hashCode()^((long)code.hashCode()<<32)).nextDouble()*.8;
                double impact=Math.abs(newsFeed.recentNewsBias(code,profile.reactionLatency()).rate())*attention;
                if(impact>strongest){strongest=impact;catalyst=code;}
            }
            // News interrupts the normal research rotation, preserving the profile's reaction latency.
            if(catalyst!=null && !watched.contains(catalyst)) watched.add(0,catalyst);
        }
        List<MarketService.BotObservation> observations=new ArrayList<>();
        List<BotOpportunitySelector.Opportunity> opportunities=new ArrayList<>();
        long equity=jdbc.queryForObject("""
                SELECT cash
                  + COALESCE((SELECT SUM(reserved_cash) FROM orders WHERE user_id=u.id AND status='OPEN'),0)
                  + COALESCE((SELECT SUM(p.quantity*pm.mark_price) FROM portfolios p
                      JOIN market_price_metrics pm ON pm.stock_id=p.stock_id WHERE p.user_id=u.id),0)
                FROM users u WHERE id=?
                """,Long.class,userId);
        for(String code:watched) {
            MarketService.BotObservation candidate=observe(market,profile,userId,code,equity,codes.size());
            observations.add(candidate);
            double strength=Math.abs(candidate.score())/market.strategies().threshold(profile,candidate.metrics());
            // Keeping an existing quote is valid but must not consume the only actionable research slot.
            boolean actionable=candidate.resting().isEmpty()?candidate.decision().quantity()>0:candidate.replace();
            opportunities.add(new BotOpportunitySelector.Opportunity(code,strength,actionable,quiet.getOrDefault(code,0L)));
        }
        researchCursors.put(profile.username(),BotOpportunitySelector.nextCursor(cursor,count,codes.size()));
        String selected=opportunitySelector.select(profile.username(),opportunities);
        return observations.stream().filter(o->o.code().equals(selected)).findFirst().orElseThrow();
    }

    private MarketService.BotObservation observe(MarketService market,BotProfile profile,long userId,String code,long equity,int symbolCount) {
        PriceMetricService.Metrics metrics=BotActivityEngine.stabilizeQuietBook(market.metrics(code,profile.reactionLatency()));
        long id=market.stockId(code);
        List<BotOrderPolicy.RestingOrder> resting=jdbc.query("SELECT id,side,price,reserved_cash,created_at FROM orders WHERE user_id=? AND stock_id=? AND status='OPEN'",
                (rs,n)->new BotOrderPolicy.RestingOrder(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).getTime()),userId,id);
        MarketService.HoldingState held = market.holdingState(id, userId);
        Long age = jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MAX(created_at), CURRENT_TIMESTAMP)/1000,0) FROM trades WHERE stock_id=? AND buyer_id=?",
                Long.class, id, userId);
        BotStrategyEngine.Position position = new BotStrategyEngine.Position(held.quantity(), market.availableCash(userId)+resting.stream().mapToLong(BotOrderPolicy.RestingOrder::cash).sum(),
                held.averagePrice(), age == null ? 0 : age,
                market.strategies().targetQuantity(profile,code,market.clock().millis(),equity,symbolCount,metrics.markPrice()));
        double news = newsFeed.recentNewsBias(code, profile.reactionLatency()).rate();
        long reference = market.dailyReferencePrice(code);
        double fundamental = jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=?", Double.class, id);
        fundamental=StockMarketProfile.valuationAnchor(code,reference,fundamental,news);
        double noise=.012*(StockMarketProfile.isLargeCap(code)?StockMarketProfile.of(code).movementWeight():1);
        double estimate = market.strategies().estimate(profile, code, fundamental * (1 + market.tickRandom().nextGaussian() * noise));
        double perceived = news == 0 ? 0 : news * (1 + profile.valueError()*8)
                + market.tickRandom().nextGaussian() * Math.abs(news) * .25;
        BotStrategyEngine.Decision decision=market.strategies().decide(profile,metrics,market.environment(code),position,
                estimate,perceived,tickSize(Math.round(metrics.lastPrice())),market.marketScale(code),code,reference);
        boolean replace=resting.size()>1 || resting.stream().anyMatch(o -> market.clock().millis()-o.createdAt()>=30_000 || botOrderPolicy.shouldReplace(profile,o,decision,
                market.clock().millis(),tickSize(o.price()),o.side().equals("BUY")?metrics.bestBid():metrics.bestAsk(),
                o.side().equals("BUY")?metrics.bestAsk():metrics.bestBid()));
        return new MarketService.BotObservation(code,metrics,position,estimate,perceived,decision.score(),resting,decision,replace);
    }
}
