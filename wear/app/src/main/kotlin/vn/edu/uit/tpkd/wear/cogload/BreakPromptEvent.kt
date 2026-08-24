// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Immutable deterministic decision snapshot; legacy AI fields are intentionally ignored when read. */
data class BreakPromptEvent(
    val eventId: String,
    val sessionId: String,
    val studentCode: String = "local",
    val sequence: Int = 0,
    val candidateAtMs: Long,
    val durationAtCandidateMs: Long,
    val breakTargetMinutes: Int = FocusMateRules.DEFAULT_BREAK_MINUTES,
    val subject: String = "Không áp dụng",
    val taskType: String,
    val focusAtCandidate: Int,
    val fatigueAtCandidate: Int,
    val featureObservedAtMs: Long,
    val featureSource: String,
    val ruleShouldBreak: Boolean,
    val breakNeeded: Boolean = ruleShouldBreak,
    val reasonCodes: Set<String> = emptySet(),
    val decisionSource: String = WatchRuleEngine.RULE_VERSION,
    val triggerSource: String,
    val cooldownRemainingMs: Long,
    val cooldownUntilAtCandidateMs: Long? = null,
    val suppressedByEventId: String? = null,
    val rejectedRecently: Boolean,
    val duplicateGuardPassed: Boolean,
    val promptEligible: Boolean,
    val prompted: Boolean,
    val promptedAtMs: Long? = null,
    val deliveryChannel: String,
    val suppressionReason: String? = null,
    val respondedAtMs: Long? = null,
    val response: String? = null,
    val declineReasonCode: String? = null,
    val cooldownUntilMs: Long? = null,
    val synthetic: Boolean = false,
    val schemaVersion: String = SCHEMA_VERSION,
    val ruleVersion: String = RULE_VERSION,
    val expiresOn: String = RetentionPolicy.expiresOnText(candidateAtMs),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("session_id", sessionId)
        put("sequence", sequence)
        put("candidate_at_ms", candidateAtMs)
        put("duration_at_candidate_ms", durationAtCandidateMs)
        put("break_target_minutes", breakTargetMinutes)
        put("task_type", taskType)
        put("focus_at_candidate", focusAtCandidate)
        put("fatigue_at_candidate", fatigueAtCandidate)
        put("feature_observed_at_ms", featureObservedAtMs)
        put("feature_source", featureSource)
        put("rule_should_break", ruleShouldBreak)
        put("break_needed", breakNeeded)
        put("reason_codes", JSONArray(reasonCodes.sorted()))
        put("decision_source", decisionSource)
        put("trigger_source", triggerSource)
        put("cooldown_remaining_ms", cooldownRemainingMs)
        put("cooldown_until_at_candidate_ms", cooldownUntilAtCandidateMs ?: JSONObject.NULL)
        put("suppressed_by_event_id", suppressedByEventId ?: JSONObject.NULL)
        put("rejected_recently", rejectedRecently)
        put("duplicate_guard_passed", duplicateGuardPassed)
        put("prompt_eligible", promptEligible)
        put("prompted", prompted)
        put("prompted_at_ms", promptedAtMs ?: JSONObject.NULL)
        put("delivery_channel", deliveryChannel)
        put("suppression_reason", suppressionReason ?: JSONObject.NULL)
        put("responded_at_ms", respondedAtMs ?: JSONObject.NULL)
        put("response", response ?: JSONObject.NULL)
        put("decline_reason_code", declineReasonCode ?: JSONObject.NULL)
        put("cooldown_until_ms", cooldownUntilMs ?: JSONObject.NULL)
        put("synthetic", synthetic)
        put("schema_version", schemaVersion)
        put("rule_version", ruleVersion)
        put("expires_on", expiresOn)
    }

    companion object {
        const val SCHEMA_VERSION = "focusmate_prompt_event_v8"
        const val RULE_VERSION = WatchRuleEngine.RULE_VERSION
        const val CHANNEL_FOREGROUND = "foreground_dialog"
        const val CHANNEL_NOTIFICATION = "background_notification"
        const val CHANNEL_NONE = "none"
        const val RESPONSE_ACCEPTED = "accepted"
        const val RESPONSE_DECLINED = "declined"
        const val SUPPRESSION_NOTIFICATIONS_DISABLED = "notifications_disabled"
        const val SUPPRESSION_COOLDOWN = "cooldown"
        const val SUPPRESSION_DUPLICATE_GUARD = "duplicate_guard"
        const val SUPPRESSION_DELIVERY_FAILED = "delivery_failed"
        const val FEATURE_SOURCE_SESSION_START = "session_start_self_report"
        const val FEATURE_SOURCE_MANUAL_UPDATE = "manual_update_before_candidate"
        const val TRIGGER_FOREGROUND_CHECK = "foreground_check"
        const val TRIGGER_MANUAL_FEATURE_UPDATE = "manual_feature_update"
        const val TRIGGER_THRESHOLD_ALARM = "threshold_alarm"
        const val TRIGGER_COOLDOWN_EXPIRY = "cooldown_expiry"
        const val DECLINE_FOCUS_SEGMENT = "focus_segment"
        const val DECLINE_ALMOST_DONE = "almost_done"
        const val DECLINE_NOT_TIRED = "not_tired"
        const val DECLINE_NOTIFICATION = "notification_deferred"

        fun fromJson(json: JSONObject): BreakPromptEvent {
            val candidateAt = json.getLong("candidate_at_ms")
            val oldMoodFatigue = StudyMood.fromCode(json.optString("mood_at_candidate")).legacyFatigueScore
            return BreakPromptEvent(
                eventId = json.getString("event_id"),
                sessionId = json.getString("session_id"),
                sequence = json.optInt("sequence", 0),
                candidateAtMs = candidateAt,
                durationAtCandidateMs = json.getLong("duration_at_candidate_ms"),
                breakTargetMinutes = json.optInt("break_target_minutes", FocusMateRules.DEFAULT_BREAK_MINUTES)
                    .coerceIn(FocusMateRules.MIN_BREAK_MINUTES, FocusMateRules.MAX_BREAK_MINUTES),
                taskType = json.optString("task_type", "Bài tập"),
                focusAtCandidate = json.optInt("focus_at_candidate", 3).coerceIn(1, 5),
                fatigueAtCandidate = json.optInt("fatigue_at_candidate", oldMoodFatigue).coerceIn(1, 10),
                featureObservedAtMs = json.optLong("feature_observed_at_ms", candidateAt),
                featureSource = json.optString("feature_source", FEATURE_SOURCE_SESSION_START),
                ruleShouldBreak = json.optBoolean("break_needed", json.optBoolean("rule_should_break", false)),
                breakNeeded = json.optBoolean("break_needed", json.optBoolean("rule_should_break", false)),
                reasonCodes = buildSet {
                    json.optJSONArray("reason_codes")?.let { values ->
                        for (index in 0 until values.length()) add(values.getString(index))
                    }
                },
                decisionSource = WatchRuleEngine.RULE_VERSION,
                triggerSource = json.optString("trigger_source", TRIGGER_FOREGROUND_CHECK),
                cooldownRemainingMs = json.optLong("cooldown_remaining_ms", 0L).coerceAtLeast(0L),
                cooldownUntilAtCandidateMs = json.optionalLong("cooldown_until_at_candidate_ms"),
                suppressedByEventId = json.optionalString("suppressed_by_event_id"),
                rejectedRecently = json.optBoolean("rejected_recently", false),
                duplicateGuardPassed = json.optBoolean("duplicate_guard_passed", true),
                promptEligible = json.optBoolean("prompt_eligible", false),
                prompted = json.optBoolean("prompted", false),
                promptedAtMs = json.optionalLong("prompted_at_ms"),
                deliveryChannel = json.optString("delivery_channel", CHANNEL_NONE),
                suppressionReason = json.optionalString("suppression_reason"),
                respondedAtMs = json.optionalLong("responded_at_ms"),
                response = json.optionalString("response"),
                declineReasonCode = json.optionalString("decline_reason_code"),
                cooldownUntilMs = json.optionalLong("cooldown_until_ms"),
                synthetic = json.optBoolean("synthetic", false),
                schemaVersion = json.optString("schema_version", "legacy_prompt_event"),
                ruleVersion = json.optString("rule_version", json.optString("decision_source", "legacy_rule")),
                expiresOn = json.optString("expires_on").takeIf(String::isNotBlank)
                    ?: RetentionPolicy.expiresOnText(candidateAt),
            )
        }
    }
}

