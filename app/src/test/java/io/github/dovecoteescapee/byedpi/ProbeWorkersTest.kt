package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.ParallelProbe
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ProbeWorkersTest {
    @Test fun sequentialRoundsReuseAtMostFourThreads() {
        val workers = mutableSetOf<Long>()
        repeat(24) {
            val result = ParallelProbe.round(1,{}, { true },{}) { Thread.currentThread().id }
            workers.add(result.single().value)
        }
        assertTrue(workers.size <= 4)
    }
    @Test fun cancelledBeforeSubmissionDoesNotStartWork() {
        val started = AtomicInteger()
        try {
            ParallelProbe.round(4,{ throw CancellationException() },{ true },{}) { started.incrementAndGet() }
            fail("Cancelled round was accepted")
        } catch (_: CancellationException) {}
        assertEquals(0,started.get())
    }
    @Test(timeout=5000) fun interruptionClosesWorkAndLaterRoundsStillRun() {
        val active = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val owner = Thread {
            try {
                ParallelProbe.round(1,{}, { true },{}) {
                    active.countDown()
                    try { CountDownLatch(1).await() } finally { ended.countDown() }
                    true
                }
            } catch (_: InterruptedException) {}
        }
        owner.start()
        assertTrue(active.await(2,TimeUnit.SECONDS))
        owner.interrupt();owner.join(2000)
        assertFalse(owner.isAlive)
        assertTrue(ended.await(2,TimeUnit.SECONDS))
        assertEquals(42,ParallelProbe.round(1,{}, { true },{}) { 42 }.single().value)
    }
    @Test(timeout=5000) fun failedCleanupStillCancelsItsWorker() {
        val active = CountDownLatch(1)
        val ended = CountDownLatch(1)
        try {
            ParallelProbe.round(2,{}, { it },{ throw IllegalStateException("cleanup") }) { i ->
                if (i == 0) { assertTrue(active.await(2,TimeUnit.SECONDS));false }
                else { active.countDown();try { CountDownLatch(1).await() } finally { ended.countDown() };true }
            }
            fail("Expected cleanup failure")
        } catch (_: IllegalStateException) {}
        assertTrue(ended.await(2,TimeUnit.SECONDS))
    }
}
