package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.routing.*
import io.github.dovecoteescapee.byedpi.utility.*
import org.junit.Test
import org.junit.Assert.*
import java.net.*
import java.io.*
import java.util.concurrent.*

class RouterTest {
    private fun hello(host: String,fragment: Boolean = false): ByteArray {
        fun length(n: Int) = byteArrayOf((n ushr 8).toByte(),n.toByte())
        val name = host.toByteArray(); val list = byteArrayOf(0)+length(name.size)+name
        val extension = byteArrayOf(0,0)+length(list.size+2)+length(list.size)+list
        val body = byteArrayOf(3,3)+ByteArray(32)+byteArrayOf(0,0,2,0x13,1,1,0)+length(extension.size)+extension
        val handshake = byteArrayOf(1,0,(body.size ushr 8).toByte(),body.size.toByte())+body
        fun record(bytes: ByteArray) = byteArrayOf(22,3,1)+length(bytes.size)+bytes
        return if (fragment) record(handshake.copyOfRange(0,17))+record(handshake.copyOfRange(17,handshake.size)) else record(handshake)
    }
    @Test fun tlsSniffingPreservesExactBytesAndChecksDomainBoundaries() {
        for (fragment in listOf(false,true)) for (host in listOf("www.instagram.com","r1.googlevideo.com","youtube.com.evil.invalid")) {
            val bytes = hello(host,fragment); val prefix = HostInspector.read(ByteArrayInputStream(bytes))
            assertArrayEquals(bytes,prefix.bytes); assertEquals(host,prefix.host)
        }
        assertEquals("youtube",HostInspector.service("R1.GoogleVideo.Com."))
        assertNull(HostInspector.service("googlevideo.com.evil.invalid"))
        assertNull(HostInspector.service("notinstagram.com"))
    }
    private class Backend(val marker: Int) : AutoCloseable {
        val server = ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val peers = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val workers = Executors.newCachedThreadPool()
        init { workers.execute { while (!server.isClosed) runCatching { val peer = server.accept(); peers.add(peer); workers.execute { handle(peer) } } } }
        private fun handle(peer: Socket) { peer.use {
            val input = DataInputStream(it.inputStream); val output = it.outputStream
            check(input.readUnsignedByte() == 5); input.skipBytes(input.readUnsignedByte()); output.write(byteArrayOf(5,0)); output.flush()
            check(input.readUnsignedByte() == 5); input.skipBytes(2); when (input.readUnsignedByte()) {1->input.skipBytes(4);4->input.skipBytes(16);3->input.skipBytes(input.readUnsignedByte())}; input.readUnsignedShort()
            output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); output.flush()
            while (true) {
                val prefix = HostInspector.read(input); if (prefix.bytes.isEmpty()) break
                output.write(marker); output.write(prefix.bytes); output.flush()
            }
        } }
        override fun close() { server.close(); synchronized(peers) { peers.forEach { runCatching { it.close() } } }; workers.shutdownNow() }
    }
    private fun connect(port: Int,command: Int = 1): Socket {
        val peer = Socket("127.0.0.1",port); peer.soTimeout = 3000
        val input = DataInputStream(peer.inputStream); peer.outputStream.write(byteArrayOf(5,1,0)); assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
        peer.outputStream.write(byteArrayOf(5,command.toByte(),0,1,127,0,0,1,0,9)); assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte()); input.skipBytes(6)
        if (command == 1) input.skipBytes(2) // UDP caller reads relay port itself.
        return peer
    }
    private fun request(peer: Socket,host: String,marker: Int) {
        val bytes = "GET / HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray(); peer.outputStream.write(bytes)
        val input = DataInputStream(peer.inputStream);assertEquals(marker,input.readUnsignedByte());val actual = ByteArray(bytes.size);input.readFully(actual);assertArrayEquals(bytes,actual)
    }
    @Test fun switchingYoutubePreservesInstagramAndExistingYoutubeFlows() {
        Backend(65).use { instagram -> Backend(66).use { old -> Backend(67).use { next ->
            ServiceRouter(0).use { router ->
                val ig = ServiceRouter.Route(instagram.server.localPort,1);val yt = ServiceRouter.Route(old.server.localPort,2)
                router.route("instagram",ig);router.route("youtube",yt);router.start()
                connect(router.port).use { igPeer -> connect(router.port).use { oldPeer ->
                    request(igPeer,"www.instagram.com",65); request(oldPeer,"www.youtube.com",66)
                    router.route("youtube",ServiceRouter.Route(next.server.localPort,3))
                    request(igPeer,"www.instagram.com",65);request(oldPeer,"www.youtube.com",66)
                    connect(router.port).use { request(it,"www.youtube.com",67) }
                    assertTrue(router.stats("instagram").rx > 0);assertTrue(router.stats("youtube").tx > 0)
                    assertEquals(ig,router.active("instagram"));assertEquals(1,router.connections(yt))
                    router.route("youtube",yt) // Rollback sends only new flows to the previous backend.
                    connect(router.port).use { request(it,"www.youtube.com",66) }
                } }
            }
        } } }
    }
    @Test fun udpAssociationPreservesDatagramAndStopsWithItsControlConnection() {
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use { echo ->
            val worker = Thread { runCatching { val p = DatagramPacket(ByteArray(1024),1024);echo.receive(p);echo.send(p) } }.apply { isDaemon = true;start() }
            ServiceRouter(0).use { router -> router.start();connect(router.port,3).use { control ->
                val relay = DataInputStream(control.inputStream).readUnsignedShort()
                DatagramSocket(InetSocketAddress("127.0.0.1",0)).use { sender ->
                    sender.soTimeout = 3000
                    val payload = byteArrayOf(0,0,0,1,127,0,0,1,(echo.localPort ushr 8).toByte(),echo.localPort.toByte(),1,2,3,4)
                    sender.send(DatagramPacket(payload,payload.size,InetSocketAddress("127.0.0.1",relay)))
                    val reply = DatagramPacket(ByteArray(1024),1024);sender.receive(reply);assertArrayEquals(payload,reply.data.copyOf(reply.length))
                    sender.soTimeout = 200
                    val quic = payload.copyOf().apply { this[8]=1;this[9]=187.toByte() }
                    sender.send(DatagramPacket(quic,quic.size,InetSocketAddress("127.0.0.1",relay)))
                    try { sender.receive(reply);fail("QUIC unexpectedly forwarded") } catch (_: SocketTimeoutException) { }
                }
            } }
            worker.join(2000)
        }
    }
    @Test fun trafficWatchRequiresDemandAndTrialHasIndependentCounters() {
        val h = FlowHealth();h.observe(FlowHealth.Sample(1,0,0,0));h.observe(FlowHealth.Sample(45001,0,0,0));assertFalse(h.stalled)
        h.observe(FlowHealth.Sample(90001,20000,0,25));assertFalse(h.stalled)
        h.observe(FlowHealth.Sample(135001,40000,0,50));assertTrue(h.stalled)
        h.reset();h.observe(FlowHealth.Sample(140001,0,0,0));h.observe(FlowHealth.Sample(145001,100,300000,1));assertFalse(h.stalled);assertTrue(h.usefulMillis>0)
    }
}
