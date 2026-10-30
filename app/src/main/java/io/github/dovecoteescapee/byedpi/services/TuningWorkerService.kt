package io.github.dovecoteescapee.byedpi.services

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyCmdPreferences
import io.github.dovecoteescapee.byedpi.utility.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** One candidate per process. Unbinding reclaims all C globals and sockets. */
class TuningWorkerService : Service() {
    private var used = false
    private val executor = Executors.newSingleThreadExecutor()
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!used) {
            used = true
            val reply = message.replyTo
            val id = message.data.getString("target") ?: ""
            val index = message.data.getInt("candidate")
            executor.execute {
                val result = try { runCandidate(id,index) } catch (_: Exception) {
                    JSONObject().put("success",0).put("total",0).put("native",false).put("rows",JSONArray()).put("error",true)
                }
                try { reply.send(Message.obtain().apply { data = Bundle().apply { putString("result",result.toString()) } }) }
                catch (_: RemoteException) { stopSelf() }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = control.binder
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        // This service is declared in :tuner; never terminate the UI/VPN process.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    private fun runCandidate(id: String, index: Int): JSONObject {
        val target = ServiceTargets.targets.first { it.id == id }
        val args = target.strategies[index]
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    ByeDpiProxy().startProxy(ByeDpiProxyCmdPreferences("--ip 127.0.0.1 --port 19080 $args"))
                }
            } catch (_: Exception) { /* Readiness check reports engine startup failure. */ }
        }.start()
        var ready = false
        repeat(30) {
            if (!ready) {
                ready = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1",19080),100) }; true }
                    catch (_: Exception) { false }
                if (!ready) Thread.sleep(100)
            }
        }
        check(ready)
        val rows = JSONArray()
        var success = 0
        var nativeOk = id != "telegram"
        val start = System.nanoTime()
        // Repeated fresh connections reduce single lucky successes.
        repeat(2) {
            target.hosts.forEach { host ->
                val ok = try { TargetProbe.https(host) } catch (_: Exception) { false }
                if (ok) success++
                rows.put(JSONObject().put("host",host).put("ok",ok))
            }
            if (id == "telegram") {
                val ok = try { TargetProbe.telegram() } catch (_: Exception) { false }
                if (it == 0) nativeOk = ok else nativeOk = nativeOk && ok
                if (ok) success++
                rows.put(JSONObject().put("host","MTProto DC2").put("ok",ok))
            }
        }
        return JSONObject().put("success",success).put("total",rows.length())
            .put("native",nativeOk).put("elapsed",(System.nanoTime()-start)/1000000).put("rows",rows)
    }
}
