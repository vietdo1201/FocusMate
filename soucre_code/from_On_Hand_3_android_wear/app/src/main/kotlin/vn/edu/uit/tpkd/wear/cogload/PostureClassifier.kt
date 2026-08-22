package vn.edu.uit.tpkd.wear.cogload

import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1
import kotlin.math.abs
import kotlin.math.roundToLong

enum class PostureState {
    NORMAL,
    HEAD_DOWN,
    LEAN_LEFT,
    LEAN_RIGHT,
    TOO_CLOSE,
    SLUMPED,
    FACE_MISSING,
    UNKNOWN,
}

data class PostureClassification(
    val state: PostureState,
    val observedAtMs: Long,
    val confidence: Double,
    val source: String,
)

interface PostureClassifier {
    fun classify(observation: FaceObservationV1, observedAtMs: Long): PostureClassification
}

/** Optional future model contract. Implementations must initially run in shadow mode only. */
interface PostureShadowModel {
    fun predict(observations: List<FaceObservationV1>): PostureClassification?
}

data class PostureGeometryConfig(
    val minimumLiveDetectorConfidence: Double = 0.50,
    val minimumCalibrationDetectorConfidence: Double = 0.70,
    val leanEnterDelta: Double = 0.15,
    val headDownEnterDelta: Double = 0.12,
    val slumpedEnterDelta: Double = 0.18,
    val tooCloseAreaRatio: Double = 1.60,
    val slumpedMinimumMs: Long = 5_000L,
    val calibrationSamples: Int = 20,
    val calibrationCenterSpread: Double = 0.04,
    val calibrationAreaSpreadRatio: Double = 0.20,
    val calibrationCropLeft: Double = 0.145,
    val calibrationCropRight: Double = 0.855,
    val calibrationCropTop: Double = 0.020,
    val calibrationCropBottom: Double = 0.980,
    val maximumContinuousGapMs: Long = 3_000L,
)

data class PostureBaseline(val centerX: Double, val centerY: Double, val area: Double)

