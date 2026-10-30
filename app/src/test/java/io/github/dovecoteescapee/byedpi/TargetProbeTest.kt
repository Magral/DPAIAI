package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TargetProbeTest {
    @Test fun acceptsMatchingNonceAndRejectsWrongPeerData() {
        val nonce = ByteArray(16) { it.toByte() }
        val reply = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(1).putInt(80).putInt(0x05162463).put(nonce).array()
        assertTrue(TargetProbe.validTelegramReply(reply,nonce))
        assertFalse(TargetProbe.validTelegramReply(reply,ByteArray(16)))
        assertFalse(TargetProbe.validTelegramReply(reply.copyOf(40),nonce))
        reply[20] = 0
        assertFalse(TargetProbe.validTelegramReply(reply,nonce))
    }
    @Test fun rejectsIncorrectEnvelopeLengthAndEncryptedPayload() {
        val nonce = ByteArray(16)
        val reply = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(1).putLong(1).putInt(80).putInt(0x05162463).put(nonce).array()
        assertFalse(TargetProbe.validTelegramReply(reply,nonce))
        reply[0] = 0
        reply[16] = 79
        assertFalse(TargetProbe.validTelegramReply(reply,nonce))
    }
}
