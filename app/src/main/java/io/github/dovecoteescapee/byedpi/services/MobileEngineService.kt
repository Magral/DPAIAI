package io.github.dovecoteescapee.byedpi.services

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import io.github.dovecoteescapee.byedpi.core.MobileProxy
import java.net.InetSocketAddress
import java.net.Socket

class MobileEngineService : Service() {
    private var used = false
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!used) {
            used = true
            val command = message.data.getString("command")
            val reply = message.replyTo
            if (command == null || !CatalogProtocol.validCommand(command)) { stopSelf(); return@Handler true }
            Thread {
                var answered = false
                runCatching {
                    MobileProxy().run(command,1080) { ready ->
                        answered = true
                        runCatching { reply.send(Message.obtain().apply { arg1 = if (ready) 1 else 0 }) }
                    }
                }
                if (!answered) runCatching { reply.send(Message.obtain().apply { arg1 = 0 }) }
                stopSelf()
            }.start()
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = control.binder
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() { super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }
}
