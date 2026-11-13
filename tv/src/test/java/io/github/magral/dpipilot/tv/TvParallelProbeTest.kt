package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*

class TvParallelProbeTest {
    @Test(timeout=10000) fun firstSocksFailureStillCollectsOtherHostsAndNeverQualifies() {
        ServerSocket(0,3,InetAddress.getLoopbackAddress()).use { server ->
            val pool = Executors.newFixedThreadPool(4)
            val entered = CountDownLatch(3)
            val failed = CountDownLatch(1)
            val replies = CopyOnWriteArrayList<Future<*>>()
            val peers = CopyOnWriteArrayList<Socket>()
            val accept = pool.submit {
                repeat(3) { position ->
                    val peer = server.accept();peers.add(peer)
                    replies.add(pool.submit {
                        peer.use {
                            val input = peer.getInputStream()
                            repeat(3) { assertTrue(input.read() >= 0) }
                            entered.countDown()
                            assertTrue(entered.await(2,TimeUnit.SECONDS))
                            if (position != 0) {
                                assertTrue(failed.await(2,TimeUnit.SECONDS))
                                peer.soTimeout=150
                                try { input.read();fail("Another core request was cancelled after the first failure") }
                                catch (_: java.net.SocketTimeoutException) { /* Independent request remains open. */ }
                            }
                            peer.getOutputStream().write(byteArrayOf(5,0xff.toByte()))
                            peer.getOutputStream().flush()
                            if(position==0)failed.countDown()
                        }
                    })
                }
            }
            try {
                val result = TvProbe.check(server.localPort,0,TvProbe.Session())
                assertFalse(result.ok);assertFalse(result.quality.eligible)
                assertTrue(result.detail.contains("/6"))
                accept.get(2,TimeUnit.SECONDS)
                replies.forEach { it.get(2,TimeUnit.SECONDS) }
                assertEquals(3,Regex("SOCKS method rejected").findAll(result.detail).count())
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
