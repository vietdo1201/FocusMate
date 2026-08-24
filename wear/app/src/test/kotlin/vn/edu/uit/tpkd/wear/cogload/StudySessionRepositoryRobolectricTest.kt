// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun corruptRowKeepsValidHistoryAndMigrationDoesNotOverwriteRawStore() {
        val valid = session("valid", 1_000L).toJson()
        val corrupt = JSONObject().put("session_id", "corrupt")
        val raw = JSONArray().put(valid).put(corrupt).toString()
        val bulk = context.getSharedPreferences(BULK_PREFS, Context.MODE_PRIVATE)
        bulk.edit().putString("sessions", raw).commit()

        val repository = StudySessionRepository(context)

        assertEquals(listOf("valid"), repository.sessions().map { it.sessionId })
        assertEquals(2, JSONArray(bulk.getString("sessions", "[]")).length())
        assertTrue(bulk.getBoolean("legacy_migration_v2_done", false))
    }

    @Test
    fun activeStateMovesToDedicatedPreferences() {
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
        val active = context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE)

        assertNotNull(repository.activeSession())
        assertEquals(9_000L, repository.cooldownUntilMs())
        assertFalse(bulk.contains("active_session"))
        assertFalse(bulk.contains("cooldown_until_ms"))
        assertTrue(active.contains("active_session"))
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
