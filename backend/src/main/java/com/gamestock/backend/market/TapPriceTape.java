package com.gamestock.backend.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 탭 베팅 판정용 체결가 기록. 매칭 소유 인스턴스가 받은 커밋된 체결가로 칸(시간 구간)마다
 * 최저·최고가를 모으고, 화면에 보낼 짧은 가격 경로와 적중률 표(TapCalibration)를 함께 들고 있는다.
 *
 * <p>칸 높이는 호가 단위 1틱이다. 가격이 5초에 0~2틱밖에 움직이지 않아 칸을 더 크게 잡으면
 * 가격이 칸을 거의 넘지 못한다.
 *
 * <p>체결이 없던 칸은 직전 가격이 그대로 이어진 것으로 본다. 기록을 시작하기 전에 열린 칸은
 * 일부만 봤으므로 판정에도, 적중률 학습에도 쓰지 않는다. 메모리에만 있으므로 재시작하면 비워진다.
 */
final class TapPriceTape {
    static final long SLOT_MS = 5_000;
    static final long SAMPLE_MS = 250;
    private static final int SAMPLES = 480;
    private static final int SLOTS_KEPT = 240;
    /** 사전값에 쓰는 최소 변동성(틱/√초). */
    private static final double SIGMA_FLOOR_TICKS = 0.35;

    record Snapshot(long price, double sigmaPerSqrtSecond, long band, long previousBand, long tick,
                    long since, long observedUntil, List<long[]> path, double[][][] probabilities,
                    long recentLow, long recentHigh) { }

    private static final class Track {
        long last, since, observedUntil, lastSlot, sampledAt;
        final long[][] samples = new long[SAMPLES][2];
        int size, head;
        final TreeMap<Long, long[]> slots = new TreeMap<>();
        TapCalibration calibration;
        long band, previousBand;
    }

    private final Map<String, Track> tracks = new ConcurrentHashMap<>();
    /**
     * 적중률 표는 8종목이 틱 단위로 함께 쓴다. 종목·구간마다 따로 세면 표본이 적어 추정이 들쭉날쭉하고,
     * 잡음으로 높게 잡힌 칸만 배당 문턱을 넘어 열려 이용자가 체계적으로 손해를 봤다(재생 시험 −40%).
     */
    private final TapCalibration shared = new TapCalibration();

    /** 기록을 새로 시작한다. 이전 칸 기록은 버린다. */
    void start(String code, long price, long now) {
        // 종목을 다시 시작해도 다른 종목과 함께 배운 표는 그대로 둔다.
        if (price <= 0) return;
        Track track = new Track();
        track.calibration = shared;
        track.last = price;
        track.since = now;
        track.observedUntil = now;
        track.lastSlot = now / SLOT_MS;
        track.slots.put(track.lastSlot, new long[]{price, price});
        track.band = track.previousBand = PriceLimitPolicy.tickSize(price);
        sample(track, now);
        track.sampledAt = now;
        tracks.put(code, track);
    }

    void stop() { tracks.clear(); shared.reset(); }

    boolean active(String code) { return tracks.containsKey(code); }

    void observe(String code, long price, long now) {
        Track track = tracks.get(code);
        if (track == null || price <= 0) return;
        synchronized (track) {
            if (now < track.observedUntil) now = track.observedUntil;
            fill(track, now);
            long[] range = track.slots.get(track.lastSlot);
            range[0] = Math.min(range[0], price);
            range[1] = Math.max(range[1], price);
            track.last = price;
            track.observedUntil = now;
            long tick = PriceLimitPolicy.tickSize(price);
            if (tick != track.band) { track.previousBand = track.band; track.band = tick; }
        }
    }

    /** 체결이 없어도 시간이 흐른 만큼 칸을 채우고 가격 표본을 남긴다. */
    void advance(long now) {
        for (Track track : tracks.values()) {
            synchronized (track) {
                if (now < track.observedUntil) continue;
                fill(track, now);
                track.observedUntil = now;
                if (now - track.sampledAt >= SAMPLE_MS) {
                    sample(track, now);
                    track.sampledAt = now;
                }
            }
        }
    }

    /** 칸의 [최저, 최고]. 다 보지 못한 칸이면 null. */
    long[] range(String code, long slot) {
        Track track = tracks.get(code);
        if (track == null) return null;
        synchronized (track) {
            if (slot * SLOT_MS < track.since) return null;
            long[] range = track.slots.get(slot);
            return range == null ? null : range.clone();
        }
    }

