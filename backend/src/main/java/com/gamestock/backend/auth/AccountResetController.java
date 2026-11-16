package com.gamestock.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
public class AccountResetController {
    private final AuthService auth;
    private final AccountResetService resetService;
    private final UserDeletionService deletion;

    public AccountResetController(AuthService auth, AccountResetService resetService, UserDeletionService deletion) {
        this.auth = auth;
        this.resetService = resetService;
        this.deletion = deletion;
    }

    public record DeletionRequest(String confirm) { }
    public record DeletionResult(boolean loginAccountDeleted) { }

    /**
     * 회원 탈퇴. 정지된 계정도 탈퇴할 수 있어야 하고, 탈퇴 도중 실패하면 계정이 잠시 정지 상태로 남으므로
     * 정지 여부와 관계없이 본인 확인만 한다.
     */
    @DeleteMapping
    public DeletionResult delete(@RequestBody(required = false) DeletionRequest request,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = auth.requireUserAllowingSuspended(authorization).id();
        return new DeletionResult(deletion.deleteSelf(userId, request == null ? null : request.confirm()));
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
