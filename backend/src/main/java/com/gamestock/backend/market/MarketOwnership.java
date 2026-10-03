package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;

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
    private volatile Connection connection;
    private volatile long connectionId;
    private String name;
    private volatile boolean stopping;
    public MarketOwnership(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public synchronized void start() throws SQLException {
        if(!enabled)return;
        connection=jdbc.getDataSource().getConnection();
        name="gamestock:matching:"+connection.getCatalog();
        try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,0),CONNECTION_ID()")){
            statement.setString(1,name);
            try(var result=statement.executeQuery()){
                result.next();
                if(result.getInt(1)!=1){
                    connection.close();connection=null;
                    log.warn("Matching ownership is held by another replica; this instance will retry without admitting matching");
                    return;
                }
                connectionId=result.getLong(2);
            }
        }catch(SQLException|RuntimeException error){connection.close();connection=null;throw error;}
    }
    public boolean enabled(){return enabled;}
    private boolean ownsLock() throws SQLException {
        Connection current=connection;
        if(current==null||current.isClosed()||!current.isValid(2)||name==null)return false;
        try(var statement=current.prepareStatement("SELECT IS_USED_LOCK(?)")){
            statement.setString(1,name);
            try(var result=statement.executeQuery()){
                result.next();
                long owner=result.getLong(1);
                return !result.wasNull()&&owner==connectionId;
            }
        }
    }
    private void closeConnection() throws SQLException {
        if(connection==null)return;
        try{connection.close();}finally{connection=null;}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000,initialDelay=30000)
    public synchronized void heartbeat(){
        if(!enabled||stopping)return;
        try{
            if(ownsLock())return;
            closeConnection();
            start();
        }catch(SQLException|RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Matching ownership unavailable; admission remains closed ({})",error.getClass().getSimpleName());}
    }
    public void requireOwner(){
        if(!enabled||connection==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"이 인스턴스는 주문 처리 담당 서버가 아닙니다.");
        Long owner=jdbc.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,name);
        if(owner==null||owner!=connectionId)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"주문 처리 담당 서버를 복구하고 있습니다.");
    }
    @PreDestroy public synchronized void close() throws SQLException{
        stopping=true;if(connection==null)return;
        try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
        finally{connection.close();connection=null;}
    }
}
