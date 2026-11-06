package io.github.magral.dpipilot.tv

import android.os.Build
import android.graphics.BitmapFactory
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
        Spec("image-head","i.ytimg.com","/vi/jNQXAC9IVRw/hqdefault.jpg"),
        Spec("cdn","redirector.googlevideo.com","/"))
    // M7lc1UVf-VE is the public sample in the official YouTube IFrame API documentation.
    private val previewSpecs = listOf(
        Spec("image","i.ytimg.com","/vi/jNQXAC9IVRw/hqdefault.jpg"),
        Spec("image-jpeg","i.ytimg.com","/vi/M7lc1UVf-VE/hqdefault.jpg"),
        Spec("image-webp","i.ytimg.com","/vi_webp/M7lc1UVf-VE/hqdefault.webp"))
    class Result(ok: Boolean,val detail: String,val score: Int = 0,val quality: ProbeQuality = ProbeQuality(ok,score,0),val inconclusive: Boolean = false) {
        val ok: Boolean = ok && score == 6 && quality.eligible && !inconclusive
    }
    class Session : ProbeSession()
    private data class Payload(val error: String?,val bytes: Int = 0,val gap: Long = 0,val unavailable: Boolean = false)
    private data class Sample(val error: String?,val elapsedMs: Long,val row: String,val preview: PreviewSample?,val unavailable: Boolean)
    fun check(port: Int,index: Int,session: Session,progress: (String)->Unit = {}): Result {
        val nodes = mutableListOf<Sample>()
        val images = mutableListOf<Sample>()
        fun runBatch(items: List<Pair<Spec,Int>>): List<Sample> {
            val child = Session()
            return ParallelProbe.round(items.size,session::checkActive,{ it.error == null },child::cancel) { position ->
                session.checkActive();child.checkActive()
                val (spec,round) = items[position]
                val label = "${spec.host}${if (spec.kind.startsWith("image")) spec.path else ""} · раунд $round"
                progress("$label: проверяется…")
                val started = System.nanoTime()
                val result = try { request(spec,port,TvPolicy.probeReadTimeout(index),session,child) }
                    catch(e: java.util.concurrent.CancellationException) { throw e }
                    catch(e: Exception) { Payload("${e.javaClass.simpleName}: ${e.message?.take(140)}") }
                session.checkActive();child.checkActive()
                val ms = ((System.nanoTime()-started)/1000000).coerceAtLeast(1)
                val preview = if (spec.kind.startsWith("image") && spec.kind != "image-head") PreviewSample(spec.kind,round,result.bytes,ms,result.gap,result.error == null) else null
                val row = "$label: ${result.error ?: "OK"}; $ms мс; ${result.bytes} байт; пауза ${result.gap} мс"
                progress(row)
                Sample(result.error,ms,row,preview,result.unavailable)
            }.map { it.value }
        }
        try {
            for(round in 1..2) {
                val rows = runBatch(specs.map { it to round });nodes.addAll(rows)
                if(rows.size != specs.size || rows.any { it.error != null }) break
            }
            val score = nodes.count { it.error == null }
            if(score == 6) images.addAll(runBatch((1..2).flatMap { round -> previewSpecs.map { it to round } }))
            session.checkActive()
            val all = nodes+images
            val preview = PreviewQuality(all.mapNotNull { it.preview })
            val latency = nodes.map { it.elapsedMs }.average().toLong().coerceAtLeast(1)
            val quality = ProbeQuality(score == 6,score,latency,preview)
            val unavailable = nodes.any { it.unavailable }
            return Result(quality.eligible,"Узлы $score/6; ${preview.text()}; "+all.joinToString(" | ") { it.row },score,quality,unavailable)
        } finally { session.cancel() }
    }
    private fun request(spec: Spec,port: Int,timeout: Int,session: Session,child: Session): Payload {
        val image = spec.kind.startsWith("image")
        val basic = spec.kind == "image-head"
        val detailed = image && !basic
        val deadline = ProbeDeadline((if(detailed) minOf(timeout,10000) else timeout).toLong()+3000)
        try {
            val result = TargetProbe.socket(spec.host,443,port,timeout,{ session.register(it);child.register(it);deadline.register(it) }).use { raw ->
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,spec.host,443,true) as SSLSocket).use tlsBlock@{ tls ->
                    tls.soTimeout = if(detailed) minOf(timeout,10000) else timeout
                    if(Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    if(Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(spec.host,tls.session))
                        throw IOException("TLS hostname mismatch")
                    if(spec.kind == "cdn") return@tlsBlock Payload(null)
                    // Core access checks stay independent of slow transfers or vendor image decoders.
                    val range = if (basic) "Range: bytes=0-1023\r\n" else ""
                    tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: ${spec.host}\r\nUser-Agent: DPI-Pilot-TV/${BuildConfig.VERSION_NAME}\r\nAccept: */*\r\nAccept-Encoding: identity\r\n${range}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));tls.outputStream.flush()
                    var previous = System.nanoTime();var gap = 0L
                    val response = HttpCodec.read(BufferedInputStream(tls.inputStream),if(detailed) PreviewQuality.LIMIT else 1024,{ _ ->
                        val now=System.nanoTime();gap=maxOf(gap,(now-previous)/1000000);previous=now
                    })
                    if(image) {
                        if (basic) return@tlsBlock Payload(if (TvPolicy.acceptsPreview(response.status,response.headers["content-type"].orEmpty(),response.body)) null else "HTTP ${response.status}; JPEG prefix invalid",response.body.size,gap,response.status in listOf(404,410))
                        if(response.status in listOf(404,410)) return@tlsBlock Payload("Тестовое превью отсутствует: HTTP ${response.status}",unavailable=true)
                        var sourceSize: PreviewBody.Size? = null
                        val accepted = PreviewBody.accepts(response) { bytes ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                            sourceSize = PreviewBody.Size(bounds.outWidth,bounds.outHeight)
                            if (bounds.outWidth !in 320..1920 || bounds.outHeight !in 180..1080) return@accepts null
                            val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return@accepts null
                            try { PreviewBody.Size(bitmap.width,bitmap.height) } finally { bitmap.recycle() }
                        }
                        Payload(if(accepted) null else "Полная картинка не подтверждена: HTTP ${response.status}; ${response.body.size} байт; capped=${response.capped}",response.body.size,gap,sourceSize?.let { it.width > 0 && it.height > 0 && (it.width !in 320..1920 || it.height !in 180..1080) } == true)
                    } else Payload(if(TvPolicy.accepts(spec.kind,response.status,response.headers["content-type"].orEmpty(),response.body.size)) null else "HTTP ${response.status}")
                }
            }
            deadline.check();return result
        } finally { deadline.close() }
    }
}
