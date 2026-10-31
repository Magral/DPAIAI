package io.github.dovecoteescapee.byedpi.utility

import android.os.Build
import io.github.dovecoteescapee.byedpi.catalog.HttpCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import javax.net.ssl.*

object ServiceProbe {
    class Session {
        private val raw = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun register(socket: java.net.Socket) { raw.set(socket); checkActive(); }
        fun checkActive() { if (stopped.get()) { raw.get()?.close(); throw java.util.concurrent.CancellationException() } }
        fun cancel() { stopped.set(true); runCatching { raw.get()?.close() } }
    }
    fun publicResponse(kind: String,status: Int,type: String,body: ByteArray): Boolean = when (kind) {
        "youtube" -> status == 204
        "instagram" -> status in listOf(200,204,301,302,303,307,308)
        "image" -> status in listOf(200,206) && type.startsWith("image/jpeg",true) && body.size >= 16 &&
            (body[0].toInt() and 255) == 255 && (body[1].toInt() and 255) == 216 && (body[2].toInt() and 255) == 255
        else -> false
    }
    fun check(id: String,command: String,port: Int,session: Session = Session(),onProgress: (String)->Unit = {}): JSONObject {
        val target = ServiceTargets.target(id); val rows = JSONArray(); var success = 0; var publicSuccess = 0; var publicTotal = 0
        val start = System.nanoTime()
        val failed = mutableSetOf<String>()
        val expectedTotal = target.endpoints.size*2
        fun remaining() = 180000L-(System.nanoTime()-start)/1000000
        repeat(2) { round ->
            for (spec in target.endpoints) {
                session.checkActive()
                var transport = false; var content = false; var detail = ""; var stage = "start"
                val prefix = "${rows.length()+1}/$expectedTotal · ${spec.host} · раунд ${round+1}"
                fun progress(value: String) { stage = value; onProgress("$prefix: $value") }
                if (spec.kind != "tls") publicTotal++
                val skip = spec.host in failed || remaining() <= 0
                val budget = minOf(MobileStrategyPolicy.timeout(command).toLong()+3000,remaining()).coerceAtLeast(1)
                val deadline = ProbeDeadline(budget)
                try {
                    if (skip) throw IOException(if (spec.host in failed) "Повтор пропущен: предыдущая проверка узла не пройдена" else "Общий лимит стратегии 180 с")
                    progress("подключение; лимит узла ${budget/1000} с")
                    TargetProbe.socket(spec.host,443,port,MobileStrategyPolicy.timeout(command),{
                        session.register(it); deadline.register(it)
                    }) { value -> progress(when (value) {
                        "local proxy connect" -> "соединение с локальным движком"
                        "SOCKS authentication" -> "согласование SOCKS5"
                        else -> "DNS / TCP к серверу через SOCKS5"
                    }) }.use { raw ->
                        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,spec.host,443,true) as SSLSocket).use { tls ->
                            tls.soTimeout = MobileStrategyPolicy.timeout(command)
                            if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            progress("TLS: ожидаем защищённое соединение")
                            tls.startHandshake()
                            if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(spec.host,tls.session)) throw IOException("TLS hostname mismatch")
                            transport = true; detail = "TLS OK"
                            if (spec.kind != "tls") {
                                val range = if (spec.kind == "image") "Range: bytes=0-1023\r\n" else ""
                                tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: ${spec.host}\r\nUser-Agent: Mozilla/5.0 (Android) DPI-Pilot\r\nAccept-Encoding: identity\r\n${range}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)); tls.outputStream.flush()
                                progress("HTTP: ожидаем публичный ответ")
                                val response = HttpCodec.read(BufferedInputStream(tls.inputStream),1024)
                                content = publicResponse(spec.kind,response.status,response.headers["content-type"].orEmpty(),response.body)
                                detail = "TLS OK; HTTP ${response.status}"+(if (!content) "; публичный контент не подтверждён" else "; публичный ответ OK")
                            }
                        }
                    }
                    deadline.check()
                } catch (e: Exception) { detail += if (deadline.timedOut) "; $stage: общий лимит узла ${budget/1000} с" else "; $stage: ${e.javaClass.simpleName}: ${e.message?.take(160)}" }
                finally { deadline.close() }
                session.checkActive()
                // Instagram's anonymous HTTP restrictions do not negate a successful TLS session.
                val ok = transport && (id == "instagram" || spec.kind == "tls" || content)
                if (ok) success++ else failed.add(spec.host)
                if (content) publicSuccess++
                onProgress("$prefix: ${if (ok) "OK" else "FAIL"}; $detail")
                rows.put(JSONObject().put("host",spec.host).put("kind",spec.kind).put("ok",ok).put("public",content).put("detail",detail).put("stage",stage).put("skipped",skip).put("round",round+1))
            }
        }
        return JSONObject().put("success",success).put("total",rows.length()).put("publicSuccess",publicSuccess).put("publicTotal",publicTotal)
            .put("elapsed",(System.nanoTime()-start)/1000000).put("rows",rows)
    }
}
