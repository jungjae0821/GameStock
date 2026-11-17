package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LedgerInvariantServiceTest {

    @Test
    void cleanLedgerStaysHealthy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        var metrics = new MarketMetrics();
        var ledger = new LedgerInvariantService(jdbc, metrics);

        assertTrue(ledger.healthy(), "점검 전에는 건강하다고 본다");
        ledger.check();
        assertTrue(ledger.healthy());
        assertEquals(0L, metrics.snapshot().get("ledger.violations"));
        verify(jdbc, times(LedgerInvariantService.CHECKS.size())).queryForObject(anyString(), eq(Long.class));
    }

    @Test
    void violationsTurnHealthOffUntilALaterCleanCheck() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(eq(LedgerInvariantService.CHECKS.get(0).sql()), eq(Long.class))).thenReturn(2L);
        var metrics = new MarketMetrics();
        var ledger = new LedgerInvariantService(jdbc, metrics);

        ledger.check();
        assertFalse(ledger.healthy());
        assertEquals(Map.of("negative_cash", 2L), ledger.lastViolations());
        assertEquals(2L, metrics.snapshot().get("ledger.violations"));

        when(jdbc.queryForObject(eq(LedgerInvariantService.CHECKS.get(0).sql()), eq(Long.class))).thenReturn(0L);
        ledger.check();
        assertTrue(ledger.healthy());
    }

    @Test
    void aFailedCheckKeepsTheLastResult() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        var metrics = new MarketMetrics();
        var ledger = new LedgerInvariantService(jdbc, metrics);
        ledger.check();
        assertFalse(ledger.healthy());

        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new QueryTimeoutException("slow"));
        ledger.check();
        assertFalse(ledger.healthy(), "DB가 느려 점검을 못 했다고 위반이 사라지지 않는다");
        assertEquals(1L, metrics.snapshot().get("ledger.check.failed"));
    }
}
