// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedActivityClassifierTest {
    @Test
    fun stepDetectorAlwaysWinsForWalking() {
        val result = RuleBasedActivityClassifier.classify(metrics(stepCount = 4, orientation = 50.0))
        assertEquals(MotionLabel.WALKING, result.label)
        assertEquals("step_detector", result.reason)
    }

    @Test
    fun orientationChangeDetectsPostureTransition() {
        val result = RuleBasedActivityClassifier.classify(metrics(orientation = 35.0))
        assertEquals(MotionLabel.POSTURE_TRANSITION, result.label)
        assertEquals("wrist_orientation_change", result.reason)
    }

    @Test
    fun stableLowMotionDetectsStationary() {
        val result = RuleBasedActivityClassifier.classify(
            metrics(movement = 0.08, rotation = 0.05, immobile = 27.0)
        )
        assertEquals(MotionLabel.STATIONARY, result.label)
        assertTrue(result.confidence >= 0.8)
    }

    @Test
    fun smallWristMotionDetectsFineHandActivity() {
        val result = RuleBasedActivityClassifier.classify(
            metrics(movement = 0.30, rotation = 0.22, immobile = 12.0, wristRotations = 3)
        )
        assertEquals(MotionLabel.FINE_HAND_MOTION, result.label)
    }

    @Test
    fun personalCalibrationAdaptsToOwnersStableWristNoise() {
        val calibrator = PersonalActivityThresholdCalibrator()
        val stable = metrics(movement = 0.16, rotation = 0.10, immobile = 27.0, orientation = 4.0)

        val first = calibrator.classify("session-a", stable)
        calibrator.classify("session-a", stable)
        val third = calibrator.classify("session-a", stable)

        assertEquals(false, first.calibrated)
        assertEquals(1, first.calibrationWindows)
        assertEquals(true, third.calibrated)
        assertEquals(PersonalActivityThresholdCalibrator.REQUIRED_STABLE_WINDOWS, third.calibrationWindows)

        val ownerStill = metrics(movement = 0.32, rotation = 0.19, immobile = 25.0)
        assertEquals(MotionLabel.FINE_HAND_MOTION, RuleBasedActivityClassifier.classify(ownerStill).label)
        assertEquals(MotionLabel.STATIONARY, calibrator.classify("session-a", ownerStill).label)
    }

    @Test
    fun personalCalibrationResetsForNewSession() {
        val calibrator = PersonalActivityThresholdCalibrator()
        val stable = metrics(movement = 0.12, rotation = 0.07, immobile = 28.0)
        repeat(3) { calibrator.classify("session-a", stable) }

        val newSession = calibrator.classify("session-b", stable)

        assertEquals(false, newSession.calibrated)
        assertEquals(1, newSession.calibrationWindows)
    }

    private fun metrics(
        stepCount: Int = 0,
        orientation: Double = 0.0,
        movement: Double = 0.12,
        rotation: Double = 0.08,
        immobile: Double = 25.0,
        wristRotations: Int = 0,
    ) = MotionWindowMetrics(
        observedAtMs = 1_000L,
        movementRms = movement,
        rotationRms = rotation,
        suddenMovementCount = 0,
        wristRotationCount = wristRotations,
        immobileSeconds = immobile,
        movementChangeFromBaseline = null,
        watchRaiseCount = 0,
        accelerometerSamples = 750,
        gyroscopeSamples = 750,
        stepCount = stepCount,
        orientationChangeDegrees = orientation,
        stepDetectorAvailable = true,
        orientationSensorAvailable = true,
    )
}
