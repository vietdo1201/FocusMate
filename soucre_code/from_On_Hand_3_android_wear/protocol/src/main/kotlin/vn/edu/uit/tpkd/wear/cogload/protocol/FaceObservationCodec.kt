package vn.edu.uit.tpkd.wear.cogload.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Codec canonical cho [FaceObservationV1]. Xem ADR 0004 và `docs/GATT_PROFILE.md` mục 7.
 *
 * [encode] là **total** và canonicalize; [decode] là **strict** và reject mọi payload
 * không canonical. Nhờ vậy wire format không phụ thuộc bản `org.json` đang chạy:
 * lệch thứ tự key, duplicate key, coerce `"true"`, comment, byte rác sau `}` và số
 * không canonical đều bị một phép so byte duy nhất chặn lại.
 */
object FaceObservationCodec {

    fun encode(observation: FaceObservationV1): ByteArray {
        val canonical = observation.canonical()
        val builder = StringBuilder(MAX_WORST_CASE_BYTES)
        builder.append('{')
        builder.append("\"schema_version\":\"").append(FaceObservationV1.SCHEMA_VERSION).append('"')
        builder.append(",\"sequence\":").append(canonical.sequence)
        builder.append(",\"esp_uptime_ms\":").append(canonical.espUptimeMs)
        builder.append(",\"face_detected\":").append(if (canonical.faceDetected) "true" else "false")
        if (canonical.faceDetected) {
            builder.appendUnit("cx", canonical.centerX)
            builder.appendUnit("cy", canonical.centerY)
            builder.appendUnit("width", canonical.width)
            builder.appendUnit("height", canonical.height)
            builder.appendUnit("area", canonical.area)
            builder.appendUnit("confidence", canonical.confidence)
        }
        builder.append(",\"quality_flags\":[")
        canonical.qualityFlags.sorted().forEachIndexed { index, flag ->
            if (index > 0) builder.append(',')
            builder.append('"').append(flag).append('"')
        }
        builder.append("]}")
        val payload = builder.toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= FaceObservationV1.MAX_PAYLOAD_BYTES) {
            "payload too large: ${payload.size} > ${FaceObservationV1.MAX_PAYLOAD_BYTES}"
        }
        return payload
    }

    /**
     * @throws IllegalArgumentException khi payload vi phạm contract hoặc không canonical.
     * @throws JSONException khi payload không parse được. Dùng [tryDecode] để không
     *   phải viết hai `catch` (JSONException là `RuntimeException` trên JVM nhưng
     *   `Exception` trên Android).
     */
    fun decode(payload: ByteArray): FaceObservationV1 {
        require(payload.size in 1..FaceObservationV1.MAX_PAYLOAD_BYTES) { "invalid payload size" }
        val root = JSONObject(payload.decodeStrictUtf8())
        require(root.optString("schema_version", "") == FaceObservationV1.SCHEMA_VERSION) {
            "unsupported schema"
        }
        require(root.keys().asSequence().all { it in FaceObservationV1.CANONICAL_KEY_ORDER }) {
            "unknown field"
        }
        val detected = root.requireBoolean("face_detected")
        val decoded = FaceObservationV1(
            sequence = root.requireLong("sequence"),
            espUptimeMs = root.requireLong("esp_uptime_ms"),
            faceDetected = detected,
            centerX = root.optionalDouble("cx", detected),
            centerY = root.optionalDouble("cy", detected),
            width = root.optionalDouble("width", detected),
            height = root.optionalDouble("height", detected),
            area = root.optionalDouble("area", detected),
            confidence = root.optionalDouble("confidence", detected),
            qualityFlags = root.requireFlags(),
        )
        require(encode(decoded).contentEquals(payload)) { "payload is not canonical" }
        return decoded
    }

    /** Hợp nhất hai họ exception thành một [Result] cho caller BLE. */
    fun tryDecode(payload: ByteArray): Result<FaceObservationV1> =
        try {
            Result.success(decode(payload))
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: JSONException) {
            Result.failure(error)
        }

    private fun ByteArray.decodeStrictUtf8(): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("payload is not valid UTF-8", error)
        }

    /** Không dùng `getBoolean`: Android coerce `"true"` thành `true`. */
    private fun JSONObject.requireBoolean(key: String): Boolean {
        val value = opt(key)
        require(value is Boolean) { "$key must be a JSON boolean" }
        return value
    }

    /** Không dùng `getLong`: Android saturate số quá 63 bit, JVM wrap âm. */
    private fun JSONObject.requireLong(key: String): Long {
        val value = opt(key)
        require(value is Number) { "$key must be a JSON number" }
        val asLong = value.toLong()
        require(value.toString() == asLong.toString()) { "$key is not a canonical integer" }
        return asLong
    }

    private fun JSONObject.optionalDouble(key: String, required: Boolean): Double? =
        if (required) {
            val value = opt(key)
            require(value is Number) { "$key must be a JSON number" }
            value.toDouble()
        } else {
            require(!has(key)) { "$key must be absent without a face" }
            null
        }

    private fun JSONObject.requireFlags(): Set<String> {
        val value = opt("quality_flags")
        require(value is JSONArray) { "quality_flags must be a JSON array" }
        val flags = LinkedHashSet<String>(value.length())
        for (index in 0 until value.length()) {
            val element = value.opt(index)
            require(element is String) { "quality_flags element must be a string" }
            require(flags.add(element)) { "duplicate quality flag: $element" }
        }
        return flags
    }

    private fun StringBuilder.appendUnit(key: String, value: Double?) {
        append(',').append('"').append(key).append("\":")
        append(FaceObservationV1.formatUnit(requireNotNull(value) { "$key missing" }))
    }

    /** Worst case hợp lệ đo được ở ADR 0004; chỉ dùng để pre-size builder. */
    private const val MAX_WORST_CASE_BYTES = 320
}
