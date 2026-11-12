package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 팬덤·시즌·공개 프로필·체결 피드·일일 예측·재기 지원금·기능 해금 API. */
@RestController
@RequestMapping("/api/engagement")
public class EngagementController {
    private final EngagementService engagement;
    private final FeatureUnlockService unlocks;
    private final AuthService auth;

    public EngagementController(EngagementService engagement, FeatureUnlockService unlocks, AuthService auth) {
        this.engagement = engagement;
        this.unlocks = unlocks;
        this.auth = auth;
    }

    @GetMapping("/me")
    public EngagementService.Me me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return engagement.me(auth.requireUser(authorization).id());
    }

    @GetMapping("/unlocks")
    public FeatureUnlockService.Progress unlocks(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return unlocks.progress(auth.requireUser(authorization).id());
    }

    @PutMapping("/fandom")
    public EngagementService.Me fandom(@RequestBody EngagementService.FandomRequest request,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        return engagement.setFandom(auth.requireUser(authorization).id(), request);
    }

    @PutMapping("/public")
    public EngagementService.Me publicProfile(@RequestBody EngagementService.PublicRequest request,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        return engagement.setPublic(auth.requireUser(authorization).id(), request);
    }

    @PostMapping("/prediction")
    public EngagementService.Me predict(@RequestBody EngagementService.PredictionRequest request,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        return engagement.predict(auth.requireUser(authorization).id(), request);
    }

    @PostMapping("/rescue")
    public EngagementService.Me rescue(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return engagement.claimRescue(auth.requireUser(authorization).id());
    }

    @GetMapping("/fandoms")
    public List<EngagementService.Fandom> fandoms() {
        return engagement.fandoms();
    }

    @GetMapping("/season")
    public ResponseEntity<EngagementService.Season> season(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long viewer = authorization == null || authorization.isBlank() ? null : auth.requireUserAllowingSuspended(authorization).id();
        var season = engagement.season(viewer);
        return season == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(season);
    }

    @GetMapping("/profiles/{nickname}")
    public EngagementService.PublicProfile profile(@PathVariable String nickname) {
        return engagement.profile(nickname);
    }

    @GetMapping("/public-nicknames")
    public List<String> publicNicknames() {
        return engagement.publicNicknames();
    }

    @GetMapping("/feed")
    public List<EngagementService.FeedItem> feed() {
        return engagement.feed();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
