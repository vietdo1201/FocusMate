// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class StudySessionRepositoryRobolectricTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun corruptRowKeepsValidHistoryAndPreservesRawRecoveryPayload() {
        val valid = session("valid", 1_000L).toJson()
        val corrupt = JSONObject().put("session_id", "corrupt")
        val raw = JSONArray().put(valid).put(corrupt).toString()
        val bulk = context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE)
        bulk.edit().putString("sessions", raw).commit()

        val repository = StudySessionRepository(context)

        assertEquals(listOf("valid"), repository.sessions().map { it.sessionId })
        assertFalse(bulk.contains("sessions"))
        assertEquals(1, FocusMateSessionDatabase(context).recoveryPayloadCount("legacy_sessions"))
        assertEquals("complete", FocusMateSessionDatabase(context).metadata(FocusMateSessionDatabase.META_PREFS_MIGRATION))
    }

    @Test
    fun activeStateMigratesToAuthoritativeDatabaseAndPreferencesAreRetired() {
        val bulk = context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE)
        bulk.edit()
            .putString("active_session", JSONObject().apply {
                put("session_id", "active")
                put("start_time_ms", 100L)
                put("task_type", "Đọc tài liệu")
            }.toString())
            .putLong("cooldown_until_ms", 9_000L)
            .commit()

        val repository = StudySessionRepository(context)
        assertNotNull(repository.activeSession())
        assertEquals(9_000L, repository.cooldownUntilMs())
        assertFalse(bulk.contains("active_session"))
        assertFalse(bulk.contains("cooldown_until_ms"))
        assertFalse(context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE).contains("active_session"))
    }

    @Test
    fun activeMigrationPreservesKnownStudyTimeWhenRecoveryIsRequired() {
        val now = 45 * 60_000L
        val clock = MutableSessionClock(TimePoint(now, now, "boot-after-upgrade"))
        context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                "active_session",
                ActiveStudySession(
                    sessionId = "legacy-active",
                    startTimeMs = 0L,
                    taskType = "Đọc tài liệu",
                    focusScore = 3,
                    fatigueScore = 5,
                ).toJson().toString(),
            )
            .commit()

        val repository = StudySessionRepository(context, clock)
        val migrated = requireNotNull(repository.activeSession())

        assertEquals(SessionState.RECOVERY_REQUIRED, migrated.timeline?.state)
        assertEquals(now, migrated.timeline?.accumulatedStudyMs)
        assertEquals(now, repository.studyDurationMs(migrated, now))
        assertEquals(45, repository.finishActiveSession(now, "finish-migrated", clock.now())?.durationMinutes)
    }

    @Test
    fun activeMigrationUsesLastBreakAnchorAndMarksLaterIntervalUnknown() {
        val now = 60 * 60_000L
        val knownAtLastBreak = 45 * 60_000L
        val clock = MutableSessionClock(TimePoint(now, 70 * 60_000L, "boot-after-upgrade"))
        context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                "active_session",
                ActiveStudySession(
                    sessionId = "legacy-after-break",
                    startTimeMs = 0L,
                    taskType = "Đọc tài liệu",
                    focusScore = 3,
                    fatigueScore = 5,
                    breakCount = 1,
                    lastBreakStudyDurationMs = knownAtLastBreak,
                ).toJson().toString(),
            )
            .commit()

        val migrated = requireNotNull(StudySessionRepository(context, clock).activeSession())

        assertEquals(knownAtLastBreak, migrated.timeline?.accumulatedStudyMs)
        assertEquals(15 * 60_000L, migrated.timeline?.unknownDurationMs)
    }

    @Test
    fun recoveredSessionStartsANewZeroLengthFocusBlock() {
        val accumulatedStudy = 55 * 60_000L
        val clock = MutableSessionClock(TimePoint(100_000L, 5_000L, "boot-new"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "recovered-block",
                startTimeMs = 0L,
                taskType = "Bài tập",
                focusScore = 3,
                fatigueScore = 5,
                focusBlockId = "block-2",
                lastBreakStudyDurationMs = 45 * 60_000L,
                continuousImmobileMs = 120_000L,
                motionBlockValidDurationMs = 300_000L,
                timeline = SessionTimelineSnapshot(
                    sessionId = "recovered-block",
                    revision = 4L,
                    state = SessionState.RECOVERY_REQUIRED,
                    blockId = "block-2",
                    enteredAt = TimePoint(90_000L, 4_000L, "boot-old"),
                    accumulatedStudyMs = accumulatedStudy,
                ),
            )
        )

        val resumed = requireNotNull(
            repository.resumeRecoveredSession("recovered-block", "resume-recovered", clock.now())
        )

        assertEquals("block-3", resumed.focusBlockId)
        assertEquals(accumulatedStudy, resumed.lastBreakStudyDurationMs)
        assertEquals(0L, repository.focusBlockDurationMs(resumed, clock.current.wallMs))
        assertEquals(0L, resumed.continuousImmobileMs)
        assertEquals(0L, resumed.motionBlockValidDurationMs)
    }

    @Test
    fun sqliteVersionOneMigratesAdditivelyToVersionTwo() {
        val path = context.getDatabasePath(FocusMateSessionDatabase.DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                """CREATE TABLE check_ins(
                    check_in_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, block_id TEXT NOT NULL,
                    source TEXT NOT NULL, wall_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL,
                    boot_id TEXT NOT NULL, focus INTEGER, fatigue INTEGER
                )"""
            )
            db.version = 1
        }

        FocusMateSessionDatabase(context).use { helper ->
            val db = helper.writableDatabase
            assertEquals(2, db.version)
            val columns = db.rawQuery("PRAGMA table_info(check_ins)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue("break_id" in columns)
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('delivery_slots','unknown_intervals')",
                null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(setOf("delivery_slots", "unknown_intervals"), tables)
        }
    }

    @Test
    fun twoRepositoryInstancesDoNotLoseConcurrentSessionWrites() {
        val first = StudySessionRepository(context)
        val second = StudySessionRepository(context)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        listOf(first, second).forEachIndexed { worker, repository ->
            executor.execute {
                start.await()
                repeat(20) { index -> repository.addSession(session("$worker-$index", worker * 100L + index)) }
                finished.countDown()
            }
        }

        start.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(40, StudySessionRepository(context).sessions().map { it.sessionId }.toSet().size)
    }

    @Test
    fun deleteAllStudyDataClearsHistory() {
        val repository = StudySessionRepository(context)
        repository.addSession(session("delete-me", 1_000L))
        assertTrue(repository.sessions().isNotEmpty())

        assertTrue(repository.deleteAllStudyData())

        assertTrue(repository.sessions().isEmpty())
    }

    @Test
    fun deleteAllStudyDataRefusesWhileActiveAndPreservesHistory() {
        val repository = StudySessionRepository(context)
        repository.addSession(session("keep-me", 1_000L))
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "active",
                startTimeMs = 2_000L,
                subject = "Không áp dụng",
                taskType = "Bài tập",
                focusScore = 3,
                fatigueScore = 5,
            )
        )

        assertFalse(repository.deleteAllStudyData())
        assertEquals("active", repository.activeSession()?.sessionId)
        assertEquals(listOf("keep-me"), repository.sessions().map { it.sessionId })
    }

    @Test
    fun newerHeartRateSnapshotIsNotReplacedByAnOutOfOrderCallback() {
        val repository = StudySessionRepository(context)
        val observedAtMs = System.currentTimeMillis()
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "active",
                startTimeMs = observedAtMs - 5 * 60_000L,
                subject = "Không áp dụng",
                taskType = "Đọc tài liệu",
                focusScore = 3,
                fatigueScore = 5,
            ),
        )

        repository.updateActiveHeartRate("active", 82.0, observedAtMs)
        repository.updateActiveHeartRate("active", 71.0, observedAtMs - 1L)

        val active = requireNotNull(repository.activeSession())
        assertEquals(82.0, active.heartRateCurrent ?: 0.0, 0.0001)
        assertEquals(observedAtMs, active.heartRateObservedAtMs)
        assertEquals(76.5, active.heartRateAverage ?: 0.0, 0.0001)
        assertEquals(2, active.heartRateSampleCount)
    }

    @Test
    fun motionRejectsDuplicateOverlapOldBlockAndResetsImmobilityAcrossGap() {
        val repository = StudySessionRepository(context)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "motion", startTimeMs = System.currentTimeMillis() - 10 * 60_000L,
                taskType = "Đọc tài liệu", focusScore = 3, fatigueScore = 5,
            )
        )
        repository.updateActiveMotion("motion", motion(sequence = 1, start = 1_000L, end = 31_000L))
        repository.updateActiveMotion("motion", motion(sequence = 1, start = 1_000L, end = 31_000L))
        repository.updateActiveMotion("motion", motion(sequence = 2, start = 30_000L, end = 60_000L))
        repository.updateActiveMotion("motion", motion(sequence = 2, start = 40_000L, end = 70_000L, block = "old"))

        var active = requireNotNull(repository.activeSession())
        assertEquals(1, active.motionWindowCount)
        assertEquals(30_000L, active.continuousImmobileMs)

        repository.updateActiveMotion("motion", motion(sequence = 2, start = 40_000L, end = 70_000L))
        active = requireNotNull(repository.activeSession())
        assertEquals(2, active.motionWindowCount)
        assertEquals(30_000L, active.continuousImmobileMs)
        assertEquals(60_000L, active.motionBlockValidDurationMs)
    }

    @Test
    fun checkInIsImmutableIdempotentAndOnlyChangesShadowPolicy() {
        val now = System.currentTimeMillis()
        val repository = StudySessionRepository(context)
        val active = ActiveStudySession(
            sessionId = "checkin", startTimeMs = now - 30 * 60_000L,
            taskType = "Bài tập", focusScore = 5, fatigueScore = 1,
        )
        repository.saveActiveSession(active)
        val at = TimePoint(now, 50_000L, "boot-1")

        assertTrue(repository.recordCheckIn("checkin", CheckInSource.USER_REQUEST, focus = 2, fatigue = 9, at = at, checkInId = "ci-1"))
        assertFalse(repository.recordCheckIn("checkin", CheckInSource.USER_REQUEST, focus = 5, fatigue = 1, at = at, checkInId = "ci-1"))

        val (baseline, shadow) = repository.evaluatePolicyPair(active, 30 * 60_000L, at)
        assertFalse(baseline.eligible)
        assertTrue(shadow.eligible)
        assertEquals(1, repository.checkIns("checkin").size)
        assertEquals(1, repository.policyComparisonCount("checkin"))
    }

    @Test
    fun duplicateBreakCommandCreatesExactlyOneEpisode() {
        val now = System.currentTimeMillis()
        val repository = StudySessionRepository(context)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "break", startTimeMs = now - 10 * 60_000L,
                taskType = "Bài tập", focusScore = 3, fatigueScore = 5,
            )
        )

        repository.startBreak("break", now, commandId = "accept-event")
        repository.startBreak("break", now, commandId = "accept-event")

        assertEquals(1, requireNotNull(repository.activeSession()).breakCount)
        assertEquals(1, repository.breakEpisodes("break").size)
    }

    @Test
    fun checkpointDuringBreakDoesNotExtendItsMonotonicDeadline() {
        val clock = MutableSessionClock(TimePoint(0L, 0L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "deadline", startTimeMs = 0L, taskType = "Bài tập", focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    sessionId = "deadline", revision = 0L, state = SessionState.STUDYING,
                    blockId = "block-0", enteredAt = clock.now(),
                ),
            )
        )
        clock.current = TimePoint(35 * 60_000L, 35 * 60_000L, "boot-test")
        repository.startBreak("deadline", clock.current.wallMs, commandId = "start-break")
        repeat(2) {
            clock.current = clock.current.copy(
                wallMs = clock.current.wallMs + 30_000L,
                elapsedMs = clock.current.elapsedMs + 30_000L,
            )
            repository.checkpointActiveSession()
        }

        val active = requireNotNull(repository.activeSession())
        assertEquals(4 * 60_000L, repository.breakRemainingMs(active))
        assertEquals(40 * 60_000L, active.timeline?.breakDeadlineElapsedMs)
    }

    @Test
    fun acceptingReminderAtomicallyCreatesOneBreakAndClearsPrompt() {
        val clock = MutableSessionClock(TimePoint(45 * 60_000L, 45 * 60_000L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        val reminder = PendingReminder(
            eventId = "reminder", kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = clock.current.wallMs, message = "Nghỉ một chút",
        )
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "atomic", startTimeMs = 0L, taskType = "Bài tập", focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    sessionId = "atomic", revision = 0L, state = SessionState.STUDYING,
                    blockId = "block-0", enteredAt = TimePoint(0L, 0L, "boot-test"),
                ),
            )
        )
        repository.setPendingReminder("atomic", reminder)

        val accepted = repository.acceptReminderAndStartBreak("atomic", "reminder", clock.current)
        val duplicate = repository.acceptReminderAndStartBreak("atomic", "reminder", clock.current)

        assertNotNull(accepted)
        assertEquals(null, duplicate)
        assertEquals(SessionState.BREAKING, repository.activeSession()?.timeline?.state)
        assertEquals(true, repository.activeSession()?.accepted)
        assertEquals(null, repository.activeSession()?.pendingReminder)
        assertEquals(1, repository.breakEpisodes("atomic").size)
    }

    @Test
    fun injectedBreakInsertFailureRollsBackAcceptedResponseAndSnapshot() {
        val clock = MutableSessionClock(TimePoint(45 * 60_000L, 45 * 60_000L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        val reminder = PendingReminder(
            eventId = "rollback-reminder", kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = clock.current.wallMs, message = "Nghỉ một chút",
        )
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "rollback", startTimeMs = 0L, taskType = "Bài tập", focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    "rollback", 0L, SessionState.STUDYING, "block-0",
                    TimePoint(0L, 0L, "boot-test"),
                ),
            )
        )
        repository.setPendingReminder("rollback", reminder)
        val helper = FocusMateSessionDatabase(context)
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER inject_break_failure BEFORE INSERT ON break_episodes " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )

        try {
            repository.acceptReminderAndStartBreak("rollback", reminder.eventId, clock.now(), commandId = "rollback-command")
            fail("Expected injected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            // Expected: the whole command transaction must roll back.
        } finally {
            helper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS inject_break_failure")
            helper.close()
        }

        val active = requireNotNull(repository.activeSession())
        assertEquals(SessionState.STUDYING, active.timeline?.state)
        assertEquals(reminder.eventId, active.pendingReminder?.eventId)
        assertEquals(null, active.accepted)
        assertTrue(repository.breakEpisodes("rollback").isEmpty())
    }

    @Test
    fun controllerMakesPauseCommandIdempotentAndRejectsOldBlockIdentity() {
        val clock = MutableSessionClock(TimePoint(1_000L, 1_000L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        val controller = DefaultSessionController(repository)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "commands", startTimeMs = 0L, taskType = "Bài tập", focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    sessionId = "commands", revision = 0L, state = SessionState.STUDYING,
                    blockId = "block-0", enteredAt = TimePoint(0L, 0L, "boot-test"),
                ),
            )
        )
        val command = SessionCommand.Pause("pause-once", clock.now(), "commands", "block-0")

        val first = controller.dispatch(command)
        val duplicate = controller.dispatch(command)
        val stale = controller.dispatch(
            SessionCommand.ResumePaused("resume-stale", clock.now(), "commands", "block-old")
        )

        assertTrue(first.applied)
        assertFalse(first.duplicate)
        assertFalse(duplicate.applied)
        assertTrue(duplicate.duplicate)
        assertFalse(stale.applied)
        assertEquals("stale_session_or_block", stale.reason)
        assertEquals(SessionState.PAUSED, repository.activeSession()?.timeline?.state)
        assertEquals(1L, repository.activeSession()?.timeline?.revision)
    }

    @Test
    fun cooldownRemainingUsesElapsedClockWhenWallClockChanges() {
        val clock = MutableSessionClock(TimePoint(1_000_000L, 50_000L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        repository.setCooldownUntilMs(clock.current.wallMs + 20 * 60_000L)

        clock.current = TimePoint(
            wallMs = clock.current.wallMs + 2 * 60 * 60_000L,
            elapsedMs = clock.current.elapsedMs + 5 * 60_000L,
            bootId = "boot-test",
        )

        assertEquals(clock.current.wallMs + 15 * 60_000L, repository.cooldownUntilMs())
    }

    @Test
    fun fatigueChangeRequiresExplicitBeforeAndAfterCheckInsLinkedToSameBreak() {
        val clock = MutableSessionClock(TimePoint(0L, 0L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "fatigue-pair", startTimeMs = 0L, taskType = "Bài tập", focusScore = 3, fatigueScore = 7,
                timeline = SessionTimelineSnapshot(
                    "fatigue-pair", 0L, SessionState.STUDYING, "block-0", clock.now(),
                ),
            )
        )
        clock.current = TimePoint(10 * 60_000L, 10 * 60_000L, "boot-test")
        assertTrue(repository.recordCheckIn("fatigue-pair", CheckInSource.BEFORE_BREAK, fatigue = 7, at = clock.now()))
        repository.startBreak("fatigue-pair", clock.current.wallMs, commandId = "pair-break", at = clock.now())
        clock.current = TimePoint(15 * 60_000L, 15 * 60_000L, "boot-test")
        repository.markBreakAwaitingDecisionIfDue(clock.current.wallMs)
        repository.resumeStudyAfterBreak("fatigue-pair", clock.current.wallMs, "pair-resume", clock.now())
        assertTrue(repository.recordCheckIn("fatigue-pair", CheckInSource.AFTER_BREAK, fatigue = 5, at = clock.now()))

        assertEquals(listOf(-2), FocusMateSessionDatabase(context).breakFatigueChanges("fatigue-pair"))
    }

    @Test
    fun retentionCapsHistoryAtFiveHundredAndDeletesEvictedSessionChildren() {
        val repository = StudySessionRepository(context)
        val oldest = session("session-000", 0L)
        repository.addSession(oldest)
        val database = FocusMateSessionDatabase(context)
        assertTrue(
            database.recordCheckIn(
                CheckInRecord(
                    checkInId = "oldest-check-in",
                    sessionId = oldest.sessionId,
                    blockId = "block-0",
                    source = CheckInSource.SESSION_END,
                    time = TimePoint(oldest.endTimeMs, 1_000L, "boot-retention"),
                    fatigue = 5,
                )
            )
        )

        (1..500).forEach { index ->
            repository.addSession(session("session-${index.toString().padStart(3, '0')}", index.toLong()))
        }

        assertEquals(500, repository.sessions().size)
        assertFalse(repository.sessions().any { it.sessionId == oldest.sessionId })
        assertTrue(repository.checkIns(oldest.sessionId).isEmpty())
        database.close()
    }

    @Test
    fun retentionRemovesExpiredChildrenAndOrphanRecoveryPayloads() {
        val repository = StudySessionRepository(context)
        val database = FocusMateSessionDatabase(context)
        val today = LocalDate.now()
        val oldWallMs = today.minusDays(40).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val expired = session("expired", 0L).copy(
            startTimeMs = oldWallMs,
            endTimeMs = oldWallMs + 60_000L,
            expiresOn = today.minusDays(10).toString(),
        )
        database.upsertSession(expired)
        assertTrue(
            database.recordCheckIn(
                CheckInRecord(
                    checkInId = "expired-check-in",
                    sessionId = expired.sessionId,
                    blockId = "block-0",
                    source = CheckInSource.SESSION_END,
                    time = TimePoint(expired.endTimeMs, 1_000L, "boot-old"),
                    fatigue = 5,
                )
            )
        )
        database.preserveRecoveryPayload("orphan", "sensitive-corrupt-row")
        database.writableDatabase.execSQL(
            "UPDATE recovery_records SET captured_wall_ms=? WHERE source=?",
            arrayOf<Any>(oldWallMs, "orphan"),
        )

        assertTrue(repository.pruneExpiredData(today))

        assertFalse(repository.sessions().any { it.sessionId == expired.sessionId })
        assertTrue(repository.checkIns(expired.sessionId).isEmpty())
        assertEquals(0, database.recoveryPayloadCount("orphan"))
        database.close()
    }

    @Test
    fun injectedResumeFailureRollsBackSnapshotEventAndBreakClosure() {
        val clock = MutableSessionClock(TimePoint(0L, 0L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "resume-rollback", startTimeMs = 0L, taskType = "Bài tập",
                focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    "resume-rollback", 0L, SessionState.STUDYING, "block-0", clock.now(),
                ),
            )
        )
        clock.current = TimePoint(10_000L, 10_000L, "boot-test")
        repository.startBreak("resume-rollback", clock.current.wallMs, commandId = "resume-break", at = clock.now())
        clock.current = TimePoint(6 * 60_000L, 6 * 60_000L, "boot-test")
        repository.markBreakAwaitingDecisionIfDue(clock.current.wallMs)
        val database = FocusMateSessionDatabase(context)
        database.writableDatabase.execSQL(
            "CREATE TRIGGER inject_resume_failure BEFORE UPDATE ON break_episodes " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )

        try {
            repository.resumeStudyAfterBreak(
                "resume-rollback", clock.current.wallMs, "resume-command", clock.now()
            )
            fail("Expected injected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            // Expected: event, snapshot and break close belong to one transaction.
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER IF EXISTS inject_resume_failure")
        }

        assertEquals(SessionState.AWAITING_RESUME, repository.activeSession()?.timeline?.state)
        assertEquals(null, repository.breakEpisodes("resume-rollback").single().endedAt)
        assertFalse(database.hasCommand("resume-command"))
        assertNotNull(
            repository.resumeStudyAfterBreak(
                "resume-rollback", clock.current.wallMs, "resume-command", clock.now()
            )
        )
        assertEquals(SessionState.STUDYING, repository.activeSession()?.timeline?.state)
        database.close()
    }

    @Test
    fun injectedFinishFailurePreservesActiveSessionAndOpenBreakForRetry() {
        val startWallMs = System.currentTimeMillis()
        val clock = MutableSessionClock(TimePoint(startWallMs, 0L, "boot-test"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "finish-rollback", startTimeMs = startWallMs, taskType = "Bài tập",
                focusScore = 3, fatigueScore = 5,
                timeline = SessionTimelineSnapshot(
                    "finish-rollback", 0L, SessionState.STUDYING, "block-0", clock.now(),
                ),
            )
        )
        clock.current = TimePoint(startWallMs + 10_000L, 10_000L, "boot-test")
        repository.startBreak("finish-rollback", clock.current.wallMs, commandId = "finish-break", at = clock.now())
        val database = FocusMateSessionDatabase(context)
        database.writableDatabase.execSQL(
            "CREATE TRIGGER inject_finish_failure BEFORE INSERT ON completed_sessions " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )

        try {
            repository.finishActiveSession(clock.current.wallMs, "finish-command")
            fail("Expected injected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            // Expected: active clear, break close and completed row are atomic.
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER IF EXISTS inject_finish_failure")
        }

        assertNotNull(repository.activeSession())
        assertEquals(null, repository.breakEpisodes("finish-rollback").single().endedAt)
        assertFalse(database.hasCommand("finish-command"))
        assertNotNull(repository.finishActiveSession(clock.current.wallMs, "finish-command"))
        assertEquals(null, repository.activeSession())
        assertEquals(1, repository.sessions().count { it.sessionId == "finish-rollback" })
        database.close()
    }

    @Test
    fun failedLegacyMigrationRollsBackAndRetriesWithoutDuplicateRows() {
        val legacy = context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE)
        legacy.edit().putString("sessions", JSONArray().put(session("legacy", 0L).toJson()).toString()).commit()
        FocusMateSessionDatabase(context).use { database ->
            database.writableDatabase.execSQL(
                "CREATE TRIGGER inject_migration_failure BEFORE INSERT ON completed_sessions " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END"
            )
        }
        FocusMateSessionDatabaseProvider.closeForTests()

        try {
            StudySessionRepository(context)
            fail("Expected injected SQLite failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            // The source preferences and incomplete marker must survive this failed attempt.
        }
        FocusMateSessionDatabaseProvider.closeForTests()
        FocusMateSessionDatabase(context).use { database ->
            assertEquals(null, database.metadata(FocusMateSessionDatabase.META_PREFS_MIGRATION))
            assertEquals(0, database.sessionPayloads().size)
            database.writableDatabase.execSQL("DROP TRIGGER IF EXISTS inject_migration_failure")
        }

        val repository = StudySessionRepository(context)
        assertEquals(listOf("legacy"), repository.sessions().map { it.sessionId })
        assertFalse(legacy.contains("sessions"))
        assertEquals(
            "complete",
            FocusMateSessionDatabase(context).metadata(FocusMateSessionDatabase.META_PREFS_MIGRATION),
        )
    }

    @Test
    fun completedReportPreservesPerReminderDeliveryTimesResultResponseAndFeedback() {
        val now = System.currentTimeMillis()
        val clock = MutableSessionClock(TimePoint(now, 45 * 60_000L, "boot-report"))
        val repository = StudySessionRepository(context, clock)
        repository.saveActiveSession(
            ActiveStudySession(
                sessionId = "report-history",
                startTimeMs = now - 45 * 60_000L,
                taskType = "Bài tập",
                focusScore = 3,
                fatigueScore = 7,
                timeline = SessionTimelineSnapshot(
                    "report-history", 0L, SessionState.STUDYING, "block-0",
                    TimePoint(now - 45 * 60_000L, 0L, "boot-report"),
                ),
            )
        )
        val reminder = PendingReminder(
            eventId = "report-reminder",
            kind = PendingReminderKind.BREAK_SUGGESTION,
            createdAtMs = now,
            createdAtElapsedMs = clock.current.elapsedMs,
            createdBootId = clock.current.bootId,
            message = "Bạn muốn nghỉ một chút không?",
            reasonCodes = setOf(WatchRuleEngine.RULE_V1_DURATION_FATIGUE),
            evidenceReferences = setOf("HEALTH-02"),
        )
        repository.setPendingReminder("report-history", reminder)
        repository.advancePendingReminder("report-history", reminder.eventId)
        val slot = requireNotNull(repository.reservePendingDeliverySlot("report-history", reminder.eventId))
        repository.completePendingDeliverySlot(reminder.eventId, slot, "POSTED")
        repository.acceptReminderAndStartBreak("report-history", reminder.eventId, clock.now())
        clock.current = TimePoint(now + 2 * 60_000L, 47 * 60_000L, "boot-report")
        assertNotNull(repository.finishActiveSession(clock.current.wallMs, "report-finish"))

        val database = FocusMateSessionDatabase(context)
        assertTrue(
            database.recordPromptFeedback(
                reminderId = reminder.eventId,
                sessionId = "report-history",
                timing = PromptTimingFeedback.ABOUT_RIGHT,
                annoyance = 2,
                wallMs = clock.current.wallMs,
            )
        )
        repository.updateSessionReview("report-history", true)

        val completed = repository.sessions().single { it.sessionId == "report-history" }
        val history = completed.reminderHistory.single()
        assertEquals(reminder.eventId, history.reminderId)
        assertEquals(BreakPromptEvent.RESPONSE_ACCEPTED, history.response)
        assertEquals(PromptTimingFeedback.ABOUT_RIGHT, history.timingFeedback)
        assertEquals(2, history.annoyance)
        assertEquals("POSTED", history.deliveries.single().result)
        assertEquals(now, history.deliveries.single().scheduledWallMs)
        assertNotNull(history.deliveries.single().receiverWallMs)
        assertNotNull(history.deliveries.single().postAttemptWallMs)
        database.close()
    }

    private fun session(id: String, startMs: Long): StudySession {
        val retainedStartMs = System.currentTimeMillis() - 60_000L + startMs
        return StudySession(
        sessionId = id,
        startTimeMs = retainedStartMs,
        endTimeMs = retainedStartMs + 60_000L,
        durationMinutes = 1,
        subject = "Không áp dụng",
        taskType = "Đọc tài liệu",
        focusScore = 3,
        fatigueScore = 5,
        breakReminderCount = 0,
        shouldBreak = false,
        interruptRisk = "low",
        accepted = null,
        deferReason = null,
    )
    }

    private fun motion(sequence: Long, start: Long, end: Long, block: String = "block-0") =
        MotionWindowMetrics(
            observedAtMs = System.currentTimeMillis(), movementRms = 0.01, rotationRms = 0.01,
            suddenMovementCount = 0, wristRotationCount = 0, immobileSeconds = 30.0,
            movementChangeFromBaseline = null, watchRaiseCount = 0,
            accelerometerSamples = 100, gyroscopeSamples = 100,
            windowStartElapsedMs = start, windowEndElapsedMs = end, bootId = "boot-1",
            blockId = block, sequence = sequence, validSampleDurationMs = 30_000L,
        )

    private fun clearPreferences() {
        FocusMateSessionDatabaseProvider.closeForTests()
        listOf(BULK_PREFS, ACTIVE_PREFS, "focusmate_local_study_ai_v2").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.deleteDatabase(FocusMateSessionDatabase.DATABASE_NAME)
    }

    companion object {
        private const val BULK_PREFS = "focusmate_local_store_v1"
        private const val ACTIVE_PREFS = "focusmate_active_state_v1"
    }

    private class MutableSessionClock(var current: TimePoint) : SessionClock {
        override fun now(): TimePoint = current
    }
}