object FocusMatePromptEventPolicy {
    fun shouldAppend(existing: List<BreakPromptEvent>, candidate: BreakPromptEvent): Boolean {
        if (existing.any { it.eventId == candidate.eventId }) return false
        return existing.none {
            !candidate.prompted && candidate.suppressionReason == BreakPromptEvent.SUPPRESSION_COOLDOWN &&
                it.sessionId == candidate.sessionId &&
                it.suppressionReason == BreakPromptEvent.SUPPRESSION_COOLDOWN &&
                it.cooldownUntilAtCandidateMs == candidate.cooldownUntilAtCandidateMs
        }
    }

    fun create(
        active: ActiveStudySession,
        candidateAtMs: Long,
        durationAtCandidateMs: Long,
        decision: BreakDecision,
        duplicateGuardPassed: Boolean,
        prompted: Boolean,
        deliveryChannel: String,
        triggerSource: String,
        suppressionReason: String? = null,
        eventId: String = UUID.randomUUID().toString(),
    ): BreakPromptEvent {
        require(candidateAtMs >= active.startTimeMs)
        require(durationAtCandidateMs >= 0L)
        require(decision.shouldBreak)
        val eligible = decision.shouldPrompt && duplicateGuardPassed
        require(!prompted || eligible)
        require(deliveryChannel in setOf(
            BreakPromptEvent.CHANNEL_FOREGROUND,
            BreakPromptEvent.CHANNEL_NOTIFICATION,
            BreakPromptEvent.CHANNEL_NONE,
        ))
        require(prompted || !suppressionReason.isNullOrBlank())
        return BreakPromptEvent(
            eventId = eventId,
            sessionId = active.sessionId,
            candidateAtMs = candidateAtMs,
            durationAtCandidateMs = durationAtCandidateMs,
            breakTargetMinutes = active.breakTargetMinutes,
            taskType = active.taskType,
            focusAtCandidate = active.focusScore,
            fatigueAtCandidate = active.fatigueScore,
            featureObservedAtMs = active.featureObservedAtMs,
            featureSource = active.featureSource,
            ruleShouldBreak = decision.shouldBreak,
            reasonCodes = decision.reasonCodes,
            triggerSource = triggerSource,
            cooldownRemainingMs = decision.cooldownRemainingMs,
            cooldownUntilAtCandidateMs = decision.cooldownRemainingMs.takeIf { it > 0 }?.let { candidateAtMs + it },
            suppressedByEventId = active.lastPromptEventId.takeIf { decision.cooldownRemainingMs > 0 },
            rejectedRecently = decision.cooldownRemainingMs > 0,
            duplicateGuardPassed = duplicateGuardPassed,
            promptEligible = eligible,
            prompted = prompted,
            promptedAtMs = candidateAtMs.takeIf { prompted },
            deliveryChannel = deliveryChannel,
            suppressionReason = suppressionReason,
            synthetic = active.synthetic,
        )
    }

    fun realEvents(events: List<BreakPromptEvent>, completedSessionIds: Set<String>): List<BreakPromptEvent> =
        events.filter { !it.synthetic && it.sessionId in completedSessionIds }

    fun withObservedResponse(
        event: BreakPromptEvent,
        response: String,
        respondedAtMs: Long,
        declineReasonCode: String? = null,
        quietUntilMs: Long? = null,
    ): BreakPromptEvent {
        if (event.response != null) return event
        require(event.prompted)
        val safeAt = respondedAtMs.coerceAtLeast(event.promptedAtMs ?: event.candidateAtMs)
        if (response == BreakPromptEvent.RESPONSE_DECLINED) {
            require(!declineReasonCode.isNullOrBlank())
            require(quietUntilMs == safeAt + WatchRuleEngine.COOLDOWN_MS)
        } else {
            require(response == BreakPromptEvent.RESPONSE_ACCEPTED)
            require(declineReasonCode == null && quietUntilMs == null)
        }
        return event.copy(
            respondedAtMs = safeAt,
            response = response,
            declineReasonCode = declineReasonCode,
            cooldownUntilMs = quietUntilMs,
        )
    }
}

private fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.optionalLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)
