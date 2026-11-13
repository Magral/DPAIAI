package io.github.dovecoteescapee.byedpi.utility

import android.os.Build
import io.github.dovecoteescapee.byedpi.catalog.HttpCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import javax.net.ssl.*

object ServiceProbe {
    class Session : ProbeSession()
    fun publicResponse(kind: String,status: Int,type: String,body: ByteArray): Boolean = when (kind) {
        "youtube" -> status == 204
        "instagram" -> status in listOf(200,204,301,302,303,307,308)
        "image" -> status in listOf(200,206) && type.startsWith("image/jpeg",true) && body.size >= 16 &&
            (body[0].toInt() and 255) == 255 && (body[1].toInt() and 255) == 216 && (body[2].toInt() and 255) == 255
        else -> false
    }

    fun check(id: String,command: String,port: Int,session: Session = Session(),known: Boolean = false,
        previews: Boolean = true,fast: Boolean = false,onProgress: (ProbeProgress)->Unit = {}): JSONObject {
        try {
            val first = attempt(id,command,port,session,previews && !fast,fast,onProgress)
            if (fast || !MobileCheckPolicy.confirmFailure(id,first,known)) return first
            session.checkActive()
            onProgress(ProbeProgress(0,ServiceTargets.total(id),"retry"))
            Thread.sleep(750)
            session.checkActive()
            // Each attempt stands alone. Partial successes are never combined.
            return attempt(id,command,port,session,false,false,onProgress).put("retried",true)
        } finally { session.cancel() }
    }

    private fun attempt(id: String,command: String,port: Int,session: Session,previews: Boolean,fast: Boolean,
        onProgress: (ProbeProgress)->Unit): JSONObject {
        val target = ServiceTargets.target(id)
        val start = System.nanoTime()
        val rows = mutableListOf<JSONObject>()
        val progressLock = Any()
        var passed = 0
        var checked = 0
        onProgress(ProbeProgress(0,target.endpoints.size*ServiceTargets.rounds(id)))
            rounds@ for (round in 1..ServiceTargets.rounds(id)) {
                val groups=listOf(target.endpoints.indices.filter { target.endpoints[it].required },target.endpoints.indices.filter { !target.endpoints[it].required })
                for ((groupIndex,indices) in groups.withIndex()) {
                    session.checkActive()
                    val child=Session()
                    val completed=ParallelProbe.round(indices.size,session::checkActive,{ true },child::cancel) { index ->
                        session.checkActive();child.checkActive()
                        val row=request(target.endpoints[indices[index]],command,port,round,session,child,fast)
                        synchronized(progressLock) {
                            if(row.optBoolean("ok"))passed++
                            checked++;onProgress(ProbeProgress(passed,ServiceTargets.total(id),completed=checked))
                        }
                        row
                    }
                    rows.addAll(completed.map { it.value })
                    // Finish all core requests before deciding. Ancillary errors never cancel other checks.
                    if(completed.size!=indices.size || (groupIndex==0 && completed.any { !it.value.optBoolean("ok") })) break@rounds
                }
            }
            session.checkActive()
            val success = rows.count { it.optBoolean("ok") }
            val result = JSONObject().put("target",id).put("success",success).put("total",target.endpoints.size*ServiceTargets.rounds(id)).put("tested",rows.size)
                .put("elapsed",(System.nanoTime()-start)/1000000).put("rows",JSONArray(rows))
                .put("publicSuccess",rows.count { it.optBoolean("public") }).put("publicTotal",target.endpoints.count { it.kind in listOf("youtube","image") }*ServiceTargets.rounds(id))
                .put("inconclusive",rows.any { it.optBoolean("unavailable") || it.optBoolean("timeout") })
                .put("deferred",false)
            val a = ProbeAssessment.evaluate(id,result)
            result.put("deferred",fast && !a.eligible)
            if (id == "youtube" && a.eligible && previews) {
                onProgress(ProbeProgress(target.endpoints.size*ServiceTargets.rounds(id),target.endpoints.size*ServiceTargets.rounds(id),"preview"))
                result.put("preview",MobilePreviewProbe.check(port,MobileStrategyPolicy.timeout(command),session).encode())
            }
            return result.put("requiredSuccess",a.requiredSuccess).put("requiredTotal",a.requiredTotal)
                .put("extraSuccess",a.extraSuccess).put("extraTotal",a.extraTotal).put("publicEndpointsPassed",a.eligible)
                .put("latencyMs",if (a.eligible) ProbeAssessment.latency(result) else 0)
    }
    private fun request(spec: ServiceTargets.Endpoint,command: String,port: Int,round: Int,
        parent: Session,child: Session,fast: Boolean): JSONObject {
        val start = System.nanoTime()
        val readTimeout = MobileSearchPolicy.timeout(minOf(MobileStrategyPolicy.timeout(command),15000),fast)
        val deadline = ProbeDeadline(readTimeout.toLong()+3000)
        var stage = "start";var detail = "";var transport = false;var content = false;var failed = false;var unavailable = false;var timedOut = false
        fun report(value: String) { stage = value }
        try {
            report("DNS / TCP через локальный SOCKS5")
            TargetProbe.socket(spec.host,443,port,readTimeout,{ parent.register(it);child.register(it);deadline.register(it) }).use { raw ->
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,spec.host,443,true) as SSLSocket).use { tls ->
                    tls.soTimeout = readTimeout
                    if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    report("TLS");tls.startHandshake()
                    if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(spec.host,tls.session)) throw IOException("TLS hostname mismatch")
                    transport = true;detail = "TLS OK"
                    // Instagram eligibility is transport-only: avoid an HTTP wait that cannot change that verdict.
                    if (spec.kind in listOf("youtube","image")) {
                        val range = if (spec.kind == "image") "Range: bytes=0-1023\r\n" else ""
                        tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: ${spec.host}\r\nUser-Agent: DPI-Pilot\r\nAccept-Encoding: identity\r\n${range}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));tls.outputStream.flush()
                        report("HTTP")
                        val response = HttpCodec.read(BufferedInputStream(tls.inputStream),1024)
                        content = publicResponse(spec.kind,response.status,response.headers["content-type"].orEmpty(),response.body)
                        detail += "; HTTP ${response.status}"
                        unavailable = spec.kind == "image" && response.status in listOf(404,410)
                    }
                }
            }
            deadline.check()
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { timedOut = deadline.timedOut || generateSequence(e as Throwable?) { it.cause }.any { it is java.net.SocketTimeoutException };failed = true;detail += "; $stage: ${e.javaClass.simpleName}: ${e.message?.take(100)}" }
        finally { deadline.close() }
        parent.checkActive();child.checkActive()
        val ok = !failed && transport && (spec.kind !in listOf("youtube","image") || content)
        return JSONObject().put("host",spec.host).put("kind",spec.kind).put("round",round).put("ok",ok).put("public",content)
            .put("timeout",timedOut).put("skipped",false).put("unavailable",unavailable).put("detail",detail).put("stage",stage).put("elapsedMs",((System.nanoTime()-start)/1000000).coerceAtLeast(1))
    }
}
