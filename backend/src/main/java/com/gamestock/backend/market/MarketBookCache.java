package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Reuses a committed book under the same database admission/stock locks as public orders.
 * Database revisions also invalidate copies held by other application instances.
 * The local lock lasts through transaction completion, not just through the service method.
 */
final class MarketBookCache {
    private final ReentrantLock lock=new ReentrantLock();
    private BatchOrderBook book;
    private Map<Long,Long> versions=Map.of();
    private CommittedTradeCounter counter=new CommittedTradeCounter();
    private boolean saved;
    private long hits,misses;

    static void ensureTables(JdbcTemplate db) {
        db.execute("CREATE TABLE IF NOT EXISTS market_book_revisions (stock_id BIGINT PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0)");
    }
    /** Runs after listings are seeded so every stock has a revision row. */
    static void seedRevisions(JdbcTemplate db) {
        db.update("INSERT IGNORE INTO market_book_revisions(stock_id) SELECT id FROM stocks");
    }
    static void invalidate(JdbcTemplate db,Long stock) {
        if(stock==null)db.update("UPDATE market_book_revisions SET revision=revision+1");
        else db.update("UPDATE market_book_revisions SET revision=revision+1 WHERE stock_id=?",stock);
    }
    BatchOrderBook borrow(BatchMarketRepository repository,long now,Set<Long> stocks,Set<Long> users) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Cached matching requires a transaction");
        if(TransactionSynchronizationManager.hasResource(this))throw new IllegalStateException("A lane can only be borrowed once per transaction");
        lock.lock();saved=false;
        TransactionSynchronizationManager.bindResource(this,Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED || !saved){book=null;versions=Map.of();counter=new CommittedTradeCounter();}
                try {TransactionSynchronizationManager.unbindResource(MarketBookCache.this);}
                finally {lock.unlock();}
            }
        });
        repository.lock(stocks);
        Map<Long,Long> current=repository.versions(stocks);
        if(book==null || !versions.equals(current)) {
            book=repository.loadLocked(now,stocks,users);versions=current;
            counter=repository.recentCounter(stocks,now);misses++;
        } else {book.beginBatch(now);hits++;}
        return book;
    }
    Map<Long,Integer> recent(long now) {return counter.recent(now);}
    void saved(long now) {
        Map<Long,Integer> counts=new HashMap<>();book.fills.forEach(f->counts.merge(f.buy().stock,1,Integer::sum));
        Map<Long,Long> next=new HashMap<>();versions.forEach((k,v)->next.put(k,v+1));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                versions=Map.copyOf(next);counts.forEach((stock,count)->counter.add(stock,now,count));saved=true;
            }
        });
    }
    long hits(){return hits;}
    long misses(){return misses;}
}
