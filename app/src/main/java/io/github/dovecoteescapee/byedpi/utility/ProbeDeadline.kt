package io.github.dovecoteescapee.byedpi.utility

import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A wall-clock limit also bounds DNS/SOCKS, TLS, and a peer dripping HTTP bytes. */
class ProbeDeadline(timeoutMillis: Long) : AutoCloseable {
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task,"probe-deadlines").apply { isDaemon = true }
        }
    }
    private val socket = AtomicReference<Socket?>()
    private val stopped = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val future = timer.schedule({ expire() },timeoutMillis,TimeUnit.MILLISECONDS)
    internal fun expire() { expired.set(true); stopped.set(true); runCatching { socket.get()?.close() } }
    fun register(value: Socket) {
        socket.set(value)
        if (stopped.get()) { value.close(); throw SocketTimeoutException("Probe deadline exceeded") }
    }
    val timedOut: Boolean get() = expired.get()
    fun check() { if (expired.get()) throw SocketTimeoutException("Probe deadline exceeded") }
    override fun close() { stopped.set(true); future.cancel(false); runCatching { socket.get()?.close() } }
}
