// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSensorServiceLifecycleTest {
    @Test
    fun foregroundServiceUsesOnlyTypesBackedByGrantedCapabilities() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            sessionSensorForegroundServiceTypes(
                hasHeartRate = false,
                hasActivityRecognition = true,
                hasBluetooth = false,
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            sessionSensorForegroundServiceTypes(
                hasHeartRate = false,
                hasActivityRecognition = false,
                hasBluetooth = true,
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            sessionSensorForegroundServiceTypes(
                hasHeartRate = true,
                hasActivityRecognition = false,
                hasBluetooth = true,
            ),
        )
        assertEquals(
            0,
            sessionSensorForegroundServiceTypes(
                hasHeartRate = false,
                hasActivityRecognition = false,
                hasBluetooth = false,
            ),
        )
    }

    @Test
    fun releasesDndOnlyWhenSessionIsAbsentOrOnBreak() {
        val active = ActiveStudySession(
            sessionId = "active",
            startTimeMs = 1_000L,
            subject = "Không áp dụng",
            taskType = "Bài tập",
            focusScore = 3,
            fatigueScore = 5,
        )
        assertTrue(shouldReleaseStudyDnd(null, 2_000L))
        assertFalse(shouldReleaseStudyDnd(active, 2_000L))
        assertTrue(
            shouldReleaseStudyDnd(
                active.copy(breakStartedAtMs = 1_500L, breakEndsAtMs = 3_000L),
                2_000L,
            )
        )
    }
}
