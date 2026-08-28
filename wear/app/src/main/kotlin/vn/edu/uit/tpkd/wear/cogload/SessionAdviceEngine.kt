// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONArray
import org.json.JSONObject

data class SessionAdviceItem(
    val code: String,
    val evidenceCodes: Set<String>,
) {
    fun toJson() = JSONObject().apply {
        put("code", code)
        put("evidence_codes", JSONArray(evidenceCodes.sorted()))
    }

    companion object {
        fun fromJson(json: JSONObject): SessionAdviceItem? {
            val code = json.optString("code").takeIf(String::isNotBlank) ?: return null
            val evidence = buildSet {
                val values = json.optJSONArray("evidence_codes") ?: return@buildSet
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            return SessionAdviceItem(code, evidence)
        }
    }
}

data class SessionAdviceContext(
    val fatigueScore: Int,
    val focusScore: Int,
    val breakReasonCodes: Set<String>,
    val endedDuringBreak: Boolean,
    val continuousImmobileMs: Long,
    val postureSummaries: List<PostureStateSummary>,
    val postureInsightReasonCodes: Set<String>,
    val yawnAlertCount: Int,
    val yawnRecentWindowCount: Int,
    val heartRateAverage: Double?,
    val heartRateBaseline: Double?,
    val heartRateSampleCount: Int,
    val heartRateBaselineSampleCount: Int,
)

/**
 * Produces a small, deterministic end-of-session action list. This engine is
 * advisory only; WatchRuleEngine remains the sole break-reminder authority.
 */
object SessionAdviceEngine {
    const val RULE_VERSION = "session_advice_v1"

    const val RECOVER_HIGH_FATIGUE = "ADVICE_RECOVER_HIGH_FATIGUE"
    const val RECOVER_HARD_60 = "ADVICE_RECOVER_HARD_60"
    const val RECOVER_DURATION_FATIGUE = "ADVICE_RECOVER_DURATION_FATIGUE"
    const val RECOVER_REPEATED_YAWN = "ADVICE_RECOVER_REPEATED_YAWN"
    const val COMPLETE_CURRENT_BREAK = "ADVICE_COMPLETE_CURRENT_BREAK"
    const val MOVE_AFTER_IMMOBILITY = "ADVICE_MOVE_AFTER_IMMOBILITY"
    const val POSTURE_HEAD_AND_BACK = "ADVICE_POSTURE_HEAD_AND_BACK"
    const val POSTURE_CENTER = "ADVICE_POSTURE_CENTER"
    const val POSTURE_DISTANCE = "ADVICE_POSTURE_DISTANCE"
    const val RECHECK_HEART_RATE = "ADVICE_RECHECK_HEART_RATE"
    const val MAINTAIN_GOOD_SESSION = "ADVICE_MAINTAIN_GOOD_SESSION"

    const val EVIDENCE_REPEATED_YAWN = "EVIDENCE_REPEATED_YAWN"
    const val EVIDENCE_HEART_RATE_ELEVATED = "EVIDENCE_HEART_RATE_ELEVATED"
    const val EVIDENCE_POSTURE_PREFIX = "EVIDENCE_POSTURE_"

    const val MIN_HEART_RATE_BASELINE_SAMPLES = 5
    const val MIN_HEART_RATE_POST_BASELINE_SAMPLES = 5
    const val HEART_RATE_MIN_INCREASE_BPM = 15.0
    const val HEART_RATE_MIN_INCREASE_RATIO = 1.15
    const val REPEATED_YAWN_COUNT = 3
    const val MAX_ADVICE_ITEMS = 3

    private val breakRules = setOf(
        WatchRuleEngine.RULE_V1_DURATION_FATIGUE,
        WatchRuleEngine.RULE_V1_HARD_60,
        WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS,
        WatchRuleEngine.RULE_V2_IMMOBILITY,
    )

    fun evaluate(context: SessionAdviceContext): List<SessionAdviceItem> {
        require(context.fatigueScore in 1..10)
        require(context.focusScore in 1..5)
        val activeBreakReasons = context.breakReasonCodes intersect breakRules
        val repeatedYawn = context.yawnAlertCount > 0 ||
            context.yawnRecentWindowCount >= REPEATED_YAWN_COUNT
        val candidates = mutableListOf<SessionAdviceItem>()

        recoveryAdvice(context, activeBreakReasons, repeatedYawn)?.let(candidates::add)

        if (WatchRuleEngine.RULE_V2_IMMOBILITY in activeBreakReasons) {
            candidates += SessionAdviceItem(
                MOVE_AFTER_IMMOBILITY,
                setOf(WatchRuleEngine.RULE_V2_IMMOBILITY),
            )
        }

        postureAdvice(context)?.let(candidates::add)

        if (heartRateEvidence(context, activeBreakReasons, repeatedYawn) != null) {
            candidates += SessionAdviceItem(
                RECHECK_HEART_RATE,
                buildSet {
                    add(EVIDENCE_HEART_RATE_ELEVATED)
                    addAll(activeBreakReasons)
                    if (repeatedYawn) add(EVIDENCE_REPEATED_YAWN)
                },
            )
        }

        return candidates.take(MAX_ADVICE_ITEMS).ifEmpty {
            listOf(SessionAdviceItem(MAINTAIN_GOOD_SESSION, emptySet()))
        }
    }

    /** Returns the derived post-baseline average only when the conservative gate passes. */
    fun heartRateEvidence(
        context: SessionAdviceContext,
        activeBreakReasons: Set<String> = context.breakReasonCodes intersect breakRules,
        repeatedYawn: Boolean = context.yawnAlertCount > 0 ||
            context.yawnRecentWindowCount >= REPEATED_YAWN_COUNT,
    ): Double? {
        val baseline = context.heartRateBaseline ?: return null
        val overall = context.heartRateAverage ?: return null
        val baselineCount = context.heartRateBaselineSampleCount
        val postCount = context.heartRateSampleCount - baselineCount
        if (!baseline.isFinite() || !overall.isFinite() || baseline <= 0.0) return null
        if (baselineCount < MIN_HEART_RATE_BASELINE_SAMPLES ||
            postCount < MIN_HEART_RATE_POST_BASELINE_SAMPLES
        ) return null
        val postAverage = (
            overall * context.heartRateSampleCount - baseline * baselineCount
            ) / postCount
        if (!postAverage.isFinite()) return null
        val elevated = postAverage - baseline >= HEART_RATE_MIN_INCREASE_BPM &&
            postAverage / baseline >= HEART_RATE_MIN_INCREASE_RATIO
        val corroborated = context.fatigueScore >= 6 || repeatedYawn || activeBreakReasons.isNotEmpty()
        return postAverage.takeIf { elevated && corroborated }
    }

    private fun recoveryAdvice(
        context: SessionAdviceContext,
        activeBreakReasons: Set<String>,
        repeatedYawn: Boolean,
    ): SessionAdviceItem? {
        val evidence = buildSet {
            addAll(activeBreakReasons.filterNot { it == WatchRuleEngine.RULE_V2_IMMOBILITY })
            if (repeatedYawn) add(EVIDENCE_REPEATED_YAWN)
        }
        val code = when {
            WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS in activeBreakReasons ||
                repeatedYawn && context.fatigueScore >= 6 -> RECOVER_HIGH_FATIGUE
            WatchRuleEngine.RULE_V1_HARD_60 in activeBreakReasons -> RECOVER_HARD_60
            WatchRuleEngine.RULE_V1_DURATION_FATIGUE in activeBreakReasons -> RECOVER_DURATION_FATIGUE
            repeatedYawn -> RECOVER_REPEATED_YAWN
            else -> return null
        }
        return SessionAdviceItem(if (context.endedDuringBreak) COMPLETE_CURRENT_BREAK else code, evidence)
    }

    private fun postureAdvice(context: SessionAdviceContext): SessionAdviceItem? {
        val hasInsight = WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS in
            context.postureInsightReasonCodes ||
            WatchRuleEngine.INSIGHT_V2_POSTURE_REPEATED in context.postureInsightReasonCodes
        if (!hasInsight) return null
        val dominant = context.postureSummaries
            .asSequence()
            .filter {
                it.totalDurationMs >= PostureInsightTracker.CONTINUOUS_THRESHOLD_MS ||
                    it.episodeCount >= PostureInsightTracker.REPEATED_EPISODES
            }
            .maxWithOrNull(
                compareBy<PostureStateSummary> { it.totalDurationMs }
                    .thenBy { it.episodeCount }
            ) ?: return null
        val code = when (dominant.state) {
            PostureState.HEAD_DOWN, PostureState.SLUMPED -> POSTURE_HEAD_AND_BACK
            PostureState.LEAN_LEFT, PostureState.LEAN_RIGHT -> POSTURE_CENTER
            PostureState.TOO_CLOSE -> POSTURE_DISTANCE
            else -> return null
        }
        return SessionAdviceItem(
            code,
            context.postureInsightReasonCodes + "$EVIDENCE_POSTURE_PREFIX${dominant.state.name}",
        )
    }
}
