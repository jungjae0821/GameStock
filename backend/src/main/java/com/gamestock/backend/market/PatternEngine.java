package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.MarketEnvironment.clamp;

/** Phase values are signed order-flow pressure, NOT prices or price deltas. */
public final class PatternEngine {
    public enum Family { TREND, PULLBACK, BREAKOUT, REVERSAL, RANGE, MEAN_REVERSION, TRADITIONAL, WEDGE, CONTINUATION, EVENT }
    public enum Pattern {
        SLOW_UPTREND(Family.TREND,.3,.4,.3), SLOW_DOWNTREND(Family.TREND,-.3,-.4,-.3),
        STRONG_UPTREND(Family.TREND,.5,.9,1), STRONG_DOWNTREND(Family.TREND,-.5,-.9,-1), STAIR_STEP_TREND(Family.TREND,.7,.05,.8),
        BULL_PULLBACK(Family.PULLBACK,.7,-.4,.8), BEAR_RALLY(Family.PULLBACK,-.7,.4,-.8), DIP_RECOVER(Family.PULLBACK,-.5,-.1,.7),
        BOUNCE_REJECT(Family.PULLBACK,.5,.1,-.7), TREND_PAUSE(Family.PULLBACK,.5,0,.4),
        RANGE_BREAKOUT_UP(Family.BREAKOUT,0,.6,1), RANGE_BREAKOUT_DOWN(Family.BREAKOUT,0,-.6,-1),
        SQUEEZE_BREAKOUT_UP(Family.BREAKOUT,.05,.1,1.2), SQUEEZE_BREAKOUT_DOWN(Family.BREAKOUT,-.05,-.1,-1.2), VOLUME_BREAKOUT(Family.BREAKOUT,.1,.8,1.1),
        BULL_TRAP(Family.REVERSAL,.8,.4,-1), BEAR_TRAP(Family.REVERSAL,-.8,-.4,1), FAILED_RETEST(Family.REVERSAL,-.5,.3,-.9),
        SPIKE_REVERSAL(Family.REVERSAL,1.2,-.8,-.4), EXHAUSTION_REVERSAL(Family.REVERSAL,.8,.1,-.6),
        TIGHT_RANGE(Family.RANGE,.1,-.1,.05), WIDE_RANGE(Family.RANGE,.6,-.6,.3), EXPANDING_RANGE(Family.RANGE,.1,-.4,.8),
        CONTRACTING_RANGE(Family.RANGE,.8,-.4,.1), CHOPPY_RANGE(Family.RANGE,.4,-.5,.4),
        OVERSOLD_RECOVERY(Family.MEAN_REVERSION,-.2,.4,.7), OVERBOUGHT_DROP(Family.MEAN_REVERSION,.2,-.4,-.7),
        SUPPORT_BOUNCE(Family.MEAN_REVERSION,-.4,.2,.6), RESISTANCE_REJECT(Family.MEAN_REVERSION,.4,-.2,-.6), VWAP_MAGNET(Family.MEAN_REVERSION,.2,-.1,0),
        DOUBLE_TOP(Family.TRADITIONAL,.5,.3,-.8), DOUBLE_BOTTOM(Family.TRADITIONAL,-.5,-.3,.8),
        HEAD_SHOULDERS(Family.TRADITIONAL,.4,.8,-1), INVERSE_HEAD_SHOULDERS(Family.TRADITIONAL,-.4,-.8,1), ROUNDED_TURN(Family.TRADITIONAL,-.4,0,.4),
        ASCENDING_TRIANGLE(Family.WEDGE,.2,.3,.8), DESCENDING_TRIANGLE(Family.WEDGE,-.2,-.3,-.8), SYMMETRIC_TRIANGLE(Family.WEDGE,.2,-.1,.6),
        RISING_WEDGE(Family.WEDGE,.5,.3,-.8), FALLING_WEDGE(Family.WEDGE,-.5,-.3,.8),
        BULL_FLAG(Family.CONTINUATION,.8,-.2,.9), BEAR_FLAG(Family.CONTINUATION,-.8,.2,-.9),
        BULL_PENNANT(Family.CONTINUATION,.7,0,.9), BEAR_PENNANT(Family.CONTINUATION,-.7,0,-.9), CUP_HANDLE(Family.CONTINUATION,.3,-.2,.8),
        GAP_UP_CONTINUE(Family.EVENT,1,.5,.8), GAP_DOWN_CONTINUE(Family.EVENT,-1,-.5,-.8),
        GAP_UP_FADE(Family.EVENT,1,-.4,-.6), GAP_DOWN_FADE(Family.EVENT,-1,.4,.6), FLASH_CRASH_RECOVERY(Family.EVENT,-1.8,-.6,1.2);
        final Family family;
        final double early, middle, late;
        Pattern(Family f, double a, double b, double c) { family=f; early=a; middle=b; late=c; }
    }
    private record Episode(Pattern pattern, long start, long duration, double strength, double volatility,
                           double noise, double volume, double liquidity, double pressure, double fakeout) {}
    private final Random random;
    private final Map<String, Episode> episodes = new HashMap<>();
    private final Map<String, Deque<Pattern>> recent = new HashMap<>();
    public PatternEngine(long seed) { random = new Random(seed); }
    public Pattern current(String symbol) { return episodes.get(symbol).pattern(); }
    public MarketEnvironment environment(String symbol, long now) {
        Episode e = episodes.get(symbol);
        if (e == null || now >= e.start + e.duration) {
            Pattern next = select(e == null ? null : e.pattern, recent.computeIfAbsent(symbol, k -> new ArrayDeque<>()));
            long pace=StockMarketProfile.isLargeCap(symbol)?30:5;
            e = new Episode(next, now, pace*(50_000 + random.nextInt(190_001)), .3 + random.nextDouble()*1.1,
                    .6+random.nextDouble()*1.7, random.nextDouble()*.15, .6+random.nextDouble(),
                    .6+random.nextDouble()*.8, .7+random.nextDouble()*.6, random.nextDouble()*.3);
            episodes.put(symbol, e);
            Deque<Pattern> history = recent.get(symbol);
            history.addLast(next); if (history.size()>4) history.removeFirst();
        }
        double progress = clamp((now-e.start)/(double)e.duration,0,1);
        Pattern p=e.pattern;
        double pressure = (progress<.33 ? p.early : progress<.66 ? p.middle : p.late)*e.strength;
        pressure += Math.sin(progress*31+e.noise*100)*e.noise;
        if (progress>.66 && e.fakeout>.23 && p.family==Family.BREAKOUT) pressure *= -.6;
        boolean breakout=p.family==Family.BREAKOUT || p.family==Family.EVENT;
        double volume=e.volume*(breakout ? 1.8 : p==Pattern.TIGHT_RANGE ? .45 : 1);
        double volatility=e.volatility*(p==Pattern.TIGHT_RANGE ? .4 : breakout ? 1.5 : 1);
        double liquidity=e.liquidity*(p==Pattern.FLASH_CRASH_RECOVERY && progress<.66 ? .25 : 1);
        return new MarketEnvironment(pressure, p.family==Family.TREND ? .8 : .35, volatility,
                1+Math.max(0,pressure)*e.pressure, 1+Math.max(0,-pressure)*e.pressure, volume, liquidity,
                p.family==Family.MEAN_REVERSION || p.family==Family.RANGE ? .8 : .15,
                breakout ? .65 : .1, p.family==Family.REVERSAL ? .7 : e.fakeout,
                breakout ? 1.8 : p==Pattern.TIGHT_RANGE ? .5 : 1,
                breakout ? 1.6 : p==Pattern.TIGHT_RANGE ? .65 : 1);
    }
    double transitionWeight(Pattern from, Pattern to, Collection<Pattern> cooldown) {
        double weight=1;
        if (from!=null) {
            if (from.family==Family.TREND && (to.family==Family.PULLBACK || to.family==Family.CONTINUATION)) weight=6;
            if (from.family==Family.RANGE && (to.family==Family.BREAKOUT || to.family==Family.WEDGE)) weight=5;
            if (from.family==Family.BREAKOUT && (to.family==Family.TREND || to.family==Family.REVERSAL)) weight=5;
            if (from.family==Family.PULLBACK && (to.family==Family.CONTINUATION || to.family==Family.TREND)) weight=5;
            if (from.family==Family.REVERSAL && to.family==Family.MEAN_REVERSION) weight=5;
            if (Math.signum(from.late)==Math.signum(to.early)) weight*=1.7;
            if (from==to) weight*=.1;
        }
        return cooldown.contains(to) ? weight*.12 : weight;
    }
    private Pattern select(Pattern from, Collection<Pattern> cooldown) {
        double total=Arrays.stream(Pattern.values()).mapToDouble(p->transitionWeight(from,p,cooldown)).sum();
        double draw=random.nextDouble()*total;
        for (Pattern p:Pattern.values()) if ((draw-=transitionWeight(from,p,cooldown))<=0) return p;
        return Pattern.TIGHT_RANGE;
    }
}
