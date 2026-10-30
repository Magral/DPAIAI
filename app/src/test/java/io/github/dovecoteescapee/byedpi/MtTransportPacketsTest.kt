package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.MtTransportPackets
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MtTransportPacketsTest {
    private val dd = 0xdddddddd.toInt()
    private val ee = 0xeeeeeeee.toInt()
    private val ef = 0xefefefef.toInt()
    private fun intermediate(size: Int,quickAck: Boolean = false): ByteArray =
        ByteBuffer.allocate(size+4).order(ByteOrder.LITTLE_ENDIAN).putInt(size or (if (quickAck) Int.MIN_VALUE else 0)).put(ByteArray(size) { (it*3).toByte() }).array()
    @Test fun paddedAndIntermediatePreservePacketBoundariesForEveryTwoChunkCut() {
        for (tag in listOf(dd,ee)) {
            val first = intermediate(83,true)
            val second = intermediate(24)
            val both = first+second
            for (cut in 0..both.size) {
                val parser = MtTransportPackets(tag)
                val out = parser.feed(both.copyOfRange(0,cut))+parser.feed(both.copyOfRange(cut,both.size))
                assertEquals("cut=$cut",2,out.size)
                assertArrayEquals(first,out[0]); assertArrayEquals(second,out[1]); assertFalse(parser.hasPending)
            }
        }
    }
    @Test fun abridgedShortExtendedAndQuickAckHeadersSurviveBytewiseInput() {
        val first = byteArrayOf(0x82.toByte())+ByteArray(8)
        val second = byteArrayOf(0xff.toByte(),0x80.toByte(),0,0)+ByteArray(128*4)
        val parser = MtTransportPackets(ef)
        val frames = (first+second).flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals(2,frames.size); assertArrayEquals(first,frames[0]); assertArrayEquals(second,frames[1])
    }
    @Test fun partialPacketsAreNotForwardedAndOversizedLengthIsRejected() {
        val parser = MtTransportPackets(dd)
        assertTrue(parser.feed(intermediate(12).copyOf(7)).isEmpty()); assertTrue(parser.hasPending)
        val tooLarge = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(MtTransportPackets.MAX_BODY+1).array()
        try { MtTransportPackets(dd).feed(tooLarge); fail("Oversized packet accepted") } catch (_: IOException) {}
        try { MtTransportPackets(ef).feed(byteArrayOf(0)); fail("Zero packet accepted") } catch (_: IOException) {}
    }
}
