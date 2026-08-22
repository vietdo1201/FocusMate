package vn.edu.uit.tpkd.wear.cogload

import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationCodec
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationFramer
import vn.edu.uit.tpkd.wear.cogload.protocol.FaceObservationV1

data class SimulatorFaults(
    val dropChunk: Int? = null,
    val duplicateChunk: Int? = null,
    val reorder: Boolean = false,
    val corruptCrc: Boolean = false,
    val degradedQuality: Boolean = false,
)

/** Deterministic local transport used to exercise exactly the same notification pipeline as BLE. */
class FaceObservationSimulator(private val framer: FaceObservationFramer = FaceObservationFramer()) {
    fun notifications(
        observation: FaceObservationV1,
        mtu: Int,
        faults: SimulatorFaults = SimulatorFaults(),
    ): List<ByteArray> {
        val emitted = if (faults.degradedQuality && observation.faceDetected) {
            observation.copy(qualityFlags = observation.qualityFlags + "unstable")
        } else observation
        val frames = framer.frame(FaceObservationCodec.encode(emitted), mtu).map(ByteArray::copyOf).toMutableList()
        if (faults.corruptCrc && frames.first().firstOrNull()?.toInt() != OPEN_BRACE) {
            frames[0][CRC_LOW_OFFSET] = (frames[0][CRC_LOW_OFFSET].toInt() xor 0x01).toByte()
        }
        faults.dropChunk?.takeIf { it in frames.indices }?.let(frames::removeAt)
        faults.duplicateChunk?.takeIf { it in frames.indices }?.let { frames.add(it, frames[it].copyOf()) }
        if (faults.reorder) frames.reverse()
        return frames
    }

    private companion object {
        const val OPEN_BRACE = 0x7B
        const val CRC_LOW_OFFSET = 6
    }
}
