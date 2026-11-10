package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.gamestock.backend.market.DerivativeModels.*;

/**
 * 공개 차트용 읽기 전용 집계: 옵션 거래 흐름, 지수 선물 봉, 오늘의 지수 봉.
 *
 * <p>DerivativeService가 생성해 위임하는 package-private 협력 객체다. 잠금 없이
 * ConcurrentHashMap 캐시만 쓴다(분리 전과 동일).
 */
final class DerivativeChartQueries {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Supplier<Clock> clock;

    DerivativeChartQueries(JdbcTemplate jdbc, Supplier<Clock> clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------ 옵션 거래 흐름

    private static final Map<String, Long> FLOW_RANGES = Map.of("1h", 3_600L, "6h", 21_600L, "1d", 86_400L, "1w", 604_800L);
    private static final Map<String, Integer> FLOW_INTERVALS = Map.of("1m", 60, "5m", 300, "15m", 900, "1h", 3_600);
    private static final int MAX_FLOW_BUCKETS = 400;
    private static final long FLOW_CACHE_MILLIS = 5_000;
    private final Map<String, Map.Entry<Long, OptionFlow>> flowCache = new ConcurrentHashMap<>();

    /**
     * 기간을 봉 간격으로 나눠 콜·풋 체결 수량과 프리미엄, 봉마다 마지막 지수를 돌려준다.
     * 신규(OPEN)와 청산(CLOSE)만 체결로 세고, 만기 결제(EXPIRE)는 거래가 아니라 뺀다.
     * 봉 번호는 바인딩한 시작 시각과의 차이로 센다. UNIX_TIMESTAMP는 DB 세션 시간대를 타서 JVM 시각과 어긋날 수 있다.
     * 공개 시세라 같은 조건은 5초 동안 같은 결과를 재사용해 DB를 반복해서 훑지 않는다.
     */
    OptionFlow optionFlow(String range, String interval) {
        Long span = FLOW_RANGES.get(range);
        Integer step = FLOW_INTERVALS.get(interval);
        if (span == null) throw new IllegalArgumentException("기간은 1h, 6h, 1d, 1w 중 하나입니다.");
        if (step == null) throw new IllegalArgumentException("봉 간격은 1m, 5m, 15m, 1h 중 하나입니다.");
        if (span / step > MAX_FLOW_BUCKETS) throw new IllegalArgumentException("기간에 비해 봉 간격이 너무 짧습니다.");
        long nowMillis = clock.get().millis();
        String key = range + "/" + interval;
        Map.Entry<Long, OptionFlow> cached = flowCache.get(key);
        if (cached != null && nowMillis - cached.getKey() < FLOW_CACHE_MILLIS) return cached.getValue();

        long to = (nowMillis / 1000 / step + 1) * step;
        int count = (int) ((span + step - 1) / step);
        long from = to - (long) count * step;
        Timestamp start = Timestamp.from(Instant.ofEpochSecond(from));
        Timestamp end = Timestamp.from(Instant.ofEpochSecond(to));
        long[] callVolume = new long[count], putVolume = new long[count];
        long[] callPremium = new long[count], putPremium = new long[count], netPremium = new long[count];
        Double[] index = new Double[count];

        jdbc.query("""
                SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, created_at) / ?) AS slot, side, action, SUM(quantity) AS volume,
                  SUM(CASE WHEN action = 'OPEN' THEN -cash_flow - fee ELSE cash_flow + fee END) AS premium
                FROM derivative_trades
                WHERE kind = 'OPTION' AND action IN ('OPEN', 'CLOSE') AND created_at >= ? AND created_at < ?
                GROUP BY slot, side, action
                """, rs -> {
            int slot = rs.getInt("slot");
            if (slot < 0 || slot >= count) return;
            boolean call = "CALL".equals(rs.getString("side"));
            boolean open = "OPEN".equals(rs.getString("action"));
            long volume = rs.getLong("volume");
            long premium = rs.getLong("premium");
            if (call) { callVolume[slot] += volume; callPremium[slot] += premium; }
            else { putVolume[slot] += volume; putPremium[slot] += premium; }
            // 콜 매수·풋 청산은 상승 쪽, 풋 매수·콜 청산은 하락 쪽 돈이다.
            netPremium[slot] += call == open ? premium : -premium;
        }, start, step, start, end);

        jdbc.query("""
                SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, o.observed_at) / ?) AS slot, o.index_value
                FROM derivative_index_observations o JOIN (
                  SELECT MAX(observed_at) AS at FROM derivative_index_observations
                  WHERE observed_at >= ? AND observed_at < ? AND invalidated = FALSE
                  GROUP BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?)
                ) last ON last.at = o.observed_at
                """, rs -> {
            int slot = rs.getInt("slot");
            if (slot >= 0 && slot < count) index[slot] = rs.getDouble("index_value");
        }, start, step, start, end, start, step);

        List<OptionFlowBucket> buckets = new ArrayList<>(count);
        long[] totals = new long[5];
        for (int slot = 0; slot < count; slot += 1) {
            buckets.add(new OptionFlowBucket(from + (long) slot * step, index[slot], callVolume[slot], putVolume[slot],
                    callPremium[slot], putPremium[slot], netPremium[slot]));
            totals[0] += callVolume[slot]; totals[1] += putVolume[slot];
            totals[2] += callPremium[slot]; totals[3] += putPremium[slot]; totals[4] += netPremium[slot];
        }
        OptionFlow flow = new OptionFlow(range, step, from, to, totals[0], totals[1], totals[2], totals[3], totals[4], buckets);
        flowCache.put(key, Map.entry(nowMillis, flow));
        return flow;
    }

    // ------------------------------------------------------------ 지수 선물 차트

    /** 차트 기간과 봉 간격(초). 주식 차트(MarketService.chartBucketSeconds)와 같은 버튼을 쓴다. 지수 기록은 30일만 남는다. */
    private static final Map<String, long[]> FUTURE_CHART_RANGES = Map.of(
            "1h", new long[]{3_600, 60}, "6h", new long[]{21_600, 300}, "12h", new long[]{43_200, 600},
            "1d", new long[]{86_400, 1_800}, "3d", new long[]{259_200, 3_600}, "2w", new long[]{1_209_600, 21_600},
            "1m", new long[]{2_592_000, 86_400});
    private final Map<String, Map.Entry<Long, List<IndexCandle>>> futureChartCache = new ConcurrentHashMap<>();

    /**
     * 지수 관측을 봉으로 묶는다. 선물 가격은 지수와 같으므로 이 봉이 곧 선물 차트다.
     * 봉 번호는 옵션 흐름과 같이 바인딩한 시작 시각과의 차이로 센다.
     * 관측은 3초마다 쌓이므로 짧은 기간은 3초, 하루를 넘는 기간은 30초 동안 같은 결과를 재사용한다.
     */
    List<IndexCandle> futureChart(String range) {
        long[] spec = FUTURE_CHART_RANGES.get(range);
        if (spec == null) throw new IllegalArgumentException("기간은 1h, 6h, 12h, 1d, 3d, 2w, 1m 중 하나입니다.");
        long nowMillis = clock.get().millis();
        Map.Entry<Long, List<IndexCandle>> cached = futureChartCache.get(range);
        if (cached != null && nowMillis - cached.getKey() < (spec[0] > 86_400 ? 30_000 : 3_000)) return cached.getValue();

        long step = spec[1];
        long to = (nowMillis / 1000 / step + 1) * step;
        long from = to - ((spec[0] + step - 1) / step) * step;
        List<IndexCandle> candles = indexCandles(from, to, step);
        futureChartCache.put(range, Map.entry(nowMillis, candles));
        return candles;
    }

    /**
     * 한국시간 오늘 0시부터 지금까지 게임주 지수의 10분 봉. 홈 지수 그래프가 쓴다.
     * 지수는 날이 바뀌어도 이어지므로 첫 봉은 1,000이 아니라 어제 마지막 값 근처에서 시작한다.
     */
    List<IndexCandle> indexToday() {
        long nowMillis = clock.get().millis();
        Map.Entry<Long, List<IndexCandle>> cached = futureChartCache.get("today");
        if (cached != null && nowMillis - cached.getKey() < 10_000) return cached.getValue();
        long step = 600;
        long from = Instant.ofEpochMilli(nowMillis).atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toEpochSecond();
        long to = (nowMillis / 1000 / step + 1) * step;
        List<IndexCandle> candles = indexCandles(from, to, step);
        futureChartCache.put("today", Map.entry(nowMillis, candles));
        return candles;
    }

    private List<IndexCandle> indexCandles(long from, long to, long step) {
        Timestamp start = Timestamp.from(Instant.ofEpochSecond(from));
        List<IndexCandle> candles = jdbc.query("""
                SELECT slot,
                       MAX(CASE WHEN first_rank = 1 THEN index_value END) AS open_value,
                       MAX(index_value) AS high_value, MIN(index_value) AS low_value,
                       MAX(CASE WHEN last_rank = 1 THEN index_value END) AS close_value,
                       COUNT(*) AS points
                FROM (
                  SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) AS slot, index_value,
                         ROW_NUMBER() OVER (PARTITION BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) ORDER BY observed_at) AS first_rank,
                         ROW_NUMBER() OVER (PARTITION BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) ORDER BY observed_at DESC) AS last_rank
                  FROM derivative_index_observations
                  WHERE observed_at >= ? AND observed_at < ? AND invalidated = FALSE
                ) bucketed
                GROUP BY slot
                ORDER BY slot
                """, (rs, row) -> new IndexCandle(
                Instant.ofEpochSecond(from + rs.getLong("slot") * step).toString(),
                rs.getDouble("open_value"), rs.getDouble("high_value"), rs.getDouble("low_value"),
                rs.getDouble("close_value"), rs.getLong("points")),
                start, step, start, step, start, step, start, Timestamp.from(Instant.ofEpochSecond(to)));
        return candles;
    }
}
