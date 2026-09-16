// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

data class DailyStudyTotal(val date: LocalDate, val label: String, val minutes: Int)

/** Compatibility adapter over the authoritative SQLite session store. */
class StudySessionRepository(
    private val context: Context,
    private val sessionClock: SessionClock = AndroidSessionClock(context),
) : SessionStore {
    private val database = FocusMateSessionDatabaseProvider.get(context)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    // Hot per-second fields (active session, cooldown) live in their own small
    // file: every apply() rewrites the whole XML, and the bulk store can reach
    // hundreds of KB once sessions/prompt_events accumulate.
    private val activePreferences = context.getSharedPreferences(ACTIVE_PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(STORE_LOCK) {
            migratePreferencesToDatabase()
            pruneExpiredStoredDataLocked(LocalDate.now())
        }
    }

    /** Idempotent copy-verify-switch migration; preferences are never dual-written afterwards. */
    private fun migratePreferencesToDatabase() {
        if (database.metadata(FocusMateSessionDatabase.META_PREFS_MIGRATION) == "complete") return
        moveActiveStateToOwnFile()
        val recoveryPayloads = mutableListOf<Pair<String, String>>()

        val rawSessions = preferences.getString(KEY_SESSIONS, "[]") ?: "[]"
        val (sessions, sessionRows) = parseSessions(rawSessions)
        if (sessions.size != sessionRows) recoveryPayloads += "legacy_sessions" to rawSessions

        val rawEvents = preferences.getString(KEY_PROMPT_EVENTS, "[]") ?: "[]"
        val eventArray = runCatching { JSONArray(rawEvents) }.getOrNull()
        val events = eventArray?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { BreakPromptEvent.fromJson(array.getJSONObject(index)) }.getOrNull()?.let(::add)
                }
            }
        }.orEmpty()
        if (eventArray == null || events.size != eventArray.length()) {
            recoveryPayloads += "legacy_prompt_events" to rawEvents
        }

        val activeRaw = activePreferences.getString(KEY_ACTIVE_SESSION, null)
        val migratedActive = activeRaw?.let { raw ->
            runCatching { ActiveStudySession.fromJson(org.json.JSONObject(raw)) }
                .map { legacy ->
                    val at = sessionClock.now()
                    val knownDurations = legacy.timeline?.let {
                        SessionTimelineReducer.durations(it, at)
                    } ?: run {
                        val wallDerivedStudyMs = StudySessionClock.studyDurationMs(legacy, at.wallMs)
                        val anchoredStudyMs = legacy.lastBreakStudyDurationMs
                            .takeIf { legacy.breakCount > 0 && it > 0L }
                            ?: wallDerivedStudyMs
                        val knownBreakMs = legacy.accumulatedBreakMs.coerceAtLeast(0L)
                        val knownPauseMs = legacy.accumulatedPauseMs.coerceAtLeast(0L)
                        val elapsedWallMs = (at.wallMs - legacy.startTimeMs).coerceAtLeast(0L)
                        SessionDurations(
                            studyMs = anchoredStudyMs,
                            breakMs = knownBreakMs,
                            pauseMs = knownPauseMs,
                            unknownMs = (
                                elapsedWallMs - anchoredStudyMs - knownBreakMs - knownPauseMs
                            ).coerceAtLeast(0L),
                        )
                    }
                    legacy.copy(
                        timeline = SessionTimelineSnapshot(
                            sessionId = legacy.sessionId,
                            revision = 0L,
                            state = SessionState.RECOVERY_REQUIRED,
                            blockId = legacy.focusBlockId,
                            enteredAt = at,
                            accumulatedStudyMs = knownDurations.studyMs,
                            accumulatedBreakMs = knownDurations.breakMs,
                            accumulatedPauseMs = knownDurations.pauseMs,
                            unknownDurationMs = knownDurations.unknownMs,
                        )
                    )
                }
                .getOrElse {
                    recoveryPayloads += "legacy_active_session" to raw
                    null
                }
        }
        val cooldown = activePreferences.getLong(KEY_COOLDOWN_UNTIL, 0L)
            .takeIf { activePreferences.contains(KEY_COOLDOWN_UNTIL) }
        val pendingReview = preferences.getString(KEY_PENDING_REVIEW_ID, null)

        database.importLegacyState(
            sessions = sessions,
            events = events,
            active = migratedActive,
            cooldownUntilMs = cooldown,
            pendingReviewId = pendingReview,
            recoveryPayloads = recoveryPayloads,
        )

        val importedIds = database.sessionPayloads().mapNotNull {
            runCatching { StudySession.fromJson(org.json.JSONObject(it)).sessionId }.getOrNull()
        }.toSet()
        check(importedIds == sessions.map(StudySession::sessionId).toSet()) { "Session migration verification failed" }
        preferences.edit().clear().putBoolean(KEY_LEGACY_MIGRATION_DONE, true).commit()
        activePreferences.edit().clear().commit()
    }

    /** One-time move of the hot active-session/cooldown keys out of the bulk store. */
    private fun moveActiveStateToOwnFile() {
        if (!preferences.contains(KEY_ACTIVE_SESSION) && !preferences.contains(KEY_COOLDOWN_UNTIL)) return
        val editor = activePreferences.edit()
        preferences.getString(KEY_ACTIVE_SESSION, null)?.let { editor.putString(KEY_ACTIVE_SESSION, it) }
        if (preferences.contains(KEY_COOLDOWN_UNTIL)) {
            editor.putLong(KEY_COOLDOWN_UNTIL, preferences.getLong(KEY_COOLDOWN_UNTIL, 0L))
        }
        // Commit the destination before deleting the only old copy. This is a
        // one-time migration, so the synchronous write is worth the crash safety.
        if (!editor.commit()) return
        preferences.edit().remove(KEY_ACTIVE_SESSION).remove(KEY_COOLDOWN_UNTIL).apply()
    }

    /** Missing/corrupt individual rows never make the whole session store unreadable. */
    fun sessions(): List<StudySession> = synchronized(STORE_LOCK) {
        storedSessionRows().first
            .filterNot { RetentionPolicy.isExpired(it.expiresOn, it.endTimeMs) }
            .sortedByDescending { it.startTimeMs }
    }

    /** Raw database rows are needed by retention; [sessions] intentionally hides expired rows. */
    private fun storedSessionRows(): Pair<List<StudySession>, Int> {
        val payloads = database.sessionPayloads()
        val parsed = payloads.mapNotNull { raw ->
            runCatching { StudySession.fromJson(org.json.JSONObject(raw)) }
                .getOrElse {
                    database.preserveRecoveryPayload("sqlite_completed_sessions", raw)
                    null
                }
        }
        return parsed to payloads.size
    }

    /** Parsed rows plus the raw row count, so callers can detect dropped rows. */
    private fun parseSessions(raw: String): Pair<List<StudySession>, Int> = runCatching {
        val json = JSONArray(raw)
        val parsed = buildList {
            for (index in 0 until json.length()) {
                runCatching { StudySession.fromJson(json.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
        parsed to json.length()
    }.getOrElse { emptyList<StudySession>() to Int.MAX_VALUE }

    /** Missing/corrupt individual event rows never make the session store unreadable. */
    fun promptEvents(): List<BreakPromptEvent> = synchronized(STORE_LOCK) {
        database.promptEventPayloads().mapNotNull { raw ->
            runCatching { BreakPromptEvent.fromJson(org.json.JSONObject(raw)) }.getOrNull()
        }.filterNot { RetentionPolicy.isExpired(it.expiresOn, it.candidateAtMs) }
            .sortedByDescending { it.candidateAtMs }
    }

    fun addSession(session: StudySession): Unit = synchronized(STORE_LOCK) {
        pruneExpiredStoredDataLocked(LocalDate.now())
        if (RetentionPolicy.isExpired(session.expiresOn, session.endTimeMs)) return@synchronized
        val updated = (sessions() + session)
            .distinctBy { it.sessionId }
            .sortedByDescending { it.startTimeMs }
            .take(MAX_STORED_SESSIONS)
        saveSessions(updated)
        val activeId = activeSession()?.sessionId
        val validIds = updated.map { it.sessionId }.toSet() + listOfNotNull(activeId)
        prunePromptEvents(validIds)
        database.deleteSessionChildren(validIds)
    }

    fun updateSessionResponse(sessionId: String, accepted: Boolean, deferReason: String?): Unit = synchronized(STORE_LOCK) {
        val updated = sessions().map { session ->
            if (session.sessionId == sessionId) session.copy(accepted = accepted, deferReason = deferReason) else session
        }
        saveSessions(updated)
    }

    fun updateSessionReview(sessionId: String, shouldBreakReviewed: Boolean): Unit = synchronized(STORE_LOCK) {
        val updated = sessions().map { session ->
            if (session.sessionId == sessionId) {
                session.copy(
                    reviewedShouldBreak = shouldBreakReviewed,
                    labelSource = "human_review_v1",
                    reminderHistory = database.reminderHistory(sessionId),
                )
            } else {
                session
            }
        }
        saveSessions(updated)
        updated.firstOrNull { it.sessionId == sessionId }?.let {
        }
        if (database.metadata(FocusMateSessionDatabase.META_PENDING_REVIEW) == sessionId) clearPendingReview()
    }

    fun recordPromptTimingFeedback(sessionId: String, timing: PromptTimingFeedback): Boolean =
        synchronized(STORE_LOCK) {
            val reminderId = promptEvents()
                .filter { it.sessionId == sessionId && it.prompted }
                .maxByOrNull(BreakPromptEvent::candidateAtMs)
                ?.eventId ?: return@synchronized false
            val recorded = database.recordPromptFeedback(
                reminderId = reminderId,
                sessionId = sessionId,
                timing = timing,
                annoyance = null,
                wallMs = System.currentTimeMillis(),
            )
            if (recorded) {
                updateSessionReview(sessionId, timing != PromptTimingFeedback.TOO_EARLY)
            }
            recorded
        }

    fun pendingReviewSession(): StudySession? {
        val sessionId = database.metadata(FocusMateSessionDatabase.META_PENDING_REVIEW) ?: return null
        return sessions().firstOrNull { it.sessionId == sessionId && it.reviewedShouldBreak == null }
            ?: run {
                clearPendingReview()
                null
            }
    }

    fun clearPendingReview() {
        database.putMetadata(FocusMateSessionDatabase.META_PENDING_REVIEW, "")
    }

    fun activeSession(): ActiveStudySession? {
        val raw = database.activePayload() ?: return null
        return runCatching { ActiveStudySession.fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun reconcileActiveSession(): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val timeline = active.timeline ?: return@synchronized active
        val now = sessionClock.now()
        val reconciled = SessionTimelineReducer.reconcile(timeline, now)
        if (reconciled == timeline) return@synchronized active
        val updated = active.copy(timeline = reconciled, pendingReminder = null)
        val commandId = "${active.sessionId}:recovery:${timeline.revision}:${now.bootId}"
        val applied = database.requireRecovery(
            commandId, timeline, updated, now, "boot_or_elapsed_discontinuity",
            active.pendingReminder?.eventId,
        )
        if (applied) updated else activeSession()
    }

    fun resumeRecoveredSession(
        sessionId: String,
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = sessionClock.now(),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        if (database.hasCommand(commandId)) return@synchronized activeSession()
        val active = activeSession() ?: return@synchronized null
        val timeline = active.timeline ?: return@synchronized null
        if (active.sessionId != sessionId || timeline.state != SessionState.RECOVERY_REQUIRED) return@synchronized null
        val resumedTimeline = SessionTimelineReducer.resumeFromRecovery(timeline, at)
        val updated = active.copy(
            timeline = resumedTimeline,
            focusBlockId = resumedTimeline.blockId,
            lastBreakStudyDurationMs = resumedTimeline.accumulatedStudyMs,
            breakStartedAtMs = null,
            breakEndsAtMs = null,
            breakAwaitingDecisionAtMs = null,
            pausedAtMs = null,
            pendingReminder = null,
            continuousImmobileMs = 0L,
            motionBlockValidDurationMs = 0L,
            lastMotionWindowStartElapsedMs = null,
            lastMotionWindowEndElapsedMs = null,
            lastMotionBootId = null,
            lastMotionBlockId = null,
            lastMotionSequence = null,
            lastMotionGeneration = null,
        )
        val applied = database.applyCommand(
            commandId, "$commandId:event", sessionId, updated.focusBlockId,
            "RESUME_RECOVERED_SESSION", at, org.json.JSONObject(), updated,
            closeReminderId = active.pendingReminder?.eventId,
        )
        if (applied) updated else activeSession()
    }

    fun checkpointActiveSession(): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val timeline = active.timeline ?: return@synchronized active
        val at = sessionClock.now()
        if (at.bootId == timeline.checkpointAt.bootId &&
            at.elapsedMs - timeline.checkpointAt.elapsedMs < CHECKPOINT_INTERVAL_MS
        ) return@synchronized active
        val checkpoint = SessionTimelineReducer.checkpoint(timeline, at)
        val updated = active.copy(timeline = checkpoint)
        val commandId = "${active.sessionId}:checkpoint:${at.elapsedMs / CHECKPOINT_INTERVAL_MS}"
        val applied = database.applyCommand(
            commandId, "$commandId:event", active.sessionId, active.focusBlockId,
            "CHECKPOINT", at, org.json.JSONObject(), updated,
        )
        if (applied) updated else activeSession()
    }

    fun saveActiveSession(session: ActiveStudySession) {
        synchronized(STORE_LOCK) {
            persistActive(session)
        }
    }

    private fun persistActive(session: ActiveStudySession) =
        database.putActive(
            session.toJson().toString(),
            session.sessionId,
            session.inferredState(),
            session.timeline?.revision ?: 0L,
        )

    fun setPendingReminder(sessionId: String, reminder: PendingReminder): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            if (active.pendingReminder?.eventId == reminder.eventId) return@synchronized active
            val coordinated = DefaultInteractionCoordinator.openOrMerge(active.pendingReminder, reminder)
            val effective = coordinated.reminder
            val updated = if (effective.kind == PendingReminderKind.BREAK_SUGGESTION && coordinated.openedNewEpisode) {
                active.copy(
                    pendingReminder = effective,
                    breakReminderCount = active.breakReminderCount + 1,
                    accepted = null,
                    deferReason = null,
                    lastPromptAtMs = effective.createdAtMs,
                    lastPromptEventId = effective.eventId,
                )
            } else {
                active.copy(pendingReminder = effective)
            }
            persistActive(updated)
            database.upsertReminderEpisode(updated, effective)
            updated
        }

    fun advancePendingReminder(sessionId: String, eventId: String): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            val pending = active.pendingReminder ?: return@synchronized null
            if (active.sessionId != sessionId || pending.eventId != eventId) return@synchronized null
            val updated = active.copy(pendingReminder = BreakReminderPolicy.nextAttempt(pending))
            persistActive(updated)
            updated.pendingReminder?.let { database.upsertReminderEpisode(updated, it) }
            updated
        }

    fun markPendingDeliveryState(
        sessionId: String,
        eventId: String,
        state: ReminderDeliveryState,
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val pending = active.pendingReminder ?: return@synchronized null
        if (active.sessionId != sessionId || pending.eventId != eventId) return@synchronized null
        val updated = active.copy(pendingReminder = pending.copy(deliveryState = state))
        persistActive(updated)
        updated.pendingReminder?.let { database.upsertReminderEpisode(updated, it) }
        updated
    }

    fun reservePendingDeliverySlot(sessionId: String, reminderId: String): Int? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val reminder = active.pendingReminder ?: return@synchronized null
        if (active.sessionId != sessionId || reminder.eventId != reminderId || reminder.attempt !in 1..2) {
            return@synchronized null
        }
        val slotIndex = reminder.attempt - 1
        val receiverAt = sessionClock.now()
        val scheduledWall = reminder.createdAtMs + BreakReminderPolicy.RETRY_OFFSETS_MS[slotIndex]
        val scheduledElapsed = reminder.createdAtElapsedMs
            ?.takeIf { reminder.createdBootId == receiverAt.bootId }
            ?.plus(BreakReminderPolicy.RETRY_OFFSETS_MS[slotIndex])
            ?: (receiverAt.elapsedMs - (receiverAt.wallMs - scheduledWall)).coerceAtLeast(0L)
        val reserved = database.reserveDeliverySlot(
            reminderId,
            slotIndex,
            TimePoint(scheduledWall, scheduledElapsed, receiverAt.bootId),
            receiverAt,
        )
        slotIndex.takeIf { reserved }
    }

    fun completePendingDeliverySlot(reminderId: String, slotIndex: Int, result: String) {
        database.completeDeliverySlot(reminderId, slotIndex, sessionClock.now(), result)
    }

    fun clearPendingReminder(sessionId: String, eventId: String): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            val pending = active.pendingReminder ?: return@synchronized active
            if (active.sessionId != sessionId || pending.eventId != eventId) return@synchronized null
            val updated = active.copy(pendingReminder = null)
            persistActive(updated)
            updated
        }

    fun startBreak(
        sessionId: String,
        startedAtMs: Long = System.currentTimeMillis(),
        durationMs: Long = StudySessionClock.BREAK_DURATION_MS,
        commandId: String = UUID.randomUUID().toString(),
        activity: BreakActivityType = BreakActivityType.FULL_BREAK,
        at: TimePoint = timePointAt(startedAtMs),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = StudySessionClock.startBreak(active, startedAtMs, durationMs)
        if (updated == active) return@synchronized active
        val transitioned = active.timeline?.let {
            SessionTimelineReducer.transition(it, SessionState.BREAKING, at, durationMs)
        }
        val timelineUpdated = if (transitioned != null) updated.copy(
            timeline = transitioned,
            lastBreakStudyDurationMs = SessionTimelineReducer.durations(transitioned, at).studyMs,
        ) else updated
        val breakId = "$sessionId:break:${updated.breakCount}"
        val applied = database.applyBreakTransition(
            commandId = commandId,
            eventId = "$commandId:event",
            active = timelineUpdated,
            episode = BreakEpisodeRecord(
                breakId = breakId,
                sessionId = sessionId,
                blockId = active.focusBlockId,
                startedAt = at,
                plannedDurationMs = durationMs,
                activity = activity,
            ),
            at = at,
        )
        if (applied) timelineUpdated else activeSession()
    }

    /** Accepts a current suggestion and starts its break in one SQLite transaction. */
    fun acceptReminderAndStartBreak(
        sessionId: String,
        reminderId: String,
        at: TimePoint = sessionClock.now(),
        durationMs: Long = StudySessionClock.BREAK_DURATION_MS,
        commandId: String = "accept:$reminderId",
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val pending = active.pendingReminder ?: return@synchronized null
        if (active.sessionId != sessionId || active.focusBlockId != active.timeline?.blockId ||
            pending.eventId != reminderId || pending.kind != PendingReminderKind.BREAK_SUGGESTION
        ) return@synchronized null
        if (active.accepted != null || active.timeline?.state != SessionState.STUDYING) return@synchronized null

        val legacyUpdated = StudySessionClock.startBreak(active, at.wallMs, durationMs)
        val transitioned = SessionTimelineReducer.transition(
            requireNotNull(active.timeline), SessionState.BREAKING, at, durationMs,
        )
        val updated = legacyUpdated.copy(
            accepted = true,
            deferReason = null,
            pendingReminder = null,
            timeline = transitioned,
            lastBreakStudyDurationMs = SessionTimelineReducer.durations(transitioned, at).studyMs,
        )
        val responseEvents = promptEvents().map { event ->
            if (event.eventId == reminderId && event.sessionId == sessionId && event.response == null && event.prompted) {
                FocusMatePromptEventPolicy.withObservedResponse(
                    event = event,
                    response = BreakPromptEvent.RESPONSE_ACCEPTED,
                    respondedAtMs = at.wallMs,
                    declineReasonCode = null,
                    quietUntilMs = null,
                )
            } else event
        }
        val episode = BreakEpisodeRecord(
            breakId = "$sessionId:break:${updated.breakCount}",
            sessionId = sessionId,
            blockId = active.focusBlockId,
            startedAt = at,
            plannedDurationMs = durationMs,
            activity = BreakActivityType.FULL_BREAK,
        )
        val applied = database.acceptReminderAndStartBreak(
            commandId = commandId,
            eventId = "$commandId:event",
            reminderId = reminderId,
            active = updated,
            episode = episode,
            at = at,
            promptEvents = responseEvents,
        )
        if (applied) updated else activeSession()
    }

    fun markBreakAwaitingDecisionIfDue(nowMs: Long = System.currentTimeMillis()): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            if (active.timeline?.state == SessionState.BREAKING && breakRemainingMs(active) > 0L) {
                return@synchronized null
            }
            var updated = if (active.timeline != null) {
                if (active.timeline.state == SessionState.AWAITING_RESUME) active
                else active.copy(breakAwaitingDecisionAtMs = nowMs)
            } else {
                StudySessionClock.markAwaitingDecisionIfDue(active, nowMs) ?: return@synchronized null
            }
            val at = timePointAt(nowMs)
            active.timeline?.takeIf { it.state == SessionState.BREAKING }?.let {
                updated = updated.copy(
                    timeline = SessionTimelineReducer.transition(it, SessionState.AWAITING_RESUME, at)
                )
            }
            val commandId = "${active.sessionId}:break-awaiting:${active.timeline?.breakDeadlineElapsedMs ?: nowMs}"
            val applied = database.applyCommand(
                commandId, "$commandId:event", active.sessionId, active.focusBlockId,
                "BREAK_AWAITING_RESUME", at, org.json.JSONObject(), updated,
            )
            if (applied) updated else activeSession()
        }

    fun resumeStudyAfterBreak(
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = timePointAt(nowMs),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        if (database.hasCommand(commandId)) return@synchronized activeSession()
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        var updated = StudySessionClock.resumeFromBreak(active, nowMs) ?: return@synchronized null
        active.timeline?.let {
            val timeline = SessionTimelineReducer.transition(it, SessionState.STUDYING, at)
            updated = updated.copy(timeline = timeline, focusBlockId = timeline.blockId)
        }
        val episode = database.breakEpisodes(sessionId).lastOrNull { it.endedAt == null }
            ?: return@synchronized null
        val actual = if (episode.startedAt.bootId == at.bootId && at.elapsedMs >= episode.startedAt.elapsedMs) {
            at.elapsedMs - episode.startedAt.elapsedMs
        } else return@synchronized null
        val applied = database.resumeBreak(
            commandId,
            "$commandId:event",
            updated,
            episode.breakId,
            at,
            actual,
            closeReminderId = active.pendingReminder?.eventId,
        )
        if (applied) updated else activeSession()
    }

    fun pauseSession(
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = timePointAt(nowMs),
    ): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            if (database.hasCommand(commandId)) return@synchronized activeSession()
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            var updated = StudySessionClock.pause(active, nowMs)?.copy(
                pendingReminder = null,
                continuousImmobileMs = 0L,
                lastMotionWindowStartElapsedMs = null,
                lastMotionWindowEndElapsedMs = null,
                lastMotionBootId = null,
                lastMotionBlockId = null,
                lastMotionSequence = null,
                lastMotionGeneration = null,
            ) ?: return@synchronized null
            active.timeline?.let {
                updated = updated.copy(timeline = SessionTimelineReducer.transition(it, SessionState.PAUSED, at))
            }
            val applied = database.applyCommand(
                commandId, "$commandId:event", sessionId, updated.focusBlockId,
                "PAUSE_SESSION", at, org.json.JSONObject(), updated,
                closeReminderId = active.pendingReminder?.eventId,
            )
            if (applied) updated else activeSession()
        }

    fun resumePausedSession(
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = timePointAt(nowMs),
    ): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            if (database.hasCommand(commandId)) return@synchronized activeSession()
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            var updated = StudySessionClock.resumeFromPause(active, nowMs) ?: return@synchronized null
            active.timeline?.let {
                updated = updated.copy(timeline = SessionTimelineReducer.transition(it, SessionState.STUDYING, at))
            }
            val applied = database.applyCommand(
                commandId, "$commandId:event", sessionId, updated.focusBlockId,
                "RESUME_PAUSED_SESSION", at, org.json.JSONObject(), updated,
            )
            if (applied) updated else activeSession()
        }

    fun extendBreak(
        sessionId: String,
        nowMs: Long,
        durationMs: Long,
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = timePointAt(nowMs),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        if (database.hasCommand(commandId)) return@synchronized activeSession()
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        var updated = StudySessionClock.extendBreak(active, nowMs, durationMs) ?: return@synchronized null
        active.timeline?.let {
            updated = updated.copy(
                timeline = SessionTimelineReducer.transition(it, SessionState.BREAKING, at, durationMs)
            )
        }
        val applied = database.applyCommand(
            commandId, "$commandId:event", sessionId, updated.focusBlockId,
            "EXTEND_BREAK", at, org.json.JSONObject().put("duration_ms", durationMs), updated,
            closeReminderId = active.pendingReminder?.eventId,
        )
        if (applied) updated else activeSession()
    }

    /** Merge a non-overlapping 30-second motion window into the current session. */
    fun updateActiveMotion(sessionId: String, metrics: MotionWindowMetrics) {
        synchronized(STORE_LOCK) {
            val latest = activeSession() ?: return@synchronized
            if (latest.sessionId != sessionId) return@synchronized
            val hasWindowIdentity = metrics.windowEndElapsedMs > metrics.windowStartElapsedMs &&
                metrics.bootId != "legacy"
            if (metrics.sessionId != "legacy" && metrics.sessionId != latest.sessionId) return@synchronized
            if (hasWindowIdentity && metrics.blockId != latest.focusBlockId) return@synchronized
            val sameChain = hasWindowIdentity &&
                latest.lastMotionBootId == metrics.bootId &&
                latest.lastMotionBlockId == metrics.blockId &&
                latest.lastMotionGeneration == metrics.collectorGeneration
            if (sameChain && latest.lastMotionSequence?.let { metrics.sequence <= it } == true) return@synchronized
            if (sameChain && latest.lastMotionWindowEndElapsedMs?.let { metrics.windowStartElapsedMs < it } == true) {
                return@synchronized
            }
            val gapMs = if (sameChain) {
                metrics.windowStartElapsedMs - (latest.lastMotionWindowEndElapsedMs ?: metrics.windowStartElapsedMs)
            } else {
                Long.MAX_VALUE
            }
            val validDurationMs = if (hasWindowIdentity) {
                metrics.validSampleDurationMs.coerceIn(0L, metrics.windowEndElapsedMs - metrics.windowStartElapsedMs)
            } else {
                30_000L
            }
            val immobileWindow = metrics.immobileSeconds * 1_000.0 >= validDurationMs * 0.96
            val continued = sameChain && gapMs in 0L..MOTION_GAP_TOLERANCE_MS
            val measured = latest.copy(
                movementRms = metrics.movementRms,
                rotationRms = metrics.rotationRms,
                motionWindowCount = latest.motionWindowCount + 1,
                suddenMovementCount = latest.suddenMovementCount + metrics.suddenMovementCount,
                wristRotationCount = latest.wristRotationCount + metrics.wristRotationCount,
                immobileSeconds = latest.immobileSeconds + metrics.immobileSeconds,
                continuousImmobileMs = if (immobileWindow) {
                    (if (continued) latest.continuousImmobileMs else 0L) + validDurationMs
                } else {
                    0L
                },
                motionBlockValidDurationMs = latest.motionBlockValidDurationMs + validDurationMs,
                lastMotionWindowStartElapsedMs = if (hasWindowIdentity) metrics.windowStartElapsedMs else latest.lastMotionWindowStartElapsedMs,
                lastMotionWindowEndElapsedMs = if (hasWindowIdentity) metrics.windowEndElapsedMs else latest.lastMotionWindowEndElapsedMs,
                lastMotionBootId = if (hasWindowIdentity) metrics.bootId else latest.lastMotionBootId,
                lastMotionBlockId = if (hasWindowIdentity) metrics.blockId else latest.lastMotionBlockId,
                lastMotionSequence = if (hasWindowIdentity) metrics.sequence else latest.lastMotionSequence,
                lastMotionGeneration = if (hasWindowIdentity) metrics.collectorGeneration else latest.lastMotionGeneration,
                movementChangeFromBaseline = metrics.movementChangeFromBaseline,
                watchRaiseCount = latest.watchRaiseCount + metrics.watchRaiseCount,
            )
            val updated = measured.copy(sessionConfidence = SessionConfidence.calculate(measured, metrics.observedAtMs))
            persistActive(updated)
            recordPolicyComparisonFor(
                updated,
                "motion:${metrics.bootId}:${metrics.blockId}:${metrics.sequence}",
                timePointAt(metrics.observedAtMs),
            )
        }
    }

    fun recordCheckIn(
        sessionId: String,
        source: CheckInSource,
        focus: Int? = null,
        fatigue: Int? = null,
        at: TimePoint = sessionClock.now(),
        checkInId: String = UUID.randomUUID().toString(),
        breakId: String? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized false
        if (active.sessionId != sessionId) return@synchronized false
        val linkedBreakId = breakId ?: when (source) {
            CheckInSource.BEFORE_BREAK -> database.breakEpisodes(sessionId).lastOrNull { it.endedAt == null }?.breakId
                ?: "$sessionId:break:${active.breakCount + 1}"
            CheckInSource.AFTER_BREAK -> database.breakEpisodes(sessionId).lastOrNull { it.endedAt != null }?.breakId
            else -> null
        }
        val record = CheckInRecord(
            checkInId = checkInId,
            sessionId = sessionId,
            blockId = active.focusBlockId,
            source = source,
            time = at,
            breakId = linkedBreakId,
            focus = focus,
            fatigue = fatigue,
        )
        val inserted = database.recordCheckIn(record)
        if (inserted) recordPolicyComparisonFor(active, "checkin:$checkInId", at)
        inserted
    }

    fun checkIns(sessionId: String, blockId: String? = null): List<CheckInRecord> =
        database.checkIns(sessionId, blockId)

    internal fun reminderHistory(sessionId: String): List<ReminderEpisodeHistory> =
        database.reminderHistory(sessionId)

    fun evaluatePolicyPair(
        active: ActiveStudySession,
        durationMs: Long,
        at: TimePoint = sessionClock.now(),
    ): Pair<PolicyDecision, PolicyDecision> {
        val validMotionDurationMs = active.motionBlockValidDurationMs.takeIf { it > 0L }
            ?: (active.motionWindowCount * 30_000L)
        val coverage = (validMotionDurationMs.toDouble() / durationMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val context = PolicyContext(
            sessionId = active.sessionId,
            blockId = active.focusBlockId,
            studyDurationMs = durationMs,
            initialFocus = active.focusScore,
            initialFatigue = active.fatigueScore,
            now = at,
            cooldownUntilWallMs = cooldownUntilMs(),
            motion = active.motionActivityObservedAtMs?.let {
                MotionEvidence(active.continuousImmobileMs, coverage, it)
            },
            checkIns = checkIns(active.sessionId, active.focusBlockId),
        )
        return WatchRulesV2Policy.evaluate(context) to CheckInShadowV1Policy.evaluate(context)
    }

    private fun recordPolicyComparisonFor(active: ActiveStudySession, inputRef: String, at: TimePoint) {
        val duration = focusBlockDurationMs(active, at.wallMs)
        val (baseline, shadow) = evaluatePolicyPair(active, duration, at)
        database.recordPolicyComparison(
            comparisonId = "${active.sessionId}:${active.focusBlockId}:$inputRef",
            sessionId = active.sessionId,
            blockId = active.focusBlockId,
            inputRef = inputRef,
            baseline = baseline,
            shadow = shadow,
            wallMs = at.wallMs,
        )
    }

    internal fun policyComparisonCount(sessionId: String): Int = database.policyComparisonCount(sessionId)
    fun breakEpisodes(sessionId: String): List<BreakEpisodeRecord> = database.breakEpisodes(sessionId)

    private fun timePointAt(wallMs: Long): TimePoint = sessionClock.now().copy(wallMs = wallMs)

    /** Store an explainable activity result without any trained motion model. */
    fun updateActiveRuleActivity(
        sessionId: String,
        result: RuleBasedActivityResult,
        observedAtMs: Long,
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val latest = activeSession() ?: return@synchronized null
        if (latest.sessionId != sessionId) return@synchronized null
        val updated = latest.copy(
            motionActivityLabel = result.label.name,
            motionActivityConfidence = result.confidence.coerceIn(0.0, 1.0),
            motionActivityObservedAtMs = observedAtMs,
            motionActivityFallbackReason = if (result.calibrated) null else
                "calibrating_personal_thresholds_${result.calibrationWindows}",
        )
        persistActive(updated)
        updated
    }

    /** Posture is persisted as insight only and never calls the reminder engine. */
    fun updateActivePosture(
        sessionId: String,
        summaries: List<PostureStateSummary>,
        insights: List<PostureInsight>,
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = active.copy(
            postureSummaries = summaries,
            postureInsightReasonCodes = active.postureInsightReasonCodes + insights.map(PostureInsight::reasonCode),
        )
        persistActive(updated)
        updated
    }

    /** Persists numeric yawn summaries only; frames and landmarks never enter storage. */
    fun updateActiveYawn(sessionId: String, detection: YawnDetection): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            val eventId = active.nextYawnSyncEventId
            val pending = if (detection.eventJustCounted) {
                (active.pendingYawnSyncEvents + PendingYawnSyncEvent(
                    eventId = eventId,
                    frameSequence = detection.frameSequence,
                    observedEspUptimeMs = detection.observedEspUptimeMs,
                )).takeLast(MAX_PENDING_YAWN_SYNC_EVENTS)
            } else {
                active.pendingYawnSyncEvents
            }
            val updated = active.copy(
                yawnCount = detection.totalCount,
                yawnAlertCount = detection.alertCount,
                yawnTotalDurationMs = detection.totalDurationMs,
                recentYawnEventTimesMs = detection.recentEventTimesMs,
                lastYawnAlertAtMs = detection.lastAlertAtMs,
                nextYawnSyncEventId = if (detection.eventJustCounted) {
                    (eventId + 1L) and UINT32_MAX
                } else active.nextYawnSyncEventId,
                pendingYawnSyncEvents = pending,
            )
            persistActive(updated)
            updated
        }

    fun applyCanonicalYawnSync(
        sessionId: String,
        state: CanonicalYawnSyncState,
        acknowledgedEventId: Long? = null,
        observedAtMs: Long = System.currentTimeMillis(),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId || active.yawnSyncSessionId != state.session) return@synchronized null
        if (state.revision < active.yawnSyncRevision) return@synchronized active
        val updated = active.copy(
            yawnCount = maxOf(active.yawnCount, state.totalCount),
            yawnSyncRevision = state.revision,
            yawnSyncWindowCount = state.windowCount,
            yawnSyncObservedAtMs = observedAtMs,
            pendingYawnSyncEvents = if (acknowledgedEventId == null) active.pendingYawnSyncEvents else {
                active.pendingYawnSyncEvents.filterNot { it.eventId == acknowledgedEventId }
            },
        )
        persistActive(updated)
        updated
    }

    /** Starts a fresh broker revision epoch after Device Info reports a new ESP boot ID. */
    fun resetActiveYawnSyncEpoch(sessionId: String): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = active.copy(
            yawnSyncRevision = 0L,
            yawnSyncWindowCount = 0,
            yawnSyncObservedAtMs = null,
            // Pending events contain uptime from the previous ESP boot and are
            // not comparable with the new monotonic clock. The checkpoint
            // total is re-seeded by the resume command instead.
            pendingYawnSyncEvents = emptyList(),
        )
        persistActive(updated)
        updated
    }

    /** Incrementally updates personal-session HR averages without storing raw samples. */
    fun updateActiveHeartRate(sessionId: String, bpm: Double, observedAtMs: Long = System.currentTimeMillis()) {
        if (!bpm.isFinite() || bpm !in 25.0..240.0) return
        synchronized(STORE_LOCK) {
            val latest = activeSession() ?: return@synchronized
            if (latest.sessionId != sessionId) return@synchronized
            val sampleCount = latest.heartRateSampleCount + 1
            val average = ((latest.heartRateAverage ?: 0.0) * latest.heartRateSampleCount + bpm) / sampleCount
            val inBaseline = StudySessionClock.studyDurationMs(latest, observedAtMs) <= HEART_RATE_BASELINE_MS
            val baselineCount = latest.heartRateBaselineSampleCount + if (inBaseline) 1 else 0
            val baseline = if (inBaseline) {
                ((latest.heartRateBaseline ?: 0.0) * latest.heartRateBaselineSampleCount + bpm) / baselineCount
            } else {
                latest.heartRateBaseline
            }
            // Health Services callbacks can arrive out of order. Preserve the
            // actual newest reading while keeping aggregate statistics.
            val isNewestCurrentSample = latest.heartRateCurrent == null ||
                latest.heartRateObservedAtMs == null || observedAtMs >= latest.heartRateObservedAtMs
            val measured = latest.copy(
                heartRateCurrent = if (isNewestCurrentSample) bpm else latest.heartRateCurrent,
                heartRateObservedAtMs = if (isNewestCurrentSample) observedAtMs else latest.heartRateObservedAtMs,
                heartRateAverage = average,
                heartRateBaseline = baseline,
                heartRateSampleCount = sampleCount,
                heartRateBaselineSampleCount = baselineCount,
            )
            val confidenceAtMs = maxOf(observedAtMs, latest.heartRateObservedAtMs ?: observedAtMs)
            val updated = measured.copy(sessionConfidence = SessionConfidence.calculate(measured, confidenceAtMs))
            persistActive(updated)
        }
    }

    /**
     * Persists one immutable decision-time snapshot. For delivered prompts the
     * aggregate session counter and exact event id are updated under the same
     * store lock (active state and events live in separate prefs files).
     */
    fun recordPromptCandidate(event: BreakPromptEvent): ActiveStudySession? {
        return synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            if (event.sessionId != active.sessionId) return@synchronized null
            val existingEvents = promptEvents()
            if (!FocusMatePromptEventPolicy.shouldAppend(existingEvents, event)) return@synchronized active

            val sequence = existingEvents.count { it.sessionId == event.sessionId } + 1
            val sequenced = event.copy(sequence = sequence)
            val alreadyAppliedToActive = active.lastPromptEventId == sequenced.eventId
            val ownsPendingReminder = active.pendingReminder?.eventId == sequenced.eventId
            val updatedActive = if (alreadyAppliedToActive) {
                active
            } else if (sequenced.prompted || ownsPendingReminder) {
                active.copy(
                    breakReminderCount = active.breakReminderCount + 1,
                    accepted = null,
                    deferReason = null,
                    lastPromptAtMs = sequenced.promptedAtMs ?: sequenced.candidateAtMs,
                    lastPromptEventId = sequenced.eventId,
                )
            } else {
                active
            }
            val updatedEvents = (existingEvents + sequenced)
                .distinctBy { it.eventId }
                .sortedByDescending { it.candidateAtMs }
            database.replaceActiveAndPromptEvents(updatedActive, updatedEvents)
            updatedActive
        }
    }

    /**
     * Records an observed action. A stale notification may require an exact
     * current event match; foreground flow can remain functional if best-effort
     * event persistence failed by setting requireCurrentEvent=false.
     */
    fun recordPromptResponse(
        sessionId: String,
        eventId: String,
        accepted: Boolean,
        declineReasonCode: String?,
        sessionDeferReason: String?,
        respondedAtMs: Long,
        quietUntilMs: Long? = null,
        requireCurrentEvent: Boolean = true,
        commandId: String = "response:$eventId:$accepted",
        at: TimePoint = timePointAt(respondedAtMs),
    ): ActiveStudySession? {
        return synchronized(STORE_LOCK) {
            if (database.hasCommand(commandId)) return@synchronized activeSession()
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            if (requireCurrentEvent && active.lastPromptEventId != eventId) return@synchronized null
            // Delivered prompts reset accepted=null. Once an action is stored,
            // repeated/opposing PendingIntents must be a no-op even if the
            // auxiliary event row itself could not be persisted.
            if (active.accepted != null) return@synchronized active
            if (!accepted && (declineReasonCode.isNullOrBlank() || sessionDeferReason.isNullOrBlank() || quietUntilMs == null)) {
                return@synchronized null
            }

            val existingEvents = promptEvents()
            val existingResponse = existingEvents.firstOrNull { it.eventId == eventId && it.sessionId == sessionId }?.response
            if (existingResponse != null) return@synchronized active
            val response = if (accepted) BreakPromptEvent.RESPONSE_ACCEPTED else BreakPromptEvent.RESPONSE_DECLINED
            val updatedEvents = existingEvents.map { event ->
                if (event.eventId == eventId && event.sessionId == sessionId && event.response == null && event.prompted) {
                    FocusMatePromptEventPolicy.withObservedResponse(
                        event = event,
                        response = response,
                        respondedAtMs = respondedAtMs,
                        declineReasonCode = if (accepted) null else declineReasonCode,
                        quietUntilMs = if (accepted) null else quietUntilMs,
                    )
                } else {
                    event
                }
            }
            val updatedActive = active.copy(
                accepted = accepted,
                deferReason = if (accepted) null else sessionDeferReason,
                pendingReminder = null,
            )
            if (!accepted) {
                val applied = database.deferReminder(
                    commandId = commandId,
                    eventId = "$commandId:event",
                    reminderId = eventId,
                    active = updatedActive,
                    promptEvents = updatedEvents,
                    cooldownUntilWallMs = requireNotNull(quietUntilMs),
                    cooldownUntilElapsedMs = at.elapsedMs + (quietUntilMs - at.wallMs).coerceAtLeast(0L),
                    cooldownBootId = at.bootId,
                    at = at,
                )
                if (applied) updatedActive else activeSession()
            } else {
                // Accepting a break suggestion must use acceptReminderAndStartBreak();
                // this compatibility path remains for non-break historical responses.
                database.replaceActiveAndPromptEvents(active = updatedActive, events = updatedEvents)
                database.updateReminderResponse(
                    eventId,
                    ReminderDeliveryState.ACCEPTED,
                    BreakPromptEvent.RESPONSE_ACCEPTED,
                )
                updatedActive
            }
        }
    }

    fun cancelActiveSession() {
        synchronized(STORE_LOCK) {
            val sessionId = activeSession()?.sessionId
            database.clearActive()
            if (sessionId != null) {
                val retained = promptEvents().filterNot { it.sessionId == sessionId }
                database.replacePromptEvents(retained)
            }
        }
    }

    fun clearActiveSession() {
        synchronized(STORE_LOCK) { database.clearActive() }
    }

    fun cooldownUntilMs(): Long {
        val now = sessionClock.now()
        val elapsedDeadline = database.metadata(FocusMateSessionDatabase.META_COOLDOWN_UNTIL_ELAPSED)?.toLongOrNull()
        val bootId = database.metadata(FocusMateSessionDatabase.META_COOLDOWN_BOOT_ID)
        if (elapsedDeadline != null && bootId == now.bootId && now.bootId != "boot-unknown") {
            return now.wallMs + (elapsedDeadline - now.elapsedMs).coerceAtLeast(0L)
        }
        return database.metadata(FocusMateSessionDatabase.META_COOLDOWN_UNTIL)?.toLongOrNull() ?: 0L
    }

    fun setCooldownUntilMs(value: Long) {
        synchronized(STORE_LOCK) {
            val now = sessionClock.now()
            database.putMetadata(FocusMateSessionDatabase.META_COOLDOWN_UNTIL, value.toString())
            database.putMetadata(
                FocusMateSessionDatabase.META_COOLDOWN_UNTIL_ELAPSED,
                (now.elapsedMs + (value - now.wallMs).coerceAtLeast(0L)).toString(),
            )
            database.putMetadata(FocusMateSessionDatabase.META_COOLDOWN_BOOT_ID, now.bootId)
        }
    }

    fun evaluateBreak(
        active: ActiveStudySession,
        durationMs: Long,
        nowMs: Long,
        cooldownUntilMs: Long = cooldownUntilMs(),
    ): BreakDecision {
        val elapsedMs = durationMs.coerceAtLeast(1L)
        val validMotionDurationMs = active.motionBlockValidDurationMs.takeIf { it > 0L }
            ?: (active.motionWindowCount * 30_000L)
        val motionCoverage = (validMotionDurationMs.toDouble() / elapsedMs).coerceIn(0.0, 1.0)
        val deterministic = WatchRuleEngine.evaluate(
            ReminderContext(
                studyDurationMs = durationMs,
                fatigueScore = active.fatigueScore.coerceIn(1, 10),
                focusScore = active.focusScore.coerceIn(1, 5),
                nowMs = nowMs,
                cooldownUntilMs = cooldownUntilMs,
                motion = active.motionActivityObservedAtMs?.let {
                    MotionEvidence(
                        continuousImmobileMs = active.continuousImmobileMs,
                        coverage = motionCoverage,
                        observedAtMs = it,
                    )
                },
            )
        )
        val decision = BreakDecision(
            shouldBreak = deterministic.shouldSuggestBreak,
            shouldPrompt = deterministic.shouldPrompt,
            cooldownRemainingMs = deterministic.cooldownRemainingMs,
            decisionSource = WatchRuleEngine.RULE_VERSION,
            promptSuppressionReason = deterministic.suppressionReason,
            reasonCodes = deterministic.reasonCodes,
        )
        if (StudySessionClock.isOnBreak(active, nowMs)) {
            return decision.copy(shouldBreak = false, shouldPrompt = false)
        }
        if (StudySessionClock.isPaused(active)) {
            return decision.copy(shouldBreak = false, shouldPrompt = false)
        }

        return decision
    }

    fun studyDurationMs(active: ActiveStudySession, nowMs: Long = System.currentTimeMillis()): Long =
        active.timeline?.let { SessionTimelineReducer.durations(it, timePointAt(nowMs)).studyMs }
            ?: StudySessionClock.studyDurationMs(active, nowMs)

    fun breakRemainingMs(active: ActiveStudySession): Long {
        val timeline = active.timeline ?: return StudySessionClock.breakRemainingMs(active, System.currentTimeMillis())
        if (timeline.state != SessionState.BREAKING) return 0L
        val now = sessionClock.now()
        if (now.bootId != timeline.enteredAt.bootId || now.bootId == "boot-unknown") return 0L
        val deadline = timeline.breakDeadlineElapsedMs
            ?: timeline.plannedBreakDurationMs?.let { timeline.enteredAt.elapsedMs + it }
            ?: return 0L
        return (deadline - now.elapsedMs).coerceAtLeast(0L)
    }

    override fun apply(command: SessionCommand): TransitionResult {
        if (database.hasCommand(command.commandId)) {
            val current = activeSession()
            return TransitionResult(
                applied = false,
                duplicate = true,
                state = current?.inferredState(),
                revision = current?.timeline?.revision,
                reason = "duplicate_command",
            )
        }
        command.expectedIdentity()?.let { (sessionId, blockId) ->
            val current = activeSession()
            if (current == null || current.sessionId != sessionId || current.focusBlockId != blockId) {
                return TransitionResult(
                    applied = false,
                    state = current?.inferredState(),
                    revision = current?.timeline?.revision,
                    reason = "stale_session_or_block",
                )
            }
        }
        return when (command) {
        is SessionCommand.Start -> {
            val existing = activeSession()
            if (existing != null) TransitionResult(false, state = existing.inferredState(), reason = "active_session_exists")
            else {
                val timeline = command.session.timeline ?: SessionTimelineSnapshot(
                    sessionId = command.session.sessionId,
                    revision = 0L,
                    state = SessionState.STUDYING,
                    blockId = command.session.focusBlockId,
                    enteredAt = command.at,
                )
                val started = command.session.copy(timeline = timeline)
                val applied = database.applyCommand(
                    command.commandId, "${command.commandId}:event", started.sessionId,
                    started.focusBlockId, "START_SESSION", command.at, org.json.JSONObject(), started,
                )
                TransitionResult(applied, duplicate = !applied, state = activeSession()?.inferredState(), revision = activeSession()?.timeline?.revision)
            }
        }
        is SessionCommand.AcceptReminder -> resultFor(
            acceptReminderAndStartBreak(
                command.sessionId, command.reminderId, command.at,
                command.plannedDurationMs, command.commandId,
            ),
            command.commandId,
        )
        is SessionCommand.DeferReminder -> resultFor(
            recordPromptResponse(
                command.sessionId, command.reminderId, false,
                command.declineReasonCode, command.sessionDeferReason, command.at.wallMs,
                command.cooldownUntilWallMs, true, command.commandId, command.at,
            ),
            command.commandId,
        )
        is SessionCommand.StartBreak -> resultFor(
            startBreak(
                command.sessionId, command.at.wallMs, command.plannedDurationMs,
                command.commandId, command.activity, command.at,
            ),
            command.commandId,
        )
        is SessionCommand.Pause -> resultFor(
            pauseSession(command.sessionId, command.at.wallMs, command.commandId, command.at), command.commandId,
        )
        is SessionCommand.ResumePaused -> resultFor(
            resumePausedSession(command.sessionId, command.at.wallMs, command.commandId, command.at), command.commandId,
        )
        is SessionCommand.ResumeAfterBreak -> resultFor(
            resumeStudyAfterBreak(command.sessionId, command.at.wallMs, command.commandId, command.at),
            command.commandId,
        )
        is SessionCommand.ExtendBreak -> resultFor(
            extendBreak(command.sessionId, command.at.wallMs, command.durationMs, command.commandId, command.at), command.commandId,
        )
        is SessionCommand.Recover -> resultFor(
            resumeRecoveredSession(command.sessionId, command.commandId, command.at), command.commandId,
        )
        is SessionCommand.Finish -> {
            val completed = finishActiveSession(command.at.wallMs, command.commandId, command.at)
            TransitionResult(
                completed != null,
                duplicate = false,
                state = if (completed != null) SessionState.COMPLETED else activeSession()?.inferredState(),
            )
        }
        is SessionCommand.Cancel -> {
            val active = activeSession()
            val applied = active?.takeIf { it.sessionId == command.sessionId }?.let {
                database.cancelSession(command.commandId, "${command.commandId}:event", it, command.at)
            } == true
            TransitionResult(applied, state = if (applied) SessionState.CANCELLED else active?.inferredState())
        }
    }
    }

    private fun SessionCommand.expectedIdentity(): Pair<String, String>? = when (this) {
        is SessionCommand.Start -> null
        is SessionCommand.AcceptReminder -> sessionId to blockId
        is SessionCommand.DeferReminder -> sessionId to blockId
        is SessionCommand.StartBreak -> sessionId to blockId
        is SessionCommand.Pause -> sessionId to blockId
        is SessionCommand.ResumePaused -> sessionId to blockId
        is SessionCommand.ResumeAfterBreak -> sessionId to blockId
        is SessionCommand.ExtendBreak -> sessionId to blockId
        is SessionCommand.Recover -> sessionId to blockId
        is SessionCommand.Finish -> sessionId to blockId
        is SessionCommand.Cancel -> sessionId to blockId
    }

    private fun resultFor(active: ActiveStudySession?, commandId: String): TransitionResult =
        TransitionResult(
            applied = active != null && database.hasCommand(commandId),
            duplicate = false,
            state = active?.inferredState(),
            revision = active?.timeline?.revision,
        )

    fun focusBlockDurationMs(active: ActiveStudySession, nowMs: Long = System.currentTimeMillis()): Long =
        (studyDurationMs(active, nowMs) - active.lastBreakStudyDurationMs).coerceAtLeast(0L)

    fun realSessions(): List<StudySession> = FocusMateSessionPolicy.realSessions(sessions())

    fun recommendedBreakTargetMinutes(): Int = LocalBreakTimingPolicy.recommend(promptEvents())

    /** Synthetic rows are deliberately kept out of user history and exports. */
    fun demoSessions(): List<StudySession> = FocusMateSessionPolicy.demoSessions(sessions())

    /** Prompt events are exported only when their real parent session is also exportable. */
    fun realPromptEvents(): List<BreakPromptEvent> {
        val parentIds = realSessions().map { it.sessionId }.toSet()
        return FocusMatePromptEventPolicy.realEvents(promptEvents(), parentIds)
    }

    fun finishActiveSession(
        endTimeMs: Long = System.currentTimeMillis(),
        commandId: String = UUID.randomUUID().toString(),
        at: TimePoint = sessionClock.now().copy(wallMs = endTimeMs),
    ): StudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val finishPoint = at.copy(wallMs = endTimeMs)
        val recoveryTimeline = active.timeline?.takeIf { it.state == SessionState.RECOVERY_REQUIRED }
        val safeEnd = (recoveryTimeline?.checkpointAt?.wallMs ?: endTimeMs).coerceAtLeast(active.startTimeMs)
        val durationMs = recoveryTimeline?.accumulatedStudyMs ?: studyDurationMs(active, safeEnd)
        val focusBlockDurationMs = focusBlockDurationMs(active, safeEnd)
        // Display/log completed whole minutes, while the rule itself uses the
        // exact timestamps above so the strict >45-minute boundary is kept.
        val durationMinutes = if (durationMs == 0L) 0 else maxOf(1, (durationMs / 60_000L).toInt())
        val decision = evaluateBreak(active, focusBlockDurationMs, safeEnd, cooldownUntilMs())
        val endedDuringBreak = StudySessionClock.isOnBreak(active, safeEnd)
        val localRecentYawns = active.recentYawnEventTimesMs.count {
            safeEnd - it in 0L..ADVICE_YAWN_WINDOW_MS
        }
        val syncedRecentYawns = active.yawnSyncObservedAtMs
            ?.takeIf { safeEnd - it in 0L..ADVICE_YAWN_WINDOW_MS }
            ?.let { active.yawnSyncWindowCount }
            ?: 0
        val recentYawnCount = maxOf(localRecentYawns, syncedRecentYawns).coerceIn(0, 64)
        val timelineDurations = active.timeline?.let { SessionTimelineReducer.durations(it, finishPoint) }
        val reminderReport = database.reminderReport(active.sessionId)
        val sessionAdvice = SessionAdviceEngine.evaluate(
            SessionAdviceContext(
                fatigueScore = active.fatigueScore,
                focusScore = active.focusScore,
                breakReasonCodes = decision.reasonCodes,
                endedDuringBreak = endedDuringBreak,
                continuousImmobileMs = active.continuousImmobileMs,
                postureSummaries = active.postureSummaries,
                postureInsightReasonCodes = active.postureInsightReasonCodes,
                yawnAlertCount = active.yawnAlertCount,
                yawnRecentWindowCount = recentYawnCount,
                heartRateAverage = active.heartRateAverage,
                heartRateBaseline = active.heartRateBaseline,
                heartRateSampleCount = active.heartRateSampleCount,
                heartRateBaselineSampleCount = active.heartRateBaselineSampleCount,
            )
        )
        val completed = StudySession(
            sessionId = active.sessionId,
            studentCode = active.studentCode,
            startTimeMs = active.startTimeMs,
            endTimeMs = safeEnd,
            durationMinutes = durationMinutes,
            subject = active.subject,
            taskType = active.taskType,
            focusScore = active.focusScore,
            fatigueScore = active.fatigueScore,
            breakReminderCount = active.breakReminderCount,
            shouldBreak = decision.shouldBreak,
            breakReasonCodes = decision.reasonCodes,
            interruptRisk = FocusMateRules.interruptRisk(
                active.focusScore,
                active.fatigueScore,
                durationMinutes,
                active.taskType,
            ),
            accepted = active.accepted,
            deferReason = active.deferReason,
            labelSource = WatchRuleEngine.RULE_VERSION,
            synthetic = active.synthetic,
            movementRms = active.movementRms,
            rotationRms = active.rotationRms,
            motionWindowCount = active.motionWindowCount,
            suddenMovementCount = active.suddenMovementCount,
            wristRotationCount = active.wristRotationCount,
            immobileSeconds = active.immobileSeconds,
            continuousImmobileMs = active.continuousImmobileMs,
            movementChangeFromBaseline = active.movementChangeFromBaseline,
            motionActivityLabel = active.motionActivityLabel,
            motionActivityConfidence = active.motionActivityConfidence,
            motionActivityObservedAtMs = active.motionActivityObservedAtMs,
            motionActivityFallbackReason = active.motionActivityFallbackReason,
            watchRaiseCount = active.watchRaiseCount,
            heartRateAverage = active.heartRateAverage,
            heartRateBaseline = active.heartRateBaseline,
            heartRateSampleCount = active.heartRateSampleCount,
            heartRateBaselineSampleCount = active.heartRateBaselineSampleCount,
            sessionConfidence = SessionConfidence.calculate(active, safeEnd),
            postureSummaries = active.postureSummaries,
            postureInsightReasonCodes = active.postureInsightReasonCodes,
            yawnCount = active.yawnCount,
            yawnAlertCount = active.yawnAlertCount,
            yawnRecentWindowCount = recentYawnCount,
            yawnTotalDurationMs = active.yawnTotalDurationMs,
            adviceRuleVersion = SessionAdviceEngine.RULE_VERSION,
            sessionAdvice = sessionAdvice,
            breakTargetMinutes = active.breakTargetMinutes,
            breakCount = active.breakCount,
            totalBreakDurationMs = recoveryTimeline?.accumulatedBreakMs ?: active.timeline?.let {
                SessionTimelineReducer.durations(it, timePointAt(safeEnd)).breakMs
            } ?: StudySessionClock.totalBreakDurationMs(active, safeEnd),
            totalPauseDurationMs = timelineDurations?.pauseMs
                ?: StudySessionClock.totalPauseDurationMs(active, safeEnd),
            unknownDurationMs = timelineDurations?.unknownMs ?: 0L,
            hasUnquantifiedUnknownInterval = database.unknownIntervalCount(active.sessionId) > 0,
            reminderReasonHistory = reminderReport.reasonCodes,
            deliveryAttemptCount = reminderReport.deliveryAttempts,
            reminderResponseCount = reminderReport.responses,
            reminderHistory = database.reminderHistory(active.sessionId),
            breakFatigueChanges = database.breakFatigueChanges(active.sessionId),
        )
        val openBreak = database.breakEpisodes(active.sessionId).lastOrNull { it.endedAt == null }
        val actualBreakDuration = openBreak?.let {
            if (it.startedAt.bootId == finishPoint.bootId && finishPoint.elapsedMs >= it.startedAt.elapsedMs) {
                finishPoint.elapsedMs - it.startedAt.elapsedMs
            } else null
        }
        val applied = database.finishSession(
            commandId = commandId,
            eventId = "$commandId:event",
            active = active,
            completed = completed,
            at = finishPoint,
            openBreak = openBreak,
            actualBreakDurationMs = actualBreakDuration,
            pendingReview = !completed.synthetic,
        )
        if (applied) {
            pruneExpiredStoredDataLocked(LocalDate.now())
            completed
        } else null
    }

    /** User-requested local erasure. Refused while a session is active. */
    fun deleteAllStudyData(): Boolean = synchronized(STORE_LOCK) {
        if (activeSession() != null) return@synchronized false
        database.clearAll()
        preferences.edit().clear().putBoolean(KEY_LEGACY_MIGRATION_DONE, true).commit()
        activePreferences.edit().clear().commit()
        ReminderDiagnostics.clear(context)
        true
    }

    /** Physically removes expired rows and rebuilds learned state from retained explicit labels. */
    fun pruneExpiredData(today: LocalDate = LocalDate.now()): Boolean {
        var changed = synchronized(STORE_LOCK) { pruneExpiredStoredDataLocked(today) }
        changed = ReminderDiagnostics.prune(context, today) > 0 || changed
        return changed
    }

    fun todayTotalMinutes(
        sourceSessions: List<StudySession> = realSessions(),
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return sourceSessions.filter { Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate() == today }
            .sumOf { it.durationMinutes }
    }

    fun lastSevenDays(
        sourceSessions: List<StudySession> = realSessions(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<DailyStudyTotal> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val totals = sourceSessions.groupBy { Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate() }
            .mapValues { (_, values) -> values.sumOf { it.durationMinutes } }
        return (6L downTo 0L).map { offset ->
            val date = today.minusDays(offset)
            val dayLabel = if (offset == 0L) {
                "Nay"
            } else {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("vi", "VN")).replaceFirstChar { it.uppercase() }
            }
            DailyStudyTotal(date, dayLabel, totals[date] ?: 0)
        }
    }

    private fun saveSessions(sessions: List<StudySession>) {
        database.replaceSessions(sessions)
    }

    private fun promptEventsJson(events: List<BreakPromptEvent>): String {
        val json = JSONArray()
        events.forEach { json.put(it.toJson()) }
        return json.toString()
    }

    private fun prunePromptEvents(validSessionIds: Set<String>) {
        val existing = promptEvents()
        val retained = existing.filter { it.sessionId in validSessionIds }
        if (retained.size != existing.size) {
            database.replacePromptEvents(retained)
        }
    }

    private fun pruneExpiredStoredDataLocked(today: LocalDate): Boolean {
        var changed = false
        val (parsedSessions, rawCount) = storedSessionRows()
        val retainedSessions = parsedSessions
            .filterNot { RetentionPolicy.isExpired(it.expiresOn, it.endTimeMs, today) }
            .sortedByDescending { it.startTimeMs }
            .take(MAX_STORED_SESSIONS)
        if (retainedSessions.size != rawCount) {
            saveSessions(retainedSessions)
            changed = true
        }

        val activeId = activeSession()?.sessionId
        val validIds = retainedSessions.map { it.sessionId }.toSet() + listOfNotNull(activeId)
        val parsedEvents = promptEvents()
        val retainedEvents = parsedEvents.filter {
            it.sessionId in validIds &&
                !RetentionPolicy.isExpired(it.expiresOn, it.candidateAtMs, today)
        }
        if (retainedEvents.size != parsedEvents.size) {
            database.replacePromptEvents(retainedEvents)
            changed = true
        }

        val pendingId = database.metadata(FocusMateSessionDatabase.META_PENDING_REVIEW)
        if (pendingId != null && retainedSessions.none { it.sessionId == pendingId }) {
            database.putMetadata(FocusMateSessionDatabase.META_PENDING_REVIEW, "")
            changed = true
        }
        database.deleteSessionChildren(validIds)
        database.pruneRecoveryPayloads(
            System.currentTimeMillis() - RetentionPolicy.RETENTION_DAYS * 24L * 60L * 60L * 1_000L
        )
        return changed
    }

    companion object {
        private const val HEART_RATE_BASELINE_MS = 60_000L
        private const val ADVICE_YAWN_WINDOW_MS = 10 * 60_000L
        private val STORE_LOCK = Any()
        private const val PREFERENCES_NAME = "focusmate_local_store_v1"
        private const val ACTIVE_PREFERENCES_NAME = "focusmate_active_state_v1"
        private const val KEY_LEGACY_MIGRATION_DONE = "legacy_migration_v2_done"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_ACTIVE_SESSION = "active_session"
        private const val KEY_PROMPT_EVENTS = "prompt_events_v1"
        private const val KEY_COOLDOWN_UNTIL = "cooldown_until_ms"
        private const val KEY_PENDING_REVIEW_ID = "pending_review_session_id"
        private const val MAX_STORED_SESSIONS = 500
        private const val MAX_PENDING_YAWN_SYNC_EVENTS = 16
        private const val UINT32_MAX = 4_294_967_295L
        private const val MOTION_GAP_TOLERANCE_MS = 2_000L
        private const val CHECKPOINT_INTERVAL_MS = 30_000L
        private const val LEGACY_SEED_LABEL = "watch_seed_v1"
        private const val LEGACY_PARTICIPANT_KEY = "participant_code"
    }
}
