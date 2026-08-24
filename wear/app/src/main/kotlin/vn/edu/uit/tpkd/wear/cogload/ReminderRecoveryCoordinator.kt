// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

internal interface ReminderRecoveryPort {
    fun activeSession(): ActiveStudySession?
    fun cooldownUntilMs(): Long
    fun cancelAlarms()
    fun prepareNotificationChannels()
    fun scheduleAlarms(active: ActiveStudySession, cooldownUntilMs: Long)
}

internal enum class ReminderRecoveryOutcome {
    IGNORED,
    CANCELLED_WITHOUT_ACTIVE_SESSION,
    RESTORED,
}

/** Testable reboot/update recovery seam around Android alarm and sync APIs. */
internal class ReminderRecoveryCoordinator(
    private val port: ReminderRecoveryPort,
) {
    fun recover(action: String?): ReminderRecoveryOutcome {
        if (action !in SUPPORTED_ACTIONS) return ReminderRecoveryOutcome.IGNORED
        val active = port.activeSession()
        if (active == null) {
            port.cancelAlarms()
            return ReminderRecoveryOutcome.CANCELLED_WITHOUT_ACTIVE_SESSION
        }
        port.prepareNotificationChannels()
        port.scheduleAlarms(active, port.cooldownUntilMs())
        return ReminderRecoveryOutcome.RESTORED
    }

    internal companion object {
        val SUPPORTED_ACTIONS = setOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
