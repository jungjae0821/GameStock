package com.gamestock.backend.openapi;

import com.gamestock.backend.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.gamestock.backend.openapi.OpenApiModels.*;

@RestController
@RequestMapping("/api/openapi")
public class OpenApiKeyController {
    private final AuthService auth;
    private final OpenApiKeyService keys;
    private final OpenApiRateLimiter limiter;
    public OpenApiKeyController(AuthService auth, OpenApiKeyService keys, OpenApiRateLimiter limiter) {
        this.auth = auth; this.keys = keys; this.limiter = limiter;
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource specification() { return new ClassPathResource("openapi/gamestock-v1.json"); }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKey create(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @Valid @RequestBody CreateKey request) {
        var user = auth.requireMember(authorization);
        limiter.account(user.id(), false);
        if (request.permission() == Permission.TRADE && "ADMIN".equalsIgnoreCase(user.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정은 거래용 API 키를 발급할 수 없습니다.");
        return keys.create(user.id(), request);
    }

    @GetMapping("/keys")
    public List<KeyInfo> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var user = auth.requireUserAllowingSuspended(authorization);
        limiter.account(user.id(), false);
        return keys.list(user.id());
    }

    @DeleteMapping("/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String keyId,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        var user = auth.requireUserAllowingSuspended(authorization);
        limiter.account(user.id(), false);
        keys.revoke(user.id(), keyId);
    }
}
