// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONObject

/** Monotonic, boot-aware reducer used by the new controller/store path. */
data class SessionTimelineSnapshot(
    val sessionId: String,
    val revision: Long,
    val state: SessionState,
    val blockId: String,
    /** Time at which the current state began. Checkpoints must never move this anchor. */
    val enteredAt: TimePoint,
    /** Time through which accumulated durations have already been persisted. */
    val checkpointAt: TimePoint = enteredAt,
    val accumulatedStudyMs: Long = 0L,
    val accumulatedBreakMs: Long = 0L,
    val accumulatedPauseMs: Long = 0L,
    val unknownDurationMs: Long = 0L,
    val plannedBreakDurationMs: Long? = null,
    /** Immutable monotonic deadline for the current BREAKING interval. */
    val breakDeadlineElapsedMs: Long? = null,
) {
    fun toJson() = JSONObject().apply {
        put("session_id", sessionId); put("revision", revision); put("state", state.name)
        put("block_id", blockId); put("entered_wall_ms", enteredAt.wallMs)
        put("entered_elapsed_ms", enteredAt.elapsedMs); put("boot_id", enteredAt.bootId)
        put("checkpoint_wall_ms", checkpointAt.wallMs)
        put("checkpoint_elapsed_ms", checkpointAt.elapsedMs)
        put("checkpoint_boot_id", checkpointAt.bootId)
        put("study_ms", accumulatedStudyMs); put("break_ms", accumulatedBreakMs)
        put("pause_ms", accumulatedPauseMs); put("unknown_ms", unknownDurationMs)
        put("planned_break_ms", plannedBreakDurationMs ?: JSONObject.NULL)
        put("break_deadline_elapsed_ms", breakDeadlineElapsedMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): SessionTimelineSnapshot? = runCatching {
            SessionTimelineSnapshot(
                sessionId = json.getString("session_id"), revision = json.getLong("revision"),
                state = SessionState.valueOf(json.getString("state")), blockId = json.getString("block_id"),
                enteredAt = TimePoint(json.getLong("entered_wall_ms"), json.getLong("entered_elapsed_ms"), json.getString("boot_id")),
                checkpointAt = if (json.has("checkpoint_elapsed_ms")) {
                    TimePoint(
                        json.optLong("checkpoint_wall_ms", json.getLong("entered_wall_ms")),
                        json.getLong("checkpoint_elapsed_ms"),
                        json.optString("checkpoint_boot_id", json.getString("boot_id")),
                    )
                } else {
                    TimePoint(json.getLong("entered_wall_ms"), json.getLong("entered_elapsed_ms"), json.getString("boot_id"))
                },
                accumulatedStudyMs = json.optLong("study_ms").coerceAtLeast(0L),
                accumulatedBreakMs = json.optLong("break_ms").coerceAtLeast(0L),
                accumulatedPauseMs = json.optLong("pause_ms").coerceAtLeast(0L),
                unknownDurationMs = json.optLong("unknown_ms").coerceAtLeast(0L),
                plannedBreakDurationMs = json.optLong("planned_break_ms").takeIf { !json.isNull("planned_break_ms") },
                breakDeadlineElapsedMs = json.optLong("break_deadline_elapsed_ms")
                    .takeIf { !json.isNull("break_deadline_elapsed_ms") },
            )
        }.getOrNull()
    }
}

data class SessionDurations(
    val studyMs: Long,
    val breakMs: Long,
    val pauseMs: Long,
    val unknownMs: Long,
)

object SessionTimelineReducer {
    fun durations(snapshot: SessionTimelineSnapshot, now: TimePoint): SessionDurations {
        val delta = validElapsedDelta(snapshot.checkpointAt, now)
        return SessionDurations(
            studyMs = snapshot.accumulatedStudyMs + if (snapshot.state == SessionState.STUDYING) delta else 0L,
            breakMs = snapshot.accumulatedBreakMs + if (snapshot.state in setOf(SessionState.BREAKING, SessionState.AWAITING_RESUME)) delta else 0L,
            pauseMs = snapshot.accumulatedPauseMs + if (snapshot.state == SessionState.PAUSED) delta else 0L,
            unknownMs = snapshot.unknownDurationMs,
        )
    }

