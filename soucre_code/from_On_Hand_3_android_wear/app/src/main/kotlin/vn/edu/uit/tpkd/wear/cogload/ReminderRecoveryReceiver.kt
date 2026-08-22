package vn.edu.uit.tpkd.wear.cogload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores alarms cleared by reboot, app replacement or an exact-alarm permission change. */
class ReminderRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ReminderRecoveryCoordinator.SUPPORTED_ACTIONS) return
        val repository = StudySessionRepository(context)
        ReminderRecoveryCoordinator(
            object : ReminderRecoveryPort {
                override fun activeSession(): ActiveStudySession? = repository.activeSession()
                override fun cooldownUntilMs(): Long = repository.cooldownUntilMs()
                override fun cancelAlarms() = BreakReminderScheduler.cancel(context)
                override fun prepareNotificationChannels() {
                    BreakAlertChannels.ensureStandard(context)
                    BreakAlertChannels.ensurePriorityIfAllowed(context)
                }
                override fun scheduleAlarms(active: ActiveStudySession, cooldownUntilMs: Long) {
                    BreakReminderScheduler.schedule(context, active, cooldownUntilMs)
                }
            }
        ).recover(intent.action)
    }
}
