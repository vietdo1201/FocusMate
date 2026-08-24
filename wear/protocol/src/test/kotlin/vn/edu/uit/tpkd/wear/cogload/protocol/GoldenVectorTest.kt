// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

import java.io.File
import java.util.Base64
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenVectorTest {
    private val fixture: JSONObject by lazy {
        val root = requireNotNull(System.getProperty("focusmate.vectors"))
        JSONObject(File(root, "golden/face_observation_v1.json").readText())
    }

    @Test
    fun positiveVectorsAreByteExactAndRoundTrip() {
        val vectors = fixture.getJSONArray("positive")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val expected = vector.getString("payload").toByteArray(Charsets.UTF_8)
            val observation = vector.getJSONObject("observation").toObservation()
            val encoded = FaceObservationCodec.encode(observation)
            assertTrue(vector.getString("id"), encoded.contentEquals(expected))
            assertEquals(vector.getInt("byte_length"), encoded.size)
            assertEquals(vector.getString("crc16"), "0x%04X".format(Crc16.ccittFalse(encoded)))
            assertEquals(observation.canonical(), FaceObservationCodec.decode(encoded))
        }
    }

    @Test
    fun negativeVectorsAreRejected() {
        val vectors = fixture.getJSONArray("negative")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val payload = if (vector.has("payload_base64")) {
                Base64.getDecoder().decode(vector.getString("payload_base64"))
            } else {
                vector.getString("payload").toByteArray(Charsets.UTF_8)
            }
            assertTrue(vector.getString("id"), FaceObservationCodec.tryDecode(payload).isFailure)
        }
    }

    @Test
    fun everyGeneratedValidObservationFitsPayloadBudget() {
        val random = Random(0xF0C05L)
        repeat(2_000) { sequence ->
            val width = 0.001 + random.nextDouble() * 0.999
            val height = 0.001 + random.nextDouble() * 0.999
            val observation = FaceObservationV1(
                sequence = sequence.toLong(),
                espUptimeMs = random.nextLong().ushr(1) % (FaceObservationV1.MAX_UPTIME_MS + 1),
                faceDetected = true,
                centerX = random.nextDouble(),
                centerY = random.nextDouble(),
                width = width,
                height = height,
                area = width * height,
                confidence = random.nextDouble(),
                qualityFlags = setOf("reserved_flag_00", "reserved_flag_01", "reserved_flag_02", "reserved_flag_03"),
            )
            val encoded = FaceObservationCodec.encode(observation)
            assertTrue(encoded.size <= FaceObservationV1.MAX_PAYLOAD_BYTES)
            assertEquals(observation.canonical(), FaceObservationCodec.decode(encoded))
        }
    }

    @Test
    fun scaledIntegerRoundingAndFormattingAreStable() {
        assertEquals("0.123456", FaceObservationV1.formatUnit(0.1234564))
        assertEquals("0.123457", FaceObservationV1.formatUnit(0.1234565))
        assertEquals("0.000000", FaceObservationV1.formatUnit(0.0))
        assertEquals("1.000000", FaceObservationV1.formatUnit(1.0))
        assertEquals(0.060000, FaceObservationV1.deriveArea(0.2, 0.3), 0.0)
    }

    private fun JSONObject.toObservation(): FaceObservationV1 {
        val detected = getBoolean("face_detected")
        val flagsJson = getJSONArray("quality_flags")
        val flags = buildSet { for (index in 0 until flagsJson.length()) add(flagsJson.getString(index)) }
        return FaceObservationV1(
            sequence = getLong("sequence"),
            espUptimeMs = getLong("esp_uptime_ms"),
            faceDetected = detected,
            centerX = if (detected) getDouble("cx") else null,
            centerY = if (detected) getDouble("cy") else null,
            width = if (detected) getDouble("width") else null,
            height = if (detected) getDouble("height") else null,
            area = if (detected) getDouble("area") else null,
            confidence = if (detected) getDouble("confidence") else null,
            qualityFlags = flags,
        )
    }
}
