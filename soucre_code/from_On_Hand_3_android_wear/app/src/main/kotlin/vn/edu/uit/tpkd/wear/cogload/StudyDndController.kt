package vn.edu.uit.tpkd.wear.cogload

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Owns only the DND state enabled for an active FocusMate session. */
object StudyDndController {
    private const val PREFS = "focusmate_dnd_state"
    private const val KEY_OWNED = "owned"
    private const val KEY_PREVIOUS_FILTER = "previous_filter"

    fun hasAccess(context: Context): Boolean =
        manager(context).isNotificationPolicyAccessGranted

    fun isOwned(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNED, false)

    fun enable(context: Context): Boolean {
        val notificationManager = manager(context)
        if (!notificationManager.isNotificationPolicyAccessGranted) return false
        // Never turn on a filter that would silence FocusMate itself.
        if (!BreakAlertChannels.priorityReady(context)) return false
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyOwned = preferences.getBoolean(KEY_OWNED, false)
        val previousFilter = notificationManager.currentInterruptionFilter
        return runCatching {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            if (!alreadyOwned) {
                preferences.edit()
                    .putInt(KEY_PREVIOUS_FILTER, previousFilter)
                    .putBoolean(KEY_OWNED, true)
                    .apply()
            }
            true
        }.getOrElse { false }
    }

    fun disable(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_OWNED, false)) return
        val notificationManager = manager(context)
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val restoreFilter = if (Build.VERSION.SDK_INT >= 35) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                preferences.getInt(KEY_PREVIOUS_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            runCatching { notificationManager.setInterruptionFilter(restoreFilter) }
        }
        preferences.edit().clear().apply()
    }

    fun requestAccess(activity: Activity) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
