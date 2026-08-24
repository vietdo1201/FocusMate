// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaceObservationV1Test {
    @Test
    fun detectedFaceRoundTripsWithinBudget() {
        val observation = FaceObservationV1(
            sequence = 42,
            espUptimeMs = 12_345,
            faceDetected = true,
            centerX = 0.5,
            centerY = 0.4,
            width = 0.2,
            height = 0.3,
            area = 0.06,
            confidence = 0.91,
            qualityFlags = setOf("stable", "well_lit"),
        )
        val payload = FaceObservationCodec.encode(observation)
        assertEquals(observation, FaceObservationCodec.decode(payload))
        check(payload.size <= FaceObservationV1.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun missingFaceRoundTripsWithoutImageFields() {
        val observation = FaceObservationV1(7, 1_000, false, qualityFlags = setOf("low_light"))
        assertEquals(observation, FaceObservationCodec.decode(FaceObservationCodec.encode(observation)))
    }

    @Test
    fun malformedUnknownAndOversizedPayloadsAreRejected() {
        assertRejected { FaceObservationCodec.decode(ByteArray(FaceObservationV1.MAX_PAYLOAD_BYTES + 1)) }
        assertRejected {
            FaceObservationCodec.decode(
                """{"schema_version":"wrong","sequence":1,"esp_uptime_ms":1,"face_detected":false}"""
                    .toByteArray()
            )
        }
        assertRejected {
            FaceObservationCodec.decode(
                """{"schema_version":"focusmate_face_observation_v1","sequence":1,"esp_uptime_ms":1,"face_detected":false,"image":"forbidden"}"""
                    .toByteArray()
            )
        }
    }

    @Test
    fun duplicateSequenceIsRejectedAndEspRebootResetsGate() {
        val gate = FaceSequenceGate()
        assertTrue(gate.onDeviceInfo("00000000000000000000000000000001"))
        val first = FaceObservationCodec.decode(FaceObservationCodec.encode(FaceObservationV1(9, 100, false)))
        val duplicate = FaceObservationCodec.decode(FaceObservationCodec.encode(FaceObservationV1(9, 101, false)))
        assertTrue(gate.accept(first))
        assertFalse(gate.accept(duplicate))
        assertFalse(gate.accept(FaceObservationV1(0, 1, false)))
        assertTrue(gate.onDeviceInfo("00000000000000000000000000000002"))
        assertTrue(gate.accept(FaceObservationV1(0, 1, false)))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected payload rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
