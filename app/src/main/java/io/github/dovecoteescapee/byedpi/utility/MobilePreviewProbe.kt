package io.github.dovecoteescapee.byedpi.utility

import android.graphics.BitmapFactory
import android.os.Build
import io.github.dovecoteescapee.byedpi.catalog.*
import java.io.BufferedInputStream
import java.io.IOException
import javax.net.ssl.*

/** Optional TV quality assessment. Failures never revoke full mobile 8/8 access. */
object MobilePreviewProbe {
    private data class Source(val kind: String,val path: String)
    private val sources = listOf(
        Source("image","/vi/jNQXAC9IVRw/hqdefault.jpg"),
        Source("image-jpeg","/vi/M7lc1UVf-VE/hqdefault.jpg"),
        Source("image-webp","/vi_webp/M7lc1UVf-VE/hqdefault.webp"))
    fun check(port: Int,timeout: Int,session: ProbeSession): PreviewQuality {
        val work = (1..2).flatMap { round -> sources.map { it to round } }
        val child = ProbeSession()
        val samples = ParallelProbe.round(work.size,session::checkActive,{ it.valid },child::cancel) { index ->
            session.checkActive();child.checkActive()
            val (spec,round) = work[index]
            val started = System.nanoTime()
            val deadline = ProbeDeadline(minOf(timeout,10000).toLong()+3000)
            var bytes = 0;var gap = 0L
            val valid = try {
                TargetProbe.socket("i.ytimg.com",443,port,minOf(timeout,10000),{
                    session.register(it);child.register(it);deadline.register(it)
                }).use { raw ->
                    ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,"i.ytimg.com",443,true) as SSLSocket).use { tls ->
                        tls.soTimeout = minOf(timeout,10000)
                        if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" }
                        tls.startHandshake()
                        if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify("i.ytimg.com",tls.session))
                            throw IOException("TLS hostname mismatch")
                        tls.outputStream.write(("GET ${spec.path} HTTP/1.1\r\nHost: i.ytimg.com\r\nUser-Agent: DPI-Pilot\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        tls.outputStream.flush()
                        var previous = System.nanoTime()
                        val response = HttpCodec.read(BufferedInputStream(tls.inputStream),PreviewQuality.LIMIT,onBytes = {
                            val now=System.nanoTime();gap=maxOf(gap,(now-previous)/1000000);previous=now
                        })
                        bytes = response.body.size
                        PreviewBody.accepts(response) { body ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            BitmapFactory.decodeByteArray(body,0,body.size,bounds)
                            if (bounds.outWidth !in 320..1920 || bounds.outHeight !in 180..1080) return@accepts null
                            val bitmap = BitmapFactory.decodeByteArray(body,0,body.size) ?: return@accepts null
                            try { PreviewBody.Size(bitmap.width,bitmap.height) } finally { bitmap.recycle() }
                        }
                    }
                }.also { deadline.check() }
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (_: Exception) { false }
            finally { deadline.close() }
            session.checkActive();child.checkActive()
            PreviewSample(spec.kind,round,bytes,((System.nanoTime()-started)/1000000).coerceAtLeast(1),gap,valid)
        }.map { it.value }
        session.checkActive()
        return PreviewQuality(samples)
    }
}
