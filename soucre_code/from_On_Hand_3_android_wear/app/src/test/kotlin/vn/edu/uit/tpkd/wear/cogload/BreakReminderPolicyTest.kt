package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakReminderPolicyTest {
    @Test
    fun retrySequenceAlertsAtZeroTwoAndFiveMinutesThenStops() {
        val createdAt = 1_000_000L
        val initial = PendingReminder(
            eventId = "event-1",
            kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = createdAt,
            message = "Nên nghỉ",
        )

        val first = BreakReminderPolicy.nextAttempt(initial)
        val second = BreakReminderPolicy.nextAttempt(first)
        val third = BreakReminderPolicy.nextAttempt(second)

        assertEquals(1, first.attempt)
        assertEquals(createdAt + 2 * 60_000L, first.nextAlertAtMs)
        assertEquals(2, second.attempt)
        assertEquals(createdAt + 5 * 60_000L, second.nextAlertAtMs)
        assertEquals(3, third.attempt)
        assertNull(third.nextAlertAtMs)
    }

    @Test
    fun pendingReminderSurvivesJsonAndClearsWhenBreakStarts() {
        val active = active().copy(
            pendingReminder = PendingReminder(
                eventId = "event-2",
                kind = PendingReminderKind.BREAK_SUGGESTION,
                createdAtMs = 5_000L,
                attempt = 2,
                nextAlertAtMs = 305_000L,
                message = "Đã học 50 phút — nên nghỉ 5 phút",
            )
        )
        val restored = ActiveStudySession.fromJson(active.toJson())
        assertEquals(active.pendingReminder, restored.pendingReminder)

        val resting = StudySessionClock.startBreak(restored, 10_000L)
        assertNull(resting.pendingReminder)
    }

    @Test
    fun actionGuardRejectsDuplicateStaleAndCrossSessionActions() {
        val pending = PendingReminder(
            eventId = "event-current",
            kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = 5_000L,
            message = "Nên nghỉ",
        )
        val active = active().copy(pendingReminder = pending)

        assertTrue(
            ReminderActionGuard.matches(
                active,
                "session-1",
                "event-current",
                PendingReminderKind.BREAK_SUGGESTION,
            )
        )
        assertFalse(
            ReminderActionGuard.matches(
                active,
                "session-old",
                "event-current",
                PendingReminderKind.BREAK_SUGGESTION,
            )
        )
        assertFalse(
            ReminderActionGuard.matches(
                active,
                "session-1",
                "event-old",
                PendingReminderKind.BREAK_SUGGESTION,
            )
        )
        assertFalse(
            ReminderActionGuard.matches(
                active.copy(pendingReminder = null),
                "session-1",
                "event-current",
                PendingReminderKind.BREAK_SUGGESTION,
            )
        )
    }

    private fun active() = ActiveStudySession(
        sessionId = "session-1",
        startTimeMs = 1_000L,
        subject = "Không áp dụng",
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 5,
    )
}
