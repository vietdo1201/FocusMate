package vn.edu.uit.tpkd.wear.cogload.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {
    private val payload = FaceObservationCodec.encode(
        FaceObservationV1(42, 12_345, false, qualityFlags = setOf("low_light"))
    )

    @Test
    fun crcMatchesStandardCheckValue() {
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".toByteArray()))
    }

    @Test
    fun mtu517IsUnframedAndMtu23RoundTripsInAnyChunkOrder() {
        val framer = FaceObservationFramer()
        val direct = framer.frame(payload, 517)
        assertEquals(1, direct.size)
        assertTrue(direct.single().contentEquals(payload))

        val chunks = framer.frame(payload, 23)
        assertTrue(chunks.size > 1)
        val reassembler = FaceObservationReassembler()
        var outcome: FaceObservationReassembler.Outcome = FaceObservationReassembler.Outcome.Pending()
        chunks.reversed().forEachIndexed { index, chunk -> outcome = reassembler.offer(chunk, index.toLong()) }
        val complete = outcome as FaceObservationReassembler.Outcome.Complete
        assertTrue(complete.payload.contentEquals(payload))
    }

    @Test
    fun newMessageSupersedesOldWithoutLosingItsFirstChunk() {
        val framer = FaceObservationFramer()
        val first = framer.frame(payload, 23)
        val secondPayload = FaceObservationCodec.encode(FaceObservationV1(43, 12_545, false))
        val second = framer.frame(secondPayload, 23)
        val reassembler = FaceObservationReassembler()
        assertTrue(reassembler.offer(first.first(), 0) is FaceObservationReassembler.Outcome.Pending)
        val switched = reassembler.offer(second.first(), 1) as FaceObservationReassembler.Outcome.Pending
        assertEquals(FaceObservationReassembler.Reason.SUPERSEDED_MESSAGE, switched.displaced)
        var outcome: FaceObservationReassembler.Outcome = switched
        second.drop(1).forEachIndexed { index, chunk -> outcome = reassembler.offer(chunk, index + 2L) }
        assertTrue((outcome as FaceObservationReassembler.Outcome.Complete).payload.contentEquals(secondPayload))
    }

    @Test
    fun timeoutKeepsFirstChunkOfFollowingMessage() {
        val framer = FaceObservationFramer()
        val first = framer.frame(payload, 23)
        val secondPayload = FaceObservationCodec.encode(FaceObservationV1(44, 12_745, false))
        val second = framer.frame(secondPayload, 23)
        val reassembler = FaceObservationReassembler(timeoutMs = 500)
        reassembler.offer(first.first(), 0)
        val restarted = reassembler.offer(second.first(), 501) as FaceObservationReassembler.Outcome.Pending
        assertEquals(FaceObservationReassembler.Reason.TIMEOUT, restarted.displaced)
        var outcome: FaceObservationReassembler.Outcome = restarted
        second.drop(1).forEachIndexed { index, chunk -> outcome = reassembler.offer(chunk, index + 502L) }
        assertTrue((outcome as FaceObservationReassembler.Outcome.Complete).payload.contentEquals(secondPayload))
    }

    @Test
    fun duplicateAndCorruptChunksAreRejected() {
        val chunks = FaceObservationFramer().frame(payload, 23)
        val duplicateAssembler = FaceObservationReassembler()
        duplicateAssembler.offer(chunks.first(), 0)
        val duplicate = duplicateAssembler.offer(chunks.first(), 1)
        assertEquals(
            FaceObservationReassembler.Reason.DUPLICATE_CHUNK,
            (duplicate as FaceObservationReassembler.Outcome.Dropped).reason,
        )

        val corrupt = chunks.map { it.copyOf() }
        corrupt.last()[corrupt.last().lastIndex] = (corrupt.last().last().toInt() xor 1).toByte()
        val corruptAssembler = FaceObservationReassembler()
        var outcome: FaceObservationReassembler.Outcome = FaceObservationReassembler.Outcome.Pending()
        corrupt.forEachIndexed { index, chunk -> outcome = corruptAssembler.offer(chunk, index.toLong()) }
        assertEquals(
            FaceObservationReassembler.Reason.CRC_MISMATCH,
            (outcome as FaceObservationReassembler.Outcome.Dropped).reason,
        )
    }

    @Test
    fun fragmentedMessageIdWrapsAt256() {
        val framer = FaceObservationFramer()
        val ids = (0..256).map { framer.frame(payload, 23).first()[1].toInt() and 0xFF }
        assertEquals(0, ids.first())
        assertEquals(255, ids[255])
        assertEquals(0, ids[256])
    }
}
