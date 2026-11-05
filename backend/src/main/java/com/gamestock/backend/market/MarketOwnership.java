package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.util.concurrent.*;
import com.zaxxer.hikari.HikariDataSource;

/** A dedicated connection owns a MySQL advisory lock. Only one replica runs bots/matching.
 * Socket-only replicas set market.matching.enabled=false and install a remote EventFanout.
 * A lost connection closes admission until a fresh advisory lock is obtained. Database
 * book revisions invalidate cached state changed while another owner held the lease.
 */
@Component
public class MarketOwnership {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MarketOwnership.class);
    private final JdbcTemplate jdbc;
    @Value("${market.matching.enabled:true}") private boolean enabled=true;
    private record Lease(Connection connection,long connectionId,String name) {}
    private volatile Lease lease;
    private volatile boolean stopping;
    private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"matching-ownership-recovery");thread.setDaemon(true);return thread;
    });
    private boolean recoveryStarted;
    public MarketOwnership(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public synchronized void start() throws SQLException {
        if(!enabled||stopping||lease!=null)return;
        Connection connection=jdbc.getDataSource().getConnection();
        try{
            String name="gamestock:matching:"+connection.getCatalog();
            long connectionId;
            boolean acquired;
            try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,0),CONNECTION_ID()")){
                statement.setQueryTimeout(2);
                statement.setString(1,name);
                try(var result=statement.executeQuery()){
                    result.next();
                    acquired=result.getInt(1)==1;
                    connectionId=result.getLong(2);
                }
            }
            if(!acquired){discard(connection);return;}
            lease=new Lease(connection,connectionId,name);
            log.info("Matching ownership acquired; matching admission resumed");
        }catch(SQLException|RuntimeException error){discard(connection);throw error;}
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public synchronized void startRecovery(){
        if(enabled&&!stopping&&!recoveryStarted){
            recoveryStarted=true;
            recovery.scheduleWithFixedDelay(this::heartbeat,2,2,TimeUnit.SECONDS);
        }
    }
    public boolean enabled(){return enabled;}
    /** A cheap local gate for scheduled work. The authoritative check remains requireOwner(). */
    public boolean available(){return enabled&&lease!=null;}
    private boolean ownsLock(Lease observed) throws SQLException {
        Connection current=observed.connection();
        if(current.isClosed()||!current.isValid(2))return false;
        try(var statement=current.prepareStatement("SELECT IS_USED_LOCK(?)")){
            statement.setQueryTimeout(2);
            statement.setString(1,observed.name());
            try(var result=statement.executeQuery()){
                result.next();
                long owner=result.getLong(1);
                return !result.wasNull()&&owner==observed.connectionId();
            }
        }
    }
    private void discard(Connection connection){
        // Returning a healthy connection to the pool would retain its MySQL advisory lock.
        try{
            if(jdbc.getDataSource() instanceof HikariDataSource pool)pool.evictConnection(connection);
            else connection.close();
        }catch(SQLException|RuntimeException error){log.warn("Could not discard matching session ({})",error.getClass().getSimpleName());}
    }
    private synchronized void invalidate(Lease observed){
        if(observed==null||lease!=observed)return;
        lease=null;
        log.warn("Matching ownership lost; admission closed until the database session is reacquired");
        discard(observed.connection());
    }
    public synchronized void heartbeat(){
        if(!enabled||stopping)return;
        Lease observed=lease;
        try{
            if(observed!=null&&ownsLock(observed))return;
            invalidate(observed);
            start();
        }catch(SQLException|RuntimeException error){
            invalidate(observed);
            log.warn("Matching ownership unavailable; retrying in 2 seconds ({})",error.getClass().getSimpleName());
        }
    }
    public void requireOwner(){
        Lease observed=lease;
        if(!enabled||observed==null)throw unavailable();
        try{
            Long owner=jdbc.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,observed.name());
            if(owner!=null&&owner==observed.connectionId()&&lease==observed)return;
        }catch(org.springframework.dao.DataAccessException error){
            invalidate(observed);throw unavailable();
        }
        invalidate(observed);throw unavailable();
    }
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DB 연결을 복구하고 있습니다. 잠시 후 주문 내역을 확인해 주세요.");}
    @PreDestroy public synchronized void close() throws SQLException{
        stopping=true;recovery.shutdownNow();
        Lease observed=lease;lease=null;
        if(observed!=null){
            try(var statement=observed.connection().prepareStatement("SELECT RELEASE_LOCK(?)")){
                statement.setQueryTimeout(2);statement.setString(1,observed.name());statement.execute();
            }catch(SQLException error){log.debug("Matching session already disconnected during shutdown");}
            finally{discard(observed.connection());}
        }
    }
}
