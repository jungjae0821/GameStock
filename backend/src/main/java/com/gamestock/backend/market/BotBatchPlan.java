package com.gamestock.backend.market;

import java.util.*;

/** Immutable order intents retained across database retries. Never redraw prices or quantities. */
final class BotBatchPlan {
    sealed interface Intent permits Submit,Cancel{}
    record Submit(long localId,long user,long stock,String side,String type,int quantity,long price,long createdAt,long expiresAt) implements Intent{}
    record Cancel(long localId,boolean local) implements Intent{}
    private final List<Intent> intents;
    BotBatchPlan(List<Intent> intents){this.intents=List.copyOf(intents);}
    List<BotActivityEngine.Activity> apply(BatchOrderBook book,long now){
        Map<Long,BatchOrderBook.Order> ids=new HashMap<>(),created=new HashMap<>();
        for(var stock:book.stocks.values())for(var account:book.accounts.values())
            for(var order:book.working(account.id,stock.id))ids.put(order.id,order);
        for(Intent intent:intents){
            if(intent instanceof Submit submit){
                if(submit.expiresAt()>0&&submit.expiresAt()<=now)continue;
                var order=book.submit(submit.user(),submit.stock(),submit.side(),submit.type(),submit.quantity(),submit.price(),submit.createdAt(),submit.expiresAt()==0?0:submit.expiresAt()-submit.createdAt());
                if(order!=null)created.put(submit.localId(),order);
            }else if(intent instanceof Cancel cancel){var order=(cancel.local()?created:ids).get(cancel.localId());if(order!=null)book.cancel(order);}
        }
        return book.stocks.values().stream().map(stock->new BotActivityEngine.Activity(stock.id,stock.code,
            (int)book.fills.stream().filter(f->f.buy().stock==stock.id).count(),(int)book.accepted.stream().filter(o->o.stock==stock.id).count(),stock.continuous)).toList();
    }
}
