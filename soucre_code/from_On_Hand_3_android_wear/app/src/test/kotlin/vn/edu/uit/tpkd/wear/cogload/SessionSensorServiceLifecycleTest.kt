package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSensorServiceLifecycleTest {
    @Test
    fun releasesDndOnlyWhenSessionIsAbsentOrOnBreak() {
        val active = ActiveStudySession(
            sessionId = "active",
            startTimeMs = 1_000L,
            subject = "Không áp dụng",
            taskType = "Bài tập",
            focusScore = 3,
            fatigueScore = 5,
        )
        assertTrue(shouldReleaseStudyDnd(null, 2_000L))
        assertFalse(shouldReleaseStudyDnd(active, 2_000L))
        assertTrue(
            shouldReleaseStudyDnd(
                active.copy(breakStartedAtMs = 1_500L, breakEndsAtMs = 3_000L),
                2_000L,
            )
        )
    }
}
