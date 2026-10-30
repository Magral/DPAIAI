package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.Test

class TelegramBridgeTest {
    @Test fun authenticatedLocalProxyRelaysOpaqueBytesBothWaysToCorrectDc() {
        val secret = ByteArray(16) { (it+9).toByte() }
        val payload = ByteArray(80) { (it*7).toByte() }
        val response = ByteArray(48) { (it*13).toByte() }
        val seen = CompletableFuture<ByteArray>()
        val remote = MockWebServer()
        remote.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var incoming: Cipher? = null
            var outgoing: Cipher? = null
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) {
                val data = bytes.toByteArray()
                if (incoming == null) {
                    assertEquals(64,data.size)
                    incoming = MtProxyCrypto.cipher(data,null)
                    outgoing = MtProxyCrypto.cipher(data.reversedArray(),null)
                    val header = incoming!!.update(data)
                    assertEquals(0xdddddddd.toInt(),ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(56))
                } else {
                    val clear = incoming!!.update(data)
                    seen.complete(clear)
                    val encoded = outgoing!!.update(response)
                    webSocket.send(encoded.copyOfRange(0,3).toByteString())
                    webSocket.send(encoded.copyOfRange(3,encoded.size).toByteString())
                }
            }
        }))
        remote.start()
        val bridge = TelegramWsBridge(secret,0,endpoints = { dc,media ->
            assertEquals(2,dc); assertFalse(media)
            listOf(remote.url("/apiws").toString().replace("http://","ws://"))
        })
        try {
            bridge.start()
            Socket("127.0.0.1",bridge.port).use { native ->
                native.soTimeout = 8000
                val seed = ByteArray(64) { (it+1).toByte() }
                ByteBuffer.wrap(seed).order(ByteOrder.LITTLE_ENDIAN).putInt(56,0xdddddddd.toInt()).putShort(60,2)
                val up = MtProxyCrypto.cipher(seed,secret)
                val down = MtProxyCrypto.cipher(seed.reversedArray(),secret)
                val encrypted = up.update(seed)
                val header = seed.copyOf().apply { encrypted.copyInto(this,56,56,64) }
                native.getOutputStream().write(header + up.update(payload)); native.getOutputStream().flush()
                val result = ByteArray(response.size).also { DataInputStream(native.getInputStream()).readFully(it) }
                assertArrayEquals(payload,seen.get(8,TimeUnit.SECONDS))
                assertArrayEquals(response,down.update(result))
                assertEquals("binary",remote.takeRequest(3,TimeUnit.SECONDS)!!.getHeader("Sec-WebSocket-Protocol"))
            }
        } finally { bridge.close(); remote.shutdown() }
    }
    @Test fun invalidAuthenticationAndUnsupportedDcAreRejected() {
        val secret = ByteArray(16) { 4 }
        fun header(dc: Short): ByteArray {
            val seed = ByteArray(64) { (it+2).toByte() }
            ByteBuffer.wrap(seed).order(ByteOrder.LITTLE_ENDIAN).putInt(56,0xdddddddd.toInt()).putShort(60,dc)
            val cipher = MtProxyCrypto.cipher(seed,secret)
            val encrypted = cipher.update(seed)
            return seed.copyOf().apply { encrypted.copyInto(this,56,56,64) }
        }
        try { MtProxyCrypto(header(2),ByteArray(16) { 5 }); fail("Bad secret accepted") } catch (_: IllegalArgumentException) {}
        try { MtProxyCrypto(header(10002),secret); fail("Test DC accepted") } catch (_: IllegalArgumentException) {}
        val media = MtProxyCrypto(header(-4),secret)
        assertEquals(4,media.dc); assertTrue(media.media)
        assertTrue(TelegramWsBridge.officialEndpoints(4,true).first().startsWith("wss://vesta-1.web.telegram.org/"))
    }
}
