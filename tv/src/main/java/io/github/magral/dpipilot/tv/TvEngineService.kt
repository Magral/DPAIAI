package io.github.magral.dpipilot.tv

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyCmdPreferences
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket

/** One engine configuration per process; unbind destroys all native C globals. */
open class TvEngineService(private val proxyPort: Int = 19080) : Service() {
    private var used = false
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!used) {
            used = true
            val index = message.data.getInt("candidate",-1)
            val reply = message.replyTo
            if (index !in TvPolicy.strategies.indices) { stopSelf(); return@Handler true }
            Thread {
                runCatching { runBlocking { ByeDpiProxy().startProxy(ByeDpiProxyCmdPreferences("--ip 127.0.0.1 --port $proxyPort ${TvPolicy.strategies[index]}")) } }
            }.start()
            Thread {
                var ready = false
                repeat(40) {
                    if (!ready) {
                        ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1",proxyPort),100) }; true }.getOrDefault(false)
                        if (!ready) Thread.sleep(100)
                    }
                }
                runCatching { reply.send(Message.obtain().apply { arg1 = if (ready) 1 else 0 }) }
            }.start()
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = control.binder
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() { super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }
}

/** Disposable candidate engine: never carries the active YouTube tunnel. */
class TvScoutService : TvEngineService(19081)
