// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RetentionPolicyTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val observed = Instant.parse("2026-08-04T05:00:00Z").toEpochMilli()

    @Test
    fun retainsThroughDayThirtyAndExpiresAtStartOfDayThirtyOne() {
        val expires = RetentionPolicy.expiresOn(observed, zone)
        assertFalse(RetentionPolicy.isExpired(expires, LocalDate.of(2026, 9, 2)))
        assertTrue(RetentionPolicy.isExpired(expires, LocalDate.of(2026, 9, 3)))
    }

    @Test
    fun persistedExpiryDoesNotMoveWhenDeviceTimezoneChanges() {
        val persisted = RetentionPolicy.expiresOnText(observed, zone)
        assertTrue(
            RetentionPolicy.isExpired(
                expiresOn = persisted,
                fallbackObservedAtMs = observed,
                today = LocalDate.of(2026, 9, 3),
                zoneId = ZoneId.of("America/Los_Angeles"),
            )
        )
    }
}
