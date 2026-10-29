package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks/{code}/community")
public class DiscussionController {
    private final DiscussionService service;
    private final AuthService auth;
    public DiscussionController(DiscussionService service,AuthService auth) {this.service=service;this.auth=auth;}
    @DeleteMapping("/entries/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code,@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) {
        var user=auth.requireUser(token);service.delete(user.id(),"ADMIN".equalsIgnoreCase(user.role()),code,id);
    }
    @GetMapping("/chat")
    public DiscussionService.Page chat(@PathVariable String code,@RequestParam(required=false) Long before,
        @RequestParam(required=false) Long after,@RequestParam(defaultValue="50") int limit) {
        return service.list(code,before,after,limit);
    }
    @PostMapping("/chat") @ResponseStatus(HttpStatus.CREATED)
    public DiscussionService.Entry send(@PathVariable String code,@RequestBody DiscussionService.Write body,
        @RequestHeader(value="Authorization",required=false) String token) {
        return service.create(auth.requireUser(token).id(),code,body);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) {return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
}
