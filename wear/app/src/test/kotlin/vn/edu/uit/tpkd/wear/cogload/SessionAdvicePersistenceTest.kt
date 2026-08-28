// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAdvicePersistenceTest {
    @Test
    fun preservesOrderedAdviceAndEvidenceAcrossJson() {
        val session = session().copy(
            breakReasonCodes = linkedSetOf(
                WatchRuleEngine.RULE_V1_HARD_60,
                WatchRuleEngine.RULE_V2_IMMOBILITY,
            ),
            yawnRecentWindowCount = 3,
            adviceRuleVersion = SessionAdviceEngine.RULE_VERSION,
            sessionAdvice = listOf(
                SessionAdviceItem(
                    SessionAdviceEngine.RECOVER_HARD_60,
                    setOf(WatchRuleEngine.RULE_V1_HARD_60),
                ),
                SessionAdviceItem(
                    SessionAdviceEngine.MOVE_AFTER_IMMOBILITY,
                    setOf(WatchRuleEngine.RULE_V2_IMMOBILITY),
                ),
            ),
        )

        val restored = StudySession.fromJson(JSONObject(session.toJson().toString()))

        assertEquals(SessionAdviceEngine.RULE_VERSION, restored.adviceRuleVersion)
        assertEquals(session.breakReasonCodes, restored.breakReasonCodes)
        assertEquals(3, restored.yawnRecentWindowCount)
        assertEquals(session.sessionAdvice, restored.sessionAdvice)
    }

    @Test
    fun legacyJsonDefaultsToNoFrozenAdviceAndIsNotRecomputed() {
        val json = session().toJson().apply {
            remove("break_reason_codes")
            remove("yawn_recent_window_count")
            remove("advice_rule_version")
            remove("session_advice")
        }

        val restored = StudySession.fromJson(json)

        assertTrue(restored.breakReasonCodes.isEmpty())
        assertEquals(0, restored.yawnRecentWindowCount)
        assertNull(restored.adviceRuleVersion)
        assertTrue(restored.sessionAdvice.isEmpty())
    }

    private fun session() = StudySession(
        sessionId = "session",
        startTimeMs = 1_000L,
        endTimeMs = 61_000L,
        durationMinutes = 1,
        taskType = "Đọc tài liệu",
        focusScore = 4,
        fatigueScore = 4,
        breakReminderCount = 0,
        shouldBreak = false,
        accepted = null,
        deferReason = null,
    )
}
