package io.github.dovecoteescapee.byedpi.services

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MobileEngineLease(private val context: Context,private val failed: () -> Unit = {}) {
    private val closing = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private fun notifyFailure() { if (!closing.get() && reported.compareAndSet(false,true)) failed() }
    private val connected = CompletableDeferred<IBinder>()
    private val ready = CompletableDeferred<Boolean>()
    private val died = CompletableDeferred<Unit>()
    private var binder: IBinder? = null
    private var bound = false
    private val callback = Messenger(Handler(Looper.getMainLooper()) { msg -> ready.complete(msg.arg1 == 1); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName,service: IBinder) {
            binder = service
            try { service.linkToDeath({ died.complete(Unit); notifyFailure() },0); connected.complete(service) }
            catch (e: RemoteException) { connected.completeExceptionally(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            died.complete(Unit); notifyFailure()
            ready.completeExceptionally(IllegalStateException("Engine stopped"))
        }
    }
    suspend fun open(command: String) {
        withContext(Dispatchers.Main) { bound = context.bindService(Intent(context,MobileEngineService::class.java),connection,Context.BIND_AUTO_CREATE); check(bound) }
        withTimeout(10000) {
            Messenger(connected.await()).send(Message.obtain().apply { replyTo = this@MobileEngineLease.callback; data = Bundle().apply { putString("command",command) } })
            check(ready.await()) { "Engine startup failed" }
        }
    }
    suspend fun close() = withContext(NonCancellable+Dispatchers.Main) {
        closing.set(true)
        if (bound) { context.unbindService(connection); bound = false }
        if (binder?.isBinderAlive == true) withTimeout(5000) { died.await() }
    }
}
