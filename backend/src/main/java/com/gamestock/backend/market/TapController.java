package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 탭 베팅 API. 격자 시세는 로그인 없이 보고, 거는 것은 로그인한 회원만 한다. */
@RestController
@RequestMapping("/api/tap")
public class TapController {
    private final TapService tap;
    private final AuthService auth;
    private FeatureUnlockService unlocks;

    /** Optional so tests that build this class by hand keep working without the unlock gate. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureUnlocks(FeatureUnlockService unlocks) { this.unlocks = unlocks; }

    public TapController(TapService tap, AuthService auth) {
        this.tap = tap;
        this.auth = auth;
    }

    @GetMapping("/{code}")
    public TapService.TapState state(@PathVariable String code,
                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = authorization == null || authorization.isBlank() ? null : auth.requireUser(authorization).id();
        return tap.state(code, userId);
    }

    @PostMapping("/bets")
    public TapService.PlaceResult place(@RequestBody TapService.PlaceRequest request,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = auth.requireUser(authorization).id();
        if (unlocks != null) unlocks.require(userId, FeatureUnlockService.Feature.TAP);
        return tap.place(userId, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", error.getMessage()));
    }
}