    /** 이 시각까지 빠짐없이 지켜봤는지. 기록이 끊겼으면 패배로 판정하지 않는다. */
    boolean coveredUntil(String code, long at) {
        Track track = tracks.get(code);
        if (track == null) return false;
        synchronized (track) { return track.observedUntil >= at; }
    }

    /**
     * 지금 가격에서 distanceTicks틱 떨어진 target 가격이 leadMs 뒤 열리는 칸 동안 닿을 확률.
     * target이 최근 15초 안에 체결된 가격이면 따로 배운 표를 쓴다.
     */
    double probability(String code, int distanceTicks, long leadMs, long target) {
        Track track = tracks.get(code);
        if (track == null) return 1;
        synchronized (track) {
            long tick = PriceLimitPolicy.tickSize(track.last);
            long[] recent = recentRange(track);
            return track.calibration.probability(distanceTicks, leadMs, TapCalibration.seen(target, recent[0], recent[1]),
                    sigma(track) / tick, SLOT_MS);
        }
    }

    private static long[] recentRange(Track track) {
        long low = track.last, high = track.last;
        long from = track.observedUntil - TapCalibration.RECENT_MS;
        for (int i = track.size - 1; i >= 0; i--) {
            long[] sample = track.samples[(track.head - track.size + i + SAMPLES) % SAMPLES];
            if (sample[0] < from) break;
            low = Math.min(low, sample[1]);
            high = Math.max(high, sample[1]);
        }
        return new long[]{low, high};
    }

    Snapshot snapshot(String code) {
        Track track = tracks.get(code);
        if (track == null) return null;
        synchronized (track) {
            List<long[]> path = new ArrayList<>(track.size);
            for (int i = 0; i < track.size; i++) path.add(track.samples[(track.head - track.size + i + SAMPLES) % SAMPLES].clone());
            long tick = PriceLimitPolicy.tickSize(track.last);
            double sigma = sigma(track);
            double[][][] table = track.calibration.table(sigma / tick, SLOT_MS);
            double[][][] probabilities = new double[2][][];
            for (int seen = 0; seen < 2; seen++) {
                probabilities[seen] = new double[table[seen].length][];
                for (int d = 0; d < table[seen].length; d++) probabilities[seen][d] = table[seen][d].clone();
            }
            long[] recent = recentRange(track);
            return new Snapshot(track.last, sigma, track.band, track.previousBand, tick, track.since, track.observedUntil, path, probabilities,
                    recent[0], recent[1]);
        }
    }

    private void fill(Track track, long now) {
        long slot = now / SLOT_MS;
        for (long next = track.lastSlot + 1; next <= slot; next++) {
            long finished = next - 1;
            long[] range = track.slots.get(finished);
            if (range != null && finished * SLOT_MS >= track.since)
                track.calibration.learn(finished * SLOT_MS, SLOT_MS / Math.max(1, tracks.size()), range[0], range[1], ordered(track), track.size);
            track.slots.put(next, new long[]{track.last, track.last});
        }
        track.lastSlot = Math.max(track.lastSlot, slot);
        while (track.slots.size() > SLOTS_KEPT) track.slots.pollFirstEntry();
    }

    private static long[][] ordered(Track track) {
        long[][] out = new long[track.size][];
        for (int i = 0; i < track.size; i++) out[i] = track.samples[(track.head - track.size + i + SAMPLES) % SAMPLES];
        return out;
    }

    private static void sample(Track track, long now) {
        track.samples[track.head][0] = now;
        track.samples[track.head][1] = track.last;
        track.head = (track.head + 1) % SAMPLES;
        track.size = Math.min(SAMPLES, track.size + 1);
    }

    /** 원/√초. 최근 2분 실현 변동성과 호가 단위 바닥값 중 큰 값. 적중률 표가 비어 있을 때 사전값으로만 쓴다. */
    private static double sigma(Track track) {
        double squares = 0, seconds = 0;
        for (int i = 1; i < track.size; i++) {
            long[] now = track.samples[(track.head - track.size + i + SAMPLES) % SAMPLES];
            long[] before = track.samples[(track.head - track.size + i - 1 + SAMPLES) % SAMPLES];
            double move = now[1] - before[1];
            squares += move * move;
            seconds += (now[0] - before[0]) / 1000.0;
        }
        double fast = seconds > 5 ? Math.sqrt(squares / seconds) : 0;
        return Math.max(PriceLimitPolicy.tickSize(track.last) * SIGMA_FLOOR_TICKS, fast);
    }
}
