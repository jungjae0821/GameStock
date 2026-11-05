package com.gamestock.backend.market;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.time.Duration;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketOwnershipTest {
    private Connection session(long id,boolean acquired)throws Exception{
        Connection connection=mock(Connection.class);
        when(connection.getCatalog()).thenReturn("test");
        when(connection.isValid(2)).thenReturn(true);
        PreparedStatement statement=mock(PreparedStatement.class);
        ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(acquired?1:0);
        when(result.getLong(1)).thenReturn(id);
        when(result.getLong(2)).thenReturn(id);
        return connection;
    }
    @Test void lostSessionIsEvictedAndTheSameOwnerReacquiresWithoutRestart()throws Exception{
        var source=mock(HikariDataSource.class);var first=session(10,true);var replacement=session(11,true);
        when(source.getConnection()).thenReturn(first,replacement);
        var owner=new MarketOwnership(new JdbcTemplate(source));
        try{
            owner.start();assertTrue(owner.available());
            when(first.isValid(2)).thenThrow(new SQLException("connection reset","08S01"));
            owner.heartbeat();assertFalse(owner.available());verify(source).evictConnection(first);
            owner.heartbeat();assertTrue(owner.available());
            verify(source,times(2)).getConnection();
        }finally{owner.close();}
        verify(source).evictConnection(replacement);
    }
    @Test void anotherOwnerKeepsAdmissionClosedAndNoPoolSessionRetainsALock()throws Exception{
        var source=mock(HikariDataSource.class);var connection=session(10,false);
        when(source.getConnection()).thenReturn(connection);
        var owner=new MarketOwnership(new JdbcTemplate(source));
        try{owner.start();assertFalse(owner.available());assertThrows(ResponseStatusException.class,owner::requireOwner);}
        finally{owner.close();}
        verify(source).evictConnection(connection);
    }
    @Test void failedAcquireCleanupCannotPublishABrokenLease()throws Exception{
        var source=mock(HikariDataSource.class);var connection=session(10,true);
        when(source.getConnection()).thenReturn(connection);
        PreparedStatement statement=connection.prepareStatement("SELECT GET_LOCK(?,0),CONNECTION_ID()");
        doThrow(new SQLException("connection lost after response")).when(statement).close();
        var owner=new MarketOwnership(new JdbcTemplate(source));
        try{assertThrows(SQLException.class,owner::start);assertFalse(owner.available());}
        finally{owner.close();}
        verify(source).evictConnection(connection);
    }
    @Test void failedAdmissionCheckInvalidatesOwnershipAndReturnsServiceUnavailable()throws Exception{
        var source=mock(HikariDataSource.class);var connection=session(10,true);
        var jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(jdbc.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,"gamestock:matching:test"))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        var owner=new MarketOwnership(jdbc);
        try{
            owner.start();var error=assertThrows(ResponseStatusException.class,owner::requireOwner);
            assertEquals(503,error.getStatusCode().value());assertFalse(owner.available());
        }finally{owner.close();}
        verify(source).evictConnection(connection);
    }
    @Test void recoveryContinuesOnItsOwnThreadWhenApplicationSchedulingIsUnavailable()throws Exception{
        var source=mock(HikariDataSource.class);var connection=session(11,true);
        when(source.getConnection()).thenThrow(new SQLException("database restarting","08001")).thenReturn(connection);
        var owner=new MarketOwnership(new JdbcTemplate(source));
        try{
            assertThrows(SQLException.class,owner::start);
            owner.startRecovery();
            await().atMost(Duration.ofSeconds(5)).until(owner::available);
        }finally{owner.close();}
    }
}
