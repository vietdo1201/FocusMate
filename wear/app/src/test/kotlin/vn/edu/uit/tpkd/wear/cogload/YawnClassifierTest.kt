// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YawnClassifierTest {
    private fun observation(
        index: Int,
        mar: Double,
        jaw: Double,
        mouthWidthRatio: Double,
    ) = YawnFrameObservation(
        observedAtMonoMs = index * 400L,
        observedAtWallMs = 100_000L + index * 400L,
        jawOpen = jaw,
        mouthAspectRatio = mar,
        mouthWidthRatio = mouthWidthRatio,
        frameSequence = index.toLong(),
    )

    private fun calibrated(): YawnClassifier = YawnClassifier().also { classifier ->
        repeat(20) { index ->
            val result = classifier.observe(observation(index, 0.08, 0.05, 0.8))
            if (index == 19) assertTrue(result.calibrated)
        }
    }

    @Test
    fun sustainedWideLaughIsNotCountedAsYawn() {
        val classifier = calibrated()
        var result: YawnDetection? = null
        repeat(8) { offset ->
            result = classifier.observe(observation(20 + offset, 0.8, 0.8, 1.16))
        }
        assertEquals(0, result!!.totalCount)
        assertEquals("smile_like", result!!.reason)
    }

    @Test
    fun sustainedVerticalOpeningIsCountedAfterConservativeDuration() {
        val classifier = calibrated()
        var result: YawnDetection? = null
        repeat(5) { offset ->
            result = classifier.observe(observation(20 + offset, 1.0, 0.8, 0.8))
        }
        assertEquals(YawnState.YAWNING, result!!.state)
        assertEquals(1, result!!.totalCount)
        assertTrue(result!!.eventJustCounted)
    }

    @Test
    fun moderateLaughBelowMarFloorIsNotCounted() {
        val classifier = calibrated()
        var result: YawnDetection? = null
        repeat(8) { offset ->
            result = classifier.observe(observation(20 + offset, 0.28, 0.8, 1.0))
        }
        assertEquals(0, result!!.totalCount)
    }
}
