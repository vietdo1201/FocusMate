// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        FocusMateSessionDatabaseProvider.closeForTests()
        context.deleteDatabase(FocusMateSessionDatabase.DATABASE_NAME)
        repository = StudySessionRepository(context)
        alarms = context.getSystemService(AlarmManager::class.java)
    }

    @After
    fun tearDown() {
        BreakReminderScheduler.cancel(context)
        if (::repository.isInitialized) repository.clearActiveSession()
        FocusMateSessionDatabaseProvider.closeForTests()
        context.deleteDatabase(FocusMateSessionDatabase.DATABASE_NAME)
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

    @Test
    fun recoveryWhenRuleIsAlreadyEligibleSchedulesImmediateEvaluation() {
        val now = System.currentTimeMillis()
        val active = active().copy(
            startTimeMs = now - 52 * 60_000L,
            fatigueScore = 6,
        )
        repository.saveActiveSession(active)
        repository.setCooldownUntilMs(now - 60_000L)

        BreakReminderScheduler.schedule(context, active, repository.cooldownUntilMs())

        val scheduledAt = requireNotNull(BreakReminderScheduler.nextAlarmAtMs(context))
        assertTrue(scheduledAt in (now + 500L)..(now + 5_000L))
    }

    @Test
    fun rebootReconcilesBootIdentityBeforeScheduling() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 11)
        val now = System.currentTimeMillis()
        repository.saveActiveSession(
            active().copy(
                timeline = SessionTimelineSnapshot(
                    sessionId = "recovery-idempotent",
                    revision = 1L,
                    state = SessionState.STUDYING,
                    blockId = "block-0",
                    enteredAt = TimePoint(now, SystemClock.elapsedRealtime(), "boot-11"),
                    accumulatedStudyMs = 10 * 60_000L,
                )
            )
        )
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 12)

        ReminderRecoveryReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(SessionState.RECOVERY_REQUIRED, repository.activeSession()?.timeline?.state)
        assertEquals(0, shadowOf(alarms).scheduledAlarms.size)
        assertNull(BreakReminderScheduler.nextAlarmAtMs(context))
    }

    @Test
    fun permissionToggleUsesOnlyRemainingDeliverySlot() {
        val nowPoint = AndroidSessionClock(context).now()
        val eligible = active().copy(
            startTimeMs = nowPoint.wallMs - 46 * 60_000L,
            fatigueScore = 6,
            timeline = SessionTimelineSnapshot(
                sessionId = "recovery-idempotent",
                revision = 0L,
                state = SessionState.STUDYING,
                blockId = "block-0",
                enteredAt = nowPoint,
                accumulatedStudyMs = 46 * 60_000L,
            ),
        )
        repository.saveActiveSession(eligible)
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadowManager = shadowOf(manager)
        shadowManager.setNotificationsEnabled(false)
        val receiver = BreakReminderReceiver()

        receiver.onReceive(
            context,
            Intent(context, BreakReminderReceiver::class.java)
                .setAction(BreakReminderScheduler.ACTION_CHECK)
                .putExtra(BreakReminderScheduler.EXTRA_SESSION_ID, eligible.sessionId),
        )

        val blocked = requireNotNull(repository.activeSession()?.pendingReminder)
        assertEquals(ReminderDeliveryState.DELIVERY_BLOCKED, blocked.deliveryState)
        assertEquals(1, blocked.attempt)
        assertTrue(shadowManager.allNotifications.isEmpty())

        shadowManager.setNotificationsEnabled(true)
        val due = blocked.copy(
            nextAlertAtMs = System.currentTimeMillis(),
            nextAlertElapsedMs = SystemClock.elapsedRealtime(),
        )
        repository.saveActiveSession(requireNotNull(repository.activeSession()).copy(pendingReminder = due))
        val retry = Intent(context, BreakReminderReceiver::class.java)
            .setAction(BreakReminderScheduler.ACTION_RETRY)
            .putExtra(BreakReminderScheduler.EXTRA_SESSION_ID, eligible.sessionId)
            .putExtra(BreakReminderScheduler.EXTRA_EVENT_ID, blocked.eventId)
        receiver.onReceive(context, retry)
        receiver.onReceive(context, retry)

        val after = requireNotNull(repository.activeSession()?.pendingReminder)
        assertEquals(2, after.attempt)
        assertFalse(shadowManager.allNotifications.isEmpty())
        val deliveries = repository.reminderHistory(eligible.sessionId).single().deliveries
        assertEquals(2, deliveries.size)
        assertEquals("DELIVERY_BLOCKED", deliveries[0].result)
        assertEquals("POST_ATTEMPTED", deliveries[1].result)
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
