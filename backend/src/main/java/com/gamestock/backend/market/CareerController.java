package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 프랍 데스크 커리어(직급·승진·OJT)와 개인 지갑 API. 이전 작업은 관리자 전용이다. */
@RestController
public class CareerController {
    private final CareerService career;
    private final CareerMigrationService migration;
    private final AuthService auth;
    private final AdminSupport admin;

    public CareerController(CareerService career, CareerMigrationService migration, AuthService auth, AdminSupport admin) {
        this.career = career;
        this.migration = migration;
        this.auth = auth;
        this.admin = admin;
    }

    public record MigrationRequest(String reason) { }

    @GetMapping("/api/career")
    public CareerService.Career career(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return career.career(auth.requireUser(authorization).id());
    }

    /** 204 when nothing changed (not a trainee, or the step was already done); the app reports stock views on every open. */
    @PostMapping("/api/career/ojt/{step}")
    public ResponseEntity<CareerService.Career> ojt(@PathVariable String step,
                                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        CareerService.Career updated = career.acknowledge(auth.requireUser(authorization).id(), step);
        return updated == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(updated);
    }

    @GetMapping("/api/career/wallet")
    public CareerService.Wallet wallet(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return career.wallet(auth.requireUser(authorization).id());
    }

    @GetMapping("/api/admin/career-migration")
    public CareerMigrationService.Report preview(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin.require(authorization, "READ");
        return migration.preview();
    }

    @PostMapping("/api/admin/career-migration")
    public CareerMigrationService.Report run(@RequestBody MigrationRequest request,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return migration.run(admin.root(authorization), request == null ? null : request.reason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
