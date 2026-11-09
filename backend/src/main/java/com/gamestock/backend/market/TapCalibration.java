package com.gamestock.backend.market;

/**
 * 탭 베팅 적중률 표. 종목마다 "지금 가격에서 d틱 떨어진 가격이, L밀리초 뒤 열리는 5초 칸 동안
 * 실제로 체결됐는가"를 칸이 끝날 때마다 세어, 배당을 실제 시장 움직임에 맞춘다.
 *
 * <p>이 시장 가격은 1틱 위아래를 자주 오가지만 한쪽으로 멀리 가지는 않는다. 브라운 운동 모형은
 * 그 짧은 왕복을 변동성으로 세어 먼 칸 확률을 크게 부풀렸고, 실제 수익률이 −70%까지 나왔다.
 *
 * <p>같은 1틱이라도 방금 오가던 가격(최근 {@value #RECENT_MS}ms 안에 체결된 가격)은 거의 반드시 다시 닿고,
 * 반대쪽은 잘 안 닿는다. 위아래를 합쳐 세면 둘 다 50%가 되어 "방금 오간 쪽"만 고르는 사람이 이긴다.
 * 그래서 목표 가격이 최근 범위 안이었는지(seen)로 표를 둘로 나눈다.
 *
 * <p>녹화한 운영 체결가 25분 재생(TapReplayTest) 기준 이용자 수익률: 전체 −12%, 1틱 칸 −3%, 2틱 −10%, 3틱 −25%.
 * 종목 통합·10초 구간 4개·반감기 60분은 그 재생에서 잡음과 반응 속도를 맞춘 값이다.
 *
 * <ul>
 *   <li>표가 비어 있는 동안은 브라운 운동 확률을 사전값(가짜 시행 {@value #PRIOR_TRIALS}회)으로 섞는다.</li>
 *   <li>오래된 기록은 반감기 60분으로 흐려져 최근 시장을 따라간다.</li>
 *   <li>윌슨 상한으로 확률을 조금 높게 잡아(배당은 낮게) 표본이 적을 때 서버가 손해 보지 않게 한다.</li>
 *   <li>먼 칸일수록 확률이 낮고, 늦게 열리는 칸일수록 확률이 높도록 단조성을 맞춘다.</li>
 * </ul>
 */
final class TapCalibration {
    static final int MAX_DISTANCE = 15;
    static final long LEAD_FROM = 1_500;
    static final long LEAD_STEP = 10_000;
    static final int LEAD_BUCKETS = 4;
    static final long RECENT_MS = 15_000;
    static final double PRIOR_TRIALS = 4;
    static final double HALF_LIFE_MS = 3_600_000;
    private static final double Z = 1.0;

    /** [seen][거리][선행 구간] */
    private final double[][][] hits = new double[2][MAX_DISTANCE + 1][LEAD_BUCKETS];
    private final double[][][] trials = new double[2][MAX_DISTANCE + 1][LEAD_BUCKETS];
    private double[][][] probability;
    private double priorSigmaTicks = -1;

    synchronized void reset() {
        for (int s = 0; s < 2; s++)
            for (int d = 0; d <= MAX_DISTANCE; d++) { java.util.Arrays.fill(hits[s][d], 0); java.util.Arrays.fill(trials[s][d], 0); }
        probability = null;
    }

    static int bucket(long leadMs) {
        return (int) Math.max(0, Math.min(LEAD_BUCKETS - 1, (leadMs - LEAD_FROM) / LEAD_STEP));
    }

    static long bucketCenter(int bucket) { return LEAD_FROM + bucket * LEAD_STEP + LEAD_STEP / 2; }

    /** 목표 가격이 최근 체결 범위 [recentLow, recentHigh] 안에 있었는지. */
    static boolean seen(long target, long recentLow, long recentHigh) { return target >= recentLow && target <= recentHigh; }

    /**
     * 끝난 칸 하나를 배운다.
     * @param decisions 가격 표본 [시각, 가격]. 시간순, 앞에서 count개가 유효하다.
     */
    synchronized void learn(long slotStart, long slotMs, long low, long high, long[][] decisions, int count) {
        double decay = Math.pow(0.5, slotMs / HALF_LIFE_MS);
        for (int s = 0; s < 2; s++)
            for (int d = 1; d <= MAX_DISTANCE; d++)
                for (int b = 0; b < LEAD_BUCKETS; b++) { hits[s][d][b] *= decay; trials[s][d][b] *= decay; }
        for (int b = 0; b < LEAD_BUCKETS; b++) {
            // 한 칸·한 구간에서는 표본 하나만 쓴다. 이웃 표본은 서로 거의 같은 정보라 시행 수를 부풀린다.
            long target = slotStart - bucketCenter(b);
            int best = -1;
            for (int i = 0; i < count; i++) {
                long lead = slotStart - decisions[i][0];
                if (lead < LEAD_FROM || lead >= LEAD_FROM + LEAD_BUCKETS * LEAD_STEP || bucket(lead) != b) continue;
                if (best < 0 || Math.abs(decisions[i][0] - target) < Math.abs(decisions[best][0] - target)) best = i;
            }
            if (best < 0) continue;
            long at = decisions[best][0], price = decisions[best][1], tick = PriceLimitPolicy.tickSize(price);
            long recentLow = price, recentHigh = price;
            for (int i = best; i >= 0 && decisions[i][0] >= at - RECENT_MS; i--) {
                recentLow = Math.min(recentLow, decisions[i][1]);
                recentHigh = Math.max(recentHigh, decisions[i][1]);
            }
            for (int d = 1; d <= MAX_DISTANCE; d++) {
                long up = price + d * tick, down = price - d * tick;
                int seenUp = seen(up, recentLow, recentHigh) ? 1 : 0, seenDown = seen(down, recentLow, recentHigh) ? 1 : 0;
                trials[seenUp][d][b] += 1;
                if (high >= up) hits[seenUp][d][b] += 1;
                trials[seenDown][d][b] += 1;
                if (low <= down) hits[seenDown][d][b] += 1;
            }
        }
        probability = null;
    }

