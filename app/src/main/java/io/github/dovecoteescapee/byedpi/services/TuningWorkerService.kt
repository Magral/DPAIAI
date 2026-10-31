package io.github.dovecoteescapee.byedpi.services

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import io.github.dovecoteescapee.byedpi.core.MobileProxy
import io.github.dovecoteescapee.byedpi.utility.*
import org.json.JSONObject
import java.util.concurrent.Executors

class TuningWorkerService : Service() {
    companion object { const val PROGRESS = 1; const val RESULT = 2 }
    private var used = false
    private val executor = Executors.newSingleThreadExecutor()
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!used) {
            used = true
            val reply = message.replyTo
            val target = message.data.getString("target").orEmpty()
            val command = message.data.getString("command")
            executor.execute {
                fun progress(text: String) { runCatching { reply.send(Message.obtain().apply {
                    what = PROGRESS; data = Bundle().apply { putString("progress",text) }
                }) } }
                val result = try {
                    require(command != null && CatalogProtocol.validCommand(command))
                    ServiceTargets.target(target)
                    progress("Запуск движка 17.3 на 127.0.0.1:19080 (до 10 с)")
                    val ready = java.util.concurrent.CountDownLatch(1)
                    val bound = java.util.concurrent.atomic.AtomicBoolean(false)
                    Thread {
                        runCatching { MobileProxy().run(command,19080) { bound.set(it); ready.countDown() } }
                        ready.countDown()
                    }.start()
                    check(ready.await(10,java.util.concurrent.TimeUnit.SECONDS) && bound.get()) { "Native listener did not start within 10s" }
                    progress("Локальный движок готов; проверяем сеть")
                    ServiceProbe.check(target,command,19080,onProgress = ::progress)
                } catch (e: Exception) { JSONObject().put("success",0).put("total",0).put("error",e.javaClass.simpleName+": "+e.message.orEmpty().take(200)) }
                runCatching { reply.send(Message.obtain().apply { what = RESULT; data = Bundle().apply { putString("result",result.toString()) } }) }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = control.binder
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }
}
