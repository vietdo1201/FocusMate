package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Nội dung characteristic Device Info / Capability. Struct little-endian 34 byte,
 * đặc tả ở `docs/GATT_PROFILE.md` mục 4.
 */
data class EspDeviceInfo(
    val protocolVersion: Int,
    val framingVersion: Int,
    val bootIdHex: String,
    val espUptimeMs: Long,
    val maxQualityFlags: Int,
    val maxFlagLength: Int,
    val nominalRateDhz: Int,
    val capabilityBits: Int,
) {
    init {
        require(bootIdHex.matches(BOOT_ID_PATTERN)) { "boot_id must be 32 lowercase hex chars" }
        require(espUptimeMs in 0L..FaceObservationV1.MAX_UPTIME_MS) { "invalid esp_uptime_ms" }
    }

    val detectorReady: Boolean get() = capabilityBits and GattProfile.CAP_DETECTOR_READY != 0
    val cameraReady: Boolean get() = capabilityBits and GattProfile.CAP_CAMERA_READY != 0
    val supportsSetRate: Boolean get() = capabilityBits and GattProfile.CAP_SET_RATE != 0
    val reportsLowLight: Boolean get() = capabilityBits and GattProfile.CAP_REPORTS_LOW_LIGHT != 0
    val reportsUnstable: Boolean get() = capabilityBits and GattProfile.CAP_REPORTS_UNSTABLE != 0
    val supportsLocalFrameV1: Boolean get() = capabilityBits and GattProfile.CAP_LOCAL_FRAME_V1 != 0

    /** Byte layout và giới hạn mà decoder v1 có thể xử lý mà không phỏng đoán. */
    val transportCompatible: Boolean
        get() = protocolVersion == GattProfile.PROTOCOL_VERSION &&
            framingVersion == GattProfile.FRAMING_VERSION &&
            maxQualityFlags == FaceObservationV1.MAX_QUALITY_FLAGS &&
            maxFlagLength == FaceObservationV1.MAX_QUALITY_FLAG_LENGTH &&
            nominalRateDhz in GattProfile.MIN_RATE_DHZ..GattProfile.MAX_RATE_DHZ &&
            capabilityBits and GattProfile.CAP_RESERVED_MASK == 0

    /** Capability tối thiểu để pipeline posture có nghĩa. Thiếu thì phải báo unavailable. */
    val usable: Boolean
        get() = transportCompatible && detectorReady && cameraReady

    fun encode(): ByteArray {
        val bytes = ByteArray(GattProfile.DEVICE_INFO_BYTES)
        bytes.putLe(0, protocolVersion.toLong(), 2)
        bytes[2] = framingVersion.toByte()
        bootIdHex.chunked(2).forEachIndexed { index, pair ->
            bytes[3 + index] = pair.toInt(16).toByte()
        }
        bytes.putLe(19, espUptimeMs, 8)
        bytes[27] = maxQualityFlags.toByte()
        bytes[28] = maxFlagLength.toByte()
        bytes[29] = nominalRateDhz.toByte()
        bytes.putLe(30, capabilityBits.toLong() and 0xFFFF_FFFFL, 4)
        return bytes
    }

    companion object {
        private val BOOT_ID_PATTERN = Regex("[0-9a-f]{32}")
        private const val HEX = "0123456789abcdef"

        fun parse(bytes: ByteArray): EspDeviceInfo {
            require(bytes.size == GattProfile.DEVICE_INFO_BYTES) {
                "device info must be exactly ${GattProfile.DEVICE_INFO_BYTES} bytes"
            }
            val bootId = StringBuilder(GattProfile.BOOT_ID_BYTES * 2)
            for (index in 0 until GattProfile.BOOT_ID_BYTES) {
                val value = bytes[3 + index].toInt() and 0xFF
                bootId.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return EspDeviceInfo(
                protocolVersion = bytes.readLe(0, 2).toInt(),
                framingVersion = bytes[2].toInt() and 0xFF,
                bootIdHex = bootId.toString(),
                espUptimeMs = bytes.readLe(19, 8),
                maxQualityFlags = bytes[27].toInt() and 0xFF,
                maxFlagLength = bytes[28].toInt() and 0xFF,
                nominalRateDhz = bytes[29].toInt() and 0xFF,
                capabilityBits = bytes.readLe(30, 4).toInt(),
            )
        }

        private fun ByteArray.readLe(offset: Int, size: Int): Long {
            var value = 0L
            for (index in size - 1 downTo 0) {
                value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
            }
            require(value >= 0L) { "unsigned overflow at offset $offset" }
            return value
        }

        private fun ByteArray.putLe(offset: Int, value: Long, size: Int) {
            for (index in 0 until size) {
                this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
            }
        }
    }
}
