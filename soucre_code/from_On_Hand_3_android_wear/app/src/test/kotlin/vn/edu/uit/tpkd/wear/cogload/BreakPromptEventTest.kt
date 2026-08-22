package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakPromptEventTest {
    @Test
    fun deterministicReasonsRoundTripWithoutLegacyAiFields() {
        val event = FocusMatePromptEventPolicy.create(
            active = active(),
            candidateAtMs = 60 * 60_000L,
            durationAtCandidateMs = 60 * 60_000L,
            decision = BreakDecision(
                shouldBreak = true,
                shouldPrompt = true,
                cooldownRemainingMs = 0,
                reasonCodes = setOf(WatchRuleEngine.RULE_V1_HARD_60),
            ),
            duplicateGuardPassed = true,
            prompted = true,
            deliveryChannel = BreakPromptEvent.CHANNEL_NOTIFICATION,
            triggerSource = BreakPromptEvent.TRIGGER_THRESHOLD_ALARM,
        )

        val json = event.toJson()
        val restored = BreakPromptEvent.fromJson(json)
        assertEquals(event.reasonCodes, restored.reasonCodes)
        assertFalse(json.has("ai_probability"))
    }

    @Test
    fun legacyAiFieldsAreIgnoredWhileMoodMapsToFatigue() {
        val legacy = JSONObject(eventJson())
            .put("mood_at_candidate", "good")
            .put("ai_probability", 0.99)
            .put("gemma_decision", "break")
        val restored = BreakPromptEvent.fromJson(legacy)
        assertEquals(3, restored.fatigueAtCandidate)
        assertTrue(restored.reasonCodes.isEmpty())
    }

    private fun active() = ActiveStudySession(
        sessionId = "session-1",
        startTimeMs = 0,
        taskType = "Bài tập",
        focusScore = 3,
        fatigueScore = 7,
    )

    private fun eventJson() = """{
        "event_id":"old-1","session_id":"session-1","candidate_at_ms":1000,
        "duration_at_candidate_ms":1000,"task_type":"Bài tập","rule_should_break":true,
        "prompted":false,"delivery_channel":"none","suppression_reason":"cooldown"
    }"""
}
