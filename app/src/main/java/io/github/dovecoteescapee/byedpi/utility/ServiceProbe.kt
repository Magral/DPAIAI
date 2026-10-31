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
    fun check(id: String,command: String,port: Int,session: Session = Session()): JSONObject {
        val target = ServiceTargets.target(id); val rows = JSONArray(); var success = 0; var publicSuccess = 0; var publicTotal = 0
        val start = System.nanoTime()
        repeat(2) { round ->
            for (spec in target.endpoints) {
                session.checkActive()
                var transport = false; var content = false; var detail = ""
                if (spec.kind != "tls") publicTotal++
                try {
                    TargetProbe.socket(spec.host,443,port,MobileStrategyPolicy.timeout(command),session::register).use { raw ->
                        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,spec.host,443,true) as SSLSocket).use { tls ->
                            tls.soTimeout = MobileStrategyPolicy.timeout(command)
                            if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            tls.startHandshake()
                            if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(spec.host,tls.session)) throw IOException("TLS hostname mismatch")
                            transport = true; detail = "TLS OK"
                            if (spec.kind != "tls") {
                                val range = if (spec.kind == "image") "Range: bytes=0-1023\r\n" else ""
                                tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: ${spec.host}\r\nUser-Agent: Mozilla/5.0 (Android) DPI-Pilot\r\nAccept-Encoding: identity\r\n${range}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)); tls.outputStream.flush()
                                val response = HttpCodec.read(BufferedInputStream(tls.inputStream),1024)
                                content = publicResponse(spec.kind,response.status,response.headers["content-type"].orEmpty(),response.body)
                                detail = "TLS OK; HTTP ${response.status}"+(if (!content) "; публичный контент не подтверждён" else "; публичный ответ OK")
                            }
                        }
                    }
                } catch (e: Exception) { detail += "; ${e.javaClass.simpleName}: ${e.message?.take(140)}" }
                session.checkActive()
                // Instagram's anonymous HTTP restrictions do not negate a successful TLS session.
                val ok = transport && (id == "instagram" || spec.kind == "tls" || content)
                if (ok) success++
                if (content) publicSuccess++
                rows.put(JSONObject().put("host",spec.host).put("kind",spec.kind).put("ok",ok).put("public",content).put("detail",detail).put("round",round+1))
            }
        }
        return JSONObject().put("success",success).put("total",rows.length()).put("publicSuccess",publicSuccess).put("publicTotal",publicTotal)
            .put("elapsed",(System.nanoTime()-start)/1000000).put("rows",rows)
    }
}
