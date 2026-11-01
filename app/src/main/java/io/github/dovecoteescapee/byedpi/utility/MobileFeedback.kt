package io.github.dovecoteescapee.byedpi.utility

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MobileFeedback {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val requested = ConcurrentHashMap<String,AtomicLong>()
    fun request(id: String) { require(id in listOf("instagram","youtube")); (requested[id] ?: AtomicLong().let { requested.putIfAbsent(id,it) ?: it }).incrementAndGet(); wake() }
    fun token(id: String) = requested[id]?.get() ?: 0L
    fun wake() { signals.trySend(Unit) }
    suspend fun waitFor(millis: Long) { withTimeoutOrNull(millis) { signals.receive() } }
    @Volatile var traffic = "Нет измерений трафика"
}
