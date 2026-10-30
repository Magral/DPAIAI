package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TelegramTransportTest {
    @Test fun obfuscatedHeaderAndStreamingCountersMatchServer() {
        val seed = ByteArray(64) { (it+1).toByte() }
        val client = TelegramObfuscation(seed)
        assertArrayEquals(seed.copyOfRange(0,56),client.header.copyOfRange(0,56))
        // Independent server-side CTR decryption: consume header before reading frames.
        val serverRead = TelegramObfuscation.cipher(seed)
        val decoded = serverRead.update(client.header)
        assertArrayEquals(ByteArray(4) { 0xdd.toByte() },decoded.copyOfRange(56,60))
        val payload = ByteArray(44) { (it*7).toByte() }
        val encoded = client.encrypt(payload)
        assertArrayEquals(payload.copyOfRange(0,13),serverRead.update(encoded.copyOfRange(0,13)))
        assertArrayEquals(payload.copyOfRange(13,44),serverRead.update(encoded.copyOfRange(13,44)))
        val serverWrite = TelegramObfuscation.cipher(seed.reversedArray())
        val response = serverWrite.update(payload)
        assertArrayEquals(payload.copyOfRange(0,4),client.decrypt(response.copyOfRange(0,4)))
        assertArrayEquals(payload.copyOfRange(4,44),client.decrypt(response.copyOfRange(4,44)))
    }
    @Test fun paddedRepliesAllowOnlyBoundedTransportPadding() {
        val nonce = ByteArray(16) { (it+2).toByte() }
        val reply = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(1).putInt(80).putInt(0x05162463).put(nonce).array()
        assertTrue(TargetProbe.validTelegramReply(reply.copyOf(115),nonce,true))
        assertFalse(TargetProbe.validTelegramReply(reply.copyOf(116),nonce,true))
        assertFalse(TargetProbe.validTelegramReply(reply.copyOf(101),nonce,false))
        assertFalse(TargetProbe.validTelegramReply(reply.copyOf(99),nonce,true))
    }
    @Test fun telegramCannotWinOnWebOnlyAndDoesNotDependOnWebPages() {
        assertFalse(TuningScore.eligible("telegram",4,14,0,10))
        assertFalse(TuningScore.eligible("telegram",12,14,8,10))
        assertFalse(TuningScore.eligible("telegram",0,0,0,0))
        assertTrue(TuningScore.eligible("telegram",10,14,10,10))
        assertFalse(TuningScore.eligible("youtube",4,6,0,0))
        assertTrue(TuningScore.eligible("youtube",6,6,0,0))
    }
}
