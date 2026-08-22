package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActiveStudySessionHeartRateTest {
    @Test
    fun preservesCurrentHeartRateAndObservationTimeAcrossSnapshotJson() {
        val session = ActiveStudySession(
            startTimeMs = 1_000L,
            subject = "hidden",
            taskType = "Đọc tài liệu",
            focusScore = 3,
            fatigueScore = 5,
            heartRateCurrent = 81.0,
            heartRateObservedAtMs = 12_000L,
        )

        val restored = ActiveStudySession.fromJson(JSONObject(session.toJson().toString()))

        assertEquals(81.0, restored.heartRateCurrent ?: 0.0, 0.0001)
        assertEquals(12_000L, restored.heartRateObservedAtMs)
    }

    @Test
    fun legacyMoodAndAiFieldsMigrateToFatigueAndAreNotRewritten() {
        val legacy = JSONObject().apply {
            put("session_id", "legacy")
            put("start_time_ms", 1_000L)
            put("task_type", "Đọc tài liệu")
            put("focus_score", 4)
            put("mood", "bad")
            put("pending_gemma_decision", JSONObject().put("request_id", "old"))
            put("ai_probability", 0.99)
        }

        val restored = ActiveStudySession.fromJson(legacy)
        val rewritten = restored.toJson()

        assertEquals(9, restored.fatigueScore)
        assertFalse(rewritten.has("mood"))
        assertFalse(rewritten.has("pending_gemma_decision"))
        assertFalse(rewritten.has("ai_probability"))
    }
}
