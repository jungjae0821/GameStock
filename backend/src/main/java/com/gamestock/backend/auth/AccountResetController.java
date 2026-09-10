package com.gamestock.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
public class AccountResetController {
    private final AuthService auth;
    private final AccountResetService resetService;

    public AccountResetController(AuthService auth, AccountResetService resetService) {
        this.auth = auth;
        this.resetService = resetService;
    }

    @DeleteMapping("/reset")
    public AccountResetService.ResetResult reset(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return resetService.reset(auth.requireUser(authorization).id());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidReset(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
    }
}
