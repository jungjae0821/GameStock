package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketReadinessTest {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final MarketOwnership ownership=mock(MarketOwnership.class);
    private final BotTradingScheduler scheduler=mock(BotTradingScheduler.class);
    private final MarketReadinessController controller=new MarketReadinessController(jdbc,ownership,scheduler);
    @Test void reportsRecoveryRatherThanHealthyWhenDatabaseCheckFails(){
        when(jdbc.queryForObject("SELECT 1",Integer.class)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("reset"));
        assertEquals(503,controller.readiness().getStatusCode().value());
        assertEquals(Map.of("status","recovering"),controller.readiness().getBody());
    }
    @Test void readyRequiresTheMatchingSchedulerWhenMatchingIsEnabled(){
        when(ownership.enabled()).thenReturn(true);
        when(scheduler.runtimeStatus()).thenReturn(Map.of("started",false));
        assertEquals(503,controller.readiness().getStatusCode().value());
        when(scheduler.runtimeStatus()).thenReturn(Map.of("started",true));
        assertEquals(200,controller.readiness().getStatusCode().value());
    }
    @Test void aReadOnlyReplicaDoesNotNeedMatchingTasks(){
        assertEquals(200,controller.readiness().getStatusCode().value());
        verify(jdbc).queryForObject("SELECT 1",Integer.class);
    }
}
