// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Stable session states. Reminder delivery is deliberately a separate state machine. */
enum class SessionState {
    STUDYING,
    PAUSED,
    BREAKING,
    AWAITING_RESUME,
    RECOVERY_REQUIRED,
    COMPLETED,
    CANCELLED,
}

data class TimePoint(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootId: String,
)

interface SessionClock {
    fun now(): TimePoint
}

class AndroidSessionClock(context: Context) : SessionClock {
    private val appContext = context.applicationContext
    override fun now(): TimePoint = TimePoint(
        wallMs = System.currentTimeMillis(),
        elapsedMs = SystemClock.elapsedRealtime(),
        bootId = runCatching {
            "boot-${Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)}"
        }.getOrElse { "boot-unknown" },
    )
}

enum class CheckInSource {
    SESSION_START,
    USER_REQUEST,
    TASK_BOUNDARY,
    BEFORE_BREAK,
    AFTER_BREAK,
    SESSION_END,
}

enum class BreakActivityType {
    FULL_BREAK,
    EYE_REST,
    CHANGE_ACTIVITY,
    UNSPECIFIED,
}

enum class ReminderDeliveryState {
    OPEN,
    DELIVERY_BLOCKED,
    QUIET_OPEN,
    ACCEPTED,
    DEFERRED,
    CLOSED,
}

enum class PromptTimingFeedback { TOO_EARLY, ABOUT_RIGHT, TOO_LATE }

/** Auditable delivery history. A successful post is not evidence that the user saw it. */
data class ReminderDeliveryHistory(
    val slotIndex: Int,
    val scheduledWallMs: Long,
    val receiverWallMs: Long? = null,
    val postAttemptWallMs: Long? = null,
    val result: String,
)

data class ReminderEpisodeHistory(
    val reminderId: String,
    val blockId: String,
    val policyVersion: String,
    val reasonCodes: Set<String>,
    val evidenceReferences: Set<String>,
    val state: ReminderDeliveryState,
    val response: String? = null,
    val deliveries: List<ReminderDeliveryHistory> = emptyList(),
    val timingFeedback: PromptTimingFeedback? = null,
    val annoyance: Int? = null,
)

data class CheckInRecord(
    val checkInId: String,
    val sessionId: String,
    val blockId: String,
    val source: CheckInSource,
    val time: TimePoint,
    val breakId: String? = null,
    val focus: Int? = null,
    val fatigue: Int? = null,
) {
    init {
        require(focus == null || focus in 1..5)
        require(fatigue == null || fatigue in 1..10)
    }
}

data class BreakEpisodeRecord(
    val breakId: String,
    val sessionId: String,
    val blockId: String,
    val startedAt: TimePoint,
    val plannedDurationMs: Long,
    val activity: BreakActivityType,
    val endedAt: TimePoint? = null,
    val actualDurationMs: Long? = null,
    val selfReportedDone: Boolean? = null,
) {
    init {
        require(plannedDurationMs > 0)
        require(actualDurationMs == null || actualDurationMs >= 0)
    }
}

data class PolicyContext(
    val sessionId: String,
    val blockId: String,
    val studyDurationMs: Long,
    val initialFocus: Int,
    val initialFatigue: Int,
    val now: TimePoint,
    val cooldownUntilWallMs: Long,
    val motion: MotionEvidence? = null,
    val checkIns: List<CheckInRecord> = emptyList(),
)

data class PolicyDecision(
    val eligible: Boolean,
    val shouldPrompt: Boolean,
    val reasonCodes: Set<String>,
    val evidenceReferences: Set<String>,
    val policyVersion: String,
    val nextEvaluationAtWallMs: Long?,
    val fallbacks: Set<String> = emptySet(),
)

interface BreakPolicy {
    fun evaluate(context: PolicyContext): PolicyDecision
}

data class InteractionResult(val reminder: PendingReminder, val openedNewEpisode: Boolean)

interface InteractionCoordinator {
    fun openOrMerge(existing: PendingReminder?, candidate: PendingReminder): InteractionResult
}

/** Keeps one open interaction and never replenishes delivery attempts when evidence changes. */
object DefaultInteractionCoordinator : InteractionCoordinator {
    override fun openOrMerge(existing: PendingReminder?, candidate: PendingReminder): InteractionResult {
        if (existing == null || existing.kind != candidate.kind) return InteractionResult(candidate, true)
        return InteractionResult(
            existing.copy(
                reasonCodes = existing.reasonCodes + candidate.reasonCodes,
                evidenceReferences = existing.evidenceReferences + candidate.evidenceReferences,
                message = if ((candidate.reasonCodes - existing.reasonCodes).isNotEmpty()) candidate.message else existing.message,
            ),
            openedNewEpisode = false,
        )
    }
}

/** Adapter around the accepted watch_rules_v2 truth table. */
object WatchRulesV2Policy : BreakPolicy {
    override fun evaluate(context: PolicyContext): PolicyDecision {
        val result = WatchRuleEngine.evaluate(
            ReminderContext(
                studyDurationMs = context.studyDurationMs,
                fatigueScore = context.initialFatigue,
                focusScore = context.initialFocus,
                nowMs = context.now.wallMs,
                cooldownUntilMs = context.cooldownUntilWallMs,
                motion = context.motion,
            )
        )
        return PolicyDecision(
            eligible = result.shouldSuggestBreak,
            shouldPrompt = result.shouldPrompt,
            reasonCodes = result.reasonCodes,
            evidenceReferences = evidenceForReasons(result.reasonCodes),
            policyVersion = WatchRuleEngine.RULE_VERSION,
            nextEvaluationAtWallMs = nextEvaluation(context, result),
        )
    }

