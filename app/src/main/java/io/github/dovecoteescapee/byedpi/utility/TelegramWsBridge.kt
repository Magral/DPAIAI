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
import java.util.concurrent.atomic.AtomicLong

internal class TelegramWsBridge(
    private val secret: ByteArray,
    private val listenPort: Int = 1443,
    private val log: (String) -> Unit = {},
    private val endpoints: (Int,Boolean) -> List<String> = ::officialEndpoints,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val preferred = ConcurrentHashMap<String,String>()
    private val cooldown = ConcurrentHashMap<String,Long>()
    private val sessions = ConcurrentHashMap<Socket,Session>()
    val rejectedAuthentication = AtomicLong(0)
    private val authenticated = AtomicLong(0)
    private val openedConnections = AtomicLong(0)
    private val receivedConnections = AtomicLong(0)
    @Volatile private var lastRemoteFailure = "нет"
    val diagnosticSummary: String get() = "Активно: ${connections.get()}; авторизовано: ${authenticated.get()}; WSS открыто: ${openedConnections.get()}; с ответом: ${receivedConnections.get()}\n" +
        "Отклонено локальных заголовков: ${rejectedAuthentication.get()} (до выхода в сеть)\nПоследний внешний сбой: $lastRemoteFailure"
    private class Session(val id: Int) {
        val closing = AtomicBoolean(false)
        val tx = AtomicLong(0)
        val rx = AtomicLong(0)
        val started = System.nanoTime()
        @Volatile var label = "C$id"
        @Volatile var phase = "local header"
        @Volatile var endpoint = ""
        @Volatile var routeKey = ""
        @Volatile var reason = "client EOF"
        @Volatile var reasonFixed = false
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val websockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private val workers = ThreadPoolExecutor(0,24,30,TimeUnit.SECONDS,SynchronousQueue<Runnable>(),
        { task -> Thread(task,"telegram-ws-client").apply { isDaemon = true } },ThreadPoolExecutor.AbortPolicy())
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(8,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).pingInterval(0,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .eventListenerFactory { call ->
            val session = call.request().tag(Session::class.java)
            object : EventListener() {
                override fun dnsStart(call: Call,domainName: String) { session?.phase = "DNS $domainName" }
                override fun connectStart(call: Call,address: InetSocketAddress,proxy: Proxy) { session?.phase = "TCP ${address.address?.hostAddress}:443" }
                override fun secureConnectStart(call: Call) { session?.phase = "TLS handshake" }
                override fun secureConnectEnd(call: Call,handshake: Handshake?) { session?.phase = "WSS upgrade" }
            }
        }.build()
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
                    catch (_: RejectedExecutionException) { log("Лимит локальных соединений (24)"); sockets.remove(socket); socket.close() }
                } catch (_: IOException) { if (!stopped.get()) log("Local listener error"); break }
            }
        },"telegram-ws-listener").apply { isDaemon = true; start() }
        log("Локальный MTProxy запущен на 127.0.0.1:$port")
    }
    private fun handle(socket: Socket) {
        connections.incrementAndGet()
        val session = Session(sequence.incrementAndGet())
        sessions[socket] = session
        var invalidAuthentication = false
        var ws: WebSocket? = null
        try {
            socket.tcpNoDelay = true; socket.keepAlive = true; socket.soTimeout = 8000
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(64).also { input.readFully(it) }
            val crypto = MtProxyCrypto(header,secret)
            authenticated.incrementAndGet()
            session.label += " DC${crypto.dc}${if (crypto.media) " media" else ""}"
            session.routeKey = "${crypto.dc}:${crypto.media}"
            session.phase = "WSS connect"
            var lastError: Exception? = null
            val routes = endpoints(crypto.dc,crypto.media).sortedWith(compareBy<String> {
                if ((cooldown[it] ?: 0) > System.nanoTime()) 1 else 0
            }.thenBy { if (preferred[session.routeKey] == it) 0 else 1 })
            for (endpoint in routes) {
                if (stopped.get() || socket.isClosed) break
                try { ws = connect(endpoint,socket,crypto,session); break }
                catch (e: Exception) {
                    lastError = e
                    lastRemoteFailure = "${session.label}: ${e.message?.take(200)}"
                    log(lastRemoteFailure)
                }
            }
            val remote = ws ?: throw (lastError ?: IOException("WSS unavailable"))
            openedConnections.incrementAndGet()
            log("${session.label}: WSS открыт (${URI(session.endpoint).host})")
            // MTProto already has its own heartbeat. Do not expire idle media sockets or send RFC pings.
            socket.soTimeout = 0
            session.phase = "MTProto relay"
            val packets = MtTransportPackets(crypto.protocolTag)
            val buffer = ByteArray(65536)
            while (!stopped.get() && !socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                val plain = crypto.decodeUpstream(buffer.copyOf(count))
                for (packet in packets.feed(plain)) {
                    while (remote.queueSize() > 1024*1024 && !socket.isClosed && !stopped.get()) {
                        // Bound queued memory through TCP backpressure; a slow upload is not a failure.
                        Thread.sleep(10)
                    }
                    if (!remote.send(crypto.encodeUpstream(packet).toByteString())) throw IOException("WSS closed")
                    session.tx.addAndGet(packet.size.toLong())
                }
            }
            if (packets.hasPending) session.reason = "client EOF with partial packet"
        } catch (e: Exception) {
            invalidAuthentication = e is IllegalArgumentException && e.message == "Invalid local proxy authentication"
            if (invalidAuthentication) rejectedAuthentication.incrementAndGet()
            if (!session.reasonFixed) session.reason = "${session.phase}: ${e.javaClass.simpleName}: ${e.message?.take(140) ?: "no detail"}"
        } finally {
            // Mark a local close BEFORE cancel(): OkHttp reports cancel via onFailure as well.
            session.closing.set(true)
            ws?.let { websockets.remove(it); it.cancel() }
            sockets.remove(socket); try { socket.close() } catch (_: IOException) {}
            sessions.remove(socket)
            connections.decrementAndGet()
            if (session.endpoint.isNotEmpty() && session.rx.get() == 0L && session.tx.get() > 0L &&
                System.nanoTime()-session.started > TimeUnit.SECONDS.toNanos(3)) {
                cooldown[session.endpoint] = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            }
            if (!stopped.get() && !invalidAuthentication) {
                val seconds = (System.nanoTime()-session.started)/1_000_000_000
                log("${session.label}: закрыто; ${session.reason}; tx=${session.tx.get()} rx=${session.rx.get()}; ${seconds}s")
            }
        }
    }
    private fun connect(endpoint: String,socket: Socket,crypto: MtProxyCrypto,session: Session): WebSocket {
        val ready = CountDownLatch(1)
        val error = AtomicReference<Exception?>()
        val opened = AtomicBoolean(false)
        val attemptClosed = AtomicBoolean(false)
        val ws = client.newWebSocket(Request.Builder().url(endpoint).tag(Session::class.java,session).header("Sec-WebSocket-Protocol","binary").build(),object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) {
                val protocol = response.header("Sec-WebSocket-Protocol")
                if (protocol != null && protocol != "binary") {
                    error.set(IOException("Unexpected WSS subprotocol")); webSocket.cancel()
                } else if (!webSocket.send(crypto.relayHeader.toByteString())) {
                    error.set(IOException("Cannot send WSS header")); webSocket.cancel()
                } else { session.endpoint = endpoint; opened.set(true) }
                ready.countDown()
            }
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) {
                if (session.closing.get() || attemptClosed.get()) return
                try {
                    if (bytes.size > MtTransportPackets.MAX_BODY+1024) throw IOException("Oversized WSS message")
                    val decoded = crypto.downstream(bytes.toByteArray())
                    val output = socket.getOutputStream()
                    output.write(decoded); output.flush()
                    if (decoded.isNotEmpty() && session.rx.getAndAdd(decoded.size.toLong()) == 0L) {
                        receivedConnections.incrementAndGet()
                        log("${session.label}: первые данные MTProto, ${decoded.size} байт")
                    }
                    preferred[session.routeKey] = endpoint
                    cooldown.remove(endpoint)
                } catch (e: Exception) {
                    session.reason = "local delivery: ${e.javaClass.simpleName}: ${e.message?.take(100)}"
                    session.reasonFixed = true
                    attemptClosed.set(true); webSocket.cancel(); try { socket.close() } catch (_: IOException) {}
                }
            }
            override fun onMessage(webSocket: WebSocket,text: String) {
                session.reason = "unexpected WSS text message"; session.reasonFixed = true
                attemptClosed.set(true); webSocket.cancel()
                if (opened.get()) try { socket.close() } catch (_: IOException) {}
            }
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) {
                webSocket.close(code,reason)
                if (session.closing.get() || attemptClosed.get()) { ready.countDown(); return }
                attemptClosed.set(true)
                session.reasonFixed = true
                session.reason = "remote close $code; MTProto=${reason.take(32)}"
                error.set(IOException("${URI(endpoint).host}: ${session.reason}"))
                ready.countDown()
                lastRemoteFailure = "${session.label}: ${URI(endpoint).host}: ${session.reason}"
                if (session.rx.get() == 0L) cooldown[endpoint] = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                if (opened.get()) try { socket.close() } catch (_: IOException) {}
            }
            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                if (session.closing.get() || attemptClosed.get()) { ready.countDown(); return }
                val status = response?.code?.let { "; HTTP $it" } ?: ""
                val message = "${URI(endpoint).host}; ${session.phase}: ${t.javaClass.simpleName}: ${t.message?.take(160) ?: "no detail"}$status"
                lastRemoteFailure = "${session.label}: $message"
                error.set(IOException(message))
                ready.countDown()
                if (opened.get()) {
                    session.reasonFixed = true; session.reason = message
                    if (session.rx.get() == 0L) cooldown[endpoint] = System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                    try { socket.close() } catch (_: IOException) {}
                }
            }
        })
        websockets.add(ws)
        if (!ready.await(12,TimeUnit.SECONDS) || !opened.get()) {
            attemptClosed.set(true); websockets.remove(ws); ws.cancel()
            throw (error.get() ?: IOException("${URI(endpoint).host}; ${session.phase}: WSS unavailable/timeout"))
        }
        return ws
    }
    /** Close obsolete streams so Telegram reconnects on the new default network. */
    fun networkChanged(reason: String = "смена сети") {
        preferred.clear(); cooldown.clear()
        authenticated.set(0); openedConnections.set(0); receivedConnections.set(0)
        lastRemoteFailure = "нет; новый цикл соединений"
        sessions.forEach { (socket,session) ->
            session.reason = "$reason; требуется новое соединение"
            session.reasonFixed = true; session.closing.set(true)
            try { socket.close() } catch (_: IOException) {}
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        log("$reason: старые соединения закрыты, выбор сервера сброшен")
    }
    fun useProbedRoutes(routes: Map<String,String>) {
        if (routes.isEmpty()) return
        networkChanged("проверка серверов завершена")
        preferred.putAll(routes)
        log("Выбраны серверы с проверенным ответом MTProto для ${routes.size} маршрутов")
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
            // Current official Web K uses kwsN[-1], whereas planetary names are its HTTPS fallback.
            // https://github.com/TelegramOrg/Telegram-web-k/blob/master/src/lib/mtproto/dcConfigurator.ts
            val aliases = if (media) listOf("kws$dc-1","$name-1","kws$dc",name)
                else listOf("kws$dc",name,"kws$dc-1","$name-1")
            return aliases.map { "wss://$it.web.telegram.org/apiws" }
        }
    }
}
