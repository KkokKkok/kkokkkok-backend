package com.kkokkkok.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import org.junit.jupiter.api.Test;

class CumulativeScoreCalculatorTest {
    @Test
    void computesProvisionalScoreFromTheSharedExample() {
        var score = CumulativeScoreCalculator.calculate(List.of(
                new CumulativeScoreCalculator.GroupScore(.6, 5, .8),
                new CumulativeScoreCalculator.GroupScore(1.0, 1, .8),
                new CumulativeScoreCalculator.GroupScore(.2, 1, 1.0),
                new CumulativeScoreCalculator.GroupScore(1.0, 1, 1.0)));

        assertEquals(90, score.score());
        assertEquals(.45, score.repeatComponent(), .0001);
        assertEquals(.820, score.combinationComponent(), .001);
    }

    @Test
    void leavesScoreUnassessedWhenThereIsNoClassifiedEvidence() {
        var score = CumulativeScoreCalculator.calculate(List.of(
                new CumulativeScoreCalculator.GroupScore(0, 0, 0)));

        assertNull(score.score());
    }

    @Test
    void countsSameDayAsOneVisit() {
        assertEquals(0, CumulativeScoreCalculator.repeatContribution(.6, 1, 1.0));
        assertEquals(.3, CumulativeScoreCalculator.repeatContribution(.6, 2, 1.0), .0001);
    }
}