    private fun nextEvaluation(context: PolicyContext, result: ReminderDecision): Long? {
        if (result.shouldPrompt) return context.now.wallMs
        if (result.cooldownRemainingMs > 0) return context.now.wallMs + result.cooldownRemainingMs
        val nextBoundary = listOf(30L, 45L, 60L)
            .map { it * 60_000L }
            .firstOrNull { it > context.studyDurationMs }
            ?: return context.now.wallMs + 60_000L
        return context.now.wallMs + (nextBoundary - context.studyDurationMs)
    }

    private fun evidenceForReasons(reasons: Set<String>): Set<String> = buildSet {
        if (WatchRuleEngine.RULE_V2_IMMOBILITY in reasons) add("HEALTH-04")
        if (reasons.any { it != WatchRuleEngine.SUPPRESSED_COOLDOWN }) add("HEALTH-02")
    }
}

/**
 * Experimental only: replaces each self-report field with the latest valid
 * value from the same block. It has no notification dependency by design.
 */
object CheckInShadowV1Policy : BreakPolicy {
    const val POLICY_VERSION = "checkin_shadow_v1"
    const val CHECK_IN_TTL_MS = 20 * 60_000L
    const val FALLBACK_FOCUS = "focus_fallback_session_start"
    const val FALLBACK_FATIGUE = "fatigue_fallback_session_start"

    override fun evaluate(context: PolicyContext): PolicyDecision {
        val valid = context.checkIns
            .asSequence()
            .filter { it.sessionId == context.sessionId && it.blockId == context.blockId }
            .filter { it.time.bootId == context.now.bootId }
            .filter { context.now.elapsedMs - it.time.elapsedMs in 0L..CHECK_IN_TTL_MS }
            .sortedByDescending { it.time.elapsedMs }
            .toList()
        val focus = valid.firstNotNullOfOrNull { it.focus }
        val fatigue = valid.firstNotNullOfOrNull { it.fatigue }
        val fallbacks = buildSet {
            if (focus == null) add(FALLBACK_FOCUS)
            if (fatigue == null) add(FALLBACK_FATIGUE)
        }
        val baseline = WatchRulesV2Policy.evaluate(
            context.copy(
                initialFocus = focus ?: context.initialFocus,
                initialFatigue = fatigue ?: context.initialFatigue,
                checkIns = emptyList(),
            )
        )
        return baseline.copy(policyVersion = POLICY_VERSION, fallbacks = fallbacks)
    }
}

sealed interface SessionCommand {
    val commandId: String
    val at: TimePoint

    data class Start(
        override val commandId: String,
        override val at: TimePoint,
        val session: ActiveStudySession,
    ) : SessionCommand

    data class AcceptReminder(
        override val commandId: String,
        override val at: TimePoint,
        val sessionId: String,
        val blockId: String,
        val reminderId: String,
        val plannedDurationMs: Long = StudySessionClock.BREAK_DURATION_MS,
    ) : SessionCommand

    data class DeferReminder(
        override val commandId: String,
        override val at: TimePoint,
        val sessionId: String,
        val blockId: String,
        val reminderId: String,
        val cooldownUntilWallMs: Long,
        val cooldownUntilElapsedMs: Long = at.elapsedMs + (cooldownUntilWallMs - at.wallMs).coerceAtLeast(0L),
        val cooldownBootId: String = at.bootId,
        val declineReasonCode: String = BreakPromptEvent.DECLINE_NOTIFICATION,
        val sessionDeferReason: String = "Để sau 20 phút",
    ) : SessionCommand

    data class StartBreak(
        override val commandId: String,
        override val at: TimePoint,
        val sessionId: String,
        val blockId: String,
        val plannedDurationMs: Long = StudySessionClock.BREAK_DURATION_MS,
        val activity: BreakActivityType = BreakActivityType.FULL_BREAK,
    ) : SessionCommand

    data class Pause(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
    data class ResumePaused(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
    data class ResumeAfterBreak(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
    data class ExtendBreak(
        override val commandId: String,
        override val at: TimePoint,
        val sessionId: String,
        val blockId: String,
        val durationMs: Long,
    ) : SessionCommand
    data class Recover(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
    data class Finish(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
    data class Cancel(override val commandId: String, override val at: TimePoint, val sessionId: String, val blockId: String) : SessionCommand
}

data class TransitionResult(
    val applied: Boolean,
    val duplicate: Boolean = false,
    val state: SessionState? = null,
    val revision: Long? = null,
    val reason: String? = null,
)

interface SessionController {
    fun dispatch(command: SessionCommand): TransitionResult
}

interface SessionStore {
    fun apply(command: SessionCommand): TransitionResult
}

class DefaultSessionController(private val store: SessionStore) : SessionController {
    override fun dispatch(command: SessionCommand): TransitionResult = store.apply(command)
}
