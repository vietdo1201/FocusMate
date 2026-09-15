// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authoritative local session store. Sensor frames/landmarks are intentionally
 * outside this database; only bounded summaries and explicit user input belong here.
 */
internal class FocusMateSessionDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE active_snapshot(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                session_id TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                updated_wall_ms INTEGER NOT NULL,
                payload TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE completed_sessions(
                session_id TEXT PRIMARY KEY,
                start_wall_ms INTEGER NOT NULL,
                end_wall_ms INTEGER NOT NULL,
                expires_on TEXT NOT NULL,
                payload TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE session_events(
                event_id TEXT PRIMARY KEY,
                command_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                block_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                wall_ms INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL,
                boot_id TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                payload TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE prompt_events(
                event_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                candidate_wall_ms INTEGER NOT NULL,
                expires_on TEXT NOT NULL,
                payload TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE reminder_episodes(
                reminder_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                block_id TEXT NOT NULL,
                state TEXT NOT NULL,
                policy_version TEXT NOT NULL,
                reason_codes TEXT NOT NULL,
                evidence_refs TEXT NOT NULL,
                initial_scheduled_ms INTEGER,
                retry_scheduled_ms INTEGER,
                delivery_attempts INTEGER NOT NULL DEFAULT 0,
                response TEXT,
                updated_wall_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE check_ins(
                check_in_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                block_id TEXT NOT NULL,
                source TEXT NOT NULL,
                wall_ms INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL,
                boot_id TEXT NOT NULL,
                break_id TEXT,
                focus INTEGER CHECK(focus IS NULL OR focus BETWEEN 1 AND 5),
                fatigue INTEGER CHECK(fatigue IS NULL OR fatigue BETWEEN 1 AND 10)
            )"""
        )
        db.execSQL(
            """CREATE TABLE break_episodes(
                break_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                block_id TEXT NOT NULL,
                started_wall_ms INTEGER NOT NULL,
                started_elapsed_ms INTEGER NOT NULL,
                started_boot_id TEXT NOT NULL,
                ended_wall_ms INTEGER,
                ended_elapsed_ms INTEGER,
                ended_boot_id TEXT,
                planned_duration_ms INTEGER NOT NULL,
                actual_duration_ms INTEGER,
                activity_type TEXT NOT NULL,
                self_reported_done INTEGER
            )"""
        )
        db.execSQL(
            """CREATE TABLE prompt_feedback(
                reminder_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                timing TEXT NOT NULL,
                annoyance INTEGER,
                wall_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE policy_comparisons(
                comparison_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                block_id TEXT NOT NULL,
                input_ref TEXT NOT NULL,
                baseline_version TEXT NOT NULL,
                baseline_result TEXT NOT NULL,
                shadow_version TEXT NOT NULL,
                shadow_result TEXT NOT NULL,
                wall_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE recovery_records(
                recovery_id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                captured_wall_ms INTEGER NOT NULL,
                raw_payload TEXT NOT NULL
            )"""
        )
        createVersionTwoTables(db)
        db.execSQL("CREATE INDEX idx_check_ins_block_time ON check_ins(session_id, block_id, wall_ms DESC)")
        db.execSQL("CREATE INDEX idx_policy_comparison_session ON policy_comparisons(session_id, wall_ms)")
        db.execSQL("CREATE INDEX idx_prompt_session ON prompt_events(session_id, candidate_wall_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version == 1 && newVersion >= 2) {
            db.execSQL("ALTER TABLE check_ins ADD COLUMN break_id TEXT")
            createVersionTwoTables(db)
            version = 2
        }
        check(version == newVersion) { "No migration from $oldVersion to $newVersion" }
    }

    private fun createVersionTwoTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS delivery_slots(
                reminder_id TEXT NOT NULL,
                slot_index INTEGER NOT NULL CHECK(slot_index BETWEEN 0 AND 1),
                scheduled_wall_ms INTEGER NOT NULL,
                scheduled_elapsed_ms INTEGER,
                boot_id TEXT,
                receiver_wall_ms INTEGER,
                receiver_elapsed_ms INTEGER,
                post_attempt_wall_ms INTEGER,
                result TEXT NOT NULL,
                PRIMARY KEY(reminder_id, slot_index)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS unknown_intervals(
                unknown_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                state_before TEXT NOT NULL,
                checkpoint_wall_ms INTEGER NOT NULL,
                detected_wall_ms INTEGER NOT NULL,
                reason TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_unknown_session ON unknown_intervals(session_id)")
    }

    fun metadata(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM metadata WHERE key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putMetadata(key: String, value: String, db: SQLiteDatabase = writableDatabase) {
        db.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun activePayload(): String? = readableDatabase.rawQuery(
        "SELECT payload FROM active_snapshot WHERE singleton=1", null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun hasCommand(commandId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM session_events WHERE command_id=? LIMIT 1", arrayOf(commandId)
    ).use { it.moveToFirst() }

    fun putActive(payload: String, sessionId: String, state: SessionState, revision: Long = 0L) {
        writableDatabase.insertWithOnConflict("active_snapshot", null, ContentValues().apply {
            put("singleton", 1)
            put("session_id", sessionId)
            put("revision", revision)
            put("state", state.name)
            put("updated_wall_ms", System.currentTimeMillis())
            put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearActive() {
        writableDatabase.delete("active_snapshot", "singleton=1", null)
    }

    fun sessionPayloads(): List<String> = readableDatabase.rawQuery(
        "SELECT payload FROM completed_sessions ORDER BY start_wall_ms DESC", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun replaceSessions(sessions: List<StudySession>) = transaction { db ->
        db.delete("completed_sessions", null, null)
        sessions.forEach { upsertSession(db, it) }
    }

    fun upsertSession(session: StudySession) = transaction { upsertSession(it, session) }

    private fun upsertSession(db: SQLiteDatabase, session: StudySession) {
        db.insertWithOnConflict("completed_sessions", null, ContentValues().apply {
            put("session_id", session.sessionId)
            put("start_wall_ms", session.startTimeMs)
            put("end_wall_ms", session.endTimeMs)
            put("expires_on", session.expiresOn)
            put("payload", session.toJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun promptEventPayloads(): List<String> = readableDatabase.rawQuery(
        "SELECT payload FROM prompt_events ORDER BY candidate_wall_ms DESC", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun replacePromptEvents(events: List<BreakPromptEvent>) = transaction { db ->
        db.delete("prompt_events", null, null)
        events.forEach { upsertPromptEvent(db, it) }
    }

    fun replaceActiveAndPromptEvents(
        active: ActiveStudySession,
        events: List<BreakPromptEvent>,
        cooldownUntilMs: Long? = null,
    ) = transaction { db ->
        putActiveInTransaction(db, active)
        db.delete("prompt_events", null, null)
        events.forEach { upsertPromptEvent(db, it) }
        cooldownUntilMs?.let { putMetadata(META_COOLDOWN_UNTIL, it.toString(), db) }
    }

    fun importLegacyState(
        sessions: List<StudySession>,
        events: List<BreakPromptEvent>,
        active: ActiveStudySession?,
        cooldownUntilMs: Long?,
        pendingReviewId: String?,
        recoveryPayloads: List<Pair<String, String>>,
    ) = transaction { db ->
        db.delete("completed_sessions", null, null)
        sessions.forEach { upsertSession(db, it) }
        db.delete("prompt_events", null, null)
        events.forEach { upsertPromptEvent(db, it) }
        if (active == null) db.delete("active_snapshot", "singleton=1", null)
        else putActiveInTransaction(db, active)
        cooldownUntilMs?.let { putMetadata(META_COOLDOWN_UNTIL, it.toString(), db) }
        pendingReviewId?.let { putMetadata(META_PENDING_REVIEW, it, db) }
        recoveryPayloads.forEach { (source, raw) -> preserveRecoveryPayload(source, raw, db) }
        putMetadata(META_PREFS_MIGRATION, "complete", db)
    }

    fun upsertReminderEpisode(active: ActiveStudySession, reminder: PendingReminder) {
        writableDatabase.insertWithOnConflict("reminder_episodes", null, ContentValues().apply {
            put("reminder_id", reminder.eventId)
            put("session_id", active.sessionId)
            put("block_id", active.focusBlockId)
            put("state", reminder.deliveryState.name)
            put("policy_version", reminder.policyVersion)
            put("reason_codes", JSONArray(reminder.reasonCodes.sorted()).toString())
            put("evidence_refs", JSONArray(reminder.evidenceReferences.sorted()).toString())
            put("initial_scheduled_ms", reminder.createdAtMs)
            put("retry_scheduled_ms", reminder.createdAtMs + BreakReminderPolicy.RETRY_OFFSETS_MS.last())
            put("delivery_attempts", reminder.attempt)
            put("updated_wall_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateReminderResponse(reminderId: String, state: ReminderDeliveryState, response: String?) {
        writableDatabase.update("reminder_episodes", ContentValues().apply {
            put("state", state.name)
            response?.let { put("response", it) } ?: putNull("response")
            put("updated_wall_ms", System.currentTimeMillis())
        }, "reminder_id=?", arrayOf(reminderId))
    }

    fun reminderReport(sessionId: String): ReminderReportAggregate = readableDatabase.query(
        "reminder_episodes",
        arrayOf("reason_codes", "delivery_attempts", "response"),
        "session_id=?",
        arrayOf(sessionId),
        null,
        null,
        "updated_wall_ms ASC",
    ).use { cursor ->
        val reasons = linkedSetOf<String>()
        var attempts = 0
        var responses = 0
        while (cursor.moveToNext()) {
            runCatching { JSONArray(cursor.getString(0)) }.getOrNull()?.let { values ->
                for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(reasons::add)
            }
            attempts += cursor.getInt(1).coerceAtLeast(0)
            if (!cursor.isNull(2)) responses++
        }
        ReminderReportAggregate(reasons, attempts, responses)
    }

    fun reminderHistory(sessionId: String): List<ReminderEpisodeHistory> = readableDatabase.query(
        "reminder_episodes",
        arrayOf("reminder_id", "block_id", "state", "policy_version", "reason_codes", "evidence_refs", "response"),
        "session_id=?",
        arrayOf(sessionId),
        null,
        null,
        "updated_wall_ms ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val reminderId = cursor.getString(0)
                val feedback = readableDatabase.query(
                    "prompt_feedback",
                    arrayOf("timing", "annoyance"),
                    "reminder_id=? AND session_id=?",
                    arrayOf(reminderId, sessionId),
                    null,
                    null,
                    null,
                    "1",
                ).use { row ->
                    if (!row.moveToFirst()) null else Pair(
                        runCatching { PromptTimingFeedback.valueOf(row.getString(0)) }.getOrNull(),
                        if (row.isNull(1)) null else row.getInt(1),
                    )
                }
                val deliveries = readableDatabase.query(
                    "delivery_slots",
                    arrayOf("slot_index", "scheduled_wall_ms", "receiver_wall_ms", "post_attempt_wall_ms", "result"),
                    "reminder_id=?",
                    arrayOf(reminderId),
                    null,
                    null,
                    "slot_index ASC",
                ).use { slots ->
                    buildList {
                        while (slots.moveToNext()) {
                            add(
                                ReminderDeliveryHistory(
                                    slotIndex = slots.getInt(0),
                                    scheduledWallMs = slots.getLong(1),
                                    receiverWallMs = if (slots.isNull(2)) null else slots.getLong(2),
                                    postAttemptWallMs = if (slots.isNull(3)) null else slots.getLong(3),
                                    result = slots.getString(4),
                                )
                            )
                        }
                    }
                }
                add(
                    ReminderEpisodeHistory(
                        reminderId = reminderId,
                        blockId = cursor.getString(1),
                        state = runCatching { ReminderDeliveryState.valueOf(cursor.getString(2)) }
                            .getOrDefault(ReminderDeliveryState.CLOSED),
                        policyVersion = cursor.getString(3),
                        reasonCodes = cursor.jsonStringSet(4),
                        evidenceReferences = cursor.jsonStringSet(5),
                        response = if (cursor.isNull(6)) null else cursor.getString(6),
                        deliveries = deliveries,
                        timingFeedback = feedback?.first,
                        annoyance = feedback?.second,
                    )
                )
            }
        }
    }

    private fun upsertPromptEvent(db: SQLiteDatabase, event: BreakPromptEvent) {
        db.insertWithOnConflict("prompt_events", null, ContentValues().apply {
            put("event_id", event.eventId)
            put("session_id", event.sessionId)
            put("candidate_wall_ms", event.candidateAtMs)
            put("expires_on", event.expiresOn)
            put("payload", event.toJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun applyCommand(
        commandId: String,
        eventId: String,
        sessionId: String,
        blockId: String,
        eventType: String,
        at: TimePoint,
        payload: JSONObject,
        active: ActiveStudySession,
        closeReminderId: String? = null,
    ): Boolean {
        var applied = false
        transaction { db ->
            val row = ContentValues().apply {
                put("event_id", eventId)
                put("command_id", commandId)
                put("session_id", sessionId)
                put("block_id", blockId)
                put("event_type", eventType)
                put("wall_ms", at.wallMs)
                put("elapsed_ms", at.elapsedMs)
                put("boot_id", at.bootId)
                put("payload_version", 1)
                put("payload", payload.toString())
            }
            applied = db.insertWithOnConflict("session_events", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (applied) {
                putActiveInTransaction(db, active)
                closeReminderId?.let { reminderId ->
                    db.update("reminder_episodes", ContentValues().apply {
                        put("state", ReminderDeliveryState.CLOSED.name)
                        put("updated_wall_ms", at.wallMs)
                    }, "reminder_id=?", arrayOf(reminderId))
                }
            }
        }
        return applied
    }

    fun deferReminder(
        commandId: String,
        eventId: String,
        reminderId: String,
        active: ActiveStudySession,
        promptEvents: List<BreakPromptEvent>,
        cooldownUntilWallMs: Long,
        cooldownUntilElapsedMs: Long,
        cooldownBootId: String,
        at: TimePoint,
    ): Boolean {
        var applied = false
        transaction { db ->
            applied = db.insertWithOnConflict("session_events", null, ContentValues().apply {
                put("event_id", eventId); put("command_id", commandId)
                put("session_id", active.sessionId); put("block_id", active.focusBlockId)
                put("event_type", "DEFER_REMINDER")
                put("wall_ms", at.wallMs); put("elapsed_ms", at.elapsedMs); put("boot_id", at.bootId)
                put("payload_version", 2)
                put("payload", JSONObject().put("reminder_id", reminderId).put("cooldown_until_wall_ms", cooldownUntilWallMs).toString())
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            putActiveInTransaction(db, active)
            db.delete("prompt_events", null, null)
            promptEvents.forEach { upsertPromptEvent(db, it) }
            putMetadata(META_COOLDOWN_UNTIL, cooldownUntilWallMs.toString(), db)
            putMetadata(META_COOLDOWN_UNTIL_ELAPSED, cooldownUntilElapsedMs.toString(), db)
            putMetadata(META_COOLDOWN_BOOT_ID, cooldownBootId, db)
            db.update("reminder_episodes", ContentValues().apply {
                put("state", ReminderDeliveryState.DEFERRED.name)
                put("response", BreakPromptEvent.RESPONSE_DECLINED)
                put("updated_wall_ms", at.wallMs)
            }, "reminder_id=?", arrayOf(reminderId))
        }
        return applied
    }

    fun cancelSession(
        commandId: String,
        eventId: String,
        active: ActiveStudySession,
        at: TimePoint,
    ): Boolean {
        var applied = false
        transaction { db ->
            applied = db.insertWithOnConflict("session_events", null, ContentValues().apply {
                put("event_id", eventId); put("command_id", commandId)
                put("session_id", active.sessionId); put("block_id", active.focusBlockId)
                put("event_type", "CANCEL_SESSION")
                put("wall_ms", at.wallMs); put("elapsed_ms", at.elapsedMs); put("boot_id", at.bootId)
                put("payload_version", 1); put("payload", JSONObject().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            active.pendingReminder?.let { reminder ->
                db.update("reminder_episodes", ContentValues().apply {
                    put("state", ReminderDeliveryState.CLOSED.name)
                    put("updated_wall_ms", at.wallMs)
                }, "reminder_id=?", arrayOf(reminder.eventId))
            }
            db.delete("prompt_events", "session_id=?", arrayOf(active.sessionId))
            db.delete("active_snapshot", "singleton=1 AND session_id=?", arrayOf(active.sessionId))
        }
        return applied
    }

    fun recordCheckIn(record: CheckInRecord): Boolean = writableDatabase.insertWithOnConflict(
        "check_ins", null, ContentValues().apply {
            put("check_in_id", record.checkInId)
            put("session_id", record.sessionId)
            put("block_id", record.blockId)
            put("source", record.source.name)
            put("wall_ms", record.time.wallMs)
            put("elapsed_ms", record.time.elapsedMs)
            put("boot_id", record.time.bootId)
            record.breakId?.let { put("break_id", it) } ?: putNull("break_id")
            record.focus?.let { put("focus", it) } ?: putNull("focus")
            record.fatigue?.let { put("fatigue", it) } ?: putNull("fatigue")
        }, SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    fun checkIns(sessionId: String, blockId: String? = null): List<CheckInRecord> {
        val where = if (blockId == null) "session_id=?" else "session_id=? AND block_id=?"
        val args = if (blockId == null) arrayOf(sessionId) else arrayOf(sessionId, blockId)
        return readableDatabase.query(
            "check_ins", null, where, args, null, null, "wall_ms ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    CheckInRecord(
                        checkInId = cursor.text("check_in_id"),
                        sessionId = cursor.text("session_id"),
                        blockId = cursor.text("block_id"),
                        source = CheckInSource.valueOf(cursor.text("source")),
                        time = TimePoint(cursor.long("wall_ms"), cursor.long("elapsed_ms"), cursor.text("boot_id")),
                        breakId = cursor.optionalText("break_id"),
                        focus = cursor.nullableInt("focus"),
                        fatigue = cursor.nullableInt("fatigue"),
                    )
                )
            }
        }
    }

    fun applyBreakTransition(
        commandId: String,
        eventId: String,
        active: ActiveStudySession,
        episode: BreakEpisodeRecord,
        at: TimePoint,
        promptEvents: List<BreakPromptEvent>? = null,
    ): Boolean {
        var applied = false
        transaction { db ->
            val event = ContentValues().apply {
                put("event_id", eventId)
                put("command_id", commandId)
                put("session_id", active.sessionId)
                put("block_id", episode.blockId)
                put("event_type", "START_BREAK")
                put("wall_ms", at.wallMs)
                put("elapsed_ms", at.elapsedMs)
                put("boot_id", at.bootId)
                put("payload_version", 1)
                put("payload", JSONObject().put("break_id", episode.breakId).toString())
            }
            applied = db.insertWithOnConflict("session_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            putActiveInTransaction(db, active)
            upsertBreakEpisode(db, episode)
            if (promptEvents != null) {
                db.delete("prompt_events", null, null)
                promptEvents.forEach { upsertPromptEvent(db, it) }
            }
        }
        return applied
    }

    /** One crash-safe transaction for an accepted reminder and the resulting break. */
    fun acceptReminderAndStartBreak(
        commandId: String,
        eventId: String,
        reminderId: String,
        active: ActiveStudySession,
        episode: BreakEpisodeRecord,
        at: TimePoint,
        promptEvents: List<BreakPromptEvent>,
    ): Boolean {
        var applied = false
        transaction { db ->
            val event = ContentValues().apply {
                put("event_id", eventId)
                put("command_id", commandId)
                put("session_id", active.sessionId)
                put("block_id", episode.blockId)
                put("event_type", "ACCEPT_REMINDER_AND_START_BREAK")
                put("wall_ms", at.wallMs)
                put("elapsed_ms", at.elapsedMs)
                put("boot_id", at.bootId)
                put("payload_version", 2)
                put("payload", JSONObject().put("break_id", episode.breakId).put("reminder_id", reminderId).toString())
            }
            applied = db.insertWithOnConflict("session_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            putActiveInTransaction(db, active)
            upsertBreakEpisode(db, episode)
            db.delete("prompt_events", null, null)
            promptEvents.forEach { upsertPromptEvent(db, it) }
            db.update("reminder_episodes", ContentValues().apply {
                put("state", ReminderDeliveryState.ACCEPTED.name)
                put("response", BreakPromptEvent.RESPONSE_ACCEPTED)
                put("updated_wall_ms", at.wallMs)
            }, "reminder_id=?", arrayOf(reminderId))
        }
        return applied
    }

    fun resumeBreak(
        commandId: String,
        eventId: String,
        active: ActiveStudySession,
        breakId: String,
        at: TimePoint,
        actualDurationMs: Long,
        closeReminderId: String? = null,
    ): Boolean {
        var applied = false
        transaction { db ->
            val event = ContentValues().apply {
                put("event_id", eventId); put("command_id", commandId)
                put("session_id", active.sessionId); put("block_id", active.focusBlockId)
                put("event_type", "RESUME_AFTER_BREAK")
                put("wall_ms", at.wallMs); put("elapsed_ms", at.elapsedMs); put("boot_id", at.bootId)
                put("payload_version", 2); put("payload", JSONObject().put("break_id", breakId).toString())
            }
            applied = db.insertWithOnConflict("session_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            putActiveInTransaction(db, active)
            db.update("break_episodes", ContentValues().apply {
                put("ended_wall_ms", at.wallMs); put("ended_elapsed_ms", at.elapsedMs); put("ended_boot_id", at.bootId)
                put("actual_duration_ms", actualDurationMs.coerceAtLeast(0L))
            }, "break_id=? AND ended_wall_ms IS NULL", arrayOf(breakId))
            closeReminderId?.let { reminderId ->
                db.update("reminder_episodes", ContentValues().apply {
                    put("state", ReminderDeliveryState.CLOSED.name)
                    put("updated_wall_ms", at.wallMs)
                }, "reminder_id=?", arrayOf(reminderId))
            }
        }
        return applied
    }

    /** Reserves a delivery slot before interacting with Android. Duplicate callbacks are harmless. */
    fun reserveDeliverySlot(
        reminderId: String,
        slotIndex: Int,
        scheduledAt: TimePoint,
        receiverAt: TimePoint,
    ): Boolean {
        require(slotIndex in 0..1)
        return writableDatabase.insertWithOnConflict(
            "delivery_slots",
            null,
            ContentValues().apply {
                put("reminder_id", reminderId)
                put("slot_index", slotIndex)
                put("scheduled_wall_ms", scheduledAt.wallMs)
                put("scheduled_elapsed_ms", scheduledAt.elapsedMs)
                put("boot_id", scheduledAt.bootId)
                put("receiver_wall_ms", receiverAt.wallMs)
                put("receiver_elapsed_ms", receiverAt.elapsedMs)
                put("result", "UNKNOWN")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    fun completeDeliverySlot(reminderId: String, slotIndex: Int, at: TimePoint, result: String) {
        writableDatabase.update("delivery_slots", ContentValues().apply {
            put("post_attempt_wall_ms", at.wallMs)
            put("result", result)
        }, "reminder_id=? AND slot_index=?", arrayOf(reminderId, slotIndex.toString()))
    }

    fun recordUnknownInterval(sessionId: String, prior: SessionTimelineSnapshot, detectedAt: TimePoint, reason: String) {
        writableDatabase.insertWithOnConflict("unknown_intervals", null, ContentValues().apply {
            put("unknown_id", "$sessionId:${prior.revision}:${detectedAt.bootId}")
            put("session_id", sessionId)
            put("state_before", prior.state.name)
            put("checkpoint_wall_ms", prior.checkpointAt.wallMs)
            put("detected_wall_ms", detectedAt.wallMs)
            put("reason", reason)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun requireRecovery(
        commandId: String,
        prior: SessionTimelineSnapshot,
        active: ActiveStudySession,
        detectedAt: TimePoint,
        reason: String,
        closeReminderId: String?,
    ): Boolean {
        var applied = false
        transaction { db ->
            applied = db.insertWithOnConflict("session_events", null, ContentValues().apply {
                put("event_id", "$commandId:event"); put("command_id", commandId)
                put("session_id", active.sessionId); put("block_id", prior.blockId)
                put("event_type", "RECOVERY_REQUIRED")
                put("wall_ms", detectedAt.wallMs); put("elapsed_ms", detectedAt.elapsedMs); put("boot_id", detectedAt.bootId)
                put("payload_version", 2); put("payload", JSONObject().put("reason", reason).toString())
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            db.insertWithOnConflict("unknown_intervals", null, ContentValues().apply {
                put("unknown_id", "${active.sessionId}:${prior.revision}:${detectedAt.bootId}")
                put("session_id", active.sessionId); put("state_before", prior.state.name)
                put("checkpoint_wall_ms", prior.checkpointAt.wallMs); put("detected_wall_ms", detectedAt.wallMs)
                put("reason", reason)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            putActiveInTransaction(db, active)
            closeReminderId?.let { reminderId ->
                db.update("reminder_episodes", ContentValues().apply {
                    put("state", ReminderDeliveryState.CLOSED.name)
                    put("updated_wall_ms", detectedAt.wallMs)
                }, "reminder_id=?", arrayOf(reminderId))
            }
        }
        return applied
    }

    fun unknownIntervalCount(sessionId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM unknown_intervals WHERE session_id=?", arrayOf(sessionId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun closeBreakEpisode(breakId: String, endedAt: TimePoint, actualDurationMs: Long) {
        writableDatabase.update("break_episodes", ContentValues().apply {
            put("ended_wall_ms", endedAt.wallMs)
            put("ended_elapsed_ms", endedAt.elapsedMs)
            put("ended_boot_id", endedAt.bootId)
            put("actual_duration_ms", actualDurationMs.coerceAtLeast(0L))
        }, "break_id=?", arrayOf(breakId))
    }

    fun finishSession(
        commandId: String,
        eventId: String,
        active: ActiveStudySession,
        completed: StudySession,
        at: TimePoint,
        openBreak: BreakEpisodeRecord?,
        actualBreakDurationMs: Long?,
        pendingReview: Boolean,
    ): Boolean {
        var applied = false
        transaction { db ->
            val event = ContentValues().apply {
                put("event_id", eventId)
                put("command_id", commandId)
                put("session_id", active.sessionId)
                put("block_id", active.focusBlockId)
                put("event_type", "FINISH_SESSION")
                put("wall_ms", at.wallMs)
                put("elapsed_ms", at.elapsedMs)
                put("boot_id", at.bootId)
                put("payload_version", 2)
                put("payload", JSONObject().put("ended_during_break", openBreak != null).toString())
            }
            applied = db.insertWithOnConflict("session_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (!applied) return@transaction
            if (openBreak != null && actualBreakDurationMs != null) {
                db.update("break_episodes", ContentValues().apply {
                    put("ended_wall_ms", at.wallMs)
                    put("ended_elapsed_ms", at.elapsedMs)
                    put("ended_boot_id", at.bootId)
                    put("actual_duration_ms", actualBreakDurationMs.coerceAtLeast(0L))
                }, "break_id=?", arrayOf(openBreak.breakId))
            }
            active.pendingReminder?.let { reminder ->
                db.update("reminder_episodes", ContentValues().apply {
                    put("state", ReminderDeliveryState.CLOSED.name)
                    put("updated_wall_ms", at.wallMs)
                }, "reminder_id=?", arrayOf(reminder.eventId))
            }
            upsertSession(db, completed)
            db.delete("active_snapshot", "singleton=1 AND session_id=?", arrayOf(active.sessionId))
            if (pendingReview) putMetadata(META_PENDING_REVIEW, completed.sessionId, db)
        }
        return applied
    }

    fun breakEpisodes(sessionId: String): List<BreakEpisodeRecord> = readableDatabase.query(
        "break_episodes", null, "session_id=?", arrayOf(sessionId), null, null, "started_wall_ms ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val endedWall = cursor.nullableLong("ended_wall_ms")
                add(
                    BreakEpisodeRecord(
                        breakId = cursor.text("break_id"),
                        sessionId = cursor.text("session_id"),
                        blockId = cursor.text("block_id"),
                        startedAt = TimePoint(
                            cursor.long("started_wall_ms"), cursor.long("started_elapsed_ms"), cursor.text("started_boot_id")
                        ),
                        plannedDurationMs = cursor.long("planned_duration_ms"),
                        activity = BreakActivityType.valueOf(cursor.text("activity_type")),
                        endedAt = endedWall?.let {
                            TimePoint(it, cursor.long("ended_elapsed_ms"), cursor.text("ended_boot_id"))
                        },
                        actualDurationMs = cursor.nullableLong("actual_duration_ms"),
                        selfReportedDone = cursor.nullableInt("self_reported_done")?.let { it != 0 },
                    )
                )
            }
        }
    }

    /** Explicit self-report pairs only; no sensor value is substituted for missing answers. */
    fun breakFatigueChanges(sessionId: String): List<Int> {
        val checkIns = checkIns(sessionId)
        return breakEpisodes(sessionId).mapNotNull { episode ->
            val endedAt = episode.endedAt ?: return@mapNotNull null
            val before = checkIns
                .asSequence()
                .filter { it.breakId == episode.breakId && it.source == CheckInSource.BEFORE_BREAK && it.fatigue != null }
                .filter { it.time.bootId == episode.startedAt.bootId }
                .filter { episode.startedAt.elapsedMs - it.time.elapsedMs in 0L..FATIGUE_PAIR_WINDOW_MS }
                .maxByOrNull { it.time.elapsedMs }
            val after = checkIns
                .asSequence()
                .filter { it.breakId == episode.breakId && it.source == CheckInSource.AFTER_BREAK && it.fatigue != null }
                .filter { it.time.bootId == endedAt.bootId }
                .filter { it.time.elapsedMs - endedAt.elapsedMs in 0L..FATIGUE_PAIR_WINDOW_MS }
                .minByOrNull { it.time.elapsedMs }
            if (before == null || after == null) null else requireNotNull(after.fatigue) - requireNotNull(before.fatigue)
        }
    }

    private fun upsertBreakEpisode(db: SQLiteDatabase, episode: BreakEpisodeRecord) {
        db.insertWithOnConflict("break_episodes", null, ContentValues().apply {
            put("break_id", episode.breakId)
            put("session_id", episode.sessionId)
            put("block_id", episode.blockId)
            put("started_wall_ms", episode.startedAt.wallMs)
            put("started_elapsed_ms", episode.startedAt.elapsedMs)
            put("started_boot_id", episode.startedAt.bootId)
            episode.endedAt?.let {
                put("ended_wall_ms", it.wallMs)
                put("ended_elapsed_ms", it.elapsedMs)
                put("ended_boot_id", it.bootId)
            } ?: run {
                putNull("ended_wall_ms")
                putNull("ended_elapsed_ms")
                putNull("ended_boot_id")
            }
            put("planned_duration_ms", episode.plannedDurationMs)
            episode.actualDurationMs?.let { put("actual_duration_ms", it) } ?: putNull("actual_duration_ms")
            put("activity_type", episode.activity.name)
            episode.selfReportedDone?.let { put("self_reported_done", if (it) 1 else 0) } ?: putNull("self_reported_done")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordPolicyComparison(
        comparisonId: String,
        sessionId: String,
        blockId: String,
        inputRef: String,
        baseline: PolicyDecision,
        shadow: PolicyDecision,
        wallMs: Long,
    ): Boolean = writableDatabase.insertWithOnConflict(
        "policy_comparisons", null, ContentValues().apply {
            put("comparison_id", comparisonId)
            put("session_id", sessionId)
            put("block_id", blockId)
            put("input_ref", inputRef)
            put("baseline_version", baseline.policyVersion)
            put("baseline_result", baseline.toJson().toString())
            put("shadow_version", shadow.policyVersion)
            put("shadow_result", shadow.toJson().toString())
            put("wall_ms", wallMs)
        }, SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    fun recordPromptFeedback(
        reminderId: String,
        sessionId: String,
        timing: PromptTimingFeedback,
        annoyance: Int?,
        wallMs: Long,
    ): Boolean {
        require(annoyance == null || annoyance in 1..5)
        return writableDatabase.insertWithOnConflict(
            "prompt_feedback", null, ContentValues().apply {
                put("reminder_id", reminderId)
                put("session_id", sessionId)
                put("timing", timing.name)
                annoyance?.let { put("annoyance", it) } ?: putNull("annoyance")
                put("wall_ms", wallMs)
            }, SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun policyComparisonCount(sessionId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM policy_comparisons WHERE session_id=?", arrayOf(sessionId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun preserveRecoveryPayload(source: String, raw: String, db: SQLiteDatabase = writableDatabase) {
        val exists = db.rawQuery(
            "SELECT 1 FROM recovery_records WHERE source=? AND raw_payload=? LIMIT 1",
            arrayOf(source, raw),
        ).use { it.moveToFirst() }
        if (exists) return
        db.insert("recovery_records", null, ContentValues().apply {
            put("source", source)
            put("captured_wall_ms", System.currentTimeMillis())
            put("raw_payload", raw)
        })
    }

    fun recoveryPayloadCount(source: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM recovery_records WHERE source=?", arrayOf(source)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun deleteSessionChildren(validSessionIds: Set<String>) = transaction { db ->
        val tables = listOf("prompt_events", "session_events", "reminder_episodes", "check_ins", "break_episodes", "prompt_feedback", "policy_comparisons", "unknown_intervals")
        if (validSessionIds.isEmpty()) {
            tables.forEach { db.delete(it, null, null) }
        } else {
            val placeholders = validSessionIds.joinToString(",") { "?" }
            val args = validSessionIds.toTypedArray()
            tables.forEach { db.delete(it, "session_id NOT IN ($placeholders)", args) }
        }
        db.execSQL("DELETE FROM delivery_slots WHERE reminder_id NOT IN (SELECT reminder_id FROM reminder_episodes)")
    }

    fun pruneRecoveryPayloads(capturedBeforeWallMs: Long) {
        writableDatabase.delete("recovery_records", "captured_wall_ms<?", arrayOf(capturedBeforeWallMs.toString()))
    }

    fun clearAll() = transaction { db ->
        listOf(
            "active_snapshot", "completed_sessions", "session_events", "prompt_events",
            "reminder_episodes", "check_ins", "break_episodes", "prompt_feedback",
            "policy_comparisons", "delivery_slots", "unknown_intervals", "recovery_records", "metadata",
        ).forEach { db.delete(it, null, null) }
        putMetadata(META_PREFS_MIGRATION, "complete", db)
    }

    fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun putActiveInTransaction(db: SQLiteDatabase, active: ActiveStudySession) {
        db.insertWithOnConflict("active_snapshot", null, ContentValues().apply {
            put("singleton", 1)
            put("session_id", active.sessionId)
            put("revision", active.timeline?.revision ?: 0L)
            put("state", active.inferredState().name)
            put("updated_wall_ms", System.currentTimeMillis())
            put("payload", active.toJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val FATIGUE_PAIR_WINDOW_MS = 5 * 60_000L
        const val DATABASE_NAME = "focusmate_session_v1.db"
        const val META_PREFS_MIGRATION = "prefs_migration_v1"
        const val META_COOLDOWN_UNTIL = "cooldown_until_ms"
        const val META_COOLDOWN_UNTIL_ELAPSED = "cooldown_until_elapsed_ms"
        const val META_COOLDOWN_BOOT_ID = "cooldown_boot_id"
        const val META_PENDING_REVIEW = "pending_review_session_id"
        internal const val DATABASE_VERSION = 2
    }
}

/** One helper per application process; repositories do not leak per-call helpers. */
internal object FocusMateSessionDatabaseProvider {
    @Volatile private var instance: FocusMateSessionDatabase? = null

    fun get(context: Context): FocusMateSessionDatabase = instance ?: synchronized(this) {
        instance ?: FocusMateSessionDatabase(context.applicationContext).also { instance = it }
    }

    internal fun closeForTests() = synchronized(this) {
        instance?.close()
        instance = null
    }
}

internal fun ActiveStudySession.inferredState(): SessionState = when {
    timeline != null -> timeline.state
    pausedAtMs != null -> SessionState.PAUSED
    breakStartedAtMs == null -> SessionState.STUDYING
    breakAwaitingDecisionAtMs != null -> SessionState.AWAITING_RESUME
    else -> SessionState.BREAKING
}

private fun PolicyDecision.toJson() = JSONObject().apply {
    put("eligible", eligible)
    put("should_prompt", shouldPrompt)
    put("reason_codes", JSONArray(reasonCodes.sorted()))
    put("evidence_refs", JSONArray(evidenceReferences.sorted()))
    put("policy_version", policyVersion)
    put("next_evaluation_at_ms", nextEvaluationAtWallMs ?: JSONObject.NULL)
    put("fallbacks", JSONArray(fallbacks.sorted()))
}

private fun android.database.Cursor.text(column: String) = getString(getColumnIndexOrThrow(column))
private fun android.database.Cursor.optionalText(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
private fun android.database.Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
private fun android.database.Cursor.nullableInt(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
private fun android.database.Cursor.nullableLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun android.database.Cursor.jsonStringSet(columnIndex: Int): Set<String> =
    runCatching { JSONArray(getString(columnIndex)) }.getOrNull()?.let { values ->
        buildSet {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.orEmpty()

internal data class ReminderReportAggregate(
    val reasonCodes: Set<String>,
    val deliveryAttempts: Int,
    val responses: Int,
)
