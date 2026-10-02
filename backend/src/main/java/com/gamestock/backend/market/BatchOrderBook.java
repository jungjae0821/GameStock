package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.PriceLimitPolicy.*;

/** Matching state borrowed under database locks. Commit retains the book; rollback discards it.
 * The adapter persists all account, order and execution changes atomically.
 */
final class BatchOrderBook {
    record PositionKey(long user,long stock) { }
    record Holding(int quantity,int settled,long average,long realized) { }
    static final class Account {
        final long id; long openingCash; final boolean liquidityProvider,bot; long cash,minimumCash;
        Account(long id,long cash,boolean liquidityProvider) {this(id,cash,liquidityProvider,liquidityProvider);}
        Account(long id,long cash,boolean liquidityProvider,boolean bot) {this.id=id;this.cash=cash;this.openingCash=cash;this.minimumCash=cash;this.liquidityProvider=liquidityProvider;this.bot=bot;}
        void change(long delta) {cash+=delta;minimumCash=Math.min(minimumCash,cash);}
    }
    static final class Stock {
        final long id,reference,staticReference; final String code;
        long last,dynamicReference,viTrigger;
        long totalVolume,previous;
        String name,genre;
        boolean continuous;
        Stock(long id,String code,long last,long reference,long staticReference,long dynamicReference,boolean continuous) {
            this.id=id;this.code=code;this.last=last;this.reference=reference;this.staticReference=staticReference;
            this.dynamicReference=dynamicReference;this.continuous=continuous;
            this.name=code;this.genre="";this.previous=last;
        }
    }
    static final class Order {
        long id; final long user,stock,createdAt,expiresAt; final String side,type;
        final int quantity; long price,reservedCash; int remaining,reservedQuantity; String status;
        boolean persisted,compactOrigin;
        Order(long id,long user,long stock,String side,String type,long price,int quantity,int remaining,
              long reservedCash,int reservedQuantity,String status,long createdAt,long expiresAt) {
            this.id=id;this.user=user;this.stock=stock;this.side=side;this.type=type;this.price=price;this.quantity=quantity;
            this.remaining=remaining;this.reservedCash=reservedCash;this.reservedQuantity=reservedQuantity;
            this.status=status;this.createdAt=createdAt;this.expiresAt=expiresAt;
        }
        boolean market() {return type.equals("MARKET");}
    }
    record Fill(Order buy,Order sell,Order maker,Order taker,int quantity,long price,long buyerFee,long sellerFee,
                Holding buyerBefore,Holding sellerBefore) { }
    record PriceChange(long stock,long previous,long price,long volume) { }
    private static final Holding EMPTY=new Holding(0,0,0,0);
    final Map<Long,Account> accounts;
    final Map<Long,Stock> stocks;
    final Map<PositionKey,Holding> holdings;
    final Map<PositionKey,Long> holdingIds=new HashMap<>();
    final Map<Long,Long> lpCashBudgets=new HashMap<>();
    final Map<Long,List<BotLedgerJournal.Print>> lastPrints=new HashMap<>();
    final List<Order> accepted=new ArrayList<>();
    final List<Fill> fills=new ArrayList<>();
    final List<PriceChange> prices=new ArrayList<>();
    final Set<Order> changedOrders=new LinkedHashSet<>();
    final Set<Long> changedAccounts=new TreeSet<>();
    final Set<PositionKey> changedHoldings=new HashSet<>();
    private final Map<Long,NavigableSet<Order>> bids=new HashMap<>(),asks=new HashMap<>();
    private final Map<PositionKey,Integer> reservedSells=new HashMap<>();
    private final Map<PositionKey,Set<Order>> byOwner=new HashMap<>();
    private final PriorityQueue<Order> expirations=new PriorityQueue<>(Comparator.comparingLong(o->o.expiresAt));
    private long sequence;
    private List<BotBatchPlan.Intent> recording;
    private int matchingDepth;
    void capture(){recording=new ArrayList<>();}
    BotBatchPlan captured(){var plan=new BotBatchPlan(recording);recording=null;return plan;}

