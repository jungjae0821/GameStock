package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/titles")
public class TitleController {
    private final TitleService titles;
    private final AuthService auth;

    public TitleController(TitleService titles, AuthService auth) {
        this.titles = titles;
        this.auth = auth;
    }

    @GetMapping
    public TitleService.TitleStatus titles(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return titles.status(auth.requireUser(authorization).id());
    }

    /**
     * The running week's return ranking. Public like the overall ranking; a signed-in
     * viewer also gets their own row even when it falls below the shown list.
     */
    @GetMapping("/weekly-ranking")
    public TitleService.WeeklyRanking weeklyRanking(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long viewerId = null;
        if (authorization != null && !authorization.isBlank()) {
            try {
                viewerId = auth.requireUser(authorization).id();
            } catch (RuntimeException expiredOrInvalid) {
                // An expired token still sees the public ranking, just without "me".
            }
        }
        return titles.weeklyRanking(viewerId);
    }

    /** Grants every title whose condition now holds and reports the ones not yet announced. */
    @PostMapping("/check")
    public TitleService.TitleStatus check(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return titles.check(auth.requireUser(authorization).id());
    }

    /** Equips an owned title; an empty {@code titleId} takes the equipped title off. */
    @PutMapping("/equipped")
    public TitleService.TitleStatus equip(@RequestBody EquipRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        return titles.equip(auth.requireUser(authorization).id(), request == null ? null : request.titleId());
    }

    /** Redeems a promotion code for the cash and titles an admin attached to it. */
    @PostMapping("/promo")
    public TitleService.PromoResult redeem(@RequestBody PromoRequest request,
                                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        return titles.redeem(auth.requireMember(authorization).id(), request == null ? null : request.code());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidTitle(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    public record EquipRequest(String titleId) { }
    public record PromoRequest(String code) { }
}
