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

    fun check(id: String,command: String,port: Int,session: Session = Session(),onProgress: (String)->Unit = {}): JSONObject {
        val target = ServiceTargets.target(id)
        val start = System.nanoTime()
        val rows = mutableListOf<JSONObject>()
        try {
            for (round in 1..2) {
                session.checkActive()
                val child = Session()
                val completed = ParallelProbe.round(target.endpoints.size,session::checkActive,{ it.optBoolean("ok") },
                    child::cancel) { index ->
                    session.checkActive();child.checkActive()
                    request(target.endpoints[index],command,port,round,index,target.endpoints.size*2,session,child,onProgress)
                }
                rows.addAll(completed.map { it.value })
                if (completed.size != target.endpoints.size || completed.any { !it.value.optBoolean("ok") }) break
            }
            session.checkActive()
            val success = rows.count { it.optBoolean("ok") }
            val result = JSONObject().put("success",success).put("total",target.endpoints.size*2).put("tested",rows.size)
                .put("elapsed",(System.nanoTime()-start)/1000000).put("rows",JSONArray(rows))
                .put("publicSuccess",rows.count { it.optBoolean("public") }).put("publicTotal",target.endpoints.count { it.kind in listOf("youtube","image") }*2)
            val a = ProbeAssessment.evaluate(id,result)
            return result.put("requiredSuccess",a.requiredSuccess).put("requiredTotal",a.requiredTotal)
                .put("extraSuccess",a.extraSuccess).put("extraTotal",a.extraTotal).put("publicEndpointsPassed",a.eligible)
                .put("latencyMs",if (a.eligible) ProbeAssessment.latency(result) else 0)
        } finally { session.cancel() }
    }
    private fun request(spec: ServiceTargets.Endpoint,command: String,port: Int,round: Int,index: Int,total: Int,
        parent: Session,child: Session,progress: (String)->Unit): JSONObject {
        val start = System.nanoTime()
        val readTimeout = MobileStrategyPolicy.timeout(command)
        val deadline = ProbeDeadline(readTimeout.toLong()+3000)
        var stage = "start";var detail = "";var transport = false;var content = false;var failed = false
        val prefix = "${(round-1)*(total/2)+index+1}/$total · ${spec.host} · раунд $round"
        fun report(value: String) { stage = value;progress("$prefix: $value") }
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
                    }
                }
            }
            deadline.check()
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { failed = true;detail += "; $stage: ${e.javaClass.simpleName}: ${e.message?.take(100)}" }
        finally { deadline.close() }
        parent.checkActive();child.checkActive()
        val ok = !failed && transport && (spec.kind !in listOf("youtube","image") || content)
        progress("$prefix: ${if (ok) "OK" else "FAIL"}; $detail")
        return JSONObject().put("host",spec.host).put("kind",spec.kind).put("round",round).put("ok",ok).put("public",content)
            .put("skipped",false).put("detail",detail).put("stage",stage).put("elapsedMs",((System.nanoTime()-start)/1000000).coerceAtLeast(1))
    }
}