    BatchOrderBook(Map<Long,Account> accounts,Map<Long,Stock> stocks,Map<PositionKey,Holding> holdings,
                   List<Order> existing,long lastOrderId,long now) {
        this.accounts=accounts;this.stocks=stocks;this.holdings=holdings;this.sequence=lastOrderId;
        for(Order o:existing) {
            add(o);
            if(o.expiresAt>0 && o.expiresAt<=now) cancel(o);
        }
    }
    private NavigableSet<Order> side(long stock,String side) {
        return (side.equals("BUY")?bids:asks).computeIfAbsent(stock,k->new TreeSet<>((a,b)->{
            int c=Boolean.compare(b.market(),a.market());
            if(c==0 && !a.market()) c=side.equals("BUY")?Long.compare(b.price,a.price):Long.compare(a.price,b.price);
            if(c==0)c=Long.compare(a.createdAt,b.createdAt);
            return c==0?Long.compare(a.id,b.id):c;
        }));
    }
    private void add(Order order) {
        if(order.remaining<=0 || !order.status.equals("OPEN")) return;
        side(order.stock,order.side).add(order);
        byOwner.computeIfAbsent(new PositionKey(order.user,order.stock),k->new LinkedHashSet<>()).add(order);
        if(order.expiresAt>0)expirations.add(order);
        if(order.reservedQuantity>0) reservedSells.merge(new PositionKey(order.user,order.stock),order.reservedQuantity,Integer::sum);
    }
    /** Start another transaction on a committed book. A rolled back book is discarded by its owner. */
    void beginBatch(long now) {
        for(Order order:accepted)sequence=Math.max(sequence,order.id);
        accepted.clear();fills.clear();prices.clear();changedOrders.clear();changedAccounts.clear();changedHoldings.clear();
        accounts.values().forEach(a->{a.openingCash=a.cash;a.minimumCash=a.cash;});
        while(!expirations.isEmpty() && expirations.peek().expiresAt<=now)cancel(expirations.remove());
    }
    Holding holding(long user,long stock) {return holdings.getOrDefault(new PositionKey(user,stock),EMPTY);}
    /** Restore only a fully depleted LP lot before inline quotes are generated. */
    void restoreLiquidityInventory(long user,long stock,int quantity,long average) {
        PositionKey key=new PositionKey(user,stock);
        Holding current=holdings.get(key);
        if(current!=null && current.quantity()>0)return;
        holdings.put(key,new Holding(quantity,quantity,average,current==null?0:current.realized()));
        changedHoldings.add(key);
    }
    int available(long user,long stock) {return holding(user,stock).settled-reservedSells.getOrDefault(new PositionKey(user,stock),0);}
    List<Order> working(long user,long stock) {
        return List.copyOf(byOwner.getOrDefault(new PositionKey(user,stock),Set.of()));
    }
    void cancel(Order o) {
        if(!o.status.equals("OPEN"))return;
        if(recording!=null&&matchingDepth==0)recording.add(new BotBatchPlan.Cancel(o.id,accepted.contains(o)));
        side(o.stock,o.side).remove(o);
        Set<Order> owned=byOwner.get(new PositionKey(o.user,o.stock));if(owned!=null)owned.remove(o);
        if(o.reservedCash>0){accounts.get(o.user).change(o.reservedCash);lpCash(o.user,o.stock,o.reservedCash);changedAccounts.add(o.user);}
        if(o.reservedQuantity>0)reservedSells.merge(new PositionKey(o.user,o.stock),-o.reservedQuantity,Integer::sum);
        o.status=o.market() && o.remaining<o.quantity?"PARTIAL":"CANCELLED";
        o.remaining=0;o.reservedCash=0;o.reservedQuantity=0;changedOrders.add(o);
    }
    Order submit(long user,long stock,String side,String type,int quantity,long price,long now,long ttl) {
        Stock symbol=stocks.get(stock);Account account=accounts.get(user);
        if(symbol==null || account==null || quantity<=0 || quantity>1000 || !symbol.continuous && !type.equals("LIMIT"))return null;
        if(!side.equals("BUY") && !side.equals("SELL"))return null;
        boolean market=type.equals("MARKET");
        if(!market && (!type.equals("LIMIT") || !dailyBand(symbol.reference).contains(price) || price%tickSize(price)!=0))return null;
        long checkPrice=market?symbol.last:price;
        long gross=Math.multiplyExact(checkPrice,(long)quantity),required=Math.addExact(gross,fee(gross));
        if(side.equals("BUY") && account.cash<required || side.equals("SELL") && available(user,stock)<quantity)return null;
        long cash=side.equals("BUY")&&!market?required:0;
        int shares=side.equals("SELL")&&!market?quantity:0;
        Order order=new Order(++sequence,user,stock,side,type,market?0:price,quantity,quantity,cash,shares,"OPEN",now,market?0:now+ttl);
        if(recording!=null)recording.add(new BotBatchPlan.Submit(order.id,user,stock,side,type,quantity,price,now,order.expiresAt));
        if(cash>0){account.change(-cash);lpCash(user,stock,-cash);changedAccounts.add(user);}
        accepted.add(order);changedOrders.add(order);add(order);
        int start=fills.size();long previous=symbol.last;
        matchingDepth++;
        try{match(symbol);if(market && order.remaining>0)cancel(order);}
        finally{matchingDepth--;}
        if(fills.size()>start) {
            long volume=0;for(int i=start;i<fills.size();i++)volume+=fills.get(i).quantity;
            prices.add(new PriceChange(stock,previous,fills.get(fills.size()-1).price,volume));
            symbol.last=fills.get(fills.size()-1).price;
            symbol.totalVolume+=volume;symbol.previous=previous;
        }
        return order;
    }
    private static boolean earlier(Order a,Order b) {return a.createdAt<b.createdAt || a.createdAt==b.createdAt && a.id<b.id;}
    void matchExisting(long id){
        Stock stock=stocks.get(id);int start=fills.size();long previous=stock.last;
        match(stock);
        if(fills.size()>start){
            long volume=fills.subList(start,fills.size()).stream().mapToLong(Fill::quantity).sum();
            stock.last=fills.get(fills.size()-1).price;stock.totalVolume+=volume;stock.previous=previous;
            prices.add(new PriceChange(id,previous,stock.last,volume));
        }
    }
    MarketModels.OrderBook snapshot(long stock){
        return new MarketModels.OrderBook(stocks.get(stock).code,levels(stock,"BUY"),levels(stock,"SELL"));
    }
    private List<MarketModels.OrderBookLevel> levels(long stock,String direction){
        Map<Long,int[]> levels=new LinkedHashMap<>();
        for(Order order:side(stock,direction)){
            if(order.market())continue;
            if(!levels.containsKey(order.price)&&levels.size()>=10)break;
            int[] level=levels.computeIfAbsent(order.price,k->new int[2]);level[0]+=order.remaining;level[1]++;
        }
        return levels.entrySet().stream().map(e->new MarketModels.OrderBookLevel(e.getKey(),e.getValue()[0],e.getValue()[1])).toList();
    }
    private void match(Stock stock) {
        var buyBook=side(stock.id,"BUY");var sellBook=side(stock.id,"SELL");
        while(!buyBook.isEmpty() && !sellBook.isEmpty() && stock.continuous) {
            Order buy=buyBook.first(),sell=sellBook.first();
            if(!buy.market() && !sell.market() && buy.price<sell.price)return;
            if(buy.user==sell.user){cancel(earlier(buy,sell)?sell:buy);continue;}
            Order maker=buy.market()&&!sell.market()?sell:sell.market()&&!buy.market()?buy:earlier(buy,sell)?buy:sell;
            Order taker=maker==buy?sell:buy;
            long price=buy.market()&&sell.market()?stock.last:maker.price;
            boolean buyBot=accounts.get(buy.user).liquidityProvider,sellBot=accounts.get(sell.user).liquidityProvider;
            PriceBand marketBand=buyBot&&sellBot?dailyBand(stock.reference):marketExecutionBand(stock.last);
            if((buy.market()||sell.market())&&!marketBand.contains(price)) {
                if(buy.market()) {if(buyBot)cancel(buy);else if(sellBot)cancel(sell);else return;}
                else {if(sellBot)cancel(sell);else if(buyBot)cancel(buy);else return;}
                continue;
            }
            if((buyBot||sellBot)&&!botBand(stock.reference).contains(price)) {
                if(buyBot)cancel(buy);if(sellBot)cancel(sell);continue;
            }
            if(!dailyBand(stock.reference).contains(price))return;
            int quantity=Math.min(buy.remaining,sell.remaining);
            if(buy.reservedCash==0)quantity=affordable(accounts.get(buy.user).cash,price,quantity);
            if(quantity<=0){if(buy.market())return;cancel(buy);continue;}
            long gross=price*quantity,buyerFee=fee(gross),sellerFee=fee(gross);
            long released=release(buy,quantity);
            if(accounts.get(buy.user).cash+released<gross+buyerFee){cancel(buy);continue;}
            if(sell.reservedQuantity==0 && available(sell.user,stock.id)<quantity){cancel(sell);continue;}
            if(Math.abs(price/(double)stock.dynamicReference-1)>=.06-1e-10 || Math.abs(price/(double)stock.staticReference-1)>=.10-1e-10) {
                stock.viTrigger=price;stock.continuous=false;return;
            }
            Holding buyerBefore=holding(buy.user,stock.id),sellerBefore=holding(sell.user,stock.id);
            accounts.get(buy.user).change(released-gross-buyerFee);
            accounts.get(sell.user).change(gross-sellerFee);
            lpCash(buy.user,stock.id,released-gross-buyerFee);lpCash(sell.user,stock.id,gross-sellerFee);
            changedAccounts.add(buy.user);changedAccounts.add(sell.user);
            adjust(buy.user,stock.id,quantity,price,buyerFee,true);
            adjust(sell.user,stock.id,quantity,price,sellerFee,false);
            fills.add(new Fill(buy,sell,maker,taker,quantity,price,buyerFee,sellerFee,buyerBefore,sellerBefore));
            fill(buy,quantity,price);fill(sell,quantity,price);stock.dynamicReference=price;
        }
    }
    private void fill(Order order,int quantity,long price) {
        if(order.market()) {
            int before=order.quantity-order.remaining;
            order.price=Math.round(((double)order.price*before+(double)price*quantity)/(before+quantity));
        }
        order.remaining-=quantity;
        if(order.reservedCash>0) {long gross=order.price*order.remaining;order.reservedCash=gross+fee(gross);}
        if(order.reservedQuantity>0) {
            reservedSells.merge(new PositionKey(order.user,order.stock),-quantity,Integer::sum);
            order.reservedQuantity-=quantity;
        }
        if(order.remaining==0){side(order.stock,order.side).remove(order);byOwner.get(new PositionKey(order.user,order.stock)).remove(order);order.status="FILLED";}
        changedOrders.add(order);
    }
    private void adjust(long user,long stock,int quantity,long price,long fee,boolean buy) {
        PositionKey key=new PositionKey(user,stock);Holding old=holdings.getOrDefault(key,EMPTY);
        int next=old.quantity+(buy?quantity:-quantity);
        if(next<0)throw new IllegalStateException("Negative holding");
        long gross=price*quantity;
        long average=buy&&next>0?Math.round(((double)old.average*old.quantity+gross+fee)/next):next==0?0:old.average;
        int settled=buy?Math.min(next,old.settled+quantity):Math.max(0,old.settled-quantity);
        long realized=buy?old.realized:old.realized+gross-fee-old.average*quantity;
        holdings.put(key,new Holding(next,settled,average,realized));changedHoldings.add(key);
    }
    static long fee(long gross) {return gross<=0?0:Math.max(1,Math.round(gross*.001));}
    private void lpCash(long user,long stock,long change) {
        if(accounts.get(user).liquidityProvider)lpCashBudgets.computeIfPresent(stock,(key,cash)->cash+change);
    }
    double[] depth(long stock) {
        return depth(stock,-1);
    }
    double[] depth(long stock,long excludedUser) {
        double[] out={stocks.get(stock).last,stocks.get(stock).last,0,0};
        for(int i=0;i<2;i++) {
            var book=side(stock,i==0?"BUY":"SELL");int levels=0;long previous=-1;
            for(Order o:book) {
                if(o.market() || o.user==excludedUser)continue;
                if(previous!=o.price){if(++levels>5)break;previous=o.price;}
                if(levels==1)out[i]=o.price;
                out[i+2]+=o.remaining;
            }
        }
        return out;
    }
    private static long release(Order order,int quantity) {
        if(order.reservedCash==0)return 0;
        long gross=order.price*(order.remaining-(long)quantity);
        return order.reservedCash-gross-fee(gross);
    }
    private static int affordable(long cash,long price,int requested) {
        int low=0,high=(int)Math.min(requested,cash/price);
        while(low<high){int mid=low+(high-low+1)/2;long gross=price*mid;if(fee(gross)<=cash-gross)low=mid;else high=mid-1;}
        return low;
    }
}
