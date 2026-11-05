package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*

class TvParallelProbeTest {
    @Test(timeout=5000) fun firstSocksFailureClosesOtherHostsAndNeverQualifies() {
        ServerSocket(0,3,InetAddress.getLoopbackAddress()).use { server ->
            val pool = Executors.newFixedThreadPool(4)
            val entered = CountDownLatch(3)
            val closed = CountDownLatch(2)
            val peers = CopyOnWriteArrayList<Socket>()
            val accept = pool.submit {
                repeat(3) { position ->
                    val peer = server.accept();peers.add(peer)
                    pool.submit {
                        peer.use {
                            val input = peer.getInputStream()
                            repeat(3) { assertTrue(input.read() >= 0) }
                            entered.countDown()
                            assertTrue(entered.await(2,TimeUnit.SECONDS))
                            if (position == 0) {
                                peer.getOutputStream().write(byteArrayOf(5,0xff.toByte()))
                                peer.getOutputStream().flush()
                            } else {
                                assertEquals(-1,input.read());closed.countDown()
                            }
                        }
                    }
                }
            }
            try {
                val result = TvProbe.check(server.localPort,0,TvProbe.Session())
                assertFalse(result.ok);assertFalse(result.quality.eligible)
                assertTrue(result.detail.contains("/6"))
                accept.get(2,TimeUnit.SECONDS)
                assertTrue(closed.await(2,TimeUnit.SECONDS))
            } finally { peers.forEach { it.close() };pool.shutdownNow() }
        }
    }

    @Test fun cancelledSessionClosesAllHostsAndFutureSockets() {
        val session = TvProbe.Session()
        val sockets = List(3) { Socket().also(session::register) }
        session.cancel()
        assertTrue(sockets.all { it.isClosed })
        val late = Socket()
        try { session.register(late);fail("Accepted cancelled socket") }
        catch (_: CancellationException) { assertTrue(late.isClosed) }
    }
}