    fun transition(
        snapshot: SessionTimelineSnapshot,
        target: SessionState,
        at: TimePoint,
        plannedBreakDurationMs: Long? = snapshot.plannedBreakDurationMs,
    ): SessionTimelineSnapshot {
        // Commands may arrive after one or more checkpoints. Comparing with the
        // state-entry anchor would accept an older callback and move checkpointAt
        // backwards, which could count the same interval twice after recovery.
        if (!sameBootAndOrdered(snapshot.checkpointAt, at)) {
            return requireRecovery(snapshot)
        }
        require(target in allowedTargets(snapshot.state)) { "${snapshot.state} -> $target is not allowed" }
        val totals = durations(snapshot, at)
        val startsNewBlock = snapshot.state == SessionState.AWAITING_RESUME && target == SessionState.STUDYING
        return snapshot.copy(
            revision = snapshot.revision + 1,
            state = target,
            blockId = if (startsNewBlock) nextBlockId(snapshot.blockId) else snapshot.blockId,
            enteredAt = at,
            checkpointAt = at,
            accumulatedStudyMs = totals.studyMs,
            accumulatedBreakMs = totals.breakMs,
            accumulatedPauseMs = totals.pauseMs,
            unknownDurationMs = totals.unknownMs,
            plannedBreakDurationMs = if (target == SessionState.BREAKING) plannedBreakDurationMs else null,
            breakDeadlineElapsedMs = if (target == SessionState.BREAKING) {
                at.elapsedMs + requireNotNull(plannedBreakDurationMs) { "Break duration is required" }
            } else null,
        )
    }

    fun reconcile(snapshot: SessionTimelineSnapshot, now: TimePoint): SessionTimelineSnapshot =
        if (snapshot.state == SessionState.RECOVERY_REQUIRED || sameBootAndOrdered(snapshot.checkpointAt, now)) snapshot
        else requireRecovery(snapshot)

    fun checkpoint(snapshot: SessionTimelineSnapshot, at: TimePoint): SessionTimelineSnapshot {
        if (!sameBootAndOrdered(snapshot.checkpointAt, at)) return requireRecovery(snapshot)
        val totals = durations(snapshot, at)
        return snapshot.copy(
            revision = snapshot.revision + 1, checkpointAt = at,
            accumulatedStudyMs = totals.studyMs, accumulatedBreakMs = totals.breakMs,
            accumulatedPauseMs = totals.pauseMs, unknownDurationMs = totals.unknownMs,
        )
    }

    fun resumeFromRecovery(snapshot: SessionTimelineSnapshot, now: TimePoint): SessionTimelineSnapshot {
        require(snapshot.state == SessionState.RECOVERY_REQUIRED)
        return snapshot.copy(
            revision = snapshot.revision + 1,
            state = SessionState.STUDYING,
            enteredAt = now,
            checkpointAt = now,
            blockId = nextBlockId(snapshot.blockId),
            breakDeadlineElapsedMs = null,
        )
    }

    private fun requireRecovery(snapshot: SessionTimelineSnapshot) = snapshot.copy(
        revision = snapshot.revision + 1,
        state = SessionState.RECOVERY_REQUIRED,
        plannedBreakDurationMs = null,
        breakDeadlineElapsedMs = null,
    )

    private fun validElapsedDelta(from: TimePoint, to: TimePoint): Long =
        if (sameBootAndOrdered(from, to)) to.elapsedMs - from.elapsedMs else 0L

    private fun sameBootAndOrdered(from: TimePoint, to: TimePoint) =
        from.bootId == to.bootId && from.bootId != "boot-unknown" && to.elapsedMs >= from.elapsedMs

    private fun allowedTargets(from: SessionState): Set<SessionState> = when (from) {
        SessionState.STUDYING -> setOf(SessionState.PAUSED, SessionState.BREAKING, SessionState.COMPLETED, SessionState.CANCELLED)
        SessionState.PAUSED -> setOf(SessionState.STUDYING, SessionState.COMPLETED, SessionState.CANCELLED)
        SessionState.BREAKING -> setOf(SessionState.AWAITING_RESUME, SessionState.COMPLETED, SessionState.CANCELLED)
        SessionState.AWAITING_RESUME -> setOf(SessionState.STUDYING, SessionState.BREAKING, SessionState.COMPLETED, SessionState.CANCELLED)
        SessionState.RECOVERY_REQUIRED -> setOf(SessionState.STUDYING, SessionState.COMPLETED, SessionState.CANCELLED)
        SessionState.COMPLETED, SessionState.CANCELLED -> emptySet()
    }

    private fun nextBlockId(blockId: String): String {
        val index = blockId.substringAfterLast('-').toIntOrNull() ?: 0
        return "block-${index + 1}"
    }
}
