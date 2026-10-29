package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api")
public class ResearchController {
    public record ChartRequest(String range,List<ResearchChartService.Indicator> indicators) {}
    public record State(boolean active,int revision) {}
    private final QuantProjectService projects;
    private final ResearchChartService charts;
    private final AuthService auth;
    private final Semaphore evaluations=new Semaphore(4);
    public ResearchController(QuantProjectService projects,ResearchChartService charts,AuthService auth) {
        this.projects=projects;this.charts=charts;this.auth=auth;
    }
    @PostMapping("/stocks/{code}/chart-analysis")
    public ResearchChartService.Chart chart(@PathVariable String code,@RequestBody ChartRequest request) {
        if(request==null)throw new IllegalArgumentException("차트 설정이 필요합니다.");
        return bounded(()->charts.chart(code,request.range(),request.indicators()));
    }
    @GetMapping("/quant/capabilities")
    public Map<String,Object> capabilities() {
        return Map.of("version",1,"languages",List.of("BLOCKS","SNSCRIPT"),"ranges",ResearchChartService.INTERVALS,
            "indicators",List.of("SMA","EMA","RSI","MACD","BBANDS","ATR"),"functions",QuantCompiler.FUNCTIONS,
            "maxProjects",20,"maxCandles",2000,"maxNodes",256,"liveOrderType","LIMIT");
    }
    @PostMapping("/quant/compile")
    public QuantCompiler.Compilation compile(@RequestBody QuantProjectService.Source request,
        @RequestHeader(value="Authorization",required=false) String token) {
        auth.requireUser(token);return bounded(()->projects.compile(request));
    }
    @PostMapping("/quant/backtest")
    public QuantBacktest.Result backtest(@RequestBody QuantProjectService.BacktestRequest request,
        @RequestHeader(value="Authorization",required=false) String token) {
        auth.requireUser(token);return bounded(()->projects.backtest(request));
    }
    @GetMapping("/quant/projects")
    public List<QuantProjectService.Project> list(@RequestHeader(value="Authorization",required=false) String token) {
        return projects.list(auth.requireUser(token).id());
    }
    @GetMapping("/quant/projects/{id}")
    public QuantProjectService.Project get(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) {
        return projects.get(auth.requireUser(token).id(),id);
    }
    @PostMapping("/quant/projects") @ResponseStatus(HttpStatus.CREATED)
    public QuantProjectService.Project create(@RequestBody QuantProjectService.Save request,
        @RequestHeader(value="Authorization",required=false) String token) {
        return projects.save(auth.requireUser(token).id(),null,request);
    }
    @PutMapping("/quant/projects/{id}")
    public QuantProjectService.Project update(@PathVariable long id,@RequestBody QuantProjectService.Save request,
        @RequestHeader(value="Authorization",required=false) String token) {
        return projects.save(auth.requireUser(token).id(),id,request);
    }
    @PatchMapping("/quant/projects/{id}/state")
    public QuantProjectService.Project state(@PathVariable long id,@RequestBody State request,
        @RequestHeader(value="Authorization",required=false) String token) {
        return projects.status(auth.requireUser(token).id(),id,request.active(),request.revision());
    }
    @DeleteMapping("/quant/projects/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id,@RequestHeader(value="Authorization",required=false) String token) {
        projects.delete(auth.requireUser(token).id(),id);
    }
    @GetMapping("/quant/projects/{id}/runs")
    public List<QuantProjectService.Run> runs(@PathVariable long id,@RequestParam(required=false) Long before,
        @RequestParam(defaultValue="30") int limit,@RequestHeader(value="Authorization",required=false) String token) {
        return projects.runs(auth.requireUser(token).id(),id,before,limit);
    }
    private <T> T bounded(java.util.function.Supplier<T> task) {
        if(!evaluations.tryAcquire())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"계산 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        try{return task.get();}finally{evaluations.release();}
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) {return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
}
