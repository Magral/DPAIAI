package io.github.dovecoteescapee.byedpi.utility

import java.net.Socket
import java.util.concurrent.CancellationException

/** Registration and cancellation are serialized: a late socket cannot escape cancellation. */
open class ProbeSession {
    private val sockets = mutableSetOf<Socket>()
    @Volatile private var stopped = false

    @Synchronized fun register(socket: Socket) {
        if (stopped) {
            runCatching { socket.close() }
            throw CancellationException()
        }
        sockets.add(socket)
    }

    fun checkActive() { if (stopped) throw CancellationException() }
    fun cancelled(): Boolean = stopped

    fun cancel() {
        val closing = synchronized(this) {
            stopped = true
            sockets.toList().also { sockets.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }
}
