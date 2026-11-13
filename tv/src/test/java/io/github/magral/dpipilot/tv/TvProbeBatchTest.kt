package io.github.magral.dpipilot.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TvProbeBatchTest {
    @Test fun deadlineClosedSocketRemainsATimeout() {
        val deadline=io.github.dovecoteescapee.byedpi.utility.ProbeDeadline(60000)
        try {
            TvProbe.deadlineGuard<Unit>(deadline) { deadline.expire();throw java.net.SocketException("Socket closed") }
            fail("Deadline was not classified")
        } catch(expected: java.net.SocketTimeoutException) {
            assertTrue(deadline.timedOut)
        } finally { deadline.close() }
    }

    @Test fun strategyTimeoutDoesNotMeanTheSharedTestSourceIsUnavailable() {
        assertFalse(TvProbe.Result(false,"timeout",inconclusive=true).resourceUnavailable)
        assertTrue(TvProbe.Result(false,"HTTP 404",inconclusive=true,resourceUnavailable=true).resourceUnavailable)
    }
    @Test fun failedCoreRequestDoesNotCancelOtherCoreResults() {
        val first=CountDownLatch(1);val aborts=AtomicInteger()
        val values=TvProbe.completeBatch(3,{}, { aborts.incrementAndGet() }) { index ->
            if(index==0) {first.countDown();false} else {first.await();true}
        }
        assertEquals(3,values.size);assertEquals(listOf(false,true,true),values.map { it.value });assertEquals(1,aborts.get())
    }
}
