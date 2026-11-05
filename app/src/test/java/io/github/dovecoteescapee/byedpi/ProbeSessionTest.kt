package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.ParallelProbe
import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*

class ProbeSessionTest {
    @Test fun cancellationClosesEverySocketAndRejectsLateRegistration() {
        val session = ProbeSession()
        val sockets = List(4) { Socket().also(session::register) }
        session.cancel();session.cancel()
        assertTrue(sockets.all { it.isClosed })
        val late = Socket()
        try { session.register(late);fail("Late socket escaped cancellation") }
        catch (_: CancellationException) { assertTrue(late.isClosed) }
        try { session.checkActive();fail("Cancelled session remained active") }
        catch (_: CancellationException) { }
    }

    @Test(timeout=5000) fun firstFailureUnblocksRealSocketReadsAndCancelsQueuedWorkers() {
        ServerSocket(0,8,InetAddress.getLoopbackAddress()).use { server ->
            val session = ProbeSession()
            val waiting = CountDownLatch(3)
            val stopped = CountDownLatch(3)
            val peers = mutableListOf<Socket>()
            val acceptor = Executors.newSingleThreadExecutor()
            val accepted = acceptor.submit {
                repeat(3) { peers.add(server.accept()) }
            }
            try {
                val rows = ParallelProbe.round(8,{}, { it },session::cancel) { index ->
                    if (index == 0) {
                        assertTrue(waiting.await(2,TimeUnit.SECONDS));false
                    } else {
                        session.checkActive()
                        Socket(InetAddress.getLoopbackAddress(),server.localPort).use { socket ->
                            session.register(socket);waiting.countDown()
                            try { socket.getInputStream().read();true }
                            finally { stopped.countDown() }
                        }
                    }
                }
                assertFalse(rows.last().value)
                assertTrue(stopped.await(2,TimeUnit.SECONDS))
                assertTrue(session.cancelled())
                accepted.get(2,TimeUnit.SECONDS)
            } finally {
                session.cancel();peers.forEach { it.close() };acceptor.shutdownNow()
            }
        }
    }
}
