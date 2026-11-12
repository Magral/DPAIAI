package io.github.dovecoteescapee.byedpi.utility

import android.content.*
import android.os.*
import io.github.dovecoteescapee.byedpi.services.TuningWorkerService
import kotlinx.coroutines.*
import org.json.JSONObject

object MobileCandidate {
    private var mediaNetwork=""
    private var mediaUntil=0L
    private var mediaUrls: List<String> = emptyList()
    suspend fun run(context: Context,target: String,command: String,known: Boolean = false,
        previews: Boolean = true,fast: Boolean = false,qualityOnly: Boolean = false,dns: String = "",onProgress: (ProbeProgress)->Unit = {}): JSONObject {
        val network=MobileNetwork.signature(context)
        if(mediaNetwork!=network || android.os.SystemClock.elapsedRealtime()>=mediaUntil) { mediaUrls=emptyList();mediaNetwork=network }
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
                data = Bundle().apply { putStringArrayList("mediaUrls",java.util.ArrayList(mediaUrls));putString("dns",dns);putString("target",target); putString("command",command);putBoolean("known",known);putBoolean("previews",previews);putBoolean("fast",fast);putBoolean("qualityOnly",qualityOnly) }
            })
            val answer=JSONObject(withTimeout(225000L) { result.await() })
            answer.optJSONObject("media")?.let { media ->
                val urls=answer.optJSONArray("_sampleUrls")
                if(media.optString("status")=="confirmed" && urls!=null) {
                    mediaUrls=(0 until urls.length()).map { urls.getString(it) }.filter { MobileVideoProbe.mediaUrl(it) }
                    mediaUntil=android.os.SystemClock.elapsedRealtime()+10*60*1000L
                } else if(media.optString("status")=="unavailable")mediaUrls=emptyList()
                answer.remove("_sampleUrls") // Signed playback URLs never enter history, exports or diagnostics.
            }
            answer.remove("_sampleUrls")
            return answer
        } finally {
            accepting.set(false)
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { context.unbindService(connection) }
                if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
            }
        }
    }
}
