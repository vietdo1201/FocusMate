// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/** Schedules user-visible break checks independently from the Wear activity. */
object BreakReminderScheduler {
    const val ACTION_CHECK = "vn.edu.uit.tpkd.wear.cogload.action.CHECK_BREAK"
    const val ACTION_RETRY = "vn.edu.uit.tpkd.wear.cogload.action.RETRY_BREAK_ALERT"
    const val ACTION_ACCEPT = "vn.edu.uit.tpkd.wear.cogload.action.ACCEPT_BREAK"
    const val ACTION_DEFER = "vn.edu.uit.tpkd.wear.cogload.action.DEFER_BREAK"
    const val ACTION_BREAK_COMPLETE = "vn.edu.uit.tpkd.wear.cogload.action.BREAK_COMPLETE"
    const val ACTION_RESUME_STUDY = "vn.edu.uit.tpkd.wear.cogload.action.RESUME_STUDY"
    const val ACTION_EXTEND_BREAK_5 = "vn.edu.uit.tpkd.wear.cogload.action.EXTEND_BREAK_5"
    const val ACTION_EXTEND_BREAK_10 = "vn.edu.uit.tpkd.wear.cogload.action.EXTEND_BREAK_10"
    const val EXTRA_SESSION_ID = "focusmate_session_id"
    const val EXTRA_EVENT_ID = "focusmate_prompt_event_id"

    private const val LEGACY_NOTIFICATION_ID = 4506
    private const val REQUEST_CHECK = 4501
    private const val REQUEST_RETRY = 4508
    private const val REQUEST_ACCEPT = 4502
    private const val REQUEST_DEFER = 4503
    private const val REQUEST_BREAK_COMPLETE = 4504
    private const val REQUEST_RESUME_STUDY = 4505
    private const val REQUEST_EXTEND_BREAK_5 = 4506
    private const val REQUEST_EXTEND_BREAK_10 = 4507
    private const val PREFS = "focusmate_reminder_runtime"
    private const val KEY_NEXT_ALARM_AT = "next_alarm_at_ms"

