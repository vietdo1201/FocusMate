// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySessionClockTest {
    @Test
    fun studyTimerPausesWhileFiveMinuteBreakCountsDown() {
        val start = 1_000L
        val beforeBreak = active(start)
        val breakStartedAt = start + 10 * 60_000L
        val resting = StudySessionClock.startBreak(beforeBreak, breakStartedAt)
        val twoMinutesIntoBreak = breakStartedAt + 2 * 60_000L

        assertEquals(10 * 60_000L, StudySessionClock.studyDurationMs(resting, twoMinutesIntoBreak))
        assertEquals(3 * 60_000L, StudySessionClock.breakRemainingMs(resting, twoMinutesIntoBreak))
        assertTrue(StudySessionClock.isOnBreak(resting, twoMinutesIntoBreak))
        assertEquals(1, resting.breakCount)
    }

    @Test
    fun endedBreakWaitsForConsentBeforeResumingSameSession() {
        val start = 1_000L
        val breakStartedAt = start + 10 * 60_000L
        val resting = StudySessionClock.startBreak(active(start), breakStartedAt)
        val breakEndedAt = breakStartedAt + StudySessionClock.BREAK_DURATION_MS
        val awaiting = StudySessionClock.markAwaitingDecisionIfDue(resting, breakEndedAt)

        assertNotNull(awaiting)
        requireNotNull(awaiting)
        assertTrue(StudySessionClock.isAwaitingBreakDecision(awaiting))
        assertTrue(StudySessionClock.isOnBreak(awaiting, breakEndedAt))

        val twoMinutesWaiting = breakEndedAt + 2 * 60_000L
        assertEquals(10 * 60_000L, StudySessionClock.studyDurationMs(awaiting, twoMinutesWaiting))
        assertEquals(7 * 60_000L, StudySessionClock.totalBreakDurationMs(awaiting, twoMinutesWaiting))

        val resumed = StudySessionClock.resumeFromBreak(awaiting, twoMinutesWaiting)
        assertNotNull(resumed)
        requireNotNull(resumed)
        assertEquals(resting.sessionId, resumed.sessionId)
        assertFalse(StudySessionClock.isOnBreak(resumed, twoMinutesWaiting))
        assertEquals(10 * 60_000L, StudySessionClock.studyDurationMs(resumed, twoMinutesWaiting))
        assertEquals(0L, StudySessionClock.focusBlockDurationMs(resumed, twoMinutesWaiting))
        assertEquals(7 * 60_000L, resumed.accumulatedBreakMs)

        val twoMinutesAfterResume = twoMinutesWaiting + 2 * 60_000L
        assertEquals(12 * 60_000L, StudySessionClock.studyDurationMs(resumed, twoMinutesAfterResume))
        assertEquals(2 * 60_000L, StudySessionClock.focusBlockDurationMs(resumed, twoMinutesAfterResume))
    }

    @Test
    fun wearerCanExtendEndedBreakByFiveOrTenMinutes() {
        val start = 1_000L
        val breakStartedAt = start + 10 * 60_000L
        val initial = StudySessionClock.startBreak(active(start), breakStartedAt)
        val initialEnd = breakStartedAt + StudySessionClock.BREAK_DURATION_MS
        val awaiting = StudySessionClock.markAwaitingDecisionIfDue(initial, initialEnd)
        requireNotNull(awaiting)

        val extendedFive = StudySessionClock.extendBreak(awaiting, initialEnd, 5 * 60_000L)
        assertNotNull(extendedFive)
        requireNotNull(extendedFive)
        assertFalse(StudySessionClock.isAwaitingBreakDecision(extendedFive))
        assertEquals(5 * 60_000L, StudySessionClock.breakRemainingMs(extendedFive, initialEnd))

        val secondEnd = initialEnd + 5 * 60_000L
        val awaitingAgain = StudySessionClock.markAwaitingDecisionIfDue(extendedFive, secondEnd)
        requireNotNull(awaitingAgain)
        val extendedTen = StudySessionClock.extendBreak(awaitingAgain, secondEnd, 10 * 60_000L)
        requireNotNull(extendedTen)
        assertEquals(10 * 60_000L, StudySessionClock.breakRemainingMs(extendedTen, secondEnd))
        assertEquals(1, extendedTen.breakCount)
    }

    @Test
    fun endingSessionDuringBreakCountsOnlyElapsedRestTime() {
        val start = 1_000L
        val breakStartedAt = start + 20 * 60_000L
        val resting = StudySessionClock.startBreak(active(start), breakStartedAt)
        val endedAt = breakStartedAt + 90_000L

        assertEquals(20 * 60_000L, StudySessionClock.studyDurationMs(resting, endedAt))
        assertEquals(90_000L, StudySessionClock.totalBreakDurationMs(resting, endedAt))
    }

    private fun active(startTimeMs: Long) = ActiveStudySession(
        sessionId = "clock-session",
        startTimeMs = startTimeMs,
        subject = "Không áp dụng",
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 5,
    )
}
