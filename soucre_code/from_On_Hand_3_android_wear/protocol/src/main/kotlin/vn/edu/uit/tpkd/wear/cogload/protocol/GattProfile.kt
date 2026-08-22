package vn.edu.uit.tpkd.wear.cogload.protocol

/**
 * Hằng số GATT chốt ở ADR 0004. Spec normative: `docs/GATT_PROFILE.md`.
 *
 * Module này là JVM thuần nên UUID để dạng string; caller Android tự gọi
 * `java.util.UUID.fromString`.
 */
object GattProfile {
    const val PROTOCOL_VERSION = 1
    const val FRAMING_VERSION = 1

    const val SERVICE_UUID = "3a9190ce-8e4e-4792-830b-4a04f637446e"
    const val DEVICE_INFO_UUID = "8c441643-7770-406d-9ddc-9c0b15d5c138"
    const val FACE_OBSERVATION_UUID = "f8c18a21-0a62-4a67-8b0d-c5efd5b81263"
    const val CONTROL_UUID = "50bf0d4c-ce93-4d39-acce-a0b5b32f4049"

    const val PREFERRED_MTU = 517
    const val DEFAULT_MTU = 23
    const val ATT_HEADER_BYTES = 3

    const val DEVICE_INFO_BYTES = 34
    const val BOOT_ID_BYTES = 16

    const val NOMINAL_RATE_DHZ = 50
    const val MIN_RATE_DHZ = 10
    const val MAX_RATE_DHZ = 100

    const val STALE_THRESHOLD_MS = 3_000L
    const val REASSEMBLY_TIMEOUT_MS = 500L
    const val FRAME_HEADER_BYTES = 8

    const val CAP_DETECTOR_READY = 1 shl 0
    const val CAP_CAMERA_READY = 1 shl 1
    const val CAP_SET_RATE = 1 shl 2
    const val CAP_REPORTS_LOW_LIGHT = 1 shl 3
    const val CAP_REPORTS_UNSTABLE = 1 shl 4
    const val CAP_RESERVED_MASK = 0xFFFF_FFE0.toInt()

    const val OPCODE_START: Byte = 0x01
    const val OPCODE_STOP: Byte = 0x02
    const val OPCODE_SET_RATE: Byte = 0x03
    const val OPCODE_RESYNC: Byte = 0x04

    fun startCommand(rateDhz: Int = NOMINAL_RATE_DHZ): ByteArray =
        byteArrayOf(OPCODE_START, requireRate(rateDhz))

    fun stopCommand(): ByteArray = byteArrayOf(OPCODE_STOP)

    fun setRateCommand(rateDhz: Int): ByteArray =
        byteArrayOf(OPCODE_SET_RATE, requireRate(rateDhz))

    fun resyncCommand(): ByteArray = byteArrayOf(OPCODE_RESYNC)

    /** Byte dữ liệu tối đa cho một notification ở MTU cho trước. */
    fun notificationCapacity(attMtu: Int): Int = attMtu - ATT_HEADER_BYTES

    private fun requireRate(rateDhz: Int): Byte {
        require(rateDhz in MIN_RATE_DHZ..MAX_RATE_DHZ) {
            "rate_dhz must be in $MIN_RATE_DHZ..$MAX_RATE_DHZ"
        }
        return rateDhz.toByte()
    }
}
