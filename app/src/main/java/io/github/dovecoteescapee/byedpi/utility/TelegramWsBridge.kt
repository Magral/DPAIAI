package io.github.dovecoteescapee.byedpi.utility

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.IOException
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class TelegramWsBridge(
    private val secret: ByteArray,
    private val listenPort: Int = 1443,
    private val log: (String) -> Unit = {},
    private val endpoints: (Int,Boolean) -> List<String> = ::officialEndpoints,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val websockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private val workers = ThreadPoolExecutor(0,24,30,TimeUnit.SECONDS,SynchronousQueue<Runnable>(),
        { task -> Thread(task,"telegram-ws-client").apply { isDaemon = true } },ThreadPoolExecutor.AbortPolicy())
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(8,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).pingInterval(25,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0
    val connections = AtomicInteger(0)
    fun start() {
        check(server == null && !stopped.get())
        server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),listenPort),16) }
        Thread({
            while (!stopped.get()) {
                try {
                    val socket = server!!.accept()
                    sockets.add(socket)
                    try { workers.execute { handle(socket) } }
                    catch (_: RejectedExecutionException) { sockets.remove(socket); socket.close() }
                } catch (_: IOException) { if (!stopped.get()) log("Local listener error"); break }
            }
        },"telegram-ws-listener").apply { isDaemon = true; start() }
        log("Локальный MTProxy запущен на 127.0.0.1:$port")
    }
    private fun handle(socket: Socket) {
        connections.incrementAndGet()
        var ws: WebSocket? = null
        try {
            socket.tcpNoDelay = true; socket.soTimeout = 8000
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(64).also { input.readFully(it) }
            val crypto = MtProxyCrypto(header,secret)
            var lastError: Exception? = null
            for (endpoint in endpoints(crypto.dc,crypto.media)) {
                if (stopped.get() || socket.isClosed) break
                try { ws = connect(endpoint,socket,crypto); break }
                catch (e: Exception) { lastError = e }
            }
            val remote = ws ?: throw (lastError ?: IOException("WSS unavailable"))
            log("DC${crypto.dc}${if (crypto.media) " media" else ""}: WSS подключён")
            socket.soTimeout = 300000
            val buffer = ByteArray(65536)
            while (!stopped.get() && !socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while (remote.queueSize() > 1024*1024 && !socket.isClosed) {
                    if (System.nanoTime() > deadline) throw IOException("WSS send queue stalled")
                    Thread.sleep(10)
                }
                if (!remote.send(crypto.upstream(buffer.copyOf(count)).toByteString())) throw IOException("WSS closed")
            }
        } catch (e: Exception) {
            // Do not include handshake bytes, secrets, payload or account identifiers.
            if (!stopped.get()) log("Соединение: ${e.javaClass.simpleName}: ${e.message?.take(160) ?: "no detail"}")
        } finally {
            ws?.let { websockets.remove(it); it.cancel() }
            sockets.remove(socket); try { socket.close() } catch (_: IOException) {}
            connections.decrementAndGet()
        }
    }
    private fun connect(endpoint: String,socket: Socket,crypto: MtProxyCrypto): WebSocket {
        val ready = CountDownLatch(1)
        val error = AtomicReference<Exception?>()
        val opened = AtomicBoolean(false)
        val ws = client.newWebSocket(Request.Builder().url(endpoint).header("Sec-WebSocket-Protocol","binary").build(),object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) {
                val protocol = response.header("Sec-WebSocket-Protocol")
                if (protocol != null && protocol != "binary") {
                    error.set(IOException("Unexpected WSS subprotocol")); webSocket.cancel()
                } else if (!webSocket.send(crypto.relayHeader.toByteString())) {
                    error.set(IOException("Cannot send WSS header")); webSocket.cancel()
                } else opened.set(true)
                ready.countDown()
            }
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) {
                try {
                    if (bytes.size > 2*1024*1024) throw IOException("Oversized WSS message")
                    val output = socket.getOutputStream()
                    output.write(crypto.downstream(bytes.toByteArray())); output.flush()
                } catch (_: Exception) { webSocket.cancel(); try { socket.close() } catch (_: IOException) {} }
            }
            override fun onMessage(webSocket: WebSocket,text: String) {
                webSocket.cancel(); if (opened.get()) try { socket.close() } catch (_: IOException) {}
            }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) {
                webSocket.close(code,reason)
                if (opened.get()) { log("WSS закрыт, код $code; транспорт: ${reason.take(32)}"); try { socket.close() } catch (_: IOException) {} }
            }
            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                val status = response?.code?.let { "; HTTP $it" } ?: ""
                error.set(IOException("${URI(endpoint).host}: ${t.javaClass.simpleName}$status"))
                ready.countDown()
                if (opened.get()) { log("WSS потерян$status"); try { socket.close() } catch (_: IOException) {} }
            }
        })
        websockets.add(ws)
        if (!ready.await(12,TimeUnit.SECONDS) || !opened.get()) {
            websockets.remove(ws); ws.cancel()
            throw (error.get() ?: IOException("WSS timeout"))
        }
        return ws
    }
    override fun close() {
        if (!stopped.compareAndSet(false,true)) return
        try { server?.close() } catch (_: IOException) {}
        sockets.forEach { try { it.close() } catch (_: IOException) {} }
        websockets.forEach { it.cancel() }
        workers.shutdownNow(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
    companion object {
        fun officialEndpoints(dc: Int,media: Boolean): List<String> {
            val name = listOf("pluto","venus","aurora","vesta","flora").getOrNull(dc-1) ?: error("Invalid DC")
            val aliases = if (media) listOf("$name-1",name) else listOf(name,"$name-1")
            return aliases.map { "wss://$it.web.telegram.org/apiws" }
        }
    }
}
