package vn.edu.uit.tpkd.wear.cogload

data class PostureInsight(
    val reasonCode: String,
    val state: PostureState,
    val observedAtMs: Long,
)

data class PostureStateSummary(
    val state: PostureState,
    val episodeCount: Int,
    val totalDurationMs: Long,
)

class PostureInsightTracker {
    private var currentState: PostureState? = null
    private var currentSinceMs = 0L
    private var lastObservedAtMs = -1L
    private val totals = mutableMapOf<PostureState, Long>()
    private val episodes = mutableMapOf<PostureState, Int>()
    private val episodeStarts = mutableMapOf<PostureState, ArrayDeque<Long>>()
    private var continuousReportedForCurrentEpisode = false
    private val repeatedReportedWindow = mutableMapOf<PostureState, Long>()

    fun observe(state: PostureState, observedAtMs: Long): List<PostureInsight> {
        require(observedAtMs >= 0L && observedAtMs >= lastObservedAtMs) { "observations must be monotonic" }
        currentState?.let { previous ->
            totals[previous] = totals.getOrDefault(previous, 0L) + (observedAtMs - lastObservedAtMs).coerceAtLeast(0L)
        }
        if (state != currentState) {
            currentState = state
            currentSinceMs = observedAtMs
            continuousReportedForCurrentEpisode = false
            if (state.isBadPosture()) {
                episodes[state] = episodes.getOrDefault(state, 0) + 1
                val starts = episodeStarts.getOrPut(state) { ArrayDeque() }
                starts.addLast(observedAtMs)
                while (starts.isNotEmpty() && observedAtMs - starts.first() > REPEATED_WINDOW_MS) starts.removeFirst()
                while (starts.size > REPEATED_EPISODES) starts.removeFirst()
            }
        }
        lastObservedAtMs = observedAtMs
        if (!state.isBadPosture()) return emptyList()

        val insights = mutableListOf<PostureInsight>()
        if (observedAtMs - currentSinceMs >= CONTINUOUS_THRESHOLD_MS &&
            !continuousReportedForCurrentEpisode
        ) {
            continuousReportedForCurrentEpisode = true
            insights += PostureInsight(WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS, state, observedAtMs)
        }
        val starts = episodeStarts[state].orEmpty()
        if (starts.size >= REPEATED_EPISODES && repeatedReportedWindow[state] != starts.first()) {
            repeatedReportedWindow[state] = starts.first()
            insights += PostureInsight(WatchRuleEngine.INSIGHT_V2_POSTURE_REPEATED, state, observedAtMs)
        }
        return insights
    }

    fun summaries(nowMs: Long = lastObservedAtMs.coerceAtLeast(0L)): List<PostureStateSummary> {
        require(nowMs >= lastObservedAtMs)
        val snapshot = totals.toMutableMap()
        currentState?.let { state ->
            snapshot[state] = snapshot.getOrDefault(state, 0L) + (nowMs - lastObservedAtMs).coerceAtLeast(0L)
        }
        return snapshot.entries
            .filter { it.key.isBadPosture() && it.value > 0L }
            .map { PostureStateSummary(it.key, episodes.getOrDefault(it.key, 0), it.value) }
            .sortedByDescending(PostureStateSummary::totalDurationMs)
    }

    fun breakSuggestion(nowMs: Long = lastObservedAtMs.coerceAtLeast(0L)): String? =
        summaries(nowMs).firstOrNull()?.let(PostureRecommendations::advice)

    /** End the current continuous episode while retaining the session summary. */
    fun pause() {
        currentState = null
        currentSinceMs = 0L
        lastObservedAtMs = -1L
        continuousReportedForCurrentEpisode = false
    }

    fun reset() {
        pause()
        totals.clear()
        episodes.clear()
        episodeStarts.clear()
        repeatedReportedWindow.clear()
    }

    private fun PostureState.isBadPosture(): Boolean = this in BAD_STATES

    companion object {
        const val INSIGHT_V2_POSTURE_CONTINUOUS = WatchRuleEngine.INSIGHT_V2_POSTURE_CONTINUOUS
        const val INSIGHT_V2_POSTURE_REPEATED = WatchRuleEngine.INSIGHT_V2_POSTURE_REPEATED
        const val CONTINUOUS_THRESHOLD_MS = 3 * 60_000L
        const val REPEATED_WINDOW_MS = 15 * 60_000L
        const val REPEATED_EPISODES = 4
        private val BAD_STATES = setOf(
            PostureState.HEAD_DOWN,
            PostureState.LEAN_LEFT,
            PostureState.LEAN_RIGHT,
            PostureState.TOO_CLOSE,
            PostureState.SLUMPED,
        )
    }
}

object PostureRecommendations {
    fun advice(summary: PostureStateSummary): String? = when (summary.state) {
        PostureState.HEAD_DOWN, PostureState.SLUMPED -> "Nâng tài liệu hoặc màn hình lên cao hơn."
        PostureState.LEAN_LEFT, PostureState.LEAN_RIGHT -> "Ngồi cân giữa và thả lỏng hai vai."
        PostureState.TOO_CLOSE -> "Lùi ghế hoặc màn hình ra xa hơn một chút."
        else -> null
    }

    fun report(summaries: List<PostureStateSummary>): String = summaries.joinToString("\n") { summary ->
        val minutes = summary.totalDurationMs / 60_000.0
        "${summary.state.name}: ${summary.episodeCount} lần • %.1f phút • ${advice(summary) ?: "Theo dõi thêm."}".format(minutes)
    }
}
