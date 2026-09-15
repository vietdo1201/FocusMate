// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class SessionTimelineTest {
    @Test
    fun wallClockJumpDoesNotChangeDurations() {
        val initial = snapshot(TimePoint(1_000_000L, 10_000L, "boot-1"))
        val forwardWall = TimePoint(1_000_000L + 2 * 60 * 60_000L, 70_000L, "boot-1")
        val backwardWall = TimePoint(1_000_000L - 2 * 60 * 60_000L, 70_000L, "boot-1")

        assertEquals(60_000L, SessionTimelineReducer.durations(initial, forwardWall).studyMs)
        assertEquals(60_000L, SessionTimelineReducer.durations(initial, backwardWall).studyMs)
    }

    @Test
    fun rebootRequiresRecoveryAndDoesNotCountUnconfirmedInterval() {
        val initial = snapshot(TimePoint(1_000_000L, 10_000L, "boot-1"))
        val rebooted = SessionTimelineReducer.reconcile(
            initial,
            TimePoint(1_200_000L, 5_000L, "boot-2"),
        )

        assertEquals(SessionState.RECOVERY_REQUIRED, rebooted.state)
        assertEquals(0L, SessionTimelineReducer.durations(rebooted, rebooted.enteredAt).studyMs)
    }

    @Test
    fun breakAndAwaitingResumeBothExcludeStudyUntilExplicitResume() {
        val initial = snapshot(TimePoint(0L, 0L, "boot"))
        val breakAt = TimePoint(35 * 60_000L, 35 * 60_000L, "boot")
        val breaking = SessionTimelineReducer.transition(initial, SessionState.BREAKING, breakAt, 5 * 60_000L)
        val lateCallback = TimePoint(50 * 60_000L, 50 * 60_000L, "boot")
        val awaiting = SessionTimelineReducer.transition(breaking, SessionState.AWAITING_RESUME, lateCallback)
        val resume = TimePoint(57 * 60_000L, 57 * 60_000L, "boot")
        val resumed = SessionTimelineReducer.transition(awaiting, SessionState.STUDYING, resume)

        assertEquals(35 * 60_000L, SessionTimelineReducer.durations(resumed, resume).studyMs)
        assertEquals(22 * 60_000L, SessionTimelineReducer.durations(resumed, resume).breakMs)
        assertEquals("block-1", resumed.blockId)
    }

    @Test
    fun checkpointsDoNotMoveBreakStartOrDeadline() {
        val initial = snapshot(TimePoint(0L, 0L, "boot"))
        val started = SessionTimelineReducer.transition(
            initial,
            SessionState.BREAKING,
            TimePoint(35 * 60_000L, 35 * 60_000L, "boot"),
            5 * 60_000L,
        )
        val first = SessionTimelineReducer.checkpoint(
            started,
            TimePoint(35 * 60_000L + 30_000L, 35 * 60_000L + 30_000L, "boot"),
        )
        val second = SessionTimelineReducer.checkpoint(
            first,
            TimePoint(36 * 60_000L, 36 * 60_000L, "boot"),
        )

        assertEquals(started.enteredAt, second.enteredAt)
        assertEquals(40 * 60_000L, second.breakDeadlineElapsedMs)
        assertEquals(60_000L, SessionTimelineReducer.durations(second, second.checkpointAt).breakMs)
        assertNotEquals(second.enteredAt, second.checkpointAt)
    }

    @Test
    fun transitionOlderThanLatestCheckpointRequiresRecovery() {
        val initial = snapshot(TimePoint(1_000L, 1_000L, "boot"))
        val checkpointed = SessionTimelineReducer.checkpoint(
            initial,
            TimePoint(31_000L, 31_000L, "boot"),
        )

        val transitioned = SessionTimelineReducer.transition(
            checkpointed,
            SessionState.PAUSED,
            TimePoint(21_000L, 21_000L, "boot"),
        )

        assertEquals(SessionState.RECOVERY_REQUIRED, transitioned.state)
        assertEquals(30_000L, transitioned.accumulatedStudyMs)
        assertEquals(checkpointed.checkpointAt, transitioned.checkpointAt)
    }

    @Test
    fun seededTransitionSequenceNeverCountsMoreThanElapsedTime() {
        val random = Random(230)
        var elapsed = 0L
        var snapshot = snapshot(TimePoint(0L, 0L, "boot-seeded"))
        repeat(250) {
            elapsed += random.nextLong(1L, 120_000L)
            val at = TimePoint(elapsed, elapsed, "boot-seeded")
            snapshot = if (random.nextBoolean()) {
                SessionTimelineReducer.checkpoint(snapshot, at)
            } else {
                val target = when (snapshot.state) {
                    SessionState.STUDYING -> if (random.nextBoolean()) SessionState.PAUSED else SessionState.BREAKING
                    SessionState.PAUSED -> SessionState.STUDYING
                    SessionState.BREAKING -> SessionState.AWAITING_RESUME
                    SessionState.AWAITING_RESUME -> if (random.nextBoolean()) SessionState.STUDYING else SessionState.BREAKING
                    else -> SessionState.STUDYING
                }
                SessionTimelineReducer.transition(
                    snapshot,
                    target,
                    at,
                    plannedBreakDurationMs = if (target == SessionState.BREAKING) 5 * 60_000L else null,
                )
            }
            val totals = SessionTimelineReducer.durations(snapshot, at)
            assertEquals(
                "seed=230 step=$it",
                elapsed,
                totals.studyMs + totals.breakMs + totals.pauseMs + totals.unknownMs,
            )
        }
    }

    private fun snapshot(at: TimePoint) = SessionTimelineSnapshot(
        sessionId = "session", revision = 0, state = SessionState.STUDYING,
        blockId = "block-0", enteredAt = at,
    )
}
