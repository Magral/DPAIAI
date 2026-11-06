package io.github.dovecoteescapee.byedpi.utility

import java.util.concurrent.*

/** Reused bounded workers; idle threads expire and every round owns only its own futures. */
object ParallelProbe {
    private val pool = ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>(128),
        ThreadFactory { r -> Thread(r,"service-probe").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    data class Item<T>(val index: Int,val value: T)
    fun <T> round(size: Int,checkActive: ()->Unit,accept: (T)->Boolean,abort: ()->Unit,run: (Int)->T): List<Item<T>> {
        require(size in 0..32)
        val completed = LinkedBlockingQueue<FutureTask<Item<T>>>()
        val futures = mutableListOf<FutureTask<Item<T>>>()
        val rows = mutableListOf<Item<T>>()
        try {
            checkActive()
            for (i in 0 until size) {
                checkActive()
                val task = object : FutureTask<Item<T>>(Callable { checkActive();Item(i,run(i)) }) {
                    override fun done() { completed.offer(this) }
                }
                futures.add(task);pool.execute(task)
            }
            while (rows.size < size) {
                checkActive()
                val next = completed.poll(100,TimeUnit.MILLISECONDS) ?: continue
                val item = try { next.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
                rows.add(item)
                if (!accept(item.value)) break
            }
            return rows.sortedBy { it.index }
        } finally {
            try { abort() } finally {
                futures.forEach { it.cancel(true);pool.remove(it) }
            }
        }
    }
}
