package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 녹화한 실제 체결가([시각, 종목, 가격] JSON)를 탭 베팅 시세 기록에 그대로 흘려 넣고,
 * 서버가 내줄 배당으로 칸마다 걸었을 때 이용자 수익률을 잰다. -Dtap.replay=파일 일 때만 돈다.
 *
 * <p>가장 유리한 이용자를 가정해, 칸마다 걸 수 있는 가장 늦은 순간(정보가 가장 많은 때)에 건다.
 */
@EnabledIfSystemProperty(named = "tap.replay", matches = ".+")
class TapReplayTest {
    private static final long SLOT = TapPriceTape.SLOT_MS;
    private static final long CLIENT_LEAD = TapService.MIN_LEAD_MS + 350;
    private static final long WARMUP_MS = Long.getLong("tap.replay.warmup", 180_000L);

    record Bet(String code, long slot, long low, long high, int distance, long lead, double multiplier, boolean seen, double predicted) { }

    @Test
    void userReturnStaysNearTheHouseEdge() throws Exception {
        List<List<Object>> rows = new ObjectMapper().readValue(new File(System.getProperty("tap.replay")), List.class);
        List<long[]> events = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (List<Object> row : rows) {
            String code = (String) row.get(1);
            index.computeIfAbsent(code, c -> { codes.add(c); return codes.size() - 1; });
            events.add(new long[]{((Number) row.get(0)).longValue(), index.get(code), ((Number) row.get(2)).longValue()});
        }
        events.sort(Comparator.comparingLong(e -> e[0]));
        long start = events.get(0)[0], end = events.get(events.size() - 1)[0];

        TapPriceTape tape = new TapPriceTape();
        Map<String, Long> firstPrice = new HashMap<>();
        for (long[] e : events) firstPrice.putIfAbsent(codes.get((int) e[1]), e[2]);
        firstPrice.forEach((code, price) -> tape.start(code, price, start));

        Map<String, Bet> latest = new HashMap<>();
        int cursor = 0;
        for (long t = start; t <= end; t += TapPriceTape.SAMPLE_MS) {
            while (cursor < events.size() && events.get(cursor)[0] <= t) {
                long[] e = events.get(cursor++);
                tape.observe(codes.get((int) e[1]), e[2], e[0]);
            }
            tape.advance(t);
            if (t - start < WARMUP_MS || (t - start) % 1000 != 0) continue;
            for (String code : codes) {
                var snapshot = tape.snapshot(code);
                long band = snapshot.band(), price = snapshot.price();
                for (long slot = t / SLOT + 1; slot <= t / SLOT + TapService.MAX_COLUMNS; slot++) {
                    long lead = slot * SLOT - t;
                    if (lead < CLIENT_LEAD || slot * SLOT + SLOT + 1_000 > end) continue;
                    long baseLow = Math.floorDiv(price, band) * band;
                    for (int step = -6; step <= 6; step++) {
                        if (step == 0) continue;
                        long low = baseLow + step * band;
                        int distance = Math.abs(step);
                        double predicted = tape.probability(code, distance, lead, low);
                        double m = TapOdds.multiplier(predicted);
                        latest.put(code + ":" + slot + ":" + low, new Bet(code, slot, low, low + band, distance, lead, m,
                                TapCalibration.seen(low, snapshot.recentLow(), snapshot.recentHigh()), predicted));
                    }
                }
            }
        }

        // 결과는 녹화본에서 직접 계산한다(칸 시작 직전 가격 + 칸 안 체결가).
        Map<String, double[]> stats = new TreeMap<>();
        Map<String, double[]> calibration = new TreeMap<>();
        for (Bet bet : latest.values()) {
            int code = index.get(bet.code());
            long from = bet.slot() * SLOT, to = from + SLOT;
            long carried = firstPrice.get(bet.code()), lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            for (long[] e : events) {
                if (e[1] != code) continue;
                if (e[0] < from) carried = e[2];
                else if (e[0] < to) { lo = Math.min(lo, e[2]); hi = Math.max(hi, e[2]); }
                else break;
            }
            lo = Math.min(lo, carried); hi = Math.max(hi, carried);
            boolean hit = hi >= bet.low() && lo < bet.high();
            double[] c = calibration.computeIfAbsent("seen " + bet.seen() + " d" + bet.distance(), k -> new double[3]);
            c[0]++; c[1] += bet.predicted(); c[2] += hit ? 1 : 0;
            if (bet.multiplier() <= 0) continue;
            for (String key : List.of("ALL", "distance " + bet.distance(), "code " + bet.code(),
                    "seen " + bet.seen(), bet.multiplier() <= 2 ? "mult <=2" : bet.multiplier() <= 5 ? "mult 2-5" : bet.multiplier() < 30 ? "mult 5-30" : "mult 30")) {
                double[] s = stats.computeIfAbsent(key, k -> new double[4]);
                s[0]++; s[1] += hit ? 1 : 0; s[2] += hit ? bet.multiplier() : 0; s[3] += bet.multiplier();
            }
        }
        stats.forEach((key, s) -> System.out.printf("TAPREPLAY %-14s bets=%6d hit=%5.1f%% avgMult=%5.2f return=%6.1f%%%n",
                key, (long) s[0], 100 * s[1] / s[0], s[3] / s[0], 100 * (s[2] / s[0] - 1)));
        calibration.forEach((key, c) -> System.out.printf("TAPCAL %-14s cells=%6d predicted=%6.2f%% actual=%6.2f%%%n", key, (long) c[0], 100 * c[1] / c[0], 100 * c[2] / c[0]));
        double overall = stats.get("ALL")[2] / stats.get("ALL")[0] - 1;
        assertTrue(overall < 0, "이용자가 평균적으로 이기면 안 된다: " + overall);
    }
}
