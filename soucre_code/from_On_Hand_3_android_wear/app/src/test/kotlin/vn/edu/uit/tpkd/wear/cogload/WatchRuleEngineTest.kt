package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRuleEngineTest {
    @Test
    fun durationFatigueRuleUsesInclusiveBoundary() {
        val beforeBoundary = evaluate(minutes = 44, extraMs = 59_000, fatigue = 10)
        assertFalse(WatchRuleEngine.RULE_V1_DURATION_FATIGUE in beforeBoundary.reasonCodes)
        assertTrue(WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS in beforeBoundary.reasonCodes)
        assertFalse(evaluate(minutes = 45, fatigue = 5).shouldSuggestBreak)
        assertReasons(evaluate(minutes = 45, fatigue = 6), WatchRuleEngine.RULE_V1_DURATION_FATIGUE)
    }

    @Test
    fun hardSixtyRuleIgnoresFatigueAndFocus() {
        assertReasons(evaluate(minutes = 60, fatigue = 1, focus = 5), WatchRuleEngine.RULE_V1_HARD_60)
    }

    @Test
    fun highFatigueLowFocusRuleStartsAtThirtyMinutes() {
        assertFalse(evaluate(minutes = 29, extraMs = 59_000, fatigue = 8, focus = 3).shouldSuggestBreak)
        assertReasons(evaluate(minutes = 30, fatigue = 8, focus = 3), WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS)
        assertFalse(evaluate(minutes = 30, fatigue = 7, focus = 3).shouldSuggestBreak)
        assertFalse(evaluate(minutes = 30, fatigue = 8, focus = 4).shouldSuggestBreak)
    }

    @Test
    fun overlappingRulesProduceOneDecisionWithAllReasons() {
        val decision = evaluate(minutes = 60, fatigue = 9, focus = 2)
        assertTrue(decision.shouldPrompt)
        assertEquals(
            setOf(
                WatchRuleEngine.RULE_V1_DURATION_FATIGUE,
                WatchRuleEngine.RULE_V1_HARD_60,
                WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS,
            ),
            decision.reasonCodes,
        )
    }

    @Test
    fun cooldownSuppressesEveryRuleUntilInclusiveExpiry() {
        val now = 1_000L
        val suppressed = evaluate(minutes = 60, nowMs = now, cooldownUntilMs = now + WatchRuleEngine.COOLDOWN_MS)
        assertTrue(suppressed.shouldSuggestBreak)
        assertFalse(suppressed.shouldPrompt)
        assertEquals(WatchRuleEngine.SUPPRESSED_COOLDOWN, suppressed.suppressionReason)
        assertTrue(WatchRuleEngine.SUPPRESSED_COOLDOWN in suppressed.reasonCodes)
        assertTrue(evaluate(minutes = 60, nowMs = now, cooldownUntilMs = now).shouldPrompt)
    }

    @Test
    fun immobilityRequiresDurationCoverageAndFreshness() {
        val now = 5_000_000L
        val eligible = MotionEvidence(30 * 60_000L, 0.80, now)
        assertReasons(
            evaluate(minutes = 45, fatigue = 1, focus = 5, nowMs = now, motion = eligible),
            WatchRuleEngine.RULE_V2_IMMOBILITY,
        )
        assertFalse(evaluate(minutes = 44, fatigue = 1, focus = 5, nowMs = now, motion = eligible).shouldSuggestBreak)
        assertFalse(evaluate(minutes = 45, fatigue = 1, focus = 5, nowMs = now, motion = eligible.copy(continuousImmobileMs = 29 * 60_000L)).shouldSuggestBreak)
        assertFalse(evaluate(minutes = 45, fatigue = 1, focus = 5, nowMs = now, motion = eligible.copy(coverage = 0.79)).shouldSuggestBreak)
        assertFalse(evaluate(minutes = 45, fatigue = 1, focus = 5, nowMs = now, motion = eligible.copy(observedAtMs = now - WatchRuleEngine.MOTION_FRESH_MS - 1)).shouldSuggestBreak)
    }

    private fun evaluate(
        minutes: Int,
        extraMs: Long = 0,
        fatigue: Int = 5,
        focus: Int = 3,
        nowMs: Long = 1_000L,
        cooldownUntilMs: Long = 0L,
        motion: MotionEvidence? = null,
    ) = WatchRuleEngine.evaluate(
        ReminderContext(
            studyDurationMs = minutes * 60_000L + extraMs,
            fatigueScore = fatigue,
            focusScore = focus,
            nowMs = nowMs,
            cooldownUntilMs = cooldownUntilMs,
            motion = motion,
        )
    )

    private fun assertReasons(decision: ReminderDecision, vararg reasons: String) {
        assertTrue(decision.shouldSuggestBreak)
        assertEquals(reasons.toSet(), decision.reasonCodes)
    }
}
