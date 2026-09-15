// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakReminderPolicyTest {
    @Test
    fun retrySequenceAlertsInitiallyAndAtFiveMinutesThenStops() {
        val createdAt = 1_000_000L
        val initial = PendingReminder(
            eventId = "event-1",
            kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = createdAt,
            message = "Nên nghỉ",
        )

        val first = BreakReminderPolicy.nextAttempt(initial)
        val second = BreakReminderPolicy.nextAttempt(first)
        assertEquals(1, first.attempt)
        assertEquals(createdAt + 5 * 60_000L, first.nextAlertAtMs)
        assertEquals(2, second.attempt)
        assertNull(second.nextAlertAtMs)
    }

    @Test
    fun retryKeepsBootAwareMonotonicDeadlineAcrossJson() {
        val initial = PendingReminder(
            eventId = "elapsed-event",
            kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = 10_000L,
            createdAtElapsedMs = 4_000L,
            createdBootId = "boot-7",
            nextAlertElapsedMs = 4_000L,
            message = "Nên nghỉ",
        )

        val first = BreakReminderPolicy.nextAttempt(initial)
        val restored = requireNotNull(PendingReminder.fromJson(first.toJson()))

        assertEquals(304_000L, restored.nextAlertElapsedMs)
        assertEquals("boot-7", restored.createdBootId)
    }

    @Test
    fun pendingReminderSurvivesJsonAndClearsWhenBreakStarts() {
        val active = active().copy(
            pendingReminder = PendingReminder(
                eventId = "event-2",
                kind = PendingReminderKind.BREAK_SUGGESTION,
                createdAtMs = 5_000L,
                attempt = 1,
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
