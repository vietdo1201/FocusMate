package vn.edu.uit.tpkd.wear.cogload.protocol

import org.json.JSONArray
import org.json.JSONObject

data class FaceObservationV1(
    val sequence: Long,
    val espUptimeMs: Long,
    val faceDetected: Boolean,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val area: Double? = null,
    val confidence: Double? = null,
    val qualityFlags: Set<String> = emptySet(),
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative" }
        require(espUptimeMs >= 0L) { "espUptimeMs must be non-negative" }
        require(qualityFlags.size <= MAX_QUALITY_FLAGS) { "too many quality flags" }
        qualityFlags.forEach {
            require(it.matches(QUALITY_FLAG_PATTERN)) { "invalid quality flag" }
        }
        if (faceDetected) {
            requireNotNull(centerX).requireUnit("centerX")
            requireNotNull(centerY).requireUnit("centerY")
            requireNotNull(width).requirePositiveUnit("width")
            requireNotNull(height).requirePositiveUnit("height")
            requireNotNull(area).requirePositiveUnit("area")
            requireNotNull(confidence).requireUnit("confidence")
            require(kotlin.math.abs(area - width * height) <= AREA_TOLERANCE) {
                "area must match normalized width * height"
            }
        } else {
            require(listOf(centerX, centerY, width, height, area, confidence).all { it == null }) {
                "bbox and confidence must be absent when no face is detected"
            }
        }
    }

    private fun Double.requireUnit(name: String) {
        require(isFinite() && this in 0.0..1.0) { "$name must be in 0..1" }
    }

    private fun Double.requirePositiveUnit(name: String) {
        require(isFinite() && this > 0.0 && this <= 1.0) { "$name must be in (0,1]" }
    }

    companion object {
        const val SCHEMA_VERSION = "focusmate_face_observation_v1"
        const val MAX_PAYLOAD_BYTES = 512
        private const val MAX_QUALITY_FLAGS = 8
        private const val AREA_TOLERANCE = 0.000_001
        private val QUALITY_FLAG_PATTERN = Regex("[a-z0-9_]{1,32}")
    }
}

object FaceObservationCodec {
    fun encode(observation: FaceObservationV1): ByteArray {
        val root = JSONObject().apply {
            put("schema_version", FaceObservationV1.SCHEMA_VERSION)
            put("sequence", observation.sequence)
            put("esp_uptime_ms", observation.espUptimeMs)
            put("face_detected", observation.faceDetected)
            if (observation.faceDetected) {
                put("cx", observation.centerX)
                put("cy", observation.centerY)
                put("width", observation.width)
                put("height", observation.height)
                put("area", observation.area)
                put("confidence", observation.confidence)
            }
            put("quality_flags", JSONArray(observation.qualityFlags.sorted()))
        }
        val payload = root.toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= FaceObservationV1.MAX_PAYLOAD_BYTES) { "payload too large" }
        return payload
    }

    fun decode(payload: ByteArray): FaceObservationV1 {
        require(payload.size in 1..FaceObservationV1.MAX_PAYLOAD_BYTES) { "invalid payload size" }
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.getString("schema_version") == FaceObservationV1.SCHEMA_VERSION) {
            "unsupported schema"
        }
        val allowed = setOf(
            "schema_version", "sequence", "esp_uptime_ms", "face_detected",
            "cx", "cy", "width", "height", "area", "confidence", "quality_flags",
        )
        require(root.keys().asSequence().all { it in allowed }) { "unknown field" }
        val detected = root.getBoolean("face_detected")
        val flags = root.optJSONArray("quality_flags") ?: JSONArray()
        return FaceObservationV1(
            sequence = root.getLong("sequence"),
            espUptimeMs = root.getLong("esp_uptime_ms"),
            faceDetected = detected,
            centerX = root.optionalDouble("cx", detected),
            centerY = root.optionalDouble("cy", detected),
            width = root.optionalDouble("width", detected),
            height = root.optionalDouble("height", detected),
            area = root.optionalDouble("area", detected),
            confidence = root.optionalDouble("confidence", detected),
            qualityFlags = buildSet {
                for (index in 0 until flags.length()) add(flags.getString(index))
            },
        )
    }

    private fun JSONObject.optionalDouble(key: String, required: Boolean): Double? =
        if (required) getDouble(key) else {
            require(!has(key) || isNull(key)) { "$key must be absent without a face" }
            null
        }
}

/** Rejects replayed/out-of-order observations while allowing a reset after ESP reboot. */
class FaceSequenceGate {
    private var lastSequence: Long? = null
    private var lastUptimeMs: Long? = null

    fun accept(observation: FaceObservationV1): Boolean {
        val previousSequence = lastSequence
        val previousUptime = lastUptimeMs
        val rebooted = previousUptime != null && observation.espUptimeMs < previousUptime
        if (!rebooted && previousSequence != null && observation.sequence <= previousSequence) return false
        lastSequence = observation.sequence
        lastUptimeMs = observation.espUptimeMs
        return true
    }
}
