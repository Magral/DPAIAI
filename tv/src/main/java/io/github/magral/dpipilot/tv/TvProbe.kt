package io.github.magral.dpipilot.tv

import android.os.Build
import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import java.io.BufferedInputStream
import java.io.IOException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object TvProbe {
    private data class Spec(val kind: String,val host: String,val path: String)
    private val specs = listOf(
        Spec("youtube","www.youtube.com","/generate_204"),
        Spec("image","i.ytimg.com","/vi/jNQXAC9IVRw/hqdefault.jpg"),
        Spec("cdn","redirector.googlevideo.com","/"))
    data class Result(val ok: Boolean,val detail: String,val score: Int = 0,val quality: ProbeQuality = ProbeQuality(ok,score,0))
    class Session {
        private val socket = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun register(next: java.net.Socket) {
            socket.set(next)
            if (stopped.get()) { next.close(); throw java.util.concurrent.CancellationException() }
        }
        fun cancel() { stopped.set(true); runCatching { socket.get()?.close() } }
        fun cancelled(): Boolean = stopped.get()
    }
    fun check(port: Int,index: Int,session: Session,progress: (String) -> Unit = {}): Result {
        val started = System.nanoTime()
        var count = 0
        var score = 0
        val rows = mutableListOf<String>()
        for (round in 1..2) {
            for (spec in specs) {
                if (session.cancelled()) throw java.util.concurrent.CancellationException()
                count++
                val label = "$count/6 · ${spec.host} · раунд $round"
                progress("$label: проверяется…")
                val error = try { request(spec,port,TvPolicy.probeReadTimeout(index),session) }
                    catch (e: java.util.concurrent.CancellationException) { throw e }
                    catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(180)}" }
                if (session.cancelled()) throw java.util.concurrent.CancellationException()
                if (error == null) score++
                val row = "$label: ${error ?: "OK"}"
                rows.add(row); progress(row)
            }
            // Diagnose all three hosts even when the first fails. Repeat only a fully successful round.
            if (round == 1 && score != 3) break
        }
        val latency = ((System.nanoTime()-started)/1000000/count.coerceAtLeast(1)).coerceAtLeast(1)
        val quality = ProbeQuality(score == 6,score,latency)
        return Result(quality.eligible,"$score/$count проверок узлов; средняя задержка $latency мс; "+rows.joinToString(" | "),score,quality)
    }
    private fun request(spec: Spec,port: Int,timeout: Int,session: Session): String? = TargetProbe.socket(spec.host,443,port,12000,session::register).use { raw ->
        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,spec.host,443,true) as SSLSocket).use tlsBlock@{ tls ->
            tls.soTimeout = timeout
            if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(spec.host,tls.session))
                throw IOException("TLS hostname mismatch")
            if (spec.kind == "cdn") return@tlsBlock null
            val range = if (spec.kind == "image") "Range: bytes=0-1023\r\n" else ""
            tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: ${spec.host}\r\nUser-Agent: DPI-Pilot-TV/${BuildConfig.VERSION_NAME}\r\nAccept: */*\r\nAccept-Encoding: identity\r\n${range}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val response = HttpCodec.read(BufferedInputStream(tls.inputStream),1024)
            val contentType = response.headers["content-type"].orEmpty()
            val accepted = if (spec.kind == "image") TvPolicy.acceptsPreview(response.status,contentType,response.body)
                else TvPolicy.accepts(spec.kind,response.status,contentType,response.body.size)
            if (accepted) null
                else "HTTP ${response.status}; type=$contentType; bytes=${response.body.size}"
        }
    }
}
