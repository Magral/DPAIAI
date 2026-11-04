package io.github.dovecoteescapee.byedpi.utility

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MobileFeedback {
    private val cancelled = AtomicLong()
    fun cancelToken() = cancelled.get()
    fun cancelScans(context: android.content.Context) {
        val profile = MobileStrategyPolicy.profile(context)
        val edit = context.getPreferences().edit()
        listOf("instagram","youtube").forEach { id -> edit.putLong("mobile_scan.$id.$profile",System.currentTimeMillis()).remove("mobile_selected.$id").remove("mobile_imported.$id") }
        edit.apply();cancelled.incrementAndGet();acknowledgeAll();wake()
    }
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val requested = ConcurrentHashMap<String,AtomicLong>()
    val consumed = ConcurrentHashMap<String,Long>()
    fun clearSignals() { while (signals.tryReceive().isSuccess) { } }
    fun acknowledgeAll() { requested.forEach { (id,token) -> consumed[id] = token.get() };clearSignals() }
    fun request(id: String) { require(id in listOf("instagram","youtube")); (requested[id] ?: AtomicLong().let { requested.putIfAbsent(id,it) ?: it }).incrementAndGet(); wake() }
    fun token(id: String) = requested[id]?.get() ?: 0L
    fun wake() { signals.trySend(Unit) }
    suspend fun waitFor(millis: Long) { if (millis == Long.MAX_VALUE) signals.receive() else withTimeoutOrNull(millis) { signals.receive() } }
    @Volatile private var snapshot = "Нет измерений трафика"
    @Volatile var provider: (() -> String)? = null
    var traffic: String
        get() = provider?.invoke() ?: snapshot
        set(value) { snapshot = value }
}
