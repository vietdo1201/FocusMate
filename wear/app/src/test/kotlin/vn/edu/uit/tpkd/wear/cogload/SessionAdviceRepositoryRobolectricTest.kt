// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionAdviceRepositoryRobolectricTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDown() = clearPreferences()

    @Test
    fun finishFreezesRuleReasonsRecentYawnsAndAdviceInStoredSession() {
        val end = System.currentTimeMillis()
        val start = end - 60 * 60_000L
        val repository = StudySessionRepository(context)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "advice-session",
                startTimeMs = start,
                taskType = "Lập trình",
                focusScore = 3,
                fatigueScore = 8,
                motionWindowCount = 120,
                continuousImmobileMs = 30 * 60_000L,
                motionActivityObservedAtMs = end,
                recentYawnEventTimesMs = listOf(end - 60_000L, end - 30_000L, end - 1_000L),
                yawnCount = 3,
            )
        )

        val completed = requireNotNull(repository.finishActiveSession(end))
        val restored = requireNotNull(StudySessionRepository(context).sessions().singleOrNull())

        assertEquals(SessionAdviceEngine.RULE_VERSION, completed.adviceRuleVersion)
        assertEquals(3, completed.yawnRecentWindowCount)
        assertTrue(WatchRuleEngine.RULE_V1_HARD_60 in completed.breakReasonCodes)
        assertTrue(WatchRuleEngine.RULE_V2_IMMOBILITY in completed.breakReasonCodes)
        assertEquals(SessionAdviceEngine.RECOVER_HIGH_FATIGUE, completed.sessionAdvice.first().code)
        assertEquals(completed.sessionAdvice, restored.sessionAdvice)
        assertEquals(completed.breakReasonCodes, restored.breakReasonCodes)
    }

    private fun clearPreferences() {
        listOf(BULK_PREFS, ACTIVE_PREFS, "focusmate_local_study_ai_v2").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    companion object {
        private const val BULK_PREFS = "focusmate_local_store_v1"
        private const val ACTIVE_PREFS = "focusmate_active_state_v1"
    }
}
