package io.github.magral.dpipilot.tv

import android.content.*
import android.os.*
import kotlinx.coroutines.*

internal class EngineLease(private val context: Context,private val unexpectedDeath: () -> Unit = {}) {
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val connected = CompletableDeferred<IBinder>()
    private val ready = CompletableDeferred<Boolean>()
    private val died = CompletableDeferred<Unit>()
    private var binder: IBinder? = null
    private var bound = false
    private val callback = Messenger(Handler(Looper.getMainLooper()) { msg -> ready.complete(msg.arg1 == 1); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName,service: IBinder) {
            binder = service
            try { service.linkToDeath({ died.complete(Unit); if (!closing.get()) unexpectedDeath() },0); connected.complete(service) }
            catch (e: RemoteException) { connected.completeExceptionally(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            died.complete(Unit)
            if (!closing.get()) unexpectedDeath()
            ready.completeExceptionally(IllegalStateException("Engine stopped"))
        }
    }
    suspend fun open(index: Int) {
        withContext(Dispatchers.Main) {
            bound = context.bindService(Intent(context,TvEngineService::class.java),connection,Context.BIND_AUTO_CREATE)
            check(bound) { "Cannot bind engine" }
        }
        withTimeout(10000) {
            val remote = Messenger(connected.await())
            remote.send(Message.obtain().apply { replyTo = this@EngineLease.callback; data = Bundle().apply { putInt("candidate",index) } })
            check(ready.await()) { "Engine did not start" }
        }
    }
    suspend fun close(): Unit = withContext(NonCancellable+Dispatchers.Main) {
        closing.set(true)
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
    }
}
