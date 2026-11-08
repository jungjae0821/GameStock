package com.gamestock.backend.market;

/**
 * 탭 베팅 배당. 가격을 산술 브라운 운동으로 보고, 칸의 시간 구간 동안 가격이
 * 칸의 가격 구간을 한 번이라도 지날 확률을 구한 뒤 하우스 몫을 떼어 배당으로 바꾼다.
 *
 * <p>칸이 열리는 순간의 가격 분포를 적분하고, 그 위치에서 구간 안에 칸에 닿을 확률은
 * 반사 원리(2·Φ(−거리/σ√L))로 계산한다. 실제 체결가는 배치 단위로만 관측되므로
 * 연속 경로보다 실제 확률이 조금 낮고, 그만큼 서버에 유리하다.
 *
 * <p>웹 화면(src/market/tapOdds.ts)도 같은 식으로 칸마다 배당을 그린다. 주문은 서버 값이 기준이다.
 */
final class TapOdds {
    static final double HOUSE_EDGE = 0.08;
    static final double MIN_MULTIPLIER = 1.1;
    static final double MAX_MULTIPLIER = 30.0;
    private static final int STEPS = 160;

    private TapOdds() { }

    /**
     * @param price 지금 가격(원)
     * @param sigmaPerSqrtSecond 1초당 가격 표준편차(원/√초)
     * @param leadMs 지금부터 칸이 열릴 때까지(밀리초, 이미 열렸으면 0)
     * @param lengthMs 칸이 열려 있는 남은 시간(밀리초)
     * @param low 칸의 아래 가격(포함)
     * @param high 칸의 위 가격(제외)
     * @param tick 호가 단위. 체결가가 호가 단위로만 나오므로 구간을 반 틱 내려 연속 근사한다.
     */
    static double touchProbability(double price, double sigmaPerSqrtSecond, double leadMs, double lengthMs,
                                   double low, double high, double tick) {
        double a = low - tick / 2.0, b = high - tick / 2.0;
        double sigma = Math.max(sigmaPerSqrtSecond, 1e-9);
        double spreadAtOpen = sigma * Math.sqrt(Math.max(leadMs, 0) / 1000.0);
        double spreadDuring = sigma * Math.sqrt(Math.max(lengthMs, 1) / 1000.0);
        if (spreadAtOpen < 1e-9) return reach(price, a, b, spreadDuring);
        double from = price - 6 * spreadAtOpen, to = price + 6 * spreadAtOpen;
        double dx = (to - from) / STEPS, total = 0;
        for (int i = 0; i <= STEPS; i++) {
            double x = from + i * dx;
            double z = (x - price) / spreadAtOpen;
            double weight = (i == 0 || i == STEPS) ? 0.5 : 1.0;
            total += weight * Math.exp(-0.5 * z * z) * reach(x, a, b, spreadDuring);
        }
        double probability = total * dx / (spreadAtOpen * Math.sqrt(2 * Math.PI));
        return Math.max(0, Math.min(1, probability));
    }

    /**
     * 0.1 단위로 내림한 배당. 걸 수 없는 칸이면 0.
     * 공정 배당이 상한을 넘는 칸도 열지 않는다. 상한으로 자르면 그 칸만 하우스 몫이 수십 %가 되기 때문이다.
     */
    static double multiplier(double probability) {
        if (!(probability > 0)) return 0;
        double fair = (1 - HOUSE_EDGE) / probability;
        double rounded = Math.floor(fair * 10 + 1e-9) / 10.0;
        if (rounded < MIN_MULTIPLIER || rounded > MAX_MULTIPLIER) return 0;
        return rounded;
    }

    private static double reach(double x, double a, double b, double spread) {
        if (x >= a && x <= b) return 1;
        double distance = x < a ? a - x : x - b;
        return Math.min(1, 2 * normalCdf(-distance / spread));
    }

    /** Abramowitz–Stegun 7.1.26. 웹과 같은 근사식을 써서 화면 배당과 서버 배당을 맞춘다. */
    static double normalCdf(double z) {
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return z >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }
}
