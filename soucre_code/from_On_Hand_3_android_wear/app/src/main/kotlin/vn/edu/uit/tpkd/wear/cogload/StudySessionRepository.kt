package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class DailyStudyTotal(val date: LocalDate, val label: String, val minutes: Int)

/** SharedPreferences is sufficient for the bounded, local-first Wear MVP. */
class StudySessionRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    // Hot per-second fields (active session, cooldown) live in their own small
    // file: every apply() rewrites the whole XML, and the bulk store can reach
    // hundreds of KB once sessions/prompt_events accumulate.
    private val activePreferences = context.getSharedPreferences(ACTIVE_PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        var retentionChanged = false
        synchronized(STORE_LOCK) {
            moveActiveStateToOwnFile()
            if (!preferences.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)) {
                migrateLegacyLocalData()
                preferences.edit().putBoolean(KEY_LEGACY_MIGRATION_DONE, true).apply()
            }
            retentionChanged = pruneExpiredStoredDataLocked(LocalDate.now())
        }
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

    /** Rewrites legacy rows once so participant/subject fields and demos disappear on disk. */
    private fun migrateLegacyLocalData() {
        val raw = preferences.getString(KEY_SESSIONS, "[]") ?: "[]"
        val (parsedSessions, rawRowCount) = parseSessions(raw)
        // Never rewrite the store while rows are unreadable: the rewrite would
        // permanently delete every row the parser had to drop.
        if (parsedSessions.size < rawRowCount) return
        val realSessions = parsedSessions
            .filterNot { it.synthetic || it.labelSource == LEGACY_SEED_LABEL }
            .map { session ->
                if (session.accepted == true && session.breakCount == 0) session.copy(breakCount = 1) else session
            }
        val validIds = realSessions.map { it.sessionId }.toSet() + listOfNotNull(activeSession()?.sessionId)
        val realEvents = promptEvents().filter { !it.synthetic && it.sessionId in validIds }
        val sessionJson = JSONArray().apply { realSessions.forEach { put(it.toJson()) } }.toString()
        preferences.edit()
            .putString(KEY_SESSIONS, sessionJson)
            .putString(KEY_PROMPT_EVENTS, promptEventsJson(realEvents))
            .remove(LEGACY_PARTICIPANT_KEY)
            .apply()
    }

    /** Missing/corrupt individual rows never make the whole session store unreadable. */
    fun sessions(): List<StudySession> = synchronized(STORE_LOCK) {
        val raw = preferences.getString(KEY_SESSIONS, "[]") ?: "[]"
        parseSessions(raw).first
            .filterNot { RetentionPolicy.isExpired(it.expiresOn, it.endTimeMs) }
            .sortedByDescending { it.startTimeMs }
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
        val raw = preferences.getString(KEY_PROMPT_EVENTS, "[]") ?: "[]"
        runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    runCatching { BreakPromptEvent.fromJson(json.getJSONObject(index)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }.filterNot { RetentionPolicy.isExpired(it.expiresOn, it.candidateAtMs) }
                .sortedByDescending { it.candidateAtMs }
        }.getOrElse { emptyList() }
    }

    fun addSession(session: StudySession): Unit = synchronized(STORE_LOCK) {
        if (pruneExpiredStoredDataLocked(LocalDate.now())) {
        }
        if (RetentionPolicy.isExpired(session.expiresOn, session.endTimeMs)) return@synchronized
        val updated = (sessions() + session)
            .distinctBy { it.sessionId }
            .sortedByDescending { it.startTimeMs }
            .take(MAX_STORED_SESSIONS)
        saveSessions(updated)
        val activeId = activeSession()?.sessionId
        prunePromptEvents(updated.map { it.sessionId }.toSet() + listOfNotNull(activeId))
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
                )
            } else {
                session
            }
        }
        saveSessions(updated)
        updated.firstOrNull { it.sessionId == sessionId }?.let {
        }
        if (preferences.getString(KEY_PENDING_REVIEW_ID, null) == sessionId) clearPendingReview()
    }

    fun pendingReviewSession(): StudySession? {
        val sessionId = preferences.getString(KEY_PENDING_REVIEW_ID, null) ?: return null
        return sessions().firstOrNull { it.sessionId == sessionId && it.reviewedShouldBreak == null }
            ?: run {
                clearPendingReview()
                null
            }
    }

    fun clearPendingReview() {
        preferences.edit().remove(KEY_PENDING_REVIEW_ID).apply()
    }

    fun activeSession(): ActiveStudySession? {
        val raw = activePreferences.getString(KEY_ACTIVE_SESSION, null) ?: return null
        return runCatching { ActiveStudySession.fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun saveActiveSession(session: ActiveStudySession) {
        synchronized(STORE_LOCK) {
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, session.toJson().toString()).apply()
        }
    }

    fun setPendingReminder(sessionId: String, reminder: PendingReminder): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            if (active.sessionId != sessionId) return@synchronized null
            if (active.pendingReminder?.eventId == reminder.eventId) return@synchronized active
            val updated = if (reminder.kind == PendingReminderKind.BREAK_SUGGESTION) {
                active.copy(
                    pendingReminder = reminder,
                    breakReminderCount = active.breakReminderCount + 1,
                    accepted = null,
                    deferReason = null,
                    lastPromptAtMs = reminder.createdAtMs,
                    lastPromptEventId = reminder.eventId,
                )
            } else {
                active.copy(pendingReminder = reminder)
            }
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
            updated
        }

    fun advancePendingReminder(sessionId: String, eventId: String): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            val pending = active.pendingReminder ?: return@synchronized null
            if (active.sessionId != sessionId || pending.eventId != eventId) return@synchronized null
            val updated = active.copy(pendingReminder = BreakReminderPolicy.nextAttempt(pending))
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
            updated
        }

    fun clearPendingReminder(sessionId: String, eventId: String): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            val pending = active.pendingReminder ?: return@synchronized active
            if (active.sessionId != sessionId || pending.eventId != eventId) return@synchronized null
            val updated = active.copy(pendingReminder = null)
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
            updated
        }

    fun startBreak(
        sessionId: String,
        startedAtMs: Long = System.currentTimeMillis(),
        durationMs: Long = StudySessionClock.BREAK_DURATION_MS,
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = StudySessionClock.startBreak(active, startedAtMs, durationMs)
        activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
        updated
    }

    fun markBreakAwaitingDecisionIfDue(nowMs: Long = System.currentTimeMillis()): ActiveStudySession? =
        synchronized(STORE_LOCK) {
            val active = activeSession() ?: return@synchronized null
            val updated = StudySessionClock.markAwaitingDecisionIfDue(active, nowMs) ?: return@synchronized null
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
            updated
        }

    fun resumeStudyAfterBreak(
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = StudySessionClock.resumeFromBreak(active, nowMs) ?: return@synchronized null
        activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
        updated
    }

    fun extendBreak(
        sessionId: String,
        nowMs: Long,
        durationMs: Long,
    ): ActiveStudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        if (active.sessionId != sessionId) return@synchronized null
        val updated = StudySessionClock.extendBreak(active, nowMs, durationMs) ?: return@synchronized null
        activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
        updated
    }

    /** Merge a non-overlapping 30-second motion window into the current session. */
    fun updateActiveMotion(sessionId: String, metrics: MotionWindowMetrics) {
        synchronized(STORE_LOCK) {
            val latest = activeSession() ?: return@synchronized
            if (latest.sessionId != sessionId) return@synchronized
            val measured = latest.copy(
                movementRms = metrics.movementRms,
                rotationRms = metrics.rotationRms,
                motionWindowCount = latest.motionWindowCount + 1,
                suddenMovementCount = latest.suddenMovementCount + metrics.suddenMovementCount,
                wristRotationCount = latest.wristRotationCount + metrics.wristRotationCount,
                immobileSeconds = latest.immobileSeconds + metrics.immobileSeconds,
                continuousImmobileMs = if (metrics.immobileSeconds >= 29.0) {
                    latest.continuousImmobileMs + 30_000L
                } else {
                    0L
                },
                movementChangeFromBaseline = metrics.movementChangeFromBaseline,
                watchRaiseCount = latest.watchRaiseCount + metrics.watchRaiseCount,
            )
            val updated = measured.copy(sessionConfidence = SessionConfidence.calculate(measured, metrics.observedAtMs))
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
        }
    }

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
        activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
        updated
    }

    /** Persistence seam for the future BLE client; posture never calls the reminder engine. */
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
        activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
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
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updated.toJson().toString()).apply()
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
            activePreferences.edit().putString(KEY_ACTIVE_SESSION, updatedActive.toJson().toString()).apply()
            preferences.edit().putString(KEY_PROMPT_EVENTS, promptEventsJson(updatedEvents)).apply()
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
    ): ActiveStudySession? {
        return synchronized(STORE_LOCK) {
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
            val activeEditor = activePreferences.edit()
                .putString(KEY_ACTIVE_SESSION, updatedActive.toJson().toString())
            if (!accepted && quietUntilMs != null) activeEditor.putLong(KEY_COOLDOWN_UNTIL, quietUntilMs)
            activeEditor.apply()
            preferences.edit().putString(KEY_PROMPT_EVENTS, promptEventsJson(updatedEvents)).apply()
            updatedActive
        }
    }

    fun cancelActiveSession() {
        synchronized(STORE_LOCK) {
            val sessionId = activeSession()?.sessionId
            activePreferences.edit().remove(KEY_ACTIVE_SESSION).apply()
            if (sessionId != null) {
                val retained = promptEvents().filterNot { it.sessionId == sessionId }
                preferences.edit().putString(KEY_PROMPT_EVENTS, promptEventsJson(retained)).apply()
            }
        }
    }

    fun clearActiveSession() {
        synchronized(STORE_LOCK) { activePreferences.edit().remove(KEY_ACTIVE_SESSION).apply() }
    }

    fun cooldownUntilMs(): Long = activePreferences.getLong(KEY_COOLDOWN_UNTIL, 0L)

    fun setCooldownUntilMs(value: Long) {
        synchronized(STORE_LOCK) { activePreferences.edit().putLong(KEY_COOLDOWN_UNTIL, value).apply() }
    }

    fun evaluateBreak(
        active: ActiveStudySession,
        durationMs: Long,
        nowMs: Long,
        cooldownUntilMs: Long = cooldownUntilMs(),
    ): BreakDecision {
        val elapsedMs = StudySessionClock.studyDurationMs(active, nowMs).coerceAtLeast(1L)
        val motionCoverage = (active.motionWindowCount * 30_000.0 / elapsedMs).coerceIn(0.0, 1.0)
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

        return decision
    }

    fun studyDurationMs(active: ActiveStudySession, nowMs: Long = System.currentTimeMillis()): Long =
        StudySessionClock.studyDurationMs(active, nowMs)

    fun focusBlockDurationMs(active: ActiveStudySession, nowMs: Long = System.currentTimeMillis()): Long =
        StudySessionClock.focusBlockDurationMs(active, nowMs)

    fun realSessions(): List<StudySession> = FocusMateSessionPolicy.realSessions(sessions())

    fun recommendedBreakTargetMinutes(): Int = LocalBreakTimingPolicy.recommend(promptEvents())

    /** Synthetic rows are deliberately kept out of user history and exports. */
    fun demoSessions(): List<StudySession> = FocusMateSessionPolicy.demoSessions(sessions())

    /** Prompt events are exported only when their real parent session is also exportable. */
    fun realPromptEvents(): List<BreakPromptEvent> {
        val parentIds = realSessions().map { it.sessionId }.toSet()
        return FocusMatePromptEventPolicy.realEvents(promptEvents(), parentIds)
    }

    fun finishActiveSession(endTimeMs: Long = System.currentTimeMillis()): StudySession? = synchronized(STORE_LOCK) {
        val active = activeSession() ?: return@synchronized null
        val safeEnd = endTimeMs.coerceAtLeast(active.startTimeMs)
        val durationMs = StudySessionClock.studyDurationMs(active, safeEnd)
        val focusBlockDurationMs = StudySessionClock.focusBlockDurationMs(active, safeEnd)
        // Display/log completed whole minutes, while the rule itself uses the
        // exact timestamps above so the strict >45-minute boundary is kept.
        val durationMinutes = if (durationMs == 0L) 0 else maxOf(1, (durationMs / 60_000L).toInt())
        val decision = evaluateBreak(active, focusBlockDurationMs, safeEnd, cooldownUntilMs())
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
            breakTargetMinutes = active.breakTargetMinutes,
            breakCount = active.breakCount,
            totalBreakDurationMs = StudySessionClock.totalBreakDurationMs(active, safeEnd),
        )
        addSession(completed)
        if (!completed.synthetic) {
            preferences.edit().putString(KEY_PENDING_REVIEW_ID, completed.sessionId).apply()
        }
        clearActiveSession()
        completed
    }

    /** User-requested local erasure. Refused while a session is active. */
    fun deleteAllStudyData(): Boolean = synchronized(STORE_LOCK) {
        if (activeSession() != null) return@synchronized false
        val bulkCleared = preferences.edit()
            .clear()
            .putBoolean(KEY_LEGACY_MIGRATION_DONE, true)
            .commit()
        val activeCleared = activePreferences.edit().clear().commit()
        if (!bulkCleared || !activeCleared) return@synchronized false
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
        val json = JSONArray()
        sessions.forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY_SESSIONS, json.toString()).apply()
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
            preferences.edit().putString(KEY_PROMPT_EVENTS, promptEventsJson(retained)).apply()
        }
    }

    private fun pruneExpiredStoredDataLocked(today: LocalDate): Boolean {
        var changed = false
        val rawSessions = preferences.getString(KEY_SESSIONS, "[]") ?: "[]"
        val (parsedSessions, rawCount) = parseSessions(rawSessions)
        val retainedSessions = parsedSessions.filterNot {
            RetentionPolicy.isExpired(it.expiresOn, it.endTimeMs, today)
        }
        if (rawCount == parsedSessions.size && retainedSessions.size != parsedSessions.size) {
            saveSessions(retainedSessions)
            changed = true
        }

        val activeId = activeSession()?.sessionId
        val validIds = retainedSessions.map { it.sessionId }.toSet() + listOfNotNull(activeId)
        val rawEvents = preferences.getString(KEY_PROMPT_EVENTS, "[]") ?: "[]"
        val eventArray = runCatching { JSONArray(rawEvents) }.getOrNull()
        if (eventArray != null) {
            val parsedEvents = buildList {
                for (index in 0 until eventArray.length()) {
                    runCatching { BreakPromptEvent.fromJson(eventArray.getJSONObject(index)) }
                        .getOrNull()?.let(::add)
                }
            }
            if (parsedEvents.size == eventArray.length()) {
                val retainedEvents = parsedEvents.filter {
                    it.sessionId in validIds &&
                        !RetentionPolicy.isExpired(it.expiresOn, it.candidateAtMs, today)
                }
                if (retainedEvents.size != parsedEvents.size) {
                    preferences.edit().putString(KEY_PROMPT_EVENTS, promptEventsJson(retainedEvents)).apply()
                    changed = true
                }
            }
        }

        val pendingId = preferences.getString(KEY_PENDING_REVIEW_ID, null)
        if (pendingId != null && retainedSessions.none { it.sessionId == pendingId }) {
            preferences.edit().remove(KEY_PENDING_REVIEW_ID).apply()
            changed = true
        }
        return changed
    }

    companion object {
        private const val HEART_RATE_BASELINE_MS = 60_000L
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
        private const val LEGACY_SEED_LABEL = "watch_seed_v1"
        private const val LEGACY_PARTICIPANT_KEY = "participant_code"
    }
}
