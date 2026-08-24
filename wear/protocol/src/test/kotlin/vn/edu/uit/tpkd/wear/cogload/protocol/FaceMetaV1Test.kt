// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaceMetaV1Test {
    private val fixture: JSONObject by lazy {
        val root = requireNotNull(System.getProperty("focusmate.vectors"))
        JSONObject(File(root, "golden/face_meta_v1.json").readText())
    }

    @Test
    fun goldenHeadersAreCanonicalAndByteExact() {
        val vectors = fixture.getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val expectedBytes = vector.getString("wire_hex").hexToBytes()
            val parsed = FaceMetaV1.parseHeader(vector.getString("header"))
            assertEquals(vector.getString("id"), vector.getBoolean("face_detected"), parsed.faceDetected)
            assertEquals(vector.getBoolean("valid"), parsed.semanticallyValid)
            assertTrue(parsed.encode().contentEquals(expectedBytes))
            assertEquals(vector.getString("header"), parsed.encodeHeader())
        }
    }

    @Test
    fun detectedVectorUsesSixteenLittleEndianWordsInLockedOrder() {
        val vector = fixture.getJSONArray("vectors").getJSONObject(1)
        val parsed = FaceMetaV1.parse(vector.getString("wire_hex").hexToBytes())
        val words = vector.getJSONArray("words")
        assertEquals(words.getInt(0), parsed.flags)
        assertEquals(words.getInt(1), parsed.confidenceQ16)
        assertEquals(words.getInt(2), parsed.centerXQ16)
        assertEquals(words.getInt(3), parsed.centerYQ16)
        assertEquals(words.getInt(4), parsed.widthQ16)
        assertEquals(words.getInt(5), parsed.heightQ16)
        repeat(5) { index ->
            assertEquals(words.getInt(6 + index * 2), parsed.keypoints[index].xQ16)
            assertEquals(words.getInt(7 + index * 2), parsed.keypoints[index].yQ16)
        }
        assertEquals(1.0, parsed.confidence, 0.0)
    }

    @Test
    fun paddingWrongLengthReservedFlagsAndNonzeroNoFaceAreRejectedOrInvalid() {
        val noFace = FaceMetaV1.parseHeader(fixture.getJSONArray("vectors").getJSONObject(0).getString("header"))
        assertFails { FaceMetaV1.parseHeader(noFace.encodeHeader() + "=") }
        assertFails { FaceMetaV1.parse(ByteArray(31)) }
        assertFalse(noFace.copy(flags = 2).transportCompatible)
        assertFalse(noFace.copy(confidenceQ16 = 1).semanticallyValid)
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
