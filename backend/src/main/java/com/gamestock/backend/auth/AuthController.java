package com.gamestock.backend.auth;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping({"/login", "/google"})
    public AuthService.LoginUser login(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.login(authorization);
    }

    /** 로그인 확인 과정에서 오늘 출석이 먼저 처리되므로 응답에는 오늘 기록이 들어 있다. */
    @GetMapping("/attendance")
    public AuthService.Attendance attendance(@RequestParam(value = "month", required = false) String month,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.attendance(auth.requireUser(authorization).id(), month);
    }

    @PostMapping("/mobile/issue")
    public AuthService.MobileCode issueMobileCode(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestBody(required = false) AuthService.MobileCodeIssue request) {
        return auth.issueMobileCode(authorization, request == null ? null : request.codeChallenge());
    }

    @PostMapping("/mobile/exchange")
    public AuthService.MobileToken exchangeMobileCode(@RequestBody AuthService.MobileCodeExchange request) {
        return auth.exchangeMobileCode(request);
    }
}
