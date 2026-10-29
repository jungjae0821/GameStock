package com.gamestock.backend.market;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.gamestock.backend.market.MarketModels.ChartCandle;

@Service
public class ResearchChartService {
    static final Map<String,Long> INTERVALS=Map.of("1h",60L,"6h",300L,"12h",600L,"1d",1800L,
        "3d",3600L,"1w",7200L,"2w",21600L,"1m",86400L,"1y",604800L);
    public record Indicator(String id,String type,int period) {}
    public record Series(String id,String pane,Map<String,List<Double>> values) {}
    public record Chart(String stockCode,String range,long intervalSeconds,String volumeUnit,List<ChartCandle> candles,
                        List<Series> indicators,String asOf) {}
    private final MarketService market;
    public ResearchChartService(MarketService market) {this.market=market;}
    static long interval(String range) {
        if(range==null) throw new IllegalArgumentException("range가 필요합니다.");
        Long seconds=INTERVALS.get(range);if(seconds==null) throw new IllegalArgumentException("지원하지 않는 range입니다.");return seconds;
    }
    List<ChartCandle> candles(String code,String range,boolean closedOnly) {
        long seconds=interval(range);Instant now=Instant.now();
        return market.chartHistory(DiscussionService.normalize(code),range).stream()
            .filter(c->!closedOnly || !Instant.parse(c.recordedAt()).plusSeconds(seconds).isAfter(now)).toList();
    }
    public Chart chart(String code,String range,List<Indicator> requested) {
        if(requested==null || requested.size()>12) throw new IllegalArgumentException("지표는 최대 12개입니다.");
        Set<String> ids=new HashSet<>();
        for(var spec:requested) {
            if(spec==null || spec.id()==null || !spec.id().matches("[A-Za-z][A-Za-z0-9_]{0,31}") || !ids.add(spec.id()))
                throw new IllegalArgumentException("지표 id는 중복 없는 영문 식별자여야 합니다.");
            if(spec.type()==null || !Set.of("SMA","EMA","RSI","MACD","BBANDS","ATR").contains(spec.type()))
                throw new IllegalArgumentException("지원하지 않는 지표입니다.");
            TechnicalIndicators.period(spec.period());
        }
        List<ChartCandle> bars=candles(code,range,false);
        double[] close=field(bars,"close"),high=field(bars,"high"),low=field(bars,"low");
        List<Series> series=new ArrayList<>();
        for(var spec:requested) {
            Map<String,List<Double>> values=new LinkedHashMap<>();String pane="overlay";
            switch(spec.type()) {
                case "SMA" -> values.put("value",boxed(TechnicalIndicators.sma(close,spec.period())));
                case "EMA" -> values.put("value",boxed(TechnicalIndicators.ema(close,spec.period())));
                case "RSI" -> {pane="oscillator";values.put("value",boxed(TechnicalIndicators.rsi(close,spec.period())));}
                case "ATR" -> {pane="oscillator";values.put("value",boxed(TechnicalIndicators.atr(high,low,close,spec.period())));}
                case "BBANDS" -> {
                    double[] mid=TechnicalIndicators.sma(close,spec.period()),sd=TechnicalIndicators.stddev(close,spec.period());
                    double[] upper=new double[mid.length],lower=new double[mid.length];
                    for(int i=0;i<mid.length;i++) {upper[i]=mid[i]+2*sd[i];lower[i]=mid[i]-2*sd[i];}
                    values.put("middle",boxed(mid));values.put("upper",boxed(upper));values.put("lower",boxed(lower));
                }
                case "MACD" -> {
                    pane="oscillator";double[] fast=TechnicalIndicators.ema(close,12),slow=TechnicalIndicators.ema(close,26);
                    double[] line=new double[close.length];for(int i=0;i<line.length;i++) line[i]=fast[i]-slow[i];
                    double[] signal=TechnicalIndicators.ema(line,9),hist=new double[line.length];
                    for(int i=0;i<hist.length;i++) hist[i]=line[i]-signal[i];
                    values.put("macd",boxed(line));values.put("signal",boxed(signal));values.put("histogram",boxed(hist));
                }
                default -> throw new IllegalArgumentException("지원하지 않는 지표입니다.");
            }
            series.add(new Series(spec.id(),pane,values));
        }
        return new Chart(DiscussionService.normalize(code),range,interval(range),"TRADE_COUNT",bars,series,Instant.now().toString());
    }
    static List<Double> boxed(double[] a) {return Arrays.stream(a).mapToObj(v->Double.isFinite(v)?v:null).toList();}
    static double[] field(List<ChartCandle> bars,String name) {
        return bars.stream().mapToDouble(c->switch(name) {
            case "open"->c.openPrice();case "high"->c.highPrice();case "low"->c.lowPrice();
            case "close"->c.closePrice();case "volume"->c.volume();default->throw new IllegalArgumentException("알 수 없는 시계열: "+name);
        }).toArray();
    }
}
