package com.gamestock.backend.market;

import java.util.*;

/** Bounded research windows with fair service among actionable opportunities. */
public final class BotOpportunitySelector {
    public record Opportunity(String symbol, double strength, boolean actionable, long quietMillis) {
        public Opportunity(String symbol,double strength,boolean actionable) {this(symbol,strength,actionable,0);}
    }
    private final Map<String, Map<String, Integer>> waiting = new HashMap<>();

    public static int nextCursor(int cursor, int count, int symbols) {
        int stride = count;
        while (gcd(stride, symbols) != 1) stride++;
        return (cursor + stride) % symbols;
    }

    public String select(String username, List<Opportunity> candidates) {
        Map<String, Integer> ages = waiting.computeIfAbsent(username, key -> new HashMap<>());
        Opportunity selected = candidates.get(0);
        double best = Double.NEGATIVE_INFINITY;
        for (Opportunity candidate : candidates) {
            // A strong signal gets priority, but cannot indefinitely monopolize an affordable alternative.
            int age = candidate.actionable() ? ages.getOrDefault(candidate.symbol(), 0) : 0;
            // Silence attracts research only after a funded strategy decision exists; it never creates a side.
            double attention = Math.min(2,Math.max(0,candidate.quietMillis()-30_000)/30_000.0);
            double priority = candidate.actionable() ? Math.min(2, candidate.strength()) + age * .5 + attention : -1;
            ages.put(candidate.symbol(), candidate.actionable() ? Math.min(20, age + 1) : 0);
            if (priority > best) { selected = candidate; best = priority; }
        }
        ages.put(selected.symbol(), 0);
        return selected.symbol();
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return a;
    }
}
