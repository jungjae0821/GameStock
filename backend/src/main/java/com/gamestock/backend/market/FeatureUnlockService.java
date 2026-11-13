package com.gamestock.backend.market;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;

/**
 * Desks open with the prop desk grade: tap betting for staff, futures for associates,
 * options for managers and quant for senior managers.
 *
 * <p>Accounts waiting for the career migration ({@code legacy}) keep everything open,
 * as do admin and demo accounts. Closing an existing position is never gated.
 */
@Service
public class FeatureUnlockService {
    public enum Feature {
        FUTURES("선물", CareerGrade.ASSOCIATE),
        OPTIONS("옵션", CareerGrade.MANAGER),
        QUANT("퀀트", CareerGrade.SENIOR_MANAGER),
        TAP("탭 베팅", CareerGrade.STAFF);

        final String label;
        final CareerGrade grade;

        Feature(String label, CareerGrade grade) {
            this.label = label;
            this.grade = grade;
        }
    }

    public record Step(String label, long current, long target, boolean done) { }
    public record FeatureProgress(String id, String name, boolean unlocked, List<Step> steps, String hint, String grade,
                                  String gradeLabel) { }
    public record Progress(boolean allUnlocked, String reason, String grade, String gradeLabel, List<FeatureProgress> features) { }

    private final CareerService career;

    public FeatureUnlockService(CareerService career) { this.career = career; }

    void useClock(Clock clock) { career.useClock(clock); }

    public Progress progress(long userId) {
        CareerService.State state = career.load(userId, false);
        String reason = state.exempt() ? "STAFF" : !state.active() ? "EARLY_MEMBER" : null;
        boolean all = reason != null;
        CareerGrade grade = state.grade();
        var features = java.util.Arrays.stream(Feature.values()).map(feature -> {
            boolean open = all || grade.atLeast(feature.grade);
            var step = new Step(feature.grade.label + " 직급", Math.min(grade.ordinal(), feature.grade.ordinal()),
                    feature.grade.ordinal(), grade.atLeast(feature.grade));
            String hint = open ? "열렸어요." : subject(feature.grade.label) + " 되면 열려요. 승진 조건은 내 커리어에서 볼 수 있어요.";
            return new FeatureProgress(feature.name(), feature.label, open, List.of(step), hint, feature.grade.name(), feature.grade.label);
        }).toList();
        return new Progress(all, reason, grade.name(), grade.label, features);
    }

    public boolean unlocked(long userId, Feature feature) {
        return career.reached(userId, feature.grade);
    }

    /** Throws 403 with the grade needed when the feature is still locked. */
    public void require(long userId, Feature feature) {
        if (!unlocked(userId, feature))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    topic(feature.label) + " " + feature.grade.label + "부터 쓸 수 있어요. 승진 조건은 내 커리어에서 볼 수 있어요.");
    }

    /** Korean particles follow whether the last syllable has a final consonant: 사원이 / 대리가, 선물은 / 퀀트는. */
    private static boolean finalConsonant(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
    }

    static String subject(String word) { return word + (finalConsonant(word) ? "이" : "가"); }

    static String topic(String word) { return word + (finalConsonant(word) ? "은" : "는"); }

    /** Derivative kinds that need an unlock; ETFs trade like stocks and stay open. */
    public void requireForDerivative(long userId, String kind) {
        if ("FUTURE".equalsIgnoreCase(kind)) require(userId, Feature.FUTURES);
        else if ("OPTION".equalsIgnoreCase(kind)) require(userId, Feature.OPTIONS);
    }
}
