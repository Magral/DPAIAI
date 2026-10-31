package io.github.dovecoteescapee.byedpi.utility

import android.content.*
import android.os.*
import io.github.dovecoteescapee.byedpi.services.TuningWorkerService
import kotlinx.coroutines.*
import org.json.JSONObject

object MobileCandidate {
    suspend fun run(context: Context,target: String,command: String): JSONObject {
        val connected = CompletableDeferred<IBinder>()
        val result = CompletableDeferred<String>()
        val died = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val callback = Messenger(Handler(Looper.getMainLooper()) { msg ->
            result.complete(msg.data.getString("result") ?: "{}"); true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                try { service.linkToDeath({ died.complete(Unit) },0); connected.complete(service) }
                catch (e: RemoteException) { connected.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                died.complete(Unit); result.completeExceptionally(IllegalStateException("Worker stopped"))
            }
        }
        val bound = withContext(Dispatchers.Main) { context.bindService(Intent(context,TuningWorkerService::class.java),connection,Context.BIND_AUTO_CREATE) }
        check(bound)
        try {
            val remote = Messenger(withTimeout(5000) { connected.await() })
            remote.send(Message.obtain().apply {
                replyTo = callback
                data = Bundle().apply { putString("target",target); putString("command",command) }
            })
            return JSONObject(withTimeout(240000L) { result.await() })
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { context.unbindService(connection) }
                if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
            }
        }
    }
}
