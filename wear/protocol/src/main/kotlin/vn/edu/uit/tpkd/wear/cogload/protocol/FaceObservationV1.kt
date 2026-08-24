// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

import kotlin.math.roundToLong

/**
 * Bbox-only observation phát bởi ESP32-S3 detector.
 *
 * Wire format canonical được chốt ở ADR 0004 và đặc tả ở `docs/GATT_PROFILE.md`.
 * Class này tuyệt đối không chở frame, crop, landmark, embedding hay identifier.
 */
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
        require(sequence in 0L..MAX_SEQUENCE) { "sequence must be in 0..$MAX_SEQUENCE" }
        require(espUptimeMs in 0L..MAX_UPTIME_MS) { "espUptimeMs must be in 0..$MAX_UPTIME_MS" }
        require(qualityFlags.size <= MAX_QUALITY_FLAGS) { "too many quality flags" }
        qualityFlags.forEach {
            require(it.matches(QUALITY_FLAG_PATTERN)) { "invalid quality flag" }
        }
        EXCLUSIVE_QUALITY_FLAG_PAIRS.forEach { (a, b) ->
            require(!(a in qualityFlags && b in qualityFlags)) { "mutually exclusive flags: $a + $b" }
        }
        if (faceDetected) {
            requireNotNull(centerX).requireUnit("centerX")
            requireNotNull(centerY).requireUnit("centerY")
            requireNotNull(width).requireEdge("width")
            requireNotNull(height).requireEdge("height")
            requireNotNull(area).requireArea()
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

    /**
     * Dạng canonical: mọi số thực quantize về [NUMBER_SCALE] chữ số thập phân và
     * `area` được **dẫn xuất** từ width/height đã quantize. Idempotent.
     */
    fun canonical(): FaceObservationV1 {
        if (!faceDetected) return this
        val quantizedWidth = quantize(requireNotNull(width))
        val quantizedHeight = quantize(requireNotNull(height))
        return copy(
            centerX = quantize(requireNotNull(centerX)),
            centerY = quantize(requireNotNull(centerY)),
            width = quantizedWidth,
            height = quantizedHeight,
            area = deriveArea(quantizedWidth, quantizedHeight),
            confidence = quantize(requireNotNull(confidence)),
        )
    }

    val isCanonical: Boolean get() = this == canonical()

    /** Flag duy nhất có ý nghĩa hành vi cho classifier. Flag khác chỉ là metadata. */
    val isDegraded: Boolean get() = qualityFlags.any { it in BEHAVIORAL_QUALITY_FLAGS }

    private fun Double.requireUnit(name: String) {
        require(isFinite() && this in 0.0..1.0) { "$name must be in 0..1" }
    }

    private fun Double.requireEdge(name: String) {
        require(isFinite() && this >= MIN_BBOX_EDGE && this <= 1.0) {
            "$name must be in [$MIN_BBOX_EDGE, 1]"
        }
    }

    private fun Double.requireArea() {
        require(isFinite() && this >= MIN_BBOX_AREA && this <= 1.0) {
            "area must be in [$MIN_BBOX_AREA, 1]"
        }
    }

    companion object {
        const val SCHEMA_VERSION = "focusmate_face_observation_v1"
        const val MAX_PAYLOAD_BYTES = 512

        /** Ngân sách flag chốt ở ADR 0004: 8x32 cho worst case 521 byte, vượt cap 512. */
        const val MAX_QUALITY_FLAGS = 4
        const val MAX_QUALITY_FLAG_LENGTH = 16

        /** Số chữ số thập phân cố định của mọi trường bbox/confidence trên wire. */
        const val NUMBER_SCALE = 6
        const val AREA_TOLERANCE = 0.000_001

        /** Cạnh bbox nhỏ nhất để `deriveArea` không quantize về 0. */
        const val MIN_BBOX_EDGE = 0.001
        const val MIN_BBOX_AREA = 0.000_001

        /** `sequence` là uint32 monotonic ở tầng GATT. */
        const val MAX_SEQUENCE = 4_294_967_295L

        /** ~31,7 năm uptime; chặn để kích thước payload chứng minh được. */
        const val MAX_UPTIME_MS = 999_999_999_999L

        val QUALITY_FLAG_PATTERN = Regex("[a-z0-9_]{1,$MAX_QUALITY_FLAG_LENGTH}")

        /** Chỉ hai flag này đổi hành vi classifier. Xem `docs/GATT_PROFILE.md` mục 7.5. */
        val BEHAVIORAL_QUALITY_FLAGS = setOf("unstable", "low_light")

        val REGISTERED_QUALITY_FLAGS = setOf(
            "stable", "unstable", "well_lit", "low_light",
            "motion_blur", "partial_face", "multi_face", "sensor_warmup",
        )

        val EXCLUSIVE_QUALITY_FLAG_PAIRS = listOf(
            "stable" to "unstable",
            "well_lit" to "low_light",
        )

        val CANONICAL_KEY_ORDER = listOf(
            "schema_version", "sequence", "esp_uptime_ms", "face_detected",
            "cx", "cy", "width", "height", "area", "confidence", "quality_flags",
        )

        /** Chuyển unit value thành micro-unit nguyên bằng round-half-up. */
        fun toScaledUnit(value: Double): Long {
            require(value.isFinite() && value in 0.0..1.0) { "value must be in 0..1" }
            return (value * WIRE_SCALE).roundToLong().coerceIn(0L, WIRE_SCALE)
        }

        fun quantize(value: Double): Double = toScaledUnit(value).toDouble() / WIRE_SCALE

        /** Chuỗi canonical luôn có đúng [NUMBER_SCALE] chữ số thập phân. */
        fun formatUnit(value: Double): String = formatScaledUnit(toScaledUnit(value))

        fun formatScaledUnit(scaled: Long): String {
            require(scaled in 0L..WIRE_SCALE) { "scaled unit must be in 0..$WIRE_SCALE" }
            val whole = scaled / WIRE_SCALE
            val fraction = (scaled % WIRE_SCALE).toString().padStart(NUMBER_SCALE, '0')
            return "$whole.$fraction"
        }

        fun deriveArea(width: Double, height: Double): Double {
            val widthScaled = toScaledUnit(width)
            val heightScaled = toScaledUnit(height)
            val areaScaled = (widthScaled * heightScaled + WIRE_SCALE / 2) / WIRE_SCALE
            return areaScaled.toDouble() / WIRE_SCALE
        }

        const val WIRE_SCALE = 1_000_000L
    }
}
