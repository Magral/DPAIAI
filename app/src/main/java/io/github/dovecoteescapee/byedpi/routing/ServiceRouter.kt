package io.github.dovecoteescapee.byedpi.routing

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Local SOCKS5 frontend. Established flows keep their backend during a route change. */
class ServiceRouter(private val bindPort: Int = 1080) : AutoCloseable {
    private fun <K: Any,V: Any> ConcurrentHashMap<K,V>.getOrCreate(key: K,create: ()->V): V = get(key) ?: create().let { putIfAbsent(key,it) ?: it }
    data class Route(val port: Int,val generation: Long)
    data class Stats(val tx: Long,val rx: Long,val writes: Long,val connections: Int,val failures: Long)
    private class Meter { val tx = AtomicLong(); val rx = AtomicLong(); val writes = AtomicLong(); val connections = AtomicInteger(); val failures = AtomicLong() }
    private data class Flow(val client: Socket,val peer: Socket,val service: String?,val route: Route?)
    private val running = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(0,192,20,TimeUnit.SECONDS,SynchronousQueue(),{ r -> Thread(r,"service-router").apply { isDaemon = true } })
    private val routes = ConcurrentHashMap<String,Route>()
    private val meters = ConcurrentHashMap<String,Meter>()
    private val routeMeters = ConcurrentHashMap<Route,Meter>()
    private val flows = java.util.Collections.newSetFromMap(ConcurrentHashMap<Flow,Boolean>())
    private val sockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Closeable,Boolean>())
    private var listener: ServerSocket? = null
    @Volatile var onDrained: ((Route)->Unit)? = null
    val port get() = listener!!.localPort
    fun start() {
        check(running.compareAndSet(false,true))
        listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1",bindPort),64) }
        Thread({
            while (running.get()) try {
                val client = listener!!.accept(); sockets.add(client)
                try { workers.execute { handle(client) } } catch (_: RejectedExecutionException) { sockets.remove(client); client.close() }
            } catch (_: IOException) { if (running.get()) Thread.yield() }
        },"socks-accept").apply { isDaemon = true; start() }
    }
    fun route(service: String,value: Route) { require(service in listOf("instagram","youtube")); routes[service] = value }
    fun active(service: String): Route? = routes[service]
    fun connections(route: Route) = flows.count { it.route == route }
    fun closeRoute(route: Route) { flows.filter { it.route == route }.forEach { runCatching { it.client.close() }; runCatching { it.peer.close() } } }
    fun stats(service: String): Stats {
        val m = meters.getOrCreate(service) { Meter() }
        return Stats(m.tx.get(),m.rx.get(),m.writes.get(),m.connections.get(),m.failures.get())
    }
    fun stats(route: Route): Stats {
        val m = routeMeters.getOrCreate(route) { Meter() }
        return Stats(m.tx.get(),m.rx.get(),m.writes.get(),m.connections.get(),m.failures.get())
    }
    private fun address(input: DataInputStream): Pair<String,ByteArray> {
        val type = input.readUnsignedByte()
        val data = when (type) { 1 -> ByteArray(4).also { input.readFully(it) }; 4 -> ByteArray(16).also { input.readFully(it) }
            3 -> ByteArray(input.readUnsignedByte()).also { require(it.isNotEmpty()); input.readFully(it) }; else -> error("SOCKS address") }
        return (if (type == 3) String(data,Charsets.US_ASCII) else InetAddress.getByAddress(data).hostAddress!!) to
            (byteArrayOf(type.toByte())+(if (type == 3) byteArrayOf(data.size.toByte()) else byteArrayOf())+data)
    }
    private fun handle(client: Socket) {
        var peer: Socket? = null; var flow: Flow? = null; var meter: Meter? = null; var routeMeter: Meter? = null
        try {
            client.soTimeout = 10000; client.tcpNoDelay = true
            val input = DataInputStream(client.inputStream); val output = client.outputStream
            require(input.readUnsignedByte() == 5)
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }; require(methods.contains(0.toByte()))
            output.write(byteArrayOf(5,0)); output.flush()
            require(input.readUnsignedByte() == 5); val command = input.readUnsignedByte(); require(input.readUnsignedByte() == 0)
            val (host,encoded) = address(input); val targetPort = input.readUnsignedShort()
            if (command == 3) { udp(client,output); return }
            require(command == 1 && targetPort > 0)
            // A numeric TUN destination has no hostname until ClientHello arrives.
            output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); output.flush()
            client.soTimeout = 5000
            val prefix = HostInspector.read(input)
            val service = HostInspector.service(prefix.host) ?: HostInspector.service(host)
            val route = service?.let { routes[it] }
            val serviceMeter = meters.getOrCreate(service ?: "unknown") { Meter() }; meter = serviceMeter
            routeMeter = route?.let { routeMeters.getOrCreate(it) { Meter() } }
            val totals = listOfNotNull(serviceMeter,routeMeter)
            val upstreamPeer = Socket(Proxy.NO_PROXY).apply { tcpNoDelay = true }; peer = upstreamPeer
            sockets.add(upstreamPeer)
            if (route != null) {
                upstreamPeer.connect(InetSocketAddress("127.0.0.1",route.port),2000); upstreamPeer.soTimeout = 65000
                val upstream = DataInputStream(upstreamPeer.inputStream); val request = upstreamPeer.outputStream
                request.write(byteArrayOf(5,1,0)); request.flush(); require(upstream.readUnsignedByte() == 5 && upstream.readUnsignedByte() == 0)
                request.write(byteArrayOf(5,1,0)+encoded+byteArrayOf((targetPort ushr 8).toByte(),targetPort.toByte())); request.flush()
                require(upstream.readUnsignedByte() == 5 && upstream.readUnsignedByte() == 0); upstream.readUnsignedByte(); address(upstream); upstream.readUnsignedShort()
            } else upstreamPeer.connect(InetSocketAddress(host,targetPort),10000)
            upstreamPeer.soTimeout = 0; client.soTimeout = 0
            val established = Flow(client,upstreamPeer,service,route); flow = established; flows.add(established); totals.forEach { it.connections.incrementAndGet() }
            upstreamPeer.outputStream.write(prefix.bytes); upstreamPeer.outputStream.flush(); totals.forEach { it.tx.addAndGet(prefix.bytes.size.toLong()); it.writes.incrementAndGet() }
            val stats = totals
            val upload = workers.submit {
                try { pump(input,upstreamPeer.outputStream) { n -> stats.forEach { it.tx.addAndGet(n.toLong()); it.writes.incrementAndGet() } }; runCatching { upstreamPeer.shutdownOutput() } }
                catch (_: Exception) { runCatching { upstreamPeer.close() }; runCatching { client.close() } }
            }
            try { pump(upstreamPeer.inputStream,output) { n -> stats.forEach { it.rx.addAndGet(n.toLong()) } } }
            finally { runCatching { client.close() }; runCatching { upstreamPeer.close() }; upload.cancel(true) }
        } catch (_: Exception) { if (flow == null) { meter?.failures?.incrementAndGet(); routeMeter?.failures?.incrementAndGet() } }
        finally {
            flow?.let { flows.remove(it); meter?.connections?.decrementAndGet(); routeMeter?.connections?.decrementAndGet(); it.route?.let { route -> if (connections(route) == 0) onDrained?.invoke(route) } }
            sockets.remove(client); runCatching { client.close() }
            peer?.let { sockets.remove(it); runCatching { it.close() } }
        }
    }
    private fun pump(input: InputStream,output: OutputStream,count: (Int)->Unit) {
        val buffer = ByteArray(32768)
        while (running.get()) { val n = input.read(buffer); if (n < 0) break; if (n > 0) { output.write(buffer,0,n); output.flush(); count(n) } }
    }
    private fun udp(client: Socket,output: OutputStream) {
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use { relay ->
            sockets.add(relay)
            output.write(byteArrayOf(5,0,0,1,127,0,0,1,(relay.localPort ushr 8).toByte(),relay.localPort.toByte())); output.flush()
            client.soTimeout = 0
            val control = workers.submit { try { while (client.inputStream.read() >= 0) { } } finally { relay.close() } }
            var source: SocketAddress? = null
            try {
                while (running.get() && !relay.isClosed) {
                    val packet = DatagramPacket(ByteArray(65507),65507); relay.receive(packet)
                    if (!packet.address.isLoopbackAddress || (source != null && source != packet.socketAddress)) continue
                    if (source == null) source = packet.socketAddress
                    val data = DataInputStream(ByteArrayInputStream(packet.data,0,packet.length))
                    if (data.readUnsignedShort() != 0 || data.readUnsignedByte() != 0) continue
                    val (host,encoded) = address(data); val target = data.readUnsignedShort()
                    if (target == 443 || target == 0) continue // Force QUIC fallback to TCP for selected apps.
                    val payload = ByteArray(data.available()).also { data.readFully(it) }; val destination = packet.socketAddress
                    try { workers.execute {
                        runCatching { DatagramSocket().use { remote ->
                            sockets.add(remote)
                            try {
                                remote.soTimeout = 5000; remote.connect(InetAddress.getByName(host),target)
                                remote.send(DatagramPacket(payload,payload.size))
                                val response = DatagramPacket(ByteArray(65507),65507); remote.receive(response)
                                val wrapped = byteArrayOf(0,0,0)+encoded+byteArrayOf((target ushr 8).toByte(),target.toByte())+response.data.copyOf(response.length)
                                if (!relay.isClosed) relay.send(DatagramPacket(wrapped,wrapped.size,destination))
                            } finally { sockets.remove(remote) }
                        } }
                    } } catch (_: RejectedExecutionException) { }
                }
            } finally { sockets.remove(relay); control.cancel(true) }
        }
    }
    override fun close() {
        running.set(false); runCatching { listener?.close() }
        sockets.toList().forEach { runCatching { it.close() } }; sockets.clear(); workers.shutdownNow(); flows.clear()
    }
}
