// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Discovery credential for LOCAL_FRAME_V1. The encrypted GATT value is exactly
 * 40 bytes; JPEG bytes never travel in this characteristic.
 */
data class FrameAccessInfoV1(
    val version: Int,
    val flags: Int,
    val httpPort: Int,
    val ipv4: String,
    val bootIdHex: String,
    val tokenHex: String,
) {
    init {
        require(version in 0..0xFF) { "version must fit u8" }
        require(flags in 0..0xFF) { "flags must fit u8" }
        require(httpPort in 0..0xFFFF) { "http_port must fit uint16" }
        require(parseIpv4(ipv4) != null) { "ipv4 must be canonical dotted decimal" }
        require(bootIdHex.matches(HEX_128)) { "boot_id must be 32 lowercase hex chars" }
        require(tokenHex.matches(HEX_128)) { "token must be 32 lowercase hex chars" }
    }

    val lanReady: Boolean get() = flags and GattProfile.FRAME_ACCESS_FLAG_LAN_READY != 0
    val tokenAuthRequired: Boolean
        get() = flags and GattProfile.FRAME_ACCESS_FLAG_TOKEN_AUTH_REQUIRED != 0
    val faceMetaV1: Boolean get() = flags and GattProfile.FRAME_ACCESS_FLAG_FACE_META_V1 != 0

    /** Layout can be interpreted without guessing; availability is checked separately. */
    val transportCompatible: Boolean
        get() = version == GattProfile.FRAME_ACCESS_INFO_VERSION &&
            flags and GattProfile.FRAME_ACCESS_FLAG_RESERVED_MASK == 0

    /** Safe endpoint requirements for the Watch frame client. */
    val usable: Boolean
        get() = transportCompatible && lanReady && tokenAuthRequired && faceMetaV1 &&
            httpPort != 0 && ipv4 != UNSPECIFIED_IPV4 && ipv4 != LIMITED_BROADCAST_IPV4 &&
            tokenHex != ZERO_128

    fun encode(): ByteArray {
        val result = ByteArray(GattProfile.FRAME_ACCESS_INFO_BYTES)
        result[0] = version.toByte()
        result[1] = flags.toByte()
        result[2] = (httpPort and 0xFF).toByte()
        result[3] = (httpPort ushr 8).toByte()
        requireNotNull(parseIpv4(ipv4)).forEachIndexed { index, octet ->
            result[4 + index] = octet.toByte()
        }
        putHex(result, 8, bootIdHex)
        putHex(result, 24, tokenHex)
        return result
    }

    companion object {
        private val HEX_128 = Regex("[0-9a-f]{32}")
        private const val HEX = "0123456789abcdef"
        private const val ZERO_128 = "00000000000000000000000000000000"
        private const val UNSPECIFIED_IPV4 = "0.0.0.0"
        private const val LIMITED_BROADCAST_IPV4 = "255.255.255.255"

        @JvmStatic
        fun parse(bytes: ByteArray): FrameAccessInfoV1 {
            require(bytes.size == GattProfile.FRAME_ACCESS_INFO_BYTES) {
                "frame access info must be exactly ${GattProfile.FRAME_ACCESS_INFO_BYTES} bytes"
            }
            return FrameAccessInfoV1(
                version = bytes[0].toInt() and 0xFF,
                flags = bytes[1].toInt() and 0xFF,
                httpPort = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8),
                ipv4 = (4 until 8).joinToString(".") { (bytes[it].toInt() and 0xFF).toString() },
                bootIdHex = readHex(bytes, 8, GattProfile.BOOT_ID_BYTES),
                tokenHex = readHex(bytes, 24, GattProfile.FRAME_ACCESS_TOKEN_BYTES),
            )
        }

        private fun parseIpv4(value: String): IntArray? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                if (part.length > 1 && part[0] == '0') return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                octets[index] = octet
            }
            return octets
        }

        private fun readHex(bytes: ByteArray, offset: Int, size: Int): String =
            buildString(size * 2) {
                repeat(size) { index ->
                    val value = bytes[offset + index].toInt() and 0xFF
                    append(HEX[value ushr 4]).append(HEX[value and 0x0F])
                }
            }

        private fun putHex(target: ByteArray, offset: Int, value: String) {
            value.chunked(2).forEachIndexed { index, pair ->
                target[offset + index] = pair.toInt(16).toByte()
            }
        }
    }
}
