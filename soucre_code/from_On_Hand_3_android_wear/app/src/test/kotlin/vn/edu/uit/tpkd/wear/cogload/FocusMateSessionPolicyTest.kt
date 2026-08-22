package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMateSessionPolicyTest {
    @Test
    fun realViewExcludesAllSyntheticRowsWithoutParticipantCodes() {
        val rows = listOf(session("real-01", false), session("real-02", false), session("demo-01", true))
        assertEquals(listOf("real-01", "real-02"), FocusMateSessionPolicy.realSessions(rows).map { it.sessionId })
        assertEquals(listOf("demo-01"), FocusMateSessionPolicy.demoSessions(rows).map { it.sessionId })
    }

    private fun session(id: String, synthetic: Boolean) = StudySession(
        sessionId = id,
        startTimeMs = 1_000L,
        endTimeMs = 61_000L,
        durationMinutes = 1,
        subject = "Không áp dụng",
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 5,
        breakReminderCount = 0,
        shouldBreak = false,
        interruptRisk = "medium",
        accepted = null,
        deferReason = null,
        synthetic = synthetic,
    )
}
