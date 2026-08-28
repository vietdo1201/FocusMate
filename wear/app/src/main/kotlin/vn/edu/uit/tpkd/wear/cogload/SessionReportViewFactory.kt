// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

data class SessionAdvicePresentation(val action: String, val reason: String)

object SessionReportViewFactory {
    private val breakRuleCodes = setOf(
        WatchRuleEngine.RULE_V1_DURATION_FATIGUE,
        WatchRuleEngine.RULE_V1_HARD_60,
        WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS,
        WatchRuleEngine.RULE_V2_IMMOBILITY,
    )

    fun create(context: Context, session: StudySession): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_session_report, null, false)
        view.findViewById<TextView>(R.id.tv_report_summary).text = context.getString(
            R.string.report_summary,
            session.durationMinutes,
            session.focusScore,
            session.fatigueScore,
        )
        val triggeredRules = session.breakReasonCodes intersect breakRuleCodes
        view.findViewById<TextView>(R.id.tv_report_rule).text = if (triggeredRules.isEmpty()) {
            context.getString(R.string.report_rule_clear)
        } else {
            context.getString(R.string.report_rule_triggered, triggeredRules.size)
        }

        val presentations = session.sessionAdvice
            .take(SessionAdviceEngine.MAX_ADVICE_ITEMS)
            .map { present(context, session, it) }
        val primary = presentations.firstOrNull() ?: present(
            context,
            session,
            SessionAdviceItem(SessionAdviceEngine.MAINTAIN_GOOD_SESSION, emptySet()),
        )
        view.findViewById<TextView>(R.id.tv_report_primary).text = primary.formatted(context)

        val secondary = presentations.drop(1)
        val secondaryHeading = view.findViewById<TextView>(R.id.tv_report_secondary_heading)
        secondaryHeading.visibility = if (secondary.isEmpty()) View.GONE else View.VISIBLE
        listOf(
            view.findViewById<TextView>(R.id.tv_report_secondary_one),
            view.findViewById<TextView>(R.id.tv_report_secondary_two),
        ).forEachIndexed { index, textView ->
            secondary.getOrNull(index)?.let {
                textView.text = it.formatted(context)
                textView.visibility = View.VISIBLE
            } ?: run { textView.visibility = View.GONE }
        }

        view.findViewById<TextView>(R.id.tv_report_metrics).text = metricsText(context, session)
        view.findViewById<TextView>(R.id.tv_report_data_quality).text = dataQualityText(context, session)
        view.findViewById<TextView>(R.id.tv_report_health_warning).visibility =
            if (session.sessionAdvice.any { it.code == SessionAdviceEngine.RECHECK_HEART_RATE }) {
                View.VISIBLE
            } else {
                View.GONE
            }
        return view
    }

    fun present(context: Context, session: StudySession, item: SessionAdviceItem): SessionAdvicePresentation {
        val action = context.getString(
            when (item.code) {
                SessionAdviceEngine.RECOVER_HIGH_FATIGUE -> R.string.advice_recover_high_fatigue
                SessionAdviceEngine.RECOVER_HARD_60 -> R.string.advice_recover_hard_60
                SessionAdviceEngine.RECOVER_DURATION_FATIGUE -> R.string.advice_recover_duration_fatigue
                SessionAdviceEngine.RECOVER_REPEATED_YAWN -> R.string.advice_recover_repeated_yawn
                SessionAdviceEngine.COMPLETE_CURRENT_BREAK -> R.string.advice_complete_current_break
                SessionAdviceEngine.MOVE_AFTER_IMMOBILITY -> R.string.advice_move_after_immobility
                SessionAdviceEngine.POSTURE_HEAD_AND_BACK -> R.string.advice_posture_head_back
                SessionAdviceEngine.POSTURE_CENTER -> R.string.advice_posture_center
                SessionAdviceEngine.POSTURE_DISTANCE -> R.string.advice_posture_distance
                SessionAdviceEngine.RECHECK_HEART_RATE -> R.string.advice_recheck_heart_rate
                else -> R.string.advice_maintain_good
            }
        )
        return SessionAdvicePresentation(action, evidenceText(context, session, item))
    }

    private fun evidenceText(context: Context, session: StudySession, item: SessionAdviceItem): String {
        if (item.code == SessionAdviceEngine.MAINTAIN_GOOD_SESSION || item.evidenceCodes.isEmpty()) {
            return context.getString(R.string.evidence_no_trigger)
        }
        val descriptions = buildList {
            if (WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS in item.evidenceCodes) {
                add(context.getString(R.string.evidence_high_fatigue, session.fatigueScore, session.focusScore))
            }
            if (WatchRuleEngine.RULE_V1_HARD_60 in item.evidenceCodes) {
                add(context.getString(R.string.evidence_hard_60))
            }
            if (WatchRuleEngine.RULE_V1_DURATION_FATIGUE in item.evidenceCodes) {
                add(context.getString(R.string.evidence_duration_fatigue, session.fatigueScore))
            }
            if (SessionAdviceEngine.EVIDENCE_REPEATED_YAWN in item.evidenceCodes) {
                add(
                    if (session.yawnRecentWindowCount >= SessionAdviceEngine.REPEATED_YAWN_COUNT) {
                        context.getString(R.string.evidence_repeated_yawn_recent, session.yawnRecentWindowCount)
                    } else {
                        context.getString(R.string.evidence_repeated_yawn_alert)
                    }
                )
            }
            if (WatchRuleEngine.RULE_V2_IMMOBILITY in item.evidenceCodes) {
                add(
                    context.getString(
                        R.string.evidence_immobility,
                        (session.continuousImmobileMs / 60_000L).coerceAtLeast(1L),
                    )
                )
            }
            postureEvidence(context, session, item)?.let(::add)
            if (SessionAdviceEngine.EVIDENCE_HEART_RATE_ELEVATED in item.evidenceCodes) {
                heartRatePostAverage(session)?.let { postAverage ->
                    add(
                        context.getString(
                            R.string.evidence_heart_rate,
                            postAverage.roundToInt(),
                            requireNotNull(session.heartRateBaseline).roundToInt(),
                        )
                    )
                }
            }
        }
        return descriptions.distinct().joinToString("; ").ifBlank {
            context.getString(R.string.evidence_generic)
        }
    }

    private fun postureEvidence(
        context: Context,
        session: StudySession,
        item: SessionAdviceItem,
    ): String? {
        val state = item.evidenceCodes.firstNotNullOfOrNull { evidence ->
            evidence.takeIf { it.startsWith(SessionAdviceEngine.EVIDENCE_POSTURE_PREFIX) }
                ?.removePrefix(SessionAdviceEngine.EVIDENCE_POSTURE_PREFIX)
                ?.let { runCatching { PostureState.valueOf(it) }.getOrNull() }
        } ?: return null
        val summary = session.postureSummaries.firstOrNull { it.state == state } ?: return null
        return context.getString(
            R.string.evidence_posture,
            postureLabel(context, state),
            summary.episodeCount,
            oneDecimalMinutes(summary.totalDurationMs),
        )
    }

    private fun metricsText(context: Context, session: StudySession): String {
        val dominant = session.postureSummaries.maxByOrNull(PostureStateSummary::totalDurationMs)
        val posture = dominant?.let {
            context.getString(
                R.string.report_posture_summary,
                postureLabel(context, it.state),
                it.episodeCount,
                oneDecimalMinutes(it.totalDurationMs),
            )
        } ?: context.getString(R.string.report_posture_no_insight)
        return context.getString(
            R.string.report_metrics,
            posture,
            session.yawnCount,
            session.yawnRecentWindowCount,
        )
    }

    private fun dataQualityText(context: Context, session: StudySession): String {
        val expectedMotionWindows = (session.durationMinutes * 2).coerceAtLeast(1)
        val motionEnough = session.motionWindowCount.toDouble() / expectedMotionWindows >=
            WatchRuleEngine.MIN_MOTION_COVERAGE
        val heartRateEnough = session.heartRateBaselineSampleCount >=
            SessionAdviceEngine.MIN_HEART_RATE_BASELINE_SAMPLES &&
            session.heartRateSampleCount - session.heartRateBaselineSampleCount >=
            SessionAdviceEngine.MIN_HEART_RATE_POST_BASELINE_SAMPLES
        fun label(enough: Boolean) = context.getString(
            if (enough) R.string.report_data_enough else R.string.report_data_partial
        )
        return context.getString(R.string.report_data_quality, label(motionEnough), label(heartRateEnough))
    }

    private fun heartRatePostAverage(session: StudySession): Double? {
        val baseline = session.heartRateBaseline ?: return null
        val overall = session.heartRateAverage ?: return null
        val postCount = session.heartRateSampleCount - session.heartRateBaselineSampleCount
        if (postCount <= 0) return null
        return (
            overall * session.heartRateSampleCount -
                baseline * session.heartRateBaselineSampleCount
            ) / postCount
    }

    private fun postureLabel(context: Context, state: PostureState): String = context.getString(
        when (state) {
            PostureState.HEAD_DOWN -> R.string.posture_head_down_label
            PostureState.SLUMPED -> R.string.posture_slumped_label
            PostureState.LEAN_LEFT -> R.string.posture_lean_left_label
            PostureState.LEAN_RIGHT -> R.string.posture_lean_right_label
            PostureState.TOO_CLOSE -> R.string.posture_too_close_label
            else -> R.string.report_posture_no_insight
        }
    )

    private fun oneDecimalMinutes(durationMs: Long): String =
        String.format(Locale.getDefault(), "%.1f", durationMs / 60_000.0)

    private fun SessionAdvicePresentation.formatted(context: Context): String =
        context.getString(R.string.report_action_reason, action, reason)
}
