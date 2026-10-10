package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.web.bind.annotation.*;

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
}
