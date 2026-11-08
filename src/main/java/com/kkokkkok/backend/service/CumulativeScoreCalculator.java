package com.kkokkkok.backend.service;

import java.util.List;

/** Versioned relative-score rules. This version is provisional pending the team evaluation. */
public final class CumulativeScoreCalculator {
    private CumulativeScoreCalculator() {}

    public static Score calculate(List<GroupScore> groups) {
        double repeat = groups.stream().mapToDouble(GroupScore::repeatContribution).max().orElse(0);
        double combinedEvidence = groups.stream()
                .mapToDouble(group -> group.specificity() * group.placeDistanceWeight()).sum();
        double combination = 1.0 - Math.pow(0.5, combinedEvidence);
        boolean assessed = groups.stream().anyMatch(group -> group.specificity() > 0);
        Integer score = assessed ? (int) Math.round(100.0 * (1.0 - (1.0 - repeat) * (1.0 - combination))) : null;
        return new Score(score, repeat, combination);
    }

    public static double repeatContribution(double specificity, int visitDays, double placeDistanceWeight) {
        double repeat = visitDays <= 1 ? 0 : 1 - Math.pow(0.5, visitDays - 1);
        return specificity * repeat * placeDistanceWeight;
    }

    public record GroupScore(double specificity, int visitDays, double placeDistanceWeight) {
        public GroupScore {
            if (specificity < 0 || specificity > 1 || visitDays < 0
                    || placeDistanceWeight < 0 || placeDistanceWeight > 1)
                throw new IllegalArgumentException("Invalid cumulative group score input");
        }
        public double repeatContribution() {
            return CumulativeScoreCalculator.repeatContribution(specificity, visitDays, placeDistanceWeight);
        }
    }

    public record Score(Integer score, double repeatComponent, double combinationComponent) {}
}
