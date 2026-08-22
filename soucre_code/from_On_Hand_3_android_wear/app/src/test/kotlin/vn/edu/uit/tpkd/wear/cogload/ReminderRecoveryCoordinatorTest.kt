package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRecoveryCoordinatorTest {
    @Test
    fun rebootWithActiveSessionRecreatesChannelsAndAlarms() {
        val fake = FakeRecoveryPort(active = active(), cooldown = 42_000L)

        val outcome = ReminderRecoveryCoordinator(fake).recover("android.intent.action.BOOT_COMPLETED")

        assertEquals(ReminderRecoveryOutcome.RESTORED, outcome)
        assertTrue(fake.channelsPrepared)
        assertEquals("session-1" to 42_000L, fake.scheduled)
        assertFalse(fake.cancelled)
    }

    @Test
    fun rebootWithoutActiveSessionCancelsOrphanAlarmsOnly() {
        val fake = FakeRecoveryPort(active = null)

        val outcome = ReminderRecoveryCoordinator(fake).recover("android.intent.action.BOOT_COMPLETED")

        assertEquals(ReminderRecoveryOutcome.CANCELLED_WITHOUT_ACTIVE_SESSION, outcome)
        assertTrue(fake.cancelled)
        assertFalse(fake.channelsPrepared)
    }

    @Test
    fun unrelatedBroadcastHasNoAlarmSideEffects() {
        val fake = FakeRecoveryPort(active = active())

        assertEquals(ReminderRecoveryOutcome.IGNORED, ReminderRecoveryCoordinator(fake).recover("other"))
        assertFalse(fake.cancelled)
        assertEquals(null, fake.scheduled)
    }

    private fun active() = ActiveStudySession(
        sessionId = "session-1",
        startTimeMs = 1_000L,
        subject = "Không áp dụng",
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 5,
    )

    private class FakeRecoveryPort(
        private val active: ActiveStudySession?,
        private val cooldown: Long = 0L,
    ) : ReminderRecoveryPort {
        var cancelled = false
        var channelsPrepared = false
        var scheduled: Pair<String, Long>? = null

        override fun activeSession(): ActiveStudySession? = active
        override fun cooldownUntilMs(): Long = cooldown
        override fun cancelAlarms() { cancelled = true }
        override fun prepareNotificationChannels() { channelsPrepared = true }
        override fun scheduleAlarms(active: ActiveStudySession, cooldownUntilMs: Long) {
            scheduled = active.sessionId to cooldownUntilMs
        }
    }
}
