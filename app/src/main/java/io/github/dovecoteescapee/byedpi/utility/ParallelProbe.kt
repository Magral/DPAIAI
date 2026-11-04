package io.github.dovecoteescapee.byedpi.utility

import java.util.concurrent.*

/** Parallel hosts, one strategy. First failure closes siblings; unfinished probes never qualify. */
object ParallelProbe {
    data class Item<T>(val index: Int,val value: T)
    fun <T> round(size: Int,checkActive: ()->Unit,accept: (T)->Boolean,abort: ()->Unit,run: (Int)->T): List<Item<T>> {
        val pool = Executors.newFixedThreadPool(minOf(4,size).coerceAtLeast(1)) { r -> Thread(r,"service-probe").apply { isDaemon = true } }
        val completed = ExecutorCompletionService<Item<T>>(pool)
        val futures = (0 until size).map { i -> completed.submit(Callable { Item(i,run(i)) }) }
        val rows = mutableListOf<Item<T>>()
        try {
            while (rows.size < size) {
                checkActive()
                val next = completed.poll(100,TimeUnit.MILLISECONDS) ?: continue
                val item = try { next.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
                rows.add(item)
                if (!accept(item.value)) break
            }
            return rows.sortedBy { it.index }
        } finally {
            abort();futures.forEach { it.cancel(true) };pool.shutdownNow()
        }
    }
}
