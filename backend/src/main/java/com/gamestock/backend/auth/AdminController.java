package com.gamestock.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Minimal protected operations endpoint; market data itself remains public. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public AdminController(AuthService auth, JdbcTemplate jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return Map.of(
                "status", "ok",
                "users", count("users"),
                "openOrders", countWhere("orders", "status = 'OPEN'"),
                "trades", count("trades"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
    }
}
