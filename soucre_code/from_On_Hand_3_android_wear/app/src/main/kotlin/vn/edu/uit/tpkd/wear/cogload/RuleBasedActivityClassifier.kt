package vn.edu.uit.tpkd.wear.cogload

enum class MotionLabel {
    WALKING,
    POSTURE_TRANSITION,
    STATIONARY,
    FINE_HAND_MOTION,
}

data class RuleBasedActivityResult(
    val label: MotionLabel,
    val confidence: Double,
    val reason: String,
    val calibrated: Boolean = true,
    val calibrationWindows: Int = 0,
)

data class RuleBasedActivityThresholds(
    val postureAngleDegrees: Double = 28.0,
    val transitionRotationRms: Double = 0.65,
    val transitionMovementRms: Double = 0.80,
    val stationaryMovementRms: Double = 0.24,
    val stationaryRotationRms: Double = 0.16,
    val fineHandRotationRms: Double = 0.18,
    val fineHandMovementRms: Double = 0.28,
)

/**
 * Training-free, explainable activity recognition for one watch owner.
 * Step Detector has priority; wrist orientation and motion thresholds are fallbacks.
 */
object RuleBasedActivityClassifier {
    const val POLICY_NAME = "rule_based_activity_v2_personal_thresholds"

    fun classify(
        metrics: MotionWindowMetrics,
        thresholds: RuleBasedActivityThresholds = RuleBasedActivityThresholds(),
    ): RuleBasedActivityResult {
        if (metrics.stepCount >= MIN_WALKING_STEPS) {
            return RuleBasedActivityResult(
                MotionLabel.WALKING,
                (0.78 + metrics.stepCount.coerceAtMost(12) * 0.015).coerceAtMost(0.95),
                "step_detector",
            )
        }

        val postureTransition =
            metrics.orientationChangeDegrees >= thresholds.postureAngleDegrees ||
                metrics.suddenMovementCount >= MIN_TRANSITION_BURSTS ||
                (metrics.rotationRms >= thresholds.transitionRotationRms &&
                    metrics.movementRms >= thresholds.transitionMovementRms)
        if (postureTransition) {
            return RuleBasedActivityResult(
                MotionLabel.POSTURE_TRANSITION,
                if (metrics.orientationChangeDegrees >= thresholds.postureAngleDegrees) 0.84 else 0.72,
                if (metrics.orientationChangeDegrees >= thresholds.postureAngleDegrees) {
                    "wrist_orientation_change"
                } else {
                    "motion_burst"
                },
            )
        }

        val stationary = metrics.immobileSeconds >= MIN_IMMOBILE_SECONDS &&
            metrics.movementRms <= thresholds.stationaryMovementRms &&
            metrics.rotationRms <= thresholds.stationaryRotationRms
        if (stationary) {
            return RuleBasedActivityResult(MotionLabel.STATIONARY, 0.82, "stable_gravity_low_motion")
        }

        val fineHandMotion = metrics.wristRotationCount >= MIN_FINE_HAND_ROTATIONS ||
            metrics.rotationRms >= thresholds.fineHandRotationRms ||
            metrics.movementRms >= thresholds.fineHandMovementRms
        return if (fineHandMotion) {
            RuleBasedActivityResult(MotionLabel.FINE_HAND_MOTION, 0.70, "small_wrist_motion")
        } else {
            RuleBasedActivityResult(MotionLabel.STATIONARY, 0.64, "no_steps_low_motion")
        }
    }

    private const val MIN_WALKING_STEPS = 2
    private const val MIN_TRANSITION_BURSTS = 2
    private const val MIN_IMMOBILE_SECONDS = 22.0
    private const val MIN_FINE_HAND_ROTATIONS = 2
}

/**
 * Learns this owner's normal wrist noise from unlabelled, low-motion windows.
 * Bounds keep calibration conservative, so one unusual window cannot make the
 * classifier insensitive to real posture changes.
 */
class PersonalActivityThresholdCalibrator {
    private var sessionId: String? = null
    private var stableWindows = 0
    private var movementBaseline = 0.10
    private var rotationBaseline = 0.06
    private var orientationNoise = 3.0

    fun classify(sessionId: String, metrics: MotionWindowMetrics): RuleBasedActivityResult {
        if (this.sessionId != sessionId) reset(sessionId)
        val result = RuleBasedActivityClassifier.classify(metrics, thresholds())
        if (isStableCalibrationWindow(metrics)) updateBaselines(metrics)
        val calibrated = stableWindows >= REQUIRED_STABLE_WINDOWS
        val confidenceScale = if (calibrated) 1.0 else 0.78
        return result.copy(
            confidence = (result.confidence * confidenceScale).coerceIn(0.0, 1.0),
            reason = if (calibrated) result.reason else "calibrating_${result.reason}",
            calibrated = calibrated,
            calibrationWindows = stableWindows,
        )
    }

    internal fun thresholds(): RuleBasedActivityThresholds {
        val stationaryMovement = (movementBaseline * 2.5).coerceIn(0.18, 0.50)
        val stationaryRotation = (rotationBaseline * 2.5).coerceIn(0.12, 0.40)
        return RuleBasedActivityThresholds(
            postureAngleDegrees = (12.0 + orientationNoise * 3.0).coerceIn(20.0, 40.0),
            transitionRotationRms = (rotationBaseline * 6.0).coerceIn(0.55, 1.10),
            transitionMovementRms = (movementBaseline * 6.0).coerceIn(0.65, 1.40),
            stationaryMovementRms = stationaryMovement,
            stationaryRotationRms = stationaryRotation,
            fineHandRotationRms = maxOf(stationaryRotation * 1.25, rotationBaseline * 3.5)
                .coerceIn(0.16, 0.55),
            fineHandMovementRms = maxOf(stationaryMovement * 1.25, movementBaseline * 3.5)
                .coerceIn(0.25, 0.70),
        )
    }

    private fun isStableCalibrationWindow(metrics: MotionWindowMetrics): Boolean =
        metrics.stepCount == 0 &&
            metrics.suddenMovementCount == 0 &&
            metrics.immobileSeconds >= 20.0 &&
            metrics.movementRms <= 0.60 &&
            metrics.rotationRms <= 0.45 &&
            metrics.orientationChangeDegrees <= 18.0

    private fun updateBaselines(metrics: MotionWindowMetrics) {
        val alpha = if (stableWindows == 0) 1.0 else 0.25
        movementBaseline = ema(movementBaseline, metrics.movementRms, alpha).coerceIn(0.04, 0.30)
        rotationBaseline = ema(rotationBaseline, metrics.rotationRms, alpha).coerceIn(0.02, 0.22)
        orientationNoise = ema(orientationNoise, metrics.orientationChangeDegrees, alpha).coerceIn(1.0, 9.0)
        stableWindows++
    }

    private fun reset(newSessionId: String) {
        sessionId = newSessionId
        stableWindows = 0
        movementBaseline = 0.10
        rotationBaseline = 0.06
        orientationNoise = 3.0
    }

    private fun ema(previous: Double, sample: Double, alpha: Double): Double =
        previous * (1.0 - alpha) + sample * alpha

    companion object {
        const val REQUIRED_STABLE_WINDOWS = 3
    }
}
