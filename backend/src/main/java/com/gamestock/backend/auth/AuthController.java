package com.gamestock.backend.auth;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/google")
    public AuthService.LoginUser googleLogin(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.login(authorization);
    }

    @PostMapping("/mobile/issue")
    public AuthService.MobileCode issueMobileCode(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.issueMobileCode(authorization);
    }

    @PostMapping("/mobile/exchange")
    public AuthService.MobileToken exchangeMobileCode(@RequestBody AuthService.MobileCodeExchange request) {
        return auth.exchangeMobileCode(request);
    }
}
