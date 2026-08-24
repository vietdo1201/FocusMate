// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

data class MotionEvidence(
    val continuousImmobileMs: Long,
    val coverage: Double,
    val observedAtMs: Long,
)

data class ReminderContext(
    val studyDurationMs: Long,
    val fatigueScore: Int,
    val focusScore: Int,
    val nowMs: Long,
    val cooldownUntilMs: Long,
    val motion: MotionEvidence? = null,
)

data class ReminderDecision(
    val shouldSuggestBreak: Boolean,
    val shouldPrompt: Boolean,
    val reasonCodes: Set<String>,
    val cooldownRemainingMs: Long,
    val suppressionReason: String? = null,
) {
    fun primaryReason(): String? = reasonCodes.firstOrNull()
}

object WatchRuleEngine {
    const val RULE_VERSION = "watch_rules_v2"
    const val RULE_V1_DURATION_FATIGUE = "RULE_V1_DURATION_FATIGUE"
    const val RULE_V1_HARD_60 = "RULE_V1_HARD_60"
    const val RULE_V1_HIGH_FATIGUE_LOW_FOCUS = "RULE_V1_HIGH_FATIGUE_LOW_FOCUS"
    const val RULE_V2_IMMOBILITY = "RULE_V2_IMMOBILITY"
    const val INSIGHT_V2_POSTURE_CONTINUOUS = "INSIGHT_V2_POSTURE_CONTINUOUS"
    const val INSIGHT_V2_POSTURE_REPEATED = "INSIGHT_V2_POSTURE_REPEATED"
    const val SUPPRESSED_COOLDOWN = "SUPPRESSED_COOLDOWN"

    const val COOLDOWN_MS = 20 * 60_000L
    const val DUPLICATE_PROMPT_GUARD_MS = 60_000L
    const val MOTION_FRESH_MS = 60_000L
    const val MIN_MOTION_COVERAGE = 0.80

    private const val MINUTE_MS = 60_000L
    private const val RULE_1_MINUTES = 45L
    private const val RULE_2_MINUTES = 60L
    private const val RULE_3_MINUTES = 30L
    private const val IMMOBILITY_MINUTES = 30L

    fun evaluate(context: ReminderContext): ReminderDecision {
        require(context.studyDurationMs >= 0L)
        require(context.fatigueScore in 1..10)
        require(context.focusScore in 1..5)
        val reasons = linkedSetOf<String>()
        if (context.studyDurationMs >= RULE_1_MINUTES * MINUTE_MS && context.fatigueScore >= 6) {
            reasons += RULE_V1_DURATION_FATIGUE
        }
        if (context.studyDurationMs >= RULE_2_MINUTES * MINUTE_MS) {
            reasons += RULE_V1_HARD_60
        }
        if (context.studyDurationMs >= RULE_3_MINUTES * MINUTE_MS &&
            context.fatigueScore >= 8 && context.focusScore <= 3
        ) {
            reasons += RULE_V1_HIGH_FATIGUE_LOW_FOCUS
        }
        val motion = context.motion
        if (context.studyDurationMs >= RULE_1_MINUTES * MINUTE_MS &&
            motion != null &&
            context.nowMs - motion.observedAtMs in 0..MOTION_FRESH_MS &&
            motion.coverage >= MIN_MOTION_COVERAGE &&
            motion.continuousImmobileMs >= IMMOBILITY_MINUTES * MINUTE_MS
        ) {
            reasons += RULE_V2_IMMOBILITY
        }
        val cooldownRemaining = (context.cooldownUntilMs - context.nowMs).coerceAtLeast(0L)
        val needed = reasons.isNotEmpty()
        if (needed && cooldownRemaining > 0L) reasons += SUPPRESSED_COOLDOWN
        return ReminderDecision(
            shouldSuggestBreak = needed,
            shouldPrompt = needed && cooldownRemaining == 0L,
            reasonCodes = reasons,
            cooldownRemainingMs = cooldownRemaining,
            suppressionReason = if (needed && cooldownRemaining > 0L) SUPPRESSED_COOLDOWN else null,
        )
    }
}
