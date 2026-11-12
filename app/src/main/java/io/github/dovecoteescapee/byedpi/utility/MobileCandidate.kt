package io.github.dovecoteescapee.byedpi.utility

import android.content.*
import android.os.*
import io.github.dovecoteescapee.byedpi.services.TuningWorkerService
import kotlinx.coroutines.*
import org.json.JSONObject

object MobileCandidate {
    suspend fun run(context: Context,target: String,command: String,known: Boolean = false,
        previews: Boolean = true,fast: Boolean = false,qualityOnly: Boolean = false,onProgress: (ProbeProgress)->Unit = {}): JSONObject {
        val connected = CompletableDeferred<IBinder>()
        val result = CompletableDeferred<String>()
        val died = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val callback = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (accepting.get()) when (msg.what) {
                TuningWorkerService.PROGRESS -> runCatching { ProbeProgress.decode(msg.data.getString("progress").orEmpty()) }.getOrNull()?.let(onProgress)
                TuningWorkerService.RESULT -> msg.data.getString("result")?.let { result.complete(it) }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                try { service.linkToDeath({ died.complete(Unit); result.completeExceptionally(IllegalStateException("Worker process died")) },0); connected.complete(service) }
                catch (e: RemoteException) { connected.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                died.complete(Unit); result.completeExceptionally(IllegalStateException("Worker stopped"))
            }
        }
        val bound = withContext(Dispatchers.Main) { context.bindService(Intent(context,TuningWorkerService::class.java),connection,Context.BIND_AUTO_CREATE) }
        check(bound) { "Cannot bind tuning worker" }
        onProgress(ProbeProgress(0,ServiceTargets.target(target).endpoints.size*2))
        try {
            val remote = Messenger(withTimeout(5000) { connected.await() })
            remote.send(Message.obtain().apply {
                replyTo = callback
                data = Bundle().apply { putString("target",target); putString("command",command);putBoolean("known",known);putBoolean("previews",previews);putBoolean("fast",fast);putBoolean("qualityOnly",qualityOnly) }
            })
            return JSONObject(withTimeout(225000L) { result.await() })
        } finally {
            accepting.set(false)
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { context.unbindService(connection) }
                if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
            }
        }
    }
}
