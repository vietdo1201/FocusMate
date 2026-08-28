// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAdviceEngineTest {
    @Test
    fun v1BoundariesMapToConcreteRecoveryPlans() {
        val before45 = reminder(minutes = 44, extraMs = 59_999L, fatigue = 6)
        val at45 = reminder(minutes = 45, fatigue = 6)
        val at60 = reminder(minutes = 60, fatigue = 1, focus = 5)
        val severe = reminder(minutes = 30, fatigue = 8, focus = 3)

        assertEquals(
            SessionAdviceEngine.MAINTAIN_GOOD_SESSION,
            evaluate(reasons = before45.reasonCodes).single().code,
        )
        assertEquals(
            SessionAdviceEngine.RECOVER_DURATION_FATIGUE,
            evaluate(fatigue = 6, reasons = at45.reasonCodes).first().code,
        )
        assertEquals(
            SessionAdviceEngine.RECOVER_HARD_60,
            evaluate(reasons = at60.reasonCodes).first().code,
        )
        assertEquals(
            SessionAdviceEngine.RECOVER_HIGH_FATIGUE,
            evaluate(fatigue = 8, focus = 3, reasons = severe.reasonCodes).first().code,
        )
    }

    @Test
    fun overlappingSignalsAreMergedOrderedAndCappedAtThree() {
        val advice = evaluate(
            fatigue = 8,
            focus = 2,
            reasons = setOf(
                WatchRuleEngine.RULE_V1_DURATION_FATIGUE,
                WatchRuleEngine.RULE_V1_HARD_60,
                WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS,
                WatchRuleEngine.RULE_V2_IMMOBILITY,
                WatchRuleEngine.SUPPRESSED_COOLDOWN,
            ),
            posture = listOf(PostureStateSummary(PostureState.HEAD_DOWN, 2, 4 * 60_000L)),
            postureInsights = setOf(WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS),
            baseline = 60.0,
            postAverage = 80.0,
        )

        assertEquals(3, advice.size)
        assertEquals(
            listOf(
                SessionAdviceEngine.RECOVER_HIGH_FATIGUE,
                SessionAdviceEngine.MOVE_AFTER_IMMOBILITY,
                SessionAdviceEngine.POSTURE_HEAD_AND_BACK,
            ),
            advice.map(SessionAdviceItem::code),
        )
        assertTrue(WatchRuleEngine.RULE_V1_HARD_60 in advice.first().evidenceCodes)
        assertFalse(WatchRuleEngine.SUPPRESSED_COOLDOWN in advice.first().evidenceCodes)
    }

    @Test
    fun postureRequiresInsightAndItsOwnDurationOrEpisodeThreshold() {
        val below = evaluate(
            posture = listOf(PostureStateSummary(PostureState.TOO_CLOSE, 3, 179_999L)),
            postureInsights = setOf(WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS),
        )
        val continuous = evaluate(
            posture = listOf(PostureStateSummary(PostureState.TOO_CLOSE, 1, 180_000L)),
            postureInsights = setOf(WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS),
        )
        val repeated = evaluate(
            posture = listOf(PostureStateSummary(PostureState.LEAN_LEFT, 4, 60_000L)),
            postureInsights = setOf(WatchRuleEngine.INSIGHT_V2_POSTURE_REPEATED),
        )

        assertEquals(SessionAdviceEngine.MAINTAIN_GOOD_SESSION, below.single().code)
        assertEquals(SessionAdviceEngine.POSTURE_DISTANCE, continuous.single().code)
        assertEquals(SessionAdviceEngine.POSTURE_CENTER, repeated.single().code)
    }

    @Test
    fun postureChoosesLargestEligibleBadState() {
        val advice = evaluate(
            posture = listOf(
                PostureStateSummary(PostureState.HEAD_DOWN, 5, 4 * 60_000L),
                PostureStateSummary(PostureState.TOO_CLOSE, 2, 7 * 60_000L),
            ),
            postureInsights = setOf(
                WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS,
                WatchRuleEngine.INSIGHT_V2_POSTURE_REPEATED,
            ),
        )

        assertEquals(SessionAdviceEngine.POSTURE_DISTANCE, advice.single().code)
        assertTrue(
            "${SessionAdviceEngine.EVIDENCE_POSTURE_PREFIX}${PostureState.TOO_CLOSE.name}" in
                advice.single().evidenceCodes
        )
    }

    @Test
    fun threeYawnsInWindowAreRequiredWithoutAnAlert() {
        assertEquals(
            SessionAdviceEngine.MAINTAIN_GOOD_SESSION,
            evaluate(recentYawns = 2).single().code,
        )
        assertEquals(
            SessionAdviceEngine.RECOVER_REPEATED_YAWN,
            evaluate(recentYawns = 3).single().code,
        )
        assertEquals(
            SessionAdviceEngine.RECOVER_REPEATED_YAWN,
            evaluate(yawnAlerts = 1).single().code,
        )
    }

    @Test
    fun heartRateRequiresSamplesBothThresholdsAndCorroboration() {
        assertFalse(hasHeartAdvice(evaluate(fatigue = 6, baseline = 60.0, postAverage = 74.99)))
        assertFalse(hasHeartAdvice(evaluate(fatigue = 6, baseline = 110.0, postAverage = 125.0)))
        assertFalse(hasHeartAdvice(evaluate(fatigue = 5, baseline = 60.0, postAverage = 75.0)))
        assertTrue(hasHeartAdvice(evaluate(fatigue = 6, baseline = 60.0, postAverage = 75.0)))
        assertTrue(
            hasHeartAdvice(
                evaluate(fatigue = 5, recentYawns = 3, baseline = 100.0, postAverage = 115.0)
            )
        )
    }

    @Test
    fun heartRateRequiresFiveSamplesOnEachSideOfBaseline() {
        assertFalse(
            hasHeartAdvice(
                evaluate(fatigue = 6, baseline = 60.0, postAverage = 80.0, baselineCount = 4)
            )
        )
        assertFalse(
            hasHeartAdvice(
                evaluate(fatigue = 6, baseline = 60.0, postAverage = 80.0, postCount = 4)
            )
        )
    }

    @Test
    fun endingDuringBreakAcknowledgesCurrentBreakInsteadOfRequestingAnother() {
        val advice = evaluate(
            fatigue = 8,
            focus = 3,
            reasons = setOf(WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS),
            endedDuringBreak = true,
        )

        assertEquals(SessionAdviceEngine.COMPLETE_CURRENT_BREAK, advice.first().code)
    }

    private fun reminder(
        minutes: Int,
        extraMs: Long = 0L,
        fatigue: Int = 5,
        focus: Int = 3,
    ) = WatchRuleEngine.evaluate(
        ReminderContext(
            studyDurationMs = minutes * 60_000L + extraMs,
            fatigueScore = fatigue,
            focusScore = focus,
            nowMs = 10_000_000L,
            cooldownUntilMs = 0L,
        )
    )

    private fun evaluate(
        fatigue: Int = 5,
        focus: Int = 3,
        reasons: Set<String> = emptySet(),
        endedDuringBreak: Boolean = false,
        posture: List<PostureStateSummary> = emptyList(),
        postureInsights: Set<String> = emptySet(),
        yawnAlerts: Int = 0,
        recentYawns: Int = 0,
        baseline: Double? = null,
        postAverage: Double? = null,
        baselineCount: Int = 5,
        postCount: Int = 5,
    ): List<SessionAdviceItem> {
        val total = baselineCount + postCount
        val overall = if (baseline != null && postAverage != null && total > 0) {
            (baseline * baselineCount + postAverage * postCount) / total
        } else {
            null
        }
        return SessionAdviceEngine.evaluate(
            SessionAdviceContext(
                fatigueScore = fatigue,
                focusScore = focus,
                breakReasonCodes = reasons,
                endedDuringBreak = endedDuringBreak,
                continuousImmobileMs = 30 * 60_000L,
                postureSummaries = posture,
                postureInsightReasonCodes = postureInsights,
                yawnAlertCount = yawnAlerts,
                yawnRecentWindowCount = recentYawns,
                heartRateAverage = overall,
                heartRateBaseline = baseline,
                heartRateSampleCount = total,
                heartRateBaselineSampleCount = baselineCount,
            )
        )
    }

    private fun hasHeartAdvice(advice: List<SessionAdviceItem>) =
        advice.any { it.code == SessionAdviceEngine.RECHECK_HEART_RATE }
}
