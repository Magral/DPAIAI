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
    private fun localHeader(secret: ByteArray): ByteArray {
        val seed = ByteArray(64) { (it+3).toByte() }
        ByteBuffer.wrap(seed).order(ByteOrder.LITTLE_ENDIAN).putInt(56,0xdddddddd.toInt()).putShort(60,2)
        val encrypted = MtProxyCrypto.cipher(seed,secret).update(seed)
        return seed.copyOf().apply { encrypted.copyInto(this,56,56,64) }
    }
    @Test fun rejectedLocalHeadersDoNotFloodExternalDiagnostics() {
        val logs = ConcurrentLinkedQueue<String>()
        val bridge = TelegramWsBridge(ByteArray(16) { 4 },0,log = { logs.add(it) },endpoints = { _,_ -> error("Unauthenticated connection reached routing") })
        try {
            bridge.start()
            repeat(20) { Socket("127.0.0.1",bridge.port).use { it.getOutputStream().write(ByteArray(64)) } }
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while (bridge.rejectedAuthentication.get() < 20 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(20L,bridge.rejectedAuthentication.get())
            assertFalse(logs.joinToString("\n"),logs.any { it.contains("Invalid local proxy authentication") })
            assertTrue(bridge.diagnosticSummary,bridge.diagnosticSummary.contains("локальных заголовков: 20"))
        } finally { bridge.close() }
    }
    @Test fun networkChangeDisconnectsOldStreamAndPreservesListener() {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})) }
        server.start()
        val opened = java.util.concurrent.LinkedBlockingQueue<String>()
        val secret = ByteArray(16) { 7 }
        val bridge = TelegramWsBridge(secret,0,log = { if (it.contains("WSS открыт")) opened.offer(it) },endpoints = { _,_ ->
            listOf(server.url("/apiws").toString().replace("http://","ws://"))
        })
        try {
            bridge.start()
            Socket("127.0.0.1",bridge.port).use { first ->
                first.soTimeout = 3000
                first.getOutputStream().write(localHeader(secret))
                assertNotNull(opened.poll(4,TimeUnit.SECONDS))
                bridge.networkChanged()
                assertEquals(-1,first.getInputStream().read())
            }
            Socket("127.0.0.1",bridge.port).use { second ->
                second.getOutputStream().write(localHeader(secret))
                assertNotNull(opened.poll(4,TimeUnit.SECONDS))
            }
        } finally { bridge.close(); server.shutdown() }
    }
    @Test fun authenticatedLocalProxyRelaysOpaqueBytesBothWaysToCorrectDc() {
        val secret = ByteArray(16) { (it+9).toByte() }
        val first = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN).putInt(80).put(ByteArray(80) { (it*7).toByte() }).array()
        val second = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(20).put(ByteArray(20) { (it*3).toByte() }).array()
        val payload = first+second
        val logs = ConcurrentLinkedQueue<String>()
        val closed = CountDownLatch(1)
        val response = ByteArray(48) { (it*13).toByte() }
        val seen = CompletableFuture<ByteArray>()
        val remote = MockWebServer()
        remote.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var incoming: Cipher? = null
            var outgoing: Cipher? = null
            val received = java.io.ByteArrayOutputStream()
            val sizes = mutableListOf<Int>()
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
                    received.write(clear); sizes.add(clear.size)
                    if (received.size() == payload.size) {
                        assertEquals(listOf(84,24),sizes)
                        seen.complete(received.toByteArray())
                        val encoded = outgoing!!.update(response)
                        webSocket.send(encoded.copyOfRange(0,3).toByteString())
                        webSocket.send(encoded.copyOfRange(3,encoded.size).toByteString())
                    }
                }
            }
        }))
        remote.start()
        val bridge = TelegramWsBridge(secret,0,log = { line -> logs.add(line); if (line.contains("закрыто;")) closed.countDown() },endpoints = { dc,media ->
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
                native.getOutputStream().write(header + up.update(payload.copyOfRange(0,2))); native.getOutputStream().flush()
                native.getOutputStream().write(up.update(payload.copyOfRange(2,payload.size))); native.getOutputStream().flush()
                val result = ByteArray(response.size).also { DataInputStream(native.getInputStream()).readFully(it) }
                assertArrayEquals(payload,seen.get(8,TimeUnit.SECONDS))
                assertArrayEquals(response,down.update(result))
                assertEquals("binary",remote.takeRequest(3,TimeUnit.SECONDS)!!.getHeader("Sec-WebSocket-Protocol"))
            }
            assertTrue("Local close was not observed",closed.await(3,TimeUnit.SECONDS))
            val summary = logs.filter { it.contains("закрыто;") }
            assertEquals(1,summary.size)
            assertTrue(summary.single(),summary.single().contains("client EOF"))
            assertTrue(summary.single(),summary.single().contains("tx=108 rx=48"))
            assertFalse(logs.joinToString("\n"),logs.any { it.contains("WSS потерян") || it.contains("Socket closed") })
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
        assertTrue(TelegramWsBridge.officialEndpoints(4,true).first().startsWith("wss://kws4-1.web.telegram.org/"))
        assertEquals(4,TelegramWsBridge.officialEndpoints(4,false).distinct().size)
        assertTrue(TelegramWsBridge.officialEndpoints(4,false).contains("wss://vesta.web.telegram.org/apiws"))
    }
}
