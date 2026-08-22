package vn.edu.uit.tpkd.wear.cogload.protocol

import java.util.Base64

data class FacePointQ16(val xQ16: Int, val yQ16: Int) {
    init {
        require(xQ16 in 0..GattProfile.Q16_MAX)
        require(yQ16 in 0..GattProfile.Q16_MAX)
    }

    val x: Double get() = xQ16.toDouble() / GattProfile.Q16_MAX
    val y: Double get() = yQ16.toDouble() / GattProfile.Q16_MAX
}

/** Public 32-byte HTTP FaceMetaV1 sidecar. This is not the firmware broker's internal struct. */
data class FaceMetaV1(
    val flags: Int,
    val confidenceQ16: Int,
    val centerXQ16: Int,
    val centerYQ16: Int,
    val widthQ16: Int,
    val heightQ16: Int,
    val keypoints: List<FacePointQ16>,
) {
    init {
        require(flags in 0..GattProfile.Q16_MAX)
        require(confidenceQ16 in 0..GattProfile.Q16_MAX)
        require(centerXQ16 in 0..GattProfile.Q16_MAX)
        require(centerYQ16 in 0..GattProfile.Q16_MAX)
        require(widthQ16 in 0..GattProfile.Q16_MAX)
        require(heightQ16 in 0..GattProfile.Q16_MAX)
        require(keypoints.size == GattProfile.FACE_META_V1_KEYPOINTS)
    }

    val faceDetected: Boolean get() = flags and GattProfile.FACE_META_V1_FLAG_FACE_DETECTED != 0
    val transportCompatible: Boolean get() = flags and GattProfile.FACE_META_V1_FLAG_RESERVED_MASK == 0
    val semanticallyValid: Boolean
        get() = transportCompatible && if (faceDetected) {
            widthQ16 > 0 && heightQ16 > 0
        } else {
            confidenceQ16 == 0 && centerXQ16 == 0 && centerYQ16 == 0 &&
                widthQ16 == 0 && heightQ16 == 0 && keypoints.all { it.xQ16 == 0 && it.yQ16 == 0 }
        }

    val confidence: Double get() = confidenceQ16.toDouble() / GattProfile.Q16_MAX
    val centerX: Double get() = centerXQ16.toDouble() / GattProfile.Q16_MAX
    val centerY: Double get() = centerYQ16.toDouble() / GattProfile.Q16_MAX
    val width: Double get() = widthQ16.toDouble() / GattProfile.Q16_MAX
    val height: Double get() = heightQ16.toDouble() / GattProfile.Q16_MAX

    fun encode(): ByteArray {
        val words = buildList {
            add(flags)
            add(confidenceQ16)
            add(centerXQ16)
            add(centerYQ16)
            add(widthQ16)
            add(heightQ16)
            keypoints.forEach { point ->
                add(point.xQ16)
                add(point.yQ16)
            }
        }
        return ByteArray(GattProfile.FACE_META_V1_BYTES).also { bytes ->
            words.forEachIndexed { index, value ->
                bytes[index * 2] = (value and 0xFF).toByte()
                bytes[index * 2 + 1] = (value ushr 8).toByte()
            }
        }
    }

    fun encodeHeader(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

    companion object {
        @JvmStatic
        fun parseHeader(value: String): FaceMetaV1 {
            require(value.length == GattProfile.FACE_META_V1_HEADER_CHARS &&
                value.all { character ->
                    character in 'A'..'Z' || character in 'a'..'z' ||
                        character in '0'..'9' || character == '-' || character == '_'
                }
            ) { "FaceMetaV1 must be canonical unpadded base64url" }
            val bytes = runCatching { Base64.getUrlDecoder().decode(value) }
                .getOrElse { throw IllegalArgumentException("invalid FaceMetaV1 base64url", it) }
            require(bytes.size == GattProfile.FACE_META_V1_BYTES)
            val parsed = parse(bytes)
            require(parsed.encodeHeader() == value) { "non-canonical FaceMetaV1 base64url" }
            return parsed
        }

        @JvmStatic
        fun parse(bytes: ByteArray): FaceMetaV1 {
            require(bytes.size == GattProfile.FACE_META_V1_BYTES) {
                "FaceMetaV1 must be exactly ${GattProfile.FACE_META_V1_BYTES} bytes"
            }
            val words = IntArray(16) { index ->
                (bytes[index * 2].toInt() and 0xFF) or
                    ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)
            }
            return FaceMetaV1(
                flags = words[0],
                confidenceQ16 = words[1],
                centerXQ16 = words[2],
                centerYQ16 = words[3],
                widthQ16 = words[4],
                heightQ16 = words[5],
                keypoints = List(GattProfile.FACE_META_V1_KEYPOINTS) { index ->
                    FacePointQ16(words[6 + index * 2], words[7 + index * 2])
                },
            )
        }
    }
}
