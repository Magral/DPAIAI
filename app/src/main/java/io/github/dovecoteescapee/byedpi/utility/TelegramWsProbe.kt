package io.github.dovecoteescapee.byedpi.utility

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Account-free transport probe. A WebSocket upgrade alone is never a successful result. */
internal object TelegramWsProbe {
    private class Attempt { @Volatile var phase = "DNS" }
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
        .writeTimeout(5,TimeUnit.SECONDS).pingInterval(0,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .eventListenerFactory { call ->
            val state = call.request().tag(Attempt::class.java)
            object : EventListener() {
                override fun dnsStart(call: Call,domainName: String) { state?.phase = "DNS" }
                override fun connectStart(call: Call,address: InetSocketAddress,proxy: Proxy) { state?.phase = "TCP ${address.address?.hostAddress}" }
                override fun secureConnectStart(call: Call) { state?.phase = "TLS" }
                override fun secureConnectEnd(call: Call,handshake: Handshake?) { state?.phase = "WSS upgrade" }
            }
        }.build()

    fun check(endpoint: String,cancelled: () -> Boolean = { false }): String {
        val started = System.nanoTime()
        val state = Attempt()
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val now = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(((now/1000) shl 32) or (((now%1000) shl 32)/1000 and -4L))
            .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
        val transport = TelegramObfuscation()
        val input = ByteArrayOutputStream()
        fun finish(message: String) { if (result.compareAndSet(null,message)) done.countDown() }
        val ws = client.newWebSocket(Request.Builder().url(endpoint).tag(Attempt::class.java,state)
            .header("Sec-WebSocket-Protocol","binary").build(),object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) {
                if (response.header("Sec-WebSocket-Protocol")?.let { it != "binary" } == true) {
                    finish("FAIL: WSS subprotocol"); return
                }
                state.phase = "MTProto resPQ"
                val frame = ByteBuffer.allocate(4+payload.size).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).put(payload).array()
                if (!webSocket.send(transport.header.toByteString()) || !webSocket.send(transport.encrypt(frame).toByteString())) finish("FAIL: WSS send")
            }
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) {
                if (result.get() != null) return
                if (input.size()+bytes.size > 4100) { finish("FAIL: oversized MTProto reply"); return }
                input.write(transport.decrypt(bytes.toByteArray()))
                val data = input.toByteArray()
                if (data.size < 4) return
                val length = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
                if (length !in 56..4096) { finish("FAIL: invalid MTProto length $length"); return }
                if (data.size < 4+length) return
                val reply = data.copyOfRange(4,4+length)
                finish(if (TargetProbe.validTelegramReply(reply,nonce,true)) "OK: MTProto resPQ + nonce" else "FAIL: MTProto nonce/response")
            }
            override fun onMessage(webSocket: WebSocket,text: String) { finish("FAIL: WSS text response") }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { finish("FAIL: ${state.phase}; close $code ${reason.take(32)}") }
            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                finish("FAIL: ${state.phase}; ${t.javaClass.simpleName}: ${t.message?.take(120)}${response?.code?.let { "; HTTP $it" } ?: ""}")
            }
        })
        try {
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while (!done.await(200,TimeUnit.MILLISECONDS)) {
                if (cancelled()) { finish("отменено"); break }
                if (System.nanoTime() >= deadline) { finish("FAIL: ${state.phase}; timeout 10s"); break }
            }
        } finally { ws.cancel() }
        return "${result.get()}; ${(System.nanoTime()-started)/1_000_000}ms"
    }
}
