// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionReportViewFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun createsScrollableReportWithPrimarySecondaryReasonsAndStartScoreNote() {
        val session = session().copy(
            breakReasonCodes = setOf(
                WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS,
                WatchRuleEngine.RULE_V2_IMMOBILITY,
            ),
            continuousImmobileMs = 31 * 60_000L,
            postureSummaries = listOf(
                PostureStateSummary(PostureState.HEAD_DOWN, 2, 4 * 60_000L),
            ),
            postureInsightReasonCodes = setOf(WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS),
            adviceRuleVersion = SessionAdviceEngine.RULE_VERSION,
            sessionAdvice = listOf(
                SessionAdviceItem(
                    SessionAdviceEngine.RECOVER_HIGH_FATIGUE,
                    setOf(WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS),
                ),
                SessionAdviceItem(
                    SessionAdviceEngine.MOVE_AFTER_IMMOBILITY,
                    setOf(WatchRuleEngine.RULE_V2_IMMOBILITY),
                ),
                SessionAdviceItem(
                    SessionAdviceEngine.POSTURE_HEAD_AND_BACK,
                    setOf(
                        WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS,
                        "${SessionAdviceEngine.EVIDENCE_POSTURE_PREFIX}${PostureState.HEAD_DOWN.name}",
                    ),
                ),
            ),
        )

        val view = SessionReportViewFactory.create(context, session)

        assertTrue(view is ScrollView)
        assertTrue(text(view, R.id.tv_report_primary).contains("nghỉ 10–15 phút"))
        assertTrue(text(view, R.id.tv_report_primary).contains("Vì"))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.tv_report_secondary_heading).visibility)
        assertTrue(text(view, R.id.tv_report_secondary_one).contains("3–5 phút"))
        assertTrue(text(view, R.id.tv_report_secondary_two).contains("cúi đầu"))
        assertTrue(text(view, R.id.tv_report_data_quality).contains("ĐỘ ĐẦY ĐỦ"))
    }

    @Test
    fun heartRateWarningOnlyAppearsForFrozenHeartRateAdvice() {
        val withoutHeartAdvice = SessionReportViewFactory.create(context, session())
        assertEquals(View.GONE, withoutHeartAdvice.findViewById<View>(R.id.tv_report_health_warning).visibility)

        val withHeartAdvice = SessionReportViewFactory.create(
            context,
            session().copy(
                heartRateBaseline = 60.0,
                heartRateAverage = 70.0,
                heartRateBaselineSampleCount = 5,
                heartRateSampleCount = 10,
                sessionAdvice = listOf(
                    SessionAdviceItem(
                        SessionAdviceEngine.RECHECK_HEART_RATE,
                        setOf(SessionAdviceEngine.EVIDENCE_HEART_RATE_ELEVATED),
                    )
                ),
            ),
        )

        assertEquals(View.VISIBLE, withHeartAdvice.findViewById<View>(R.id.tv_report_health_warning).visibility)
        assertTrue(text(withHeartAdvice, R.id.tv_report_primary).contains("80 BPM"))
        assertTrue(text(withHeartAdvice, R.id.tv_report_health_warning).contains("không phải chẩn đoán"))
    }

    @Test
    fun missingFrozenAdviceUsesHonestMaintenanceFallback() {
        val view = SessionReportViewFactory.create(context, session().copy(sessionAdvice = emptyList()))

        assertTrue(text(view, R.id.tv_report_primary).contains("Chưa có rule đáng tin cậy"))
        assertTrue(text(view, R.id.tv_report_primary).contains("không có tín hiệu nào vượt ngưỡng"))
        assertEquals(View.GONE, view.findViewById<View>(R.id.tv_report_secondary_heading).visibility)
    }

    private fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()

    private fun session() = StudySession(
        sessionId = "report",
        startTimeMs = 1_000L,
        endTimeMs = 60 * 60_000L + 1_000L,
        durationMinutes = 60,
        taskType = "Lập trình",
        focusScore = 3,
        fatigueScore = 8,
        breakReminderCount = 0,
        shouldBreak = true,
        accepted = null,
        deferReason = null,
        motionWindowCount = 120,
        adviceRuleVersion = SessionAdviceEngine.RULE_VERSION,
        sessionAdvice = listOf(
            SessionAdviceItem(SessionAdviceEngine.MAINTAIN_GOOD_SESSION, emptySet()),
        ),
    )
}
