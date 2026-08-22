package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PendingReminderKind(val wireValue: String) {
    BREAK_SUGGESTION("break_suggestion"),
    BREAK_ENDED("break_ended");

    companion object {
        fun fromWireValue(value: String): PendingReminderKind? = entries.firstOrNull { it.wireValue == value }
    }
}

data class PendingReminder(
    val eventId: String,
    val kind: PendingReminderKind,
    val createdAtMs: Long,
    val attempt: Int = 0,
    val nextAlertAtMs: Long? = createdAtMs,
    val title: String? = null,
    val message: String,
) {
    fun toJson() = JSONObject().apply {
        put("event_id", eventId)
        put("kind", kind.wireValue)
        put("created_at_ms", createdAtMs)
        put("attempt", attempt)
        put("next_alert_at_ms", nextAlertAtMs ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("message", message)
    }

    companion object {
        fun fromJson(json: JSONObject): PendingReminder? {
            val kind = PendingReminderKind.fromWireValue(json.optString("kind")) ?: return null
            val eventId = json.optString("event_id").takeIf(String::isNotBlank) ?: return null
            return PendingReminder(
                eventId = eventId,
                kind = kind,
                createdAtMs = json.optLong("created_at_ms"),
                attempt = json.optInt("attempt").coerceIn(0, BreakReminderPolicy.MAX_ATTEMPTS),
                nextAlertAtMs = json.optionalLong("next_alert_at_ms"),
                title = json.optionalString("title"),
                message = json.optString("message"),
            )
        }
    }
}

object BreakReminderPolicy {
    const val MAX_ATTEMPTS = 3
    val RETRY_OFFSETS_MS = longArrayOf(0L, 2 * 60_000L, 5 * 60_000L)

    fun nextAttempt(reminder: PendingReminder): PendingReminder {
        val attempt = (reminder.attempt + 1).coerceAtMost(MAX_ATTEMPTS)
        return reminder.copy(attempt = attempt, nextAlertAtMs = RETRY_OFFSETS_MS.getOrNull(attempt)?.let {
            reminder.createdAtMs + it
        })
    }
}

object ReminderActionGuard {
    fun matches(
        active: ActiveStudySession,
        sessionId: String?,
        eventId: String?,
        kind: PendingReminderKind,
    ): Boolean {
        val pending = active.pendingReminder ?: return false
        return sessionId != null && eventId != null && active.sessionId == sessionId &&
            pending.eventId == eventId && pending.kind == kind
    }
}

data class StudySession(
    val sessionId: String,
    val studentCode: String = LOCAL_PROFILE,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
    val subject: String = HIDDEN_SUBJECT,
    val taskType: String,
    val focusScore: Int,
    val fatigueScore: Int,
    val breakReminderCount: Int,
    val shouldBreak: Boolean,
    val reviewedShouldBreak: Boolean? = null,
    val interruptRisk: String = "medium",
    val accepted: Boolean?,
    val deferReason: String?,
    val labelSource: String = WatchRuleEngine.RULE_VERSION,
    val synthetic: Boolean = false,
    val movementRms: Double? = null,
    val rotationRms: Double? = null,
    val motionWindowCount: Int = 0,
    val suddenMovementCount: Int = 0,
    val wristRotationCount: Int = 0,
    val immobileSeconds: Double = 0.0,
    val continuousImmobileMs: Long = 0L,
    val movementChangeFromBaseline: Double? = null,
    val motionActivityLabel: String? = null,
    val motionActivityConfidence: Double? = null,
    val motionActivityObservedAtMs: Long? = null,
    val motionActivityFallbackReason: String? = null,
    val watchRaiseCount: Int = 0,
    val heartRateAverage: Double? = null,
    val heartRateBaseline: Double? = null,
    val heartRateSampleCount: Int = 0,
    val heartRateBaselineSampleCount: Int = 0,
    val sessionConfidence: Int = 0,
    val postureSummaries: List<PostureStateSummary> = emptyList(),
    val postureInsightReasonCodes: Set<String> = emptySet(),
    val breakTargetMinutes: Int = FocusMateRules.DEFAULT_BREAK_MINUTES,
    val breakCount: Int = 0,
    val totalBreakDurationMs: Long = 0L,
    val expiresOn: String = RetentionPolicy.expiresOnText(endTimeMs),
) {
    fun toJson() = JSONObject().apply {
        put("session_id", sessionId)
        put("start_time_ms", startTimeMs)
        put("end_time_ms", endTimeMs)
        put("duration_minutes", durationMinutes)
        put("task_type", taskType)
        put("focus_score", focusScore)
        put("fatigue_score", fatigueScore)
        put("break_reminder_count", breakReminderCount)
        put("should_break", shouldBreak)
        put("reviewed_should_break", reviewedShouldBreak ?: JSONObject.NULL)
        put("interrupt_risk", interruptRisk)
        put("accepted", accepted ?: JSONObject.NULL)
        put("defer_reason", deferReason ?: JSONObject.NULL)
        put("label_source", labelSource)
        put("synthetic", synthetic)
        putMotion(this@StudySession)
        put("heart_rate_average", heartRateAverage ?: JSONObject.NULL)
        put("heart_rate_baseline", heartRateBaseline ?: JSONObject.NULL)
        put("heart_rate_sample_count", heartRateSampleCount)
        put("heart_rate_baseline_sample_count", heartRateBaselineSampleCount)
        put("session_confidence", sessionConfidence)
        put("posture_summaries", postureSummaries.toJson())
        put("posture_insight_reason_codes", JSONArray(postureInsightReasonCodes.sorted()))
        put("break_target_minutes", breakTargetMinutes)
        put("break_count", breakCount)
        put("total_break_duration_ms", totalBreakDurationMs)
        put("expires_on", expiresOn)
    }

    companion object {
        fun fromJson(json: JSONObject): StudySession {
            val end = json.getLong("end_time_ms")
            return StudySession(
                sessionId = json.getString("session_id"),
                startTimeMs = json.getLong("start_time_ms"),
                endTimeMs = end,
                durationMinutes = json.optInt("duration_minutes", 0).coerceAtLeast(0),
                taskType = json.optString("task_type", "Bài tập"),
                focusScore = json.optInt("focus_score", 3).coerceIn(1, 5),
                fatigueScore = json.fatigueScore(),
                breakReminderCount = json.optInt("break_reminder_count", 0).coerceAtLeast(0),
                shouldBreak = json.optBoolean("should_break", json.optBoolean("rule_should_break", false)),
                reviewedShouldBreak = json.optionalBoolean("reviewed_should_break"),
                interruptRisk = json.optString("interrupt_risk", "medium"),
                accepted = json.optionalBoolean("accepted"),
                deferReason = json.optionalString("defer_reason"),
                labelSource = json.optString("label_source", "legacy_rule"),
                synthetic = json.optBoolean("synthetic", false),
                movementRms = json.optionalDouble("movement_rms"),
                rotationRms = json.optionalDouble("rotation_rms"),
                motionWindowCount = json.optInt("motion_window_count", 0),
                suddenMovementCount = json.optInt("sudden_movement_count", 0),
                wristRotationCount = json.optInt("wrist_rotation_count", 0),
                immobileSeconds = json.optDouble("immobile_seconds", 0.0),
                continuousImmobileMs = json.optLong("continuous_immobile_ms", 0L),
                movementChangeFromBaseline = json.optionalDouble("movement_change_from_baseline"),
                motionActivityLabel = json.optionalString("motion_activity_label"),
                motionActivityConfidence = json.optionalDouble("motion_activity_confidence"),
                motionActivityObservedAtMs = json.optionalLong("motion_activity_observed_at_ms"),
                motionActivityFallbackReason = json.optionalString("motion_activity_fallback_reason"),
                watchRaiseCount = json.optInt("watch_raise_count", 0),
                heartRateAverage = json.optionalDouble("heart_rate_average"),
                heartRateBaseline = json.optionalDouble("heart_rate_baseline"),
                heartRateSampleCount = json.optInt("heart_rate_sample_count", 0),
                heartRateBaselineSampleCount = json.optInt("heart_rate_baseline_sample_count", 0),
                sessionConfidence = json.optInt("session_confidence", 0).coerceIn(0, 100),
                postureSummaries = json.postureSummaries(),
                postureInsightReasonCodes = json.stringSet("posture_insight_reason_codes"),
                breakTargetMinutes = json.optInt("break_target_minutes", FocusMateRules.DEFAULT_BREAK_MINUTES)
                    .coerceIn(FocusMateRules.MIN_BREAK_MINUTES, FocusMateRules.MAX_BREAK_MINUTES),
                breakCount = json.optInt("break_count", 0).coerceAtLeast(0),
                totalBreakDurationMs = json.optLong("total_break_duration_ms", 0L).coerceAtLeast(0L),
                expiresOn = json.optString("expires_on").takeIf(String::isNotBlank)
                    ?: RetentionPolicy.expiresOnText(end),
            )
        }
    }
}

data class ActiveStudySession(
    val sessionId: String = UUID.randomUUID().toString(),
    val studentCode: String = LOCAL_PROFILE,
    val startTimeMs: Long,
    val subject: String = HIDDEN_SUBJECT,
    val taskType: String,
    val focusScore: Int,
    val fatigueScore: Int,
    val breakReminderCount: Int = 0,
    val accepted: Boolean? = null,
    val deferReason: String? = null,
    val synthetic: Boolean = false,
    val featureObservedAtMs: Long = startTimeMs,
    val featureSource: String = BreakPromptEvent.FEATURE_SOURCE_SESSION_START,
    val movementRms: Double? = null,
    val rotationRms: Double? = null,
    val motionWindowCount: Int = 0,
    val suddenMovementCount: Int = 0,
    val wristRotationCount: Int = 0,
    val immobileSeconds: Double = 0.0,
    val continuousImmobileMs: Long = 0L,
    val movementChangeFromBaseline: Double? = null,
    val motionActivityLabel: String? = null,
    val motionActivityConfidence: Double? = null,
    val motionActivityObservedAtMs: Long? = null,
    val motionActivityFallbackReason: String? = null,
    val watchRaiseCount: Int = 0,
    val heartRateCurrent: Double? = null,
    val heartRateObservedAtMs: Long? = null,
    val heartRateAverage: Double? = null,
    val heartRateBaseline: Double? = null,
    val heartRateSampleCount: Int = 0,
    val heartRateBaselineSampleCount: Int = 0,
    val sessionConfidence: Int = 0,
    val postureSummaries: List<PostureStateSummary> = emptyList(),
    val postureInsightReasonCodes: Set<String> = emptySet(),
    val breakTargetMinutes: Int = FocusMateRules.DEFAULT_BREAK_MINUTES,
    val breakCount: Int = 0,
    val accumulatedBreakMs: Long = 0L,
    val lastBreakStudyDurationMs: Long = 0L,
    val breakStartedAtMs: Long? = null,
    val breakEndsAtMs: Long? = null,
    val breakAwaitingDecisionAtMs: Long? = null,
    val lastPromptAtMs: Long = 0L,
    val lastPromptEventId: String? = null,
    val pendingReminder: PendingReminder? = null,
) {
    fun toJson() = JSONObject().apply {
        put("session_id", sessionId)
        put("start_time_ms", startTimeMs)
        put("task_type", taskType)
        put("focus_score", focusScore)
        put("fatigue_score", fatigueScore)
        put("break_reminder_count", breakReminderCount)
        put("accepted", accepted ?: JSONObject.NULL)
        put("defer_reason", deferReason ?: JSONObject.NULL)
        put("synthetic", synthetic)
        put("feature_observed_at_ms", featureObservedAtMs)
        put("feature_source", featureSource)
        putMotion(this@ActiveStudySession)
        put("heart_rate_current", heartRateCurrent ?: JSONObject.NULL)
        put("heart_rate_observed_at_ms", heartRateObservedAtMs ?: JSONObject.NULL)
        put("heart_rate_average", heartRateAverage ?: JSONObject.NULL)
        put("heart_rate_baseline", heartRateBaseline ?: JSONObject.NULL)
        put("heart_rate_sample_count", heartRateSampleCount)
        put("heart_rate_baseline_sample_count", heartRateBaselineSampleCount)
        put("session_confidence", sessionConfidence)
        put("posture_summaries", postureSummaries.toJson())
        put("posture_insight_reason_codes", JSONArray(postureInsightReasonCodes.sorted()))
        put("break_target_minutes", breakTargetMinutes)
        put("break_count", breakCount)
        put("accumulated_break_ms", accumulatedBreakMs)
        put("last_break_study_duration_ms", lastBreakStudyDurationMs)
        put("break_started_at_ms", breakStartedAtMs ?: JSONObject.NULL)
        put("break_ends_at_ms", breakEndsAtMs ?: JSONObject.NULL)
        put("break_awaiting_decision_at_ms", breakAwaitingDecisionAtMs ?: JSONObject.NULL)
        put("last_prompt_at_ms", lastPromptAtMs)
        put("last_prompt_event_id", lastPromptEventId ?: JSONObject.NULL)
        put("pending_reminder", pendingReminder?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject) = ActiveStudySession(
            sessionId = json.optString("session_id").takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
            startTimeMs = json.getLong("start_time_ms"),
            taskType = json.optString("task_type", "Bài tập"),
            focusScore = json.optInt("focus_score", 3).coerceIn(1, 5),
            fatigueScore = json.fatigueScore(),
            breakReminderCount = json.optInt("break_reminder_count", 0).coerceAtLeast(0),
            accepted = json.optionalBoolean("accepted"),
            deferReason = json.optionalString("defer_reason"),
            synthetic = json.optBoolean("synthetic", false),
            featureObservedAtMs = json.optLong("feature_observed_at_ms", json.getLong("start_time_ms")),
            featureSource = json.optString("feature_source", BreakPromptEvent.FEATURE_SOURCE_SESSION_START),
            movementRms = json.optionalDouble("movement_rms"),
            rotationRms = json.optionalDouble("rotation_rms"),
            motionWindowCount = json.optInt("motion_window_count", 0),
            suddenMovementCount = json.optInt("sudden_movement_count", 0),
            wristRotationCount = json.optInt("wrist_rotation_count", 0),
            immobileSeconds = json.optDouble("immobile_seconds", 0.0),
            continuousImmobileMs = json.optLong("continuous_immobile_ms", 0L),
            movementChangeFromBaseline = json.optionalDouble("movement_change_from_baseline"),
            motionActivityLabel = json.optionalString("motion_activity_label"),
            motionActivityConfidence = json.optionalDouble("motion_activity_confidence"),
            motionActivityObservedAtMs = json.optionalLong("motion_activity_observed_at_ms"),
            motionActivityFallbackReason = json.optionalString("motion_activity_fallback_reason"),
            watchRaiseCount = json.optInt("watch_raise_count", 0),
            heartRateCurrent = json.optionalDouble("heart_rate_current"),
            heartRateObservedAtMs = json.optionalLong("heart_rate_observed_at_ms"),
            heartRateAverage = json.optionalDouble("heart_rate_average"),
            heartRateBaseline = json.optionalDouble("heart_rate_baseline"),
            heartRateSampleCount = json.optInt("heart_rate_sample_count", 0),
            heartRateBaselineSampleCount = json.optInt("heart_rate_baseline_sample_count", 0),
            sessionConfidence = json.optInt("session_confidence", 0).coerceIn(0, 100),
            postureSummaries = json.postureSummaries(),
            postureInsightReasonCodes = json.stringSet("posture_insight_reason_codes"),
            breakTargetMinutes = json.optInt("break_target_minutes", FocusMateRules.DEFAULT_BREAK_MINUTES)
                .coerceIn(FocusMateRules.MIN_BREAK_MINUTES, FocusMateRules.MAX_BREAK_MINUTES),
            breakCount = json.optInt("break_count", 0).coerceAtLeast(0),
            accumulatedBreakMs = json.optLong("accumulated_break_ms", 0L).coerceAtLeast(0L),
            lastBreakStudyDurationMs = json.optLong("last_break_study_duration_ms", 0L).coerceAtLeast(0L),
            breakStartedAtMs = json.optionalLong("break_started_at_ms"),
            breakEndsAtMs = json.optionalLong("break_ends_at_ms"),
            breakAwaitingDecisionAtMs = json.optionalLong("break_awaiting_decision_at_ms"),
            lastPromptAtMs = json.optLong("last_prompt_at_ms", 0L),
            lastPromptEventId = json.optionalString("last_prompt_event_id"),
            pendingReminder = json.optJSONObject("pending_reminder")?.let(PendingReminder::fromJson),
        )
}
}

object StudySessionClock {
    const val BREAK_DURATION_MS = 5 * 60_000L

    fun breakRemainingMs(active: ActiveStudySession, nowMs: Long): Long {
        val start = active.breakStartedAtMs ?: return 0
        val end = active.breakEndsAtMs ?: return 0
        return if (end <= start) 0 else (end - nowMs).coerceAtLeast(0)
    }

    fun isOnBreak(active: ActiveStudySession, nowMs: Long) = active.breakStartedAtMs?.let { nowMs >= it } == true
    fun isAwaitingBreakDecision(active: ActiveStudySession) =
        active.breakStartedAtMs != null && active.breakAwaitingDecisionAtMs != null

    fun currentBreakElapsedMs(active: ActiveStudySession, nowMs: Long): Long {
        val start = active.breakStartedAtMs ?: return 0
        val end = active.breakEndsAtMs ?: return 0
        if (end <= start || nowMs <= start) return 0
        return ((if (isAwaitingBreakDecision(active)) nowMs else minOf(nowMs, end)) - start).coerceAtLeast(0)
    }

    fun totalBreakDurationMs(active: ActiveStudySession, nowMs: Long) =
        active.accumulatedBreakMs.coerceAtLeast(0) + currentBreakElapsedMs(active, nowMs)

    fun studyDurationMs(active: ActiveStudySession, nowMs: Long) =
        ((nowMs - active.startTimeMs).coerceAtLeast(0) - totalBreakDurationMs(active, nowMs)).coerceAtLeast(0)

    fun focusBlockDurationMs(active: ActiveStudySession, nowMs: Long) =
        (studyDurationMs(active, nowMs) - active.lastBreakStudyDurationMs).coerceAtLeast(0)

    fun startBreak(active: ActiveStudySession, nowMs: Long, durationMs: Long = BREAK_DURATION_MS): ActiveStudySession {
        require(durationMs > 0)
        if (active.breakStartedAtMs != null) return active
        return active.copy(
            breakStartedAtMs = nowMs,
            breakEndsAtMs = nowMs + durationMs,
            breakAwaitingDecisionAtMs = null,
            lastBreakStudyDurationMs = studyDurationMs(active, nowMs),
            breakCount = active.breakCount + 1,
            pendingReminder = null,
        )
    }

    fun markAwaitingDecisionIfDue(active: ActiveStudySession, nowMs: Long): ActiveStudySession? {
        val start = active.breakStartedAtMs ?: return null
        val end = active.breakEndsAtMs ?: return null
        if (end > start && nowMs < end) return null
        if (active.breakAwaitingDecisionAtMs != null) return active
        return active.copy(breakAwaitingDecisionAtMs = nowMs.coerceAtLeast(end))
    }

    fun resumeFromBreak(active: ActiveStudySession, nowMs: Long): ActiveStudySession? {
        val start = active.breakStartedAtMs ?: return null
        if (!isAwaitingBreakDecision(active)) return null
        return active.copy(
            accumulatedBreakMs = active.accumulatedBreakMs + (nowMs - start).coerceAtLeast(0),
            breakStartedAtMs = null,
            breakEndsAtMs = null,
            breakAwaitingDecisionAtMs = null,
            pendingReminder = null,
        )
    }

    fun extendBreak(active: ActiveStudySession, nowMs: Long, durationMs: Long): ActiveStudySession? {
        require(durationMs > 0)
        if (!isAwaitingBreakDecision(active)) return null
        return active.copy(
            breakEndsAtMs = nowMs + durationMs,
            breakAwaitingDecisionAtMs = null,
            pendingReminder = null,
        )
    }
}

data class BreakDecision(
    val shouldBreak: Boolean,
    val shouldPrompt: Boolean,
    val cooldownRemainingMs: Long,
    val decisionSource: String = WatchRuleEngine.RULE_VERSION,
    val promptSuppressionReason: String? = null,
    val reasonCodes: Set<String> = emptySet(),
)

object SessionConfidence {
    fun calculate(active: ActiveStudySession, nowMs: Long): Int {
        val expected = (StudySessionClock.studyDurationMs(active, nowMs) / 30_000L).toInt().coerceAtLeast(1)
        val motion = (active.motionWindowCount.toDouble() / expected).coerceIn(0.0, 1.0)
        val heartRate = (active.heartRateSampleCount / 3.0).coerceIn(0.0, 1.0)
        return ((0.70 * motion + 0.30 * heartRate) * 100).toInt().coerceIn(0, 100)
    }
}

object FocusMateSessionPolicy {
    fun realSessions(sessions: List<StudySession>) = sessions.filter { !it.synthetic }
    fun demoSessions(sessions: List<StudySession>) = sessions.filter { it.synthetic }
}

/** Compatibility-only display bounds; WatchRuleEngine is the sole decision authority. */
object FocusMateRules {
    const val MIN_BREAK_MINUTES = 45
    const val DEFAULT_BREAK_MINUTES = 45
    const val MAX_BREAK_MINUTES = 50
    const val COOLDOWN_MS = WatchRuleEngine.COOLDOWN_MS
    const val DUPLICATE_PROMPT_GUARD_MS = WatchRuleEngine.DUPLICATE_PROMPT_GUARD_MS

    fun thresholdMs(targetMinutes: Int) = targetMinutes.coerceIn(MIN_BREAK_MINUTES, MAX_BREAK_MINUTES) * 60_000L

    fun interruptRisk(focusScore: Int, fatigueScore: Int, durationMinutes: Int, taskType: String): String = when {
        fatigueScore >= 8 || durationMinutes >= 75 -> "low"
        focusScore >= 4 && Regex("ôn thi|bài tập|lập trình").containsMatchIn(taskType.lowercase()) -> "high"
        else -> "medium"
    }
}

object LocalBreakTimingPolicy {
    fun recommend(events: List<BreakPromptEvent>): Int = FocusMateRules.DEFAULT_BREAK_MINUTES
}

private fun JSONObject.fatigueScore(): Int {
    if (has("fatigue_score") && !isNull("fatigue_score")) return getInt("fatigue_score").coerceIn(1, 10)
    return StudyMood.fromCode(optString("mood").ifBlank { optString("mood_code") }).legacyFatigueScore
}

private fun JSONObject.putMotion(session: StudySession) {
    put("movement_rms", session.movementRms ?: JSONObject.NULL)
    put("rotation_rms", session.rotationRms ?: JSONObject.NULL)
    put("motion_window_count", session.motionWindowCount)
    put("sudden_movement_count", session.suddenMovementCount)
    put("wrist_rotation_count", session.wristRotationCount)
    put("immobile_seconds", session.immobileSeconds)
    put("continuous_immobile_ms", session.continuousImmobileMs)
    put("movement_change_from_baseline", session.movementChangeFromBaseline ?: JSONObject.NULL)
    put("motion_activity_label", session.motionActivityLabel ?: JSONObject.NULL)
    put("motion_activity_confidence", session.motionActivityConfidence ?: JSONObject.NULL)
    put("motion_activity_observed_at_ms", session.motionActivityObservedAtMs ?: JSONObject.NULL)
    put("motion_activity_fallback_reason", session.motionActivityFallbackReason ?: JSONObject.NULL)
    put("watch_raise_count", session.watchRaiseCount)
}

private fun JSONObject.putMotion(session: ActiveStudySession) {
    put("movement_rms", session.movementRms ?: JSONObject.NULL)
    put("rotation_rms", session.rotationRms ?: JSONObject.NULL)
    put("motion_window_count", session.motionWindowCount)
    put("sudden_movement_count", session.suddenMovementCount)
    put("wrist_rotation_count", session.wristRotationCount)
    put("immobile_seconds", session.immobileSeconds)
    put("continuous_immobile_ms", session.continuousImmobileMs)
    put("movement_change_from_baseline", session.movementChangeFromBaseline ?: JSONObject.NULL)
    put("motion_activity_label", session.motionActivityLabel ?: JSONObject.NULL)
    put("motion_activity_confidence", session.motionActivityConfidence ?: JSONObject.NULL)
    put("motion_activity_observed_at_ms", session.motionActivityObservedAtMs ?: JSONObject.NULL)
    put("motion_activity_fallback_reason", session.motionActivityFallbackReason ?: JSONObject.NULL)
    put("watch_raise_count", session.watchRaiseCount)
}

private fun List<PostureStateSummary>.toJson() = JSONArray().also { array ->
    forEach { summary -> array.put(JSONObject().apply {
        put("state", summary.state.name)
        put("episode_count", summary.episodeCount)
        put("total_duration_ms", summary.totalDurationMs)
    }) }
}

private fun JSONObject.postureSummaries(): List<PostureStateSummary> = buildList {
    val values = optJSONArray("posture_summaries") ?: return@buildList
    for (index in 0 until values.length()) {
        val row = values.optJSONObject(index) ?: continue
        val state = runCatching { PostureState.valueOf(row.optString("state")) }.getOrNull() ?: continue
        add(PostureStateSummary(state, row.optInt("episode_count", 0), row.optLong("total_duration_ms", 0L)))
    }
}

private fun JSONObject.stringSet(key: String): Set<String> = buildSet {
    val values = optJSONArray(key) ?: return@buildSet
    for (index in 0 until values.length()) add(values.getString(index))
}

private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.optionalDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
private fun JSONObject.optionalBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else getBoolean(key)

private const val LOCAL_PROFILE = "local"
private const val HIDDEN_SUBJECT = "Không áp dụng"
