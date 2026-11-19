package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class BillboardController {
    private final BillboardService billboard;
    private final AuthService auth;
    private final AdminSupport access;

    public BillboardController(BillboardService billboard, AuthService auth, AdminSupport access) {
        this.billboard = billboard;
        this.auth = auth;
        this.access = access;
    }

    /** 지금 보이는 문구와 다음 예약. 로그인 없이 홈에서 읽는다. */
    @GetMapping("/api/billboard")
    public BillboardService.Board board() {
        return billboard.board();
    }

    @GetMapping("/api/billboard/mine")
    public List<BillboardService.Booking> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return billboard.mine(auth.requireUser(authorization).id());
    }

    @PostMapping("/api/billboard")
    public BillboardService.ActionResult buy(@RequestBody BillboardService.Purchase request,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.LoginUser user = auth.requireMember(authorization);
        return billboard.buy(user.id(), user.requiresNickname() ? null : user.nickname(), request);
    }

    @PostMapping("/api/billboard/{id}/report")
    public Map<String, Object> report(@PathVariable long id, @RequestBody(required = false) BillboardService.ReportRequest request,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        return billboard.report(auth.requireMember(authorization).id(), id, request);
    }

    @GetMapping("/api/admin/billboard")
    public List<Map<String, Object>> adminList(@RequestParam(defaultValue = "0") long before, @RequestParam(defaultValue = "50") int limit,
                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        access.require(authorization, "READ");
        return billboard.adminList(before, limit);
    }

    /** 내리기는 콘텐츠 권한, 돌려주기까지 하면 보상 권한도 있어야 한다. */
    @PostMapping("/api/admin/billboard/{id}/hide")
    public Map<String, Object> hide(@PathVariable long id, @RequestBody BillboardService.Hide request,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        long actor = access.require(authorization, "CONTENT");
        if (request != null && request.refund()) access.require(authorization, "REWARDS");
        return billboard.hide(actor, id, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
