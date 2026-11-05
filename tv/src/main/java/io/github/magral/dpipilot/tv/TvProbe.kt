package io.github.magral.dpipilot.tv

import android.os.Build
import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import io.github.dovecoteescapee.byedpi.utility.ParallelProbe
import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import io.github.dovecoteescapee.byedpi.utility.ProbeDeadline
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
    class Result(ok: Boolean,val detail: String,val score: Int = 0,val quality: ProbeQuality = ProbeQuality(ok,score,0)) {
        val ok: Boolean = ok && score == 6 && quality.eligible
    }
    class Session : ProbeSession()
    private data class Sample(val error: String?,val elapsedMs: Long,val row: String)
    fun check(port: Int,index: Int,session: Session,progress: (String) -> Unit = {}): Result {
        val samples = mutableListOf<Sample>()
        try {
            for (round in 1..2) {
                session.checkActive()
                val child = Session()
                val completed = ParallelProbe.round(specs.size,session::checkActive,{ it.error == null },
                    child::cancel) { position ->
                    session.checkActive();child.checkActive()
                    val spec = specs[position]
                    val label = "${(round-1)*specs.size+position+1}/6 · ${spec.host} · раунд $round"
                    progress("$label: проверяется…")
                    val started = System.nanoTime()
                    val error = try { request(spec,port,TvPolicy.probeReadTimeout(index),session,child) }
                        catch (e: java.util.concurrent.CancellationException) { throw e }
                        catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(180)}" }
                    session.checkActive();child.checkActive()
                    val row = "$label: ${error ?: "OK"}"
                    progress(row)
                    Sample(error,((System.nanoTime()-started)/1000000).coerceAtLeast(1),row)
                }
                samples.addAll(completed.map { it.value })
                if (completed.size != specs.size || completed.any { it.value.error != null }) break
            }
            session.checkActive()
            val score = samples.count { it.error == null }
            // Parallel wall time divided by host count would understate per-request latency.
            val latency = samples.map { it.elapsedMs }.average().toLong().coerceAtLeast(1)
            val quality = ProbeQuality(score == 6,score,latency)
            return Result(quality.eligible,"$score/6 проверок узлов (завершено ${samples.size}); средняя задержка $latency мс; "+samples.joinToString(" | ") { it.row },score,quality)
        } finally { session.cancel() }
    }
    private fun request(spec: Spec,port: Int,timeout: Int,session: Session,child: Session): String? {
        val deadline = ProbeDeadline(timeout.toLong()+3000)
        try {
            val result = TargetProbe.socket(spec.host,443,port,timeout,{ session.register(it);child.register(it);deadline.register(it) }).use { raw ->
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
            deadline.check()
            return result
        } finally { deadline.close() }
    }
}
