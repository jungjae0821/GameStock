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

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidTitle(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    public record EquipRequest(String titleId) { }
}
