package vn.edu.uit.tpkd.wear.cogload

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRecoveryReceiverRobolectricTest {
    private lateinit var context: Context
    private lateinit var repository: StudySessionRepository
    private lateinit var alarms: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("focusmate_active_state_v1", "focusmate_reminder_runtime").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        repository = StudySessionRepository(context)
        alarms = context.getSystemService(AlarmManager::class.java)
    }

    @After
    fun tearDown() {
        BreakReminderScheduler.cancel(context)
        repository.clearActiveSession()
    }

    @Test
    fun duplicateBootBroadcastReplacesRatherThanDuplicatesAlarm() {
        repository.saveActiveSession(active())
        val receiver = ReminderRecoveryReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.map { it.operation }.distinct().size)
    }

    @Test
    fun duplicateBootWithoutSessionLeavesNoOrphanAlarm() {
        val receiver = ReminderRecoveryReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0, shadowOf(alarms).scheduledAlarms.size)
        assertNull(BreakReminderScheduler.nextAlarmAtMs(context))
    }

    private fun active() = ActiveStudySession(
        sessionId = "recovery-idempotent",
        startTimeMs = System.currentTimeMillis() - 5 * 60_000L,
        subject = "Không áp dụng",
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 5,
    )
}