    fun schedule(context: Context, active: ActiveStudySession, cooldownUntilMs: Long) {
        val alarmManager = alarmManager(context)
        val nowPoint = AndroidSessionClock(context).now()
        val now = nowPoint.wallMs
        cancelAlarm(alarmManager, context, ACTION_CHECK, REQUEST_CHECK)
        cancelAlarm(alarmManager, context, ACTION_RETRY, REQUEST_RETRY)
        cancelAlarm(alarmManager, context, ACTION_BREAK_COMPLETE, REQUEST_BREAK_COMPLETE)
        rememberNextAlarm(context, null)

        if (active.timeline?.state == SessionState.RECOVERY_REQUIRED) {
            StudyOngoingActivity.show(context, active)
            return
        }

        val pending = active.pendingReminder
        if (pending != null) {
            val nextAlertAt = pending.nextAlertAtMs
            if (nextAlertAt != null) {
                scheduleAlarm(
                    context,
                    maxOf(nextAlertAt, now + 1_000L),
                    ACTION_RETRY,
                    REQUEST_RETRY,
                    active.sessionId,
                    pending.eventId,
                    triggerElapsedMs = pending.nextAlertElapsedMs
                        ?.takeIf { pending.createdBootId == nowPoint.bootId },
                )
            }
            StudyOngoingActivity.show(context, active)
            return
        }

        if (StudySessionClock.isPaused(active)) {
            StudyOngoingActivity.show(context, active)
            return
        }

        if (active.breakStartedAtMs != null) {
            if (StudySessionClock.isAwaitingBreakDecision(active)) {
                rememberNextAlarm(context, null)
                StudyOngoingActivity.show(context, active)
                return
            }
            scheduleAlarm(
                context,
                now + maxOf(StudySessionRepository(context).breakRemainingMs(active), 1_000L),
                ACTION_BREAK_COMPLETE,
                REQUEST_BREAK_COMPLETE,
                active.sessionId,
            )
            StudyOngoingActivity.show(context, active)
            return
        }

        val currentFocusBlockMs = StudySessionRepository(context).focusBlockDurationMs(active, now)
        val validMotionDurationMs = active.motionBlockValidDurationMs.takeIf { it > 0L }
            ?: (active.motionWindowCount * 30_000L)
        val motionCoverage = (validMotionDurationMs.toDouble() / currentFocusBlockMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val dueNow = WatchRuleEngine.evaluate(
            ReminderContext(
                studyDurationMs = currentFocusBlockMs,
                fatigueScore = active.fatigueScore,
                focusScore = active.focusScore,
                nowMs = now,
                cooldownUntilMs = cooldownUntilMs,
                motion = active.motionActivityObservedAtMs?.let {
                    MotionEvidence(active.continuousImmobileMs, motionCoverage, it)
                },
            )
        ).shouldPrompt
        if (dueNow) {
            scheduleAlarm(context, now + 1_000L, ACTION_CHECK, REQUEST_CHECK, active.sessionId)
            StudyOngoingActivity.show(context, active)
            return
        }
        val nextRuleBoundaryMs = when {
            currentFocusBlockMs < 30 * 60_000L -> 30 * 60_000L
            currentFocusBlockMs < 45 * 60_000L -> 45 * 60_000L
            currentFocusBlockMs < 60 * 60_000L -> 60 * 60_000L
            else -> currentFocusBlockMs + 60_000L
        }
        val boundaryAt = now + (nextRuleBoundaryMs - currentFocusBlockMs).coerceAtLeast(1_000L)
        val duplicateGuardAt = active.lastPromptAtMs + WatchRuleEngine.DUPLICATE_PROMPT_GUARD_MS
        // A future cooldown must not postpone an earlier rule boundary, but an
        // already-eligible suppressed candidate must be revisited at expiry.
        val candidateAt = if (cooldownUntilMs > now && currentFocusBlockMs >= 30 * 60_000L) {
            minOf(boundaryAt, cooldownUntilMs)
        } else {
            boundaryAt
        }
        val triggerAt = maxOf(candidateAt, duplicateGuardAt, now + 1_000L)
        scheduleAlarm(context, triggerAt, ACTION_CHECK, REQUEST_CHECK, active.sessionId)
        StudyOngoingActivity.show(context, active)
    }

    fun cancel(context: Context) {
        val alarmManager = alarmManager(context)
        cancelAlarm(alarmManager, context, ACTION_CHECK, REQUEST_CHECK)
        cancelAlarm(alarmManager, context, ACTION_RETRY, REQUEST_RETRY)
        cancelAlarm(alarmManager, context, ACTION_BREAK_COMPLETE, REQUEST_BREAK_COMPLETE)
        rememberNextAlarm(context, null)
        BreakAlertChannels.notificationManager(context).cancelAll()
        StudyOngoingActivity.cancel(context)
    }

    fun requestImmediateCheck(context: Context) {
        context.sendBroadcast(Intent(context, BreakReminderReceiver::class.java).setAction(ACTION_CHECK))
    }

    fun newSuggestion(
        durationMinutes: Int,
        nowMs: Long,
        title: String? = null,
        message: String? = null,
        reasonCodes: Set<String> = emptySet(),
        evidenceReferences: Set<String> = emptySet(),
        timePoint: TimePoint? = null,
    ): PendingReminder = PendingReminder(
        eventId = UUID.randomUUID().toString(),
        kind = PendingReminderKind.BREAK_SUGGESTION,
        createdAtMs = nowMs,
        createdAtElapsedMs = timePoint?.elapsedMs,
        createdBootId = timePoint?.bootId,
        nextAlertElapsedMs = timePoint?.elapsedMs,
        title = title?.trim()?.takeIf(String::isNotBlank)?.take(96),
        message = message?.trim()?.takeIf(String::isNotBlank)?.take(360)
            ?: "Đã học $durationMinutes phút — nên nghỉ 5 phút",
        reasonCodes = reasonCodes,
        evidenceReferences = evidenceReferences,
    )

    fun newBreakEnded(nowMs: Long, timePoint: TimePoint? = null): PendingReminder = PendingReminder(
        eventId = UUID.randomUUID().toString(),
        kind = PendingReminderKind.BREAK_ENDED,
        createdAtMs = nowMs,
        createdAtElapsedMs = timePoint?.elapsedMs,
        createdBootId = timePoint?.bootId,
        nextAlertElapsedMs = timePoint?.elapsedMs,
        message = "Đã hết giờ nghỉ — tiếp tục học hay nghỉ thêm?",
    )

    fun showPendingNotification(
        context: Context,
        active: ActiveStudySession,
        reminder: PendingReminder,
    ): Boolean {
        val manager = BreakAlertChannels.notificationManager(context)
        val channelId = BreakAlertChannels.channelForDelivery(context)
        val channel = manager.getNotificationChannel(channelId)
        if (!manager.areNotificationsEnabled() ||
            channel == null ||
            channel.importance == NotificationManager.IMPORTANCE_NONE
        ) return false

        cancelReminderNotifications(context, reminder.eventId)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(
                if (reminder.kind == PendingReminderKind.BREAK_SUGGESTION) {
                    reminder.title ?: "Đến lúc nghỉ một chút"
                } else {
                    "Hết giờ nghỉ"
                }
            )
            .setContentText(reminder.message)
            .setStyle(Notification.BigTextStyle().bigText(reminder.message))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_ALARM)
            .setLocalOnly(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (reminder.kind == PendingReminderKind.BREAK_SUGGESTION) {
            builder
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Nghỉ 5 phút",
                        pendingBroadcast(
                            context,
                            ACTION_ACCEPT,
                            eventRequestCode(REQUEST_ACCEPT, reminder.eventId),
                            active.sessionId,
                            reminder.eventId,
                        ),
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Để sau 20 phút",
                        pendingBroadcast(
                            context,
                            ACTION_DEFER,
                            eventRequestCode(REQUEST_DEFER, reminder.eventId),
                            active.sessionId,
                            reminder.eventId,
                        ),
                    ).build()
                )
        } else {
            builder
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Tiếp tục học",
                        pendingBroadcast(
                            context,
                            ACTION_RESUME_STUDY,
                            eventRequestCode(REQUEST_RESUME_STUDY, reminder.eventId),
                            active.sessionId,
                            reminder.eventId,
                        ),
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Nghỉ thêm 5 phút",
                        pendingBroadcast(
                            context,
                            ACTION_EXTEND_BREAK_5,
                            eventRequestCode(REQUEST_EXTEND_BREAK_5, reminder.eventId),
                            active.sessionId,
                            reminder.eventId,
                        ),
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Nghỉ thêm 10 phút",
                        pendingBroadcast(
                            context,
                            ACTION_EXTEND_BREAK_10,
                            eventRequestCode(REQUEST_EXTEND_BREAK_10, reminder.eventId),
                            active.sessionId,
                            reminder.eventId,
                        ),
                    ).build()
                )
        }
        val id = notificationId(reminder.eventId, reminder.attempt.coerceAtLeast(1))
        manager.notify(id, builder.build())
        ReminderDiagnostics.record(context, reminder, "watch", delivered = true)
        return true
    }

    fun dismissReminder(context: Context, eventId: String) {
        cancelReminderNotifications(context, eventId)
        BreakAlertChannels.notificationManager(context).cancel(LEGACY_NOTIFICATION_ID)
    }

    fun showTestAlert(context: Context): Boolean {
        val manager = BreakAlertChannels.notificationManager(context)
        val channelId = BreakAlertChannels.channelForDelivery(context)
        val channel = manager.getNotificationChannel(channelId)
        if (!manager.areNotificationsEnabled() ||
            channel == null ||
            channel.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        manager.notify(
            4599,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("FocusMate rung thử")
                .setContentText("Nếu bạn cảm nhận được rung, kênh nhắc đang hoạt động.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .setTimeoutAfter(8_000L)
                .build(),
        )
        return true
    }

    fun exactAlarmsReady(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    fun nextAlarmAtMs(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_NEXT_ALARM_AT, 0L)
            .takeIf { it > 0L }

    private fun scheduleAlarm(
        context: Context,
        triggerAtMs: Long,
        action: String,
        requestCode: Int,
        sessionId: String? = null,
        eventId: String? = null,
        triggerElapsedMs: Long? = null,
    ) {
        val alarmManager = alarmManager(context)
        val operation = pendingBroadcast(context, action, requestCode, sessionId, eventId)
        val elapsedTrigger = triggerElapsedMs ?: run {
            val delay = (triggerAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            SystemClock.elapsedRealtime() + delay
        }
        if (exactAlarmsReady(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, operation)
        }
        rememberNextAlarm(context, triggerAtMs)
        Log.i(TAG, "planned_wall=$triggerAtMs planned_elapsed=$elapsedTrigger action=$action exact=${exactAlarmsReady(context)}")
    }

    private fun cancelAlarm(
        alarmManager: AlarmManager,
        context: Context,
        action: String,
        requestCode: Int,
    ) {
        alarmManager.cancel(pendingBroadcast(context, action, requestCode))
    }

    private fun pendingBroadcast(
        context: Context,
        action: String,
        requestCode: Int,
        sessionId: String? = null,
        eventId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, BreakReminderReceiver::class.java).setAction(action)
        if (sessionId != null) intent.putExtra(EXTRA_SESSION_ID, sessionId)
        if (eventId != null) intent.putExtra(EXTRA_EVENT_ID, eventId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelReminderNotifications(context: Context, eventId: String) {
        val manager = BreakAlertChannels.notificationManager(context)
        for (attempt in 1..BreakReminderPolicy.MAX_ATTEMPTS) {
            manager.cancel(notificationId(eventId, attempt))
        }
    }

    private fun notificationId(eventId: String, attempt: Int): Int =
        10_000 + (eventId.hashCode() and 0x3fffffff) % 900_000

    private fun eventRequestCode(base: Int, eventId: String): Int =
        base + (eventId.hashCode() and 0x3fffffff)

    private fun rememberNextAlarm(context: Context, triggerAtMs: Long?) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val earliest = triggerAtMs?.let { requested ->
            preferences.getLong(KEY_NEXT_ALARM_AT, 0L)
                .takeIf { it > 0L }
                ?.let { existing -> minOf(existing, requested) }
                ?: requested
        } ?: 0L
        preferences.edit().putLong(KEY_NEXT_ALARM_AT, earliest).apply()
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private const val TAG = "FocusMateReminder"
}
