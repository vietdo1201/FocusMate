package vn.edu.uit.tpkd.wear.cogload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID
import kotlin.math.ceil

/** Handles exact alarms, retries and notification actions without depending on MainActivity. */
class BreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = StudySessionRepository(context)
        val expectedSessionId = intent.getStringExtra(BreakReminderScheduler.EXTRA_SESSION_ID)
        val expectedEventId = intent.getStringExtra(BreakReminderScheduler.EXTRA_EVENT_ID)
        Log.i(TAG, "received_at=${System.currentTimeMillis()} action=${intent.action}")
        when (intent.action) {
            BreakReminderScheduler.ACTION_CHECK ->
                checkAndNotify(context, repository, expectedSessionId)
            BreakReminderScheduler.ACTION_RETRY ->
                retryPendingAlert(context, repository, expectedSessionId, expectedEventId)
            BreakReminderScheduler.ACTION_ACCEPT ->
                acceptBreak(context, repository, expectedSessionId, expectedEventId)
            BreakReminderScheduler.ACTION_DEFER ->
                deferBreak(context, repository, expectedSessionId, expectedEventId)
            BreakReminderScheduler.ACTION_BREAK_COMPLETE ->
                handleBreakTimeEnded(context, repository, expectedSessionId)
            BreakReminderScheduler.ACTION_RESUME_STUDY ->
                resumeStudyAfterChoice(context, repository, expectedSessionId, expectedEventId)
            BreakReminderScheduler.ACTION_EXTEND_BREAK_5 ->
                extendBreak(context, repository, expectedSessionId, expectedEventId, 5)
            BreakReminderScheduler.ACTION_EXTEND_BREAK_10 ->
                extendBreak(context, repository, expectedSessionId, expectedEventId, 10)
        }
    }

    private fun checkAndNotify(
        context: Context,
        repository: StudySessionRepository,
        expectedSessionId: String?,
    ) {
        val active = repository.activeSession() ?: run {
            BreakReminderScheduler.cancel(context)
            SessionSensorService.stop(context)
            StudyDndController.disable(context)
            return
        }
        if (expectedSessionId != null && active.sessionId != expectedSessionId) return
        val now = System.currentTimeMillis()
        if (active.breakStartedAtMs != null) {
            if (active.pendingReminder != null) {
                BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
                return
            }
            val awaiting = repository.markBreakAwaitingDecisionIfDue(now)
            if (awaiting != null && StudySessionClock.isAwaitingBreakDecision(awaiting)) {
                createBreakEndedReminder(context, repository, awaiting, now)
            } else {
                StudyDndController.disable(context)
                SessionSensorService.stop(context)
                BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
            }
            return
        }

        val durationMs = repository.focusBlockDurationMs(active, now)
        if (active.pendingReminder != null) {
            BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
            return
        }
        val decision = repository.evaluateBreak(active, durationMs, now)
        val duplicateGuardPassed =
            now - active.lastPromptAtMs >= WatchRuleEngine.DUPLICATE_PROMPT_GUARD_MS
        if (!decision.shouldPrompt || !duplicateGuardPassed) {
            recordSuppressedCandidateIfNeeded(repository, active, durationMs, now, decision, duplicateGuardPassed)
            BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
            return
        }

        val reminder = BreakReminderScheduler.newSuggestion(
            ceil(durationMs / 60_000.0).toInt(),
            now,
        )
        val pendingActive = repository.setPendingReminder(active.sessionId, reminder) ?: return
        val attemptedActive = repository.advancePendingReminder(active.sessionId, reminder.eventId) ?: pendingActive
        val attemptedReminder = attemptedActive.pendingReminder ?: return
        val shown = BreakReminderScheduler.showPendingNotification(
            context,
            attemptedActive,
            attemptedReminder,
        )
        if (!shown) {
            ReminderDiagnostics.record(
                context,
                attemptedReminder,
                "watch",
                delivered = false,
                reason = "notifications_disabled_or_channel_blocked",
            )
        }
        recordPromptCandidate(
            repository,
            active,
            durationMs,
            now,
            decision,
            duplicateGuardPassed,
            reminder.eventId,
            shown,
        )
        val latest = repository.activeSession() ?: attemptedActive
        BreakReminderScheduler.schedule(context, latest, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun retryPendingAlert(
        context: Context,
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
    ) {
        val active = repository.activeSession() ?: return
        val pending = active.pendingReminder ?: return
        if (sessionId == null || eventId == null ||
            active.sessionId != sessionId || pending.eventId != eventId
        ) return
        val nextAt = pending.nextAlertAtMs ?: return
        if (System.currentTimeMillis() + 1_000L < nextAt) {
            BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
            return
        }
        val attempted = repository.advancePendingReminder(sessionId, eventId) ?: return
        val attemptedReminder = attempted.pendingReminder ?: return
        val shown = BreakReminderScheduler.showPendingNotification(context, attempted, attemptedReminder)
        if (!shown) {
            ReminderDiagnostics.record(
                context,
                attemptedReminder,
                "watch",
                delivered = false,
                reason = "notifications_disabled_or_channel_blocked",
            )
        }
        BreakReminderScheduler.schedule(context, attempted, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun acceptBreak(
        context: Context,
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
    ) {
        val active = repository.activeSession() ?: return
        val validSessionId = sessionId ?: return
        val validEventId = eventId ?: return
        if (!ReminderActionGuard.matches(
                active,
                validSessionId,
                validEventId,
                PendingReminderKind.BREAK_SUGGESTION,
            ) ||
            active.accepted != null
        ) return
        val updated = runCatching {
            repository.recordPromptResponse(
                sessionId = validSessionId,
                eventId = validEventId,
                accepted = true,
                declineReasonCode = null,
                sessionDeferReason = null,
                respondedAtMs = System.currentTimeMillis(),
            )
        }.getOrNull() ?: return
        if (updated.sessionId != validSessionId || updated.accepted != true) return
        val resting = repository.startBreak(validSessionId, System.currentTimeMillis()) ?: return
        StudyDndController.disable(context)
        BreakReminderScheduler.dismissReminder(context, validEventId)
        SessionSensorService.stop(context)
        BreakReminderScheduler.schedule(context, resting, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun deferBreak(
        context: Context,
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
    ) {
        val active = repository.activeSession() ?: return
        val validSessionId = sessionId ?: return
        val validEventId = eventId ?: return
        if (!ReminderActionGuard.matches(
                active,
                validSessionId,
                validEventId,
                PendingReminderKind.BREAK_SUGGESTION,
            ) ||
            active.accepted != null
        ) return
        val now = System.currentTimeMillis()
        val cooldownUntil = now + WatchRuleEngine.COOLDOWN_MS
        val updated = repository.recordPromptResponse(
            sessionId = validSessionId,
            eventId = validEventId,
            accepted = false,
            declineReasonCode = BreakPromptEvent.DECLINE_NOTIFICATION,
            sessionDeferReason = "Từ chối từ thông báo",
            respondedAtMs = now,
            quietUntilMs = cooldownUntil,
        ) ?: return
        BreakReminderScheduler.dismissReminder(context, validEventId)
        BreakReminderScheduler.schedule(context, updated, cooldownUntil)
        notifySessionChanged(context)
    }

    private fun handleBreakTimeEnded(
        context: Context,
        repository: StudySessionRepository,
        expectedSessionId: String?,
    ) {
        val active = repository.activeSession() ?: run {
            BreakReminderScheduler.cancel(context)
            return
        }
        if (expectedSessionId != null && active.sessionId != expectedSessionId) return
        val now = System.currentTimeMillis()
        val awaiting = repository.markBreakAwaitingDecisionIfDue(now)
        if (awaiting != null && StudySessionClock.isAwaitingBreakDecision(awaiting)) {
            createBreakEndedReminder(context, repository, awaiting, now)
        } else {
            BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())
        }
    }

    private fun createBreakEndedReminder(
        context: Context,
        repository: StudySessionRepository,
        awaiting: ActiveStudySession,
        nowMs: Long,
    ) {
        StudyDndController.disable(context)
        SessionSensorService.stop(context)
        val reminder = awaiting.pendingReminder
            ?: BreakReminderScheduler.newBreakEnded(nowMs)
        val pendingActive = if (awaiting.pendingReminder == null) {
            repository.setPendingReminder(awaiting.sessionId, reminder) ?: awaiting
        } else {
            awaiting
        }
        val attempted = if (reminder.attempt == 0) {
            repository.advancePendingReminder(awaiting.sessionId, reminder.eventId) ?: pendingActive
        } else {
            pendingActive
        }
        attempted.pendingReminder?.let {
            val shown = BreakReminderScheduler.showPendingNotification(context, attempted, it)
            if (!shown) {
                ReminderDiagnostics.record(
                    context,
                    it,
                    "watch",
                    delivered = false,
                    reason = "notifications_disabled_or_channel_blocked",
                )
            }
        }
        BreakReminderScheduler.schedule(context, attempted, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun resumeStudyAfterChoice(
        context: Context,
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
    ) {
        if (!validBreakEndedAction(repository, sessionId, eventId)) return
        val resumed = repository.resumeStudyAfterBreak(sessionId!!, System.currentTimeMillis()) ?: return
        BreakReminderScheduler.dismissReminder(context, eventId!!)
        StudyDndController.enable(context)
        SessionSensorService.start(context)
        BreakReminderScheduler.schedule(context, resumed, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun extendBreak(
        context: Context,
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
        minutes: Int,
    ) {
        if (!validBreakEndedAction(repository, sessionId, eventId)) return
        val extended = repository.extendBreak(
            sessionId = sessionId!!,
            nowMs = System.currentTimeMillis(),
            durationMs = minutes * 60_000L,
        ) ?: return
        BreakReminderScheduler.dismissReminder(context, eventId!!)
        StudyDndController.disable(context)
        SessionSensorService.stop(context)
        BreakReminderScheduler.schedule(context, extended, repository.cooldownUntilMs())
        notifySessionChanged(context)
    }

    private fun validBreakEndedAction(
        repository: StudySessionRepository,
        sessionId: String?,
        eventId: String?,
    ): Boolean {
        val active = repository.activeSession() ?: return false
        return ReminderActionGuard.matches(
            active,
            sessionId,
            eventId,
            PendingReminderKind.BREAK_ENDED,
        )
    }

    private fun recordPromptCandidate(
        repository: StudySessionRepository,
        active: ActiveStudySession,
        durationMs: Long,
        nowMs: Long,
        decision: BreakDecision,
        duplicateGuardPassed: Boolean,
        eventId: String,
        shown: Boolean,
    ) {
        runCatching {
            FocusMatePromptEventPolicy.create(
                active = active,
                candidateAtMs = nowMs,
                durationAtCandidateMs = durationMs,
                decision = decision,
                duplicateGuardPassed = duplicateGuardPassed,
                prompted = shown,
                deliveryChannel = if (shown) {
                    BreakPromptEvent.CHANNEL_NOTIFICATION
                } else {
                    BreakPromptEvent.CHANNEL_NONE
                },
                triggerSource = if (active.accepted == false) {
                    BreakPromptEvent.TRIGGER_COOLDOWN_EXPIRY
                } else {
                    BreakPromptEvent.TRIGGER_THRESHOLD_ALARM
                },
                suppressionReason = if (shown) {
                    null
                } else {
                    BreakPromptEvent.SUPPRESSION_NOTIFICATIONS_DISABLED
                },
                eventId = eventId,
            )
        }.getOrNull()?.let { repository.recordPromptCandidate(it) }
    }

    private fun recordSuppressedCandidateIfNeeded(
        repository: StudySessionRepository,
        active: ActiveStudySession,
        durationMs: Long,
        nowMs: Long,
        decision: BreakDecision,
        duplicateGuardPassed: Boolean,
    ) {
        if (!decision.shouldBreak) return
        val suppressionReason = decision.promptSuppressionReason
            ?: if (!duplicateGuardPassed) BreakPromptEvent.SUPPRESSION_DUPLICATE_GUARD else null
            ?: return
        runCatching {
            FocusMatePromptEventPolicy.create(
                active = active,
                candidateAtMs = nowMs,
                durationAtCandidateMs = durationMs,
                decision = decision,
                duplicateGuardPassed = duplicateGuardPassed,
                prompted = false,
                deliveryChannel = BreakPromptEvent.CHANNEL_NONE,
                triggerSource = BreakPromptEvent.TRIGGER_COOLDOWN_EXPIRY,
                suppressionReason = suppressionReason,
                eventId = UUID.randomUUID().toString(),
            )
        }.getOrNull()?.let { repository.recordPromptCandidate(it) }
    }

    private fun notifySessionChanged(context: Context) {
    }

    private companion object {
        const val TAG = "FocusMateReminder"
    }
}
