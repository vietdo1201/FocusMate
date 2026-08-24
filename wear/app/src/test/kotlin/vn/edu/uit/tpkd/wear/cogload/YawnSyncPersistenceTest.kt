// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YawnSyncPersistenceTest {
    @Test
    fun activeSessionRoundTripsCheckpointAndOutbox() {
        val active = ActiveStudySession(
            sessionId = "study-session",
            startTimeMs = 1_000L,
            taskType = "Đọc tài liệu",
            focusScore = 4,
            fatigueScore = 2,
            yawnCount = 3,
            yawnSyncSessionId = "00112233445566778899aabbccddeeff",
            yawnSyncClientId = 42L,
            yawnSyncRevision = 7L,
            yawnSyncWindowCount = 2,
            yawnSyncObservedAtMs = 5_000L,
            nextYawnSyncEventId = 9L,
            pendingYawnSyncEvents = listOf(PendingYawnSyncEvent(8L, 100L, 4_500L)),
        )

        val restored = ActiveStudySession.fromJson(JSONObject(active.toJson().toString()))

        assertEquals(active.yawnSyncSessionId, restored.yawnSyncSessionId)
        assertEquals(7L, restored.yawnSyncRevision)
        assertEquals(2, restored.yawnSyncWindowCount)
        assertEquals(9L, restored.nextYawnSyncEventId)
        assertEquals(active.pendingYawnSyncEvents, restored.pendingYawnSyncEvents)
    }

    @Test
    fun legacySessionGetsValidPrivateSyncIdentity() {
        val legacy = JSONObject().apply {
            put("start_time_ms", 1_000L)
            put("task_type", "Bài tập")
            put("focus_score", 3)
            put("fatigue_score", 3)
        }
        val restored = ActiveStudySession.fromJson(legacy)
        assertTrue(restored.yawnSyncSessionId.matches(Regex("[0-9a-f]{32}")))
        assertTrue(restored.yawnSyncClientId in 1L..4_294_967_295L)
    }

    @Test
    fun canonicalIncreaseNeverBecomesAnotherLocalOutboxEvent() {
        val classifier = YawnClassifier(YawnSeed(totalCount = 5))

        val first = classifier.synchronizeRemote(
            remoteTotalCount = 6,
            remoteWindowCount = 1,
            observedAtMonoMs = 10_000L,
            observedAtWallMs = 100_000L,
        )!!
        assertEquals(6, first.totalCount)
        assertTrue(first.persistenceChanged)
        assertTrue(!first.eventJustCounted)

        val second = classifier.synchronizeRemote(
            remoteTotalCount = 7,
            remoteWindowCount = 1,
            observedAtMonoMs = 10_500L,
            observedAtWallMs = 100_500L,
        )!!
        assertEquals(7, second.totalCount)
        assertTrue(!second.eventJustCounted)
    }
}