class GeometryPostureClassifier(
    private val config: PostureGeometryConfig = PostureGeometryConfig(),
) : PostureClassifier {
    private var baseline: PostureBaseline? = null
    private var slumpedSinceMs: Long? = null
    private var lastObservedAtMs: Long? = null

    fun isCalibrationCandidate(observation: FaceObservationV1): Boolean =
        observation.faceDetected &&
            (observation.confidence ?: 0.0) >= config.minimumCalibrationDetectorConfidence &&
            "unstable" !in observation.qualityFlags &&
            "low_light" !in observation.qualityFlags &&
            calibrationBboxFullyVisible(observation)

    fun calibrate(observations: List<FaceObservationV1>): Boolean {
        val valid = observations.filter(::isCalibrationCandidate).takeLast(config.calibrationSamples)
        if (valid.size < config.calibrationSamples) return false
        val xs = valid.map { FaceObservationV1.toScaledUnit(requireNotNull(it.centerX)) }
        val ys = valid.map { FaceObservationV1.toScaledUnit(requireNotNull(it.centerY)) }
        val areas = valid.map { FaceObservationV1.toScaledUnit(requireNotNull(it.area)) }
        val cx = xs.medianQ6()
        val cy = ys.medianQ6()
        val area = areas.medianQ6()
        if ((xs.max() - xs.min()) > unitThresholdQ6(config.calibrationCenterSpread) ||
            (ys.max() - ys.min()) > unitThresholdQ6(config.calibrationCenterSpread) ||
            ratioQ6(areas.max() - areas.min(), area) > unitThresholdQ6(config.calibrationAreaSpreadRatio)
        ) return false
        baseline = PostureBaseline(cx.toUnit(), cy.toUnit(), area.toUnit())
        resetTemporalState()
        return true
    }

    fun isCalibrated(): Boolean = baseline != null

    fun baseline(): PostureBaseline? = baseline

    /** Baseline is session- and boot-scoped; never carry it across either boundary. */
    fun reset() {
        baseline = null
        resetTemporalState()
    }

    /** Keep a valid baseline while breaking all temporal continuity. */
    fun resetTemporalState() {
        slumpedSinceMs = null
        lastObservedAtMs = null
    }

    override fun classify(observation: FaceObservationV1, observedAtMs: Long): PostureClassification {
        require(observedAtMs >= 0L)
        val previousObservedAt = lastObservedAtMs
        if (previousObservedAt != null &&
            (observedAtMs <= previousObservedAt || observedAtMs - previousObservedAt > config.maximumContinuousGapMs)
        ) {
            slumpedSinceMs = null
        }
        lastObservedAtMs = observedAtMs
        if (!observation.faceDetected) {
            slumpedSinceMs = null
            return result(PostureState.FACE_MISSING, observedAtMs, 1.0)
        }
        val detectorConfidence = observation.confidence ?: 0.0
        if (detectorConfidence < config.minimumLiveDetectorConfidence ||
            "unstable" in observation.qualityFlags || "low_light" in observation.qualityFlags
        ) {
            slumpedSinceMs = null
            return result(PostureState.UNKNOWN, observedAtMs, detectorConfidence)
        }
        val reference = baseline ?: run {
            slumpedSinceMs = null
            return result(PostureState.UNKNOWN, observedAtMs, detectorConfidence)
        }
        // The upright camera is not a selfie mirror: the seated person's left
        // appears on the image's right. Keep dx semantic from their view so
        // negative means their left and positive means their right.
        val baselineCxQ6 = FaceObservationV1.toScaledUnit(reference.centerX)
        val baselineCyQ6 = FaceObservationV1.toScaledUnit(reference.centerY)
        val baselineAreaQ6 = FaceObservationV1.toScaledUnit(reference.area)
        val observedCxQ6 = FaceObservationV1.toScaledUnit(requireNotNull(observation.centerX))
        val observedCyQ6 = FaceObservationV1.toScaledUnit(requireNotNull(observation.centerY))
        val observedAreaQ6 = FaceObservationV1.toScaledUnit(requireNotNull(observation.area))
        val dxQ6 = baselineCxQ6 - observedCxQ6
        val dyQ6 = observedCyQ6 - baselineCyQ6
        val areaRatioQ6 = ratioQ6(observedAreaQ6, baselineAreaQ6)
        val leanDeltaQ6 = unitThresholdQ6(config.leanEnterDelta)
        val headDownDeltaQ6 = unitThresholdQ6(config.headDownEnterDelta)
        val slumpedDeltaQ6 = unitThresholdQ6(config.slumpedEnterDelta)
        val tooCloseRatioQ6 = scaledNonNegative(config.tooCloseAreaRatio)
        val lateralQ6 = abs(dxQ6)
        val leanCandidate = lateralQ6 >= leanDeltaQ6
        val headCandidate = dyQ6 >= headDownDeltaQ6
        val leanDominant = leanCandidate &&
            (!headCandidate || lateralQ6 * headDownDeltaQ6 >= dyQ6 * leanDeltaQ6)
        val state = when {
            areaRatioQ6 >= tooCloseRatioQ6 -> {
                slumpedSinceMs = null
                PostureState.TOO_CLOSE
            }
            leanDominant -> {
                slumpedSinceMs = null
                if (dxQ6 < 0L) PostureState.LEAN_LEFT else PostureState.LEAN_RIGHT
            }
            dyQ6 >= slumpedDeltaQ6 -> {
                val since = slumpedSinceMs ?: observedAtMs.also { slumpedSinceMs = it }
                if (observedAtMs - since >= config.slumpedMinimumMs) PostureState.SLUMPED else PostureState.HEAD_DOWN
            }
            dyQ6 >= headDownDeltaQ6 -> {
                slumpedSinceMs = null
                PostureState.HEAD_DOWN
            }
            dxQ6 <= -leanDeltaQ6 -> {
                slumpedSinceMs = null
                PostureState.LEAN_LEFT
            }
            dxQ6 >= leanDeltaQ6 -> {
                slumpedSinceMs = null
                PostureState.LEAN_RIGHT
            }
            else -> {
                slumpedSinceMs = null
                PostureState.NORMAL
            }
        }
        val geometryConfidenceQ6 = when (state) {
            PostureState.TOO_CLOSE -> if (areaRatioQ6 <= SCALE) 0L else
                (areaRatioQ6 - SCALE) * SCALE / (tooCloseRatioQ6 - SCALE)
            PostureState.HEAD_DOWN, PostureState.SLUMPED -> if (dyQ6 <= 0L) 0L else
                dyQ6 * SCALE / headDownDeltaQ6
            PostureState.LEAN_LEFT, PostureState.LEAN_RIGHT -> lateralQ6 * SCALE / leanDeltaQ6
            else -> SCALE
        }
        val detectorConfidenceQ6 = FaceObservationV1.toScaledUnit(detectorConfidence)
        return result(
            state,
            observedAtMs,
            minOf(detectorConfidenceQ6, geometryConfidenceQ6.coerceAtMost(SCALE)).toUnit(),
        )
    }

    private fun result(state: PostureState, atMs: Long, confidence: Double) =
        PostureClassification(state, atMs, confidence.coerceIn(0.0, 1.0), SOURCE)

    private fun calibrationBboxFullyVisible(observation: FaceObservationV1): Boolean {
        val cx = FaceObservationV1.toScaledUnit(requireNotNull(observation.centerX))
        val cy = FaceObservationV1.toScaledUnit(requireNotNull(observation.centerY))
        val width = FaceObservationV1.toScaledUnit(requireNotNull(observation.width))
        val height = FaceObservationV1.toScaledUnit(requireNotNull(observation.height))
        return 2L * cx - width >= 2L * unitThresholdQ6(config.calibrationCropLeft) &&
            2L * cx + width <= 2L * unitThresholdQ6(config.calibrationCropRight) &&
            2L * cy - height >= 2L * unitThresholdQ6(config.calibrationCropTop) &&
            2L * cy + height <= 2L * unitThresholdQ6(config.calibrationCropBottom)
    }

    private fun List<Long>.medianQ6(): Long {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2L else sorted[middle]
    }

    private fun unitThresholdQ6(value: Double): Long = FaceObservationV1.toScaledUnit(value)

    private fun scaledNonNegative(value: Double): Long {
        require(value.isFinite() && value >= 0.0)
        return (value * SCALE).roundToLong()
    }

    private fun ratioQ6(numerator: Long, denominator: Long): Long {
        if (denominator <= 0L) return 0L
        return ((numerator * SCALE + denominator / 2L) / denominator).coerceAtMost(UINT32_MAX)
    }

    private fun Long.toUnit(): Double = toDouble() / SCALE

    companion object {
        const val SOURCE = "watch_geometry_v2_experimental"
        private const val SCALE = FaceObservationV1.WIRE_SCALE
        private const val UINT32_MAX = 4_294_967_295L
    }
}
