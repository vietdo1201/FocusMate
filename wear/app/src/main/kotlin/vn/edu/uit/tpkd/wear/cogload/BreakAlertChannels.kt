// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Versioned channels avoid inheriting the broken v1 DND setting. The priority
 * channel is deliberately created only after policy access has been granted.
 */
object BreakAlertChannels {
    const val STANDARD_CHANNEL_ID = "focusmate_break_alerts_standard_v3"
    const val PRIORITY_CHANNEL_ID = "focusmate_break_alerts_priority_v3"

    fun ensureStandard(context: Context): NotificationChannel {
        val manager = manager(context)
        manager.createNotificationChannel(newChannel(STANDARD_CHANNEL_ID, "Nhắc nghỉ FocusMate"))
        return requireNotNull(manager.getNotificationChannel(STANDARD_CHANNEL_ID))
    }

    fun ensurePriorityIfAllowed(context: Context): NotificationChannel? {
        val manager = manager(context)
        if (!manager.isNotificationPolicyAccessGranted) return null
        manager.createNotificationChannel(
            newChannel(PRIORITY_CHANNEL_ID, "Nhắc nghỉ ưu tiên FocusMate").apply {
                setBypassDnd(true)
            }
        )
        return manager.getNotificationChannel(PRIORITY_CHANNEL_ID)
    }

    fun channelForDelivery(context: Context): String {
        val priority = ensurePriorityIfAllowed(context)
        if (priority != null && priority.canBypassDnd()) return PRIORITY_CHANNEL_ID
        ensureStandard(context)
        return STANDARD_CHANNEL_ID
    }

    fun priorityReady(context: Context): Boolean {
        val channel = ensurePriorityIfAllowed(context) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH &&
            channel.shouldVibrate() &&
            channel.canBypassDnd()
    }

    fun notificationsReady(context: Context): Boolean {
        val manager = manager(context)
        val channel = if (StudyDndController.hasAccess(context)) {
            ensurePriorityIfAllowed(context)
        } else {
            ensureStandard(context)
        }
        return manager.areNotificationsEnabled() &&
            channel != null &&
            channel.importance != NotificationManager.IMPORTANCE_NONE &&
            channel.shouldVibrate()
    }

    fun notificationManager(context: Context): NotificationManager = manager(context)

    private fun newChannel(id: String, name: String): NotificationChannel =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rung nhắc nghỉ và báo hết giờ nghỉ"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setAllowBubbles(false)
        }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
