package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class StockTalkController {
    private final StockTalkService talk;
    private final AuthService auth;

    public StockTalkController(StockTalkService talk, AuthService auth) {
        this.talk = talk;
        this.auth = auth;
    }

    /** 로그인 없이도 읽는다. 로그인했으면 내 좋아요·내 글 표시를 함께 채운다. */
    @GetMapping("/api/stocks/{code}/talk")
    public StockTalkService.Page list(@PathVariable String code, @RequestParam(defaultValue = "0") long before,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        long viewer = authorization == null || authorization.isBlank() ? 0 : auth.requireUserAllowingSuspended(authorization).id();
        return talk.list(code, before, viewer);
    }

    @PostMapping("/api/stocks/{code}/talk")
    public StockTalkService.Post write(@PathVariable String code, @RequestBody StockTalkService.Write request,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.LoginUser user = auth.requireMember(authorization);
        return talk.write(user.id(), user.requiresNickname() ? null : user.nickname(), code, request);
    }

    @DeleteMapping("/api/talk/{id}")
    public Map<String, Object> delete(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return talk.delete(auth.requireUser(authorization).id(), id);
    }

    @PostMapping("/api/talk/{id}/like")
    public StockTalkService.Like like(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return talk.like(auth.requireMember(authorization).id(), id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
