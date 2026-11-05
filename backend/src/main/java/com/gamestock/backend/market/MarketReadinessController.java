package com.gamestock.backend.market;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
public class MarketReadinessController {
    private final JdbcTemplate jdbc;
    private final MarketOwnership ownership;
    private final BotTradingScheduler scheduler;
    public MarketReadinessController(JdbcTemplate jdbc,MarketOwnership ownership,BotTradingScheduler scheduler){
        this.jdbc=jdbc;this.ownership=ownership;this.scheduler=scheduler;
    }
    @GetMapping("/api/health/ready") public ResponseEntity<Map<String,Object>> readiness(){
        try{
            jdbc.queryForObject("SELECT 1",Integer.class);
            if(ownership.enabled()){
                ownership.requireOwner();
                if(!Boolean.TRUE.equals(scheduler.runtimeStatus().get("started")))return recovering();
            }
            return ResponseEntity.ok(Map.of("status","ready"));
        }catch(DataAccessException|ResponseStatusException unavailable){return recovering();}
    }
    private ResponseEntity<Map<String,Object>> recovering(){
        return ResponseEntity.status(503).body(Map.of("status","recovering"));
    }
}
