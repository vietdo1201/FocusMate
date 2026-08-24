// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/** Keeps an active study timer visible on Wear surfaces without keeping the screen awake. */
object StudyOngoingActivity {
    private const val CHANNEL_ID = "focusmate_study_status_v1"
    private const val NOTIFICATION_ID = 4520

    fun show(context: Context, active: ActiveStudySession, nowMs: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trạng thái phiên học", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Timer phiên học hiện trên mặt đồng hồ và màn hình gần đây"
            }
        )
        if (!manager.areNotificationsEnabled()) return

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stateText = when {
            active.pendingReminder != null -> "Nên nghỉ ngay"
            StudySessionClock.isAwaitingBreakDecision(active) -> "Chờ bạn chọn"
            StudySessionClock.isOnBreak(active, nowMs) -> "Đang nghỉ"
            else -> "Đang học"
        }
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("FocusMate · $stateText")
            .setContentText(active.taskType)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Notification.CATEGORY_STOPWATCH
                } else {
                    Notification.CATEGORY_STATUS
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val status = when {
            active.pendingReminder != null ->
                Status.forPart(Status.TextPart("Nên nghỉ ngay"))
            StudySessionClock.isAwaitingBreakDecision(active) ->
                Status.forPart(Status.TextPart("Chờ bạn chọn"))
            StudySessionClock.isOnBreak(active, nowMs) -> {
                val remaining = StudySessionClock.breakRemainingMs(active, nowMs)
                val elapsedEnd = SystemClock.elapsedRealtime() + remaining
                Status.Builder()
                    .addTemplate("Nghỉ · #time#")
                    .addPart("time", Status.TimerPart(elapsedEnd))
                    .build()
            }
            else -> {
                val studyDuration = StudySessionClock.studyDurationMs(active, nowMs)
                val elapsedStart = SystemClock.elapsedRealtime() - studyDuration
                Status.Builder()
                    .addTemplate("Học · #time#")
                    .addPart("time", Status.StopwatchPart(elapsedStart))
                    .build()
            }
        }
        OngoingActivity.Builder(context, NOTIFICATION_ID, notificationBuilder)
            .setStaticIcon(R.drawable.ic_launcher)
            .setTouchIntent(openIntent)
            .setStatus(status)
            .build()
            .apply(context)
        manager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
