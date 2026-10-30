package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

class TelegramWsProbeTest {
    private fun probe(wrongNonce: Boolean): String {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var input: Cipher? = null
            var output: Cipher? = null
            override fun onMessage(ws: WebSocket,bytes: ByteString) {
                val data = bytes.toByteArray()
                if (input == null) {
                    input = MtProxyCrypto.cipher(data,null)
                    input!!.update(data)
                    output = MtProxyCrypto.cipher(data.reversedArray(),null)
                } else {
                    val request = input!!.update(data)
                    val nonce = request.copyOfRange(28,44)
                    if (wrongNonce) nonce[0] = (nonce[0].toInt() xor 1).toByte()
                    val reply = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(56).putLong(0).putLong(4).putInt(36).putInt(0x05162463)
                        .put(nonce).put(ByteArray(16)).array()
                    val encrypted = output!!.update(reply)
                    ws.send(encrypted.copyOfRange(0,2).toByteString())
                    ws.send(encrypted.copyOfRange(2,encrypted.size).toByteString())
                }
            }
        }))
        server.start()
        return try { TelegramWsProbe.check(server.url("/apiws").toString().replace("http://","ws://")) }
            finally { server.shutdown() }
    }
    @Test fun fragmentedReplyMustContainMatchingNonce() {
        assertTrue(probe(false).startsWith("OK: MTProto resPQ + nonce"))
        assertTrue(probe(true).startsWith("FAIL: MTProto nonce/response"))
    }
    @Test fun httpReachabilityIsNotMtprotoSuccess() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val result = TelegramWsProbe.check(server.url("/apiws").toString().replace("http://","ws://"))
            assertTrue(result,result.startsWith("FAIL:"))
            assertTrue(result,result.contains("HTTP 403"))
        } finally { server.shutdown() }
    }
}
