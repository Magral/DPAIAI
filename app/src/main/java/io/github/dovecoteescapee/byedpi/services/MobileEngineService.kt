package io.github.dovecoteescapee.byedpi.services

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.dovecoteescapee.byedpi.utility.MobilePlan
import io.github.dovecoteescapee.byedpi.core.MobileProxy

open class MobileEngineService : Service() {
    protected open val nativePort: Int = 1080
    private var used = false
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!used) {
            used = true
            val command = message.data.getString("command")
            val reply = message.replyTo
            if (command == null || !MobilePlan.valid(command)) { stopSelf(); return@Handler true }
            Thread {
                var answered = false
                runCatching {
                    MobileProxy().run(command,nativePort) { ready ->
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

class InstagramAService : MobileEngineService() { override val nativePort = 19410 }
class InstagramBService : MobileEngineService() { override val nativePort = 19411 }
class YoutubeAService : MobileEngineService() { override val nativePort = 19420 }
class YoutubeBService : MobileEngineService() { override val nativePort = 19421 }
