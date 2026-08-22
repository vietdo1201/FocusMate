package vn.edu.uit.tpkd.wear.cogload

import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1
import kotlin.math.abs

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
)

data class PostureBaseline(val centerX: Double, val centerY: Double, val area: Double)

class GeometryPostureClassifier(
    private val config: PostureGeometryConfig = PostureGeometryConfig(),
) : PostureClassifier {
    private var baseline: PostureBaseline? = null
    private var slumpedSinceMs: Long? = null

    fun isCalibrationCandidate(observation: FaceObservationV1): Boolean =
        observation.faceDetected &&
            (observation.confidence ?: 0.0) >= config.minimumCalibrationDetectorConfidence &&
            "unstable" !in observation.qualityFlags &&
            "low_light" !in observation.qualityFlags

    fun calibrate(observations: List<FaceObservationV1>): Boolean {
        val valid = observations.filter(::isCalibrationCandidate).takeLast(config.calibrationSamples)
        if (valid.size < config.calibrationSamples) return false
        val xs = valid.map { requireNotNull(it.centerX) }
        val ys = valid.map { requireNotNull(it.centerY) }
        val areas = valid.map { requireNotNull(it.area) }
        val cx = xs.median()
        val cy = ys.median()
        val area = areas.median()
        if ((xs.max() - xs.min()) > config.calibrationCenterSpread ||
            (ys.max() - ys.min()) > config.calibrationCenterSpread ||
            (areas.max() - areas.min()) / area > config.calibrationAreaSpreadRatio
        ) return false
        baseline = PostureBaseline(cx, cy, area)
        slumpedSinceMs = null
        return true
    }

    fun isCalibrated(): Boolean = baseline != null

    fun baseline(): PostureBaseline? = baseline

    /** Baseline is session- and boot-scoped; never carry it across either boundary. */
    fun reset() {
        baseline = null
        slumpedSinceMs = null
    }

    override fun classify(observation: FaceObservationV1, observedAtMs: Long): PostureClassification {
        require(observedAtMs >= 0L)
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
        val dx = requireNotNull(observation.centerX) - reference.centerX
        val dy = requireNotNull(observation.centerY) - reference.centerY
        val areaRatio = requireNotNull(observation.area) / reference.area
        val state = when {
            areaRatio >= config.tooCloseAreaRatio -> {
                slumpedSinceMs = null
                PostureState.TOO_CLOSE
            }
            dy >= config.slumpedEnterDelta -> {
                val since = slumpedSinceMs ?: observedAtMs.also { slumpedSinceMs = it }
                if (observedAtMs - since >= config.slumpedMinimumMs) PostureState.SLUMPED else PostureState.HEAD_DOWN
            }
            dy >= config.headDownEnterDelta -> {
                slumpedSinceMs = null
                PostureState.HEAD_DOWN
            }
            dx <= -config.leanEnterDelta -> {
                slumpedSinceMs = null
                PostureState.LEAN_LEFT
            }
            dx >= config.leanEnterDelta -> {
                slumpedSinceMs = null
                PostureState.LEAN_RIGHT
            }
            else -> {
                slumpedSinceMs = null
                PostureState.NORMAL
            }
        }
        val geometryConfidence = when (state) {
            PostureState.TOO_CLOSE -> ((areaRatio - 1.0) / (config.tooCloseAreaRatio - 1.0)).coerceIn(0.0, 1.0)
            PostureState.HEAD_DOWN, PostureState.SLUMPED -> (dy / config.headDownEnterDelta).coerceIn(0.0, 1.0)
            PostureState.LEAN_LEFT, PostureState.LEAN_RIGHT -> (abs(dx) / config.leanEnterDelta).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        return result(state, observedAtMs, minOf(detectorConfidence, geometryConfidence))
    }

    private fun result(state: PostureState, atMs: Long, confidence: Double) =
        PostureClassification(state, atMs, confidence.coerceIn(0.0, 1.0), SOURCE)

    private fun List<Double>.median(): Double {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    companion object {
        const val SOURCE = "watch_geometry_v1_experimental"
    }
}