    /** d틱 떨어진 칸이 lead 뒤 열리는 5초 동안 닿을 확률(보수적으로 높게 잡은 값). */
    synchronized double probability(int distanceTicks, long leadMs, boolean seen, double sigmaTicks, long slotMs) {
        return table(sigmaTicks, slotMs)[seen ? 1 : 0][Math.max(1, Math.min(MAX_DISTANCE, distanceTicks))][bucket(leadMs)];
    }

    /** [seen][거리][선행 구간] 확률 표. 화면이 같은 값으로 칸 배당을 그린다. */
    synchronized double[][][] table(double sigmaTicks, long slotMs) {
        if (probability == null || Math.abs(sigmaTicks - priorSigmaTicks) > 0.05 * Math.max(priorSigmaTicks, 1e-9))
            rebuild(sigmaTicks, slotMs);
        return probability;
    }

    /**
     * 칸별 적중률을 사전값과 섞은 뒤, 단조성을 PAV(인접 위반 합치기)로 맞추고 윌슨 상한을 씌운다.
     * 앞 구간 최댓값을 쓰면 들쭉날쭉한 추정 중 큰 값만 골라 먼 칸 확률이 늘 부풀었다.
     */
    private void rebuild(double sigmaTicks, long slotMs) {
        double[][][] rate = new double[2][MAX_DISTANCE + 1][LEAD_BUCKETS];
        double[][][] weight = new double[2][MAX_DISTANCE + 1][LEAD_BUCKETS];
        for (int s = 0; s < 2; s++) {
            for (int d = 1; d <= MAX_DISTANCE; d++) {
                for (int b = 0; b < LEAD_BUCKETS; b++) {
                    double prior = TapOdds.touchProbability(0, sigmaTicks, bucketCenter(b), slotMs, d, d + 1, 1);
                    weight[s][d][b] = trials[s][d][b] + PRIOR_TRIALS;
                    rate[s][d][b] = (hits[s][d][b] + PRIOR_TRIALS * prior) / weight[s][d][b];
                }
                // 늦게 열리는 칸일수록 확률이 높다(비감소).
                isotonic(rate[s][d], weight[s][d], true);
            }
            for (int b = 0; b < LEAD_BUCKETS; b++) {
                // 먼 칸일수록 확률이 낮다(비증가).
                double[] values = new double[MAX_DISTANCE], weights = new double[MAX_DISTANCE];
                for (int d = 1; d <= MAX_DISTANCE; d++) { values[d - 1] = rate[s][d][b]; weights[d - 1] = weight[s][d][b]; }
                isotonic(values, weights, false);
                for (int d = 1; d <= MAX_DISTANCE; d++) { rate[s][d][b] = values[d - 1]; weight[s][d][b] = weights[d - 1]; }
            }
        }
        double[][][] next = new double[2][MAX_DISTANCE + 1][LEAD_BUCKETS];
        double z2 = Z * Z;
        for (int s = 0; s < 2; s++)
            for (int d = 1; d <= MAX_DISTANCE; d++)
                for (int b = 0; b < LEAD_BUCKETS; b++) {
                    double p = rate[s][d][b], n = weight[s][d][b];
                    next[s][d][b] = Math.min(1, (p + z2 / (2 * n) + Z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / (1 + z2 / n));
                }
        probability = next;
        priorSigmaTicks = sigmaTicks;
    }

    /** 가중 PAV. 값을 단조로 맞추고, 합쳐진 구간의 가중치(표본 수)는 구간 합으로 돌려준다. */
    static void isotonic(double[] values, double[] weights, boolean increasing) {
        int n = values.length;
        double[] blockValue = new double[n], blockWeight = new double[n];
        int[] blockSize = new int[n];
        int blocks = 0;
        for (int i = 0; i < n; i++) {
            blockValue[blocks] = values[i]; blockWeight[blocks] = weights[i]; blockSize[blocks] = 1; blocks++;
            while (blocks > 1 && (increasing ? blockValue[blocks - 2] > blockValue[blocks - 1] : blockValue[blocks - 2] < blockValue[blocks - 1])) {
                double w = blockWeight[blocks - 2] + blockWeight[blocks - 1];
                blockValue[blocks - 2] = (blockValue[blocks - 2] * blockWeight[blocks - 2] + blockValue[blocks - 1] * blockWeight[blocks - 1]) / w;
                blockWeight[blocks - 2] = w;
                blockSize[blocks - 2] += blockSize[blocks - 1];
                blocks--;
            }
        }
        for (int b = 0, i = 0; b < blocks; b++)
            for (int k = 0; k < blockSize[b]; k++, i++) { values[i] = blockValue[b]; weights[i] = blockWeight[b]; }
    }
}
