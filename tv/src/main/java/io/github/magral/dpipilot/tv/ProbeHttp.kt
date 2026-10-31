package io.github.magral.dpipilot.tv

import android.os.Build
import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import java.io.*
import java.net.URI
import java.util.Locale
import javax.net.ssl.*

/** Bounded HTTP/1.1, identity encoding only. Never logs a signed URL or follows an arbitrary host. */
object HttpCodec {
    data class Response(val status: Int,val headers: Map<String,String>,val body: ByteArray,val capped: Boolean = false)
    fun read(input: InputStream,limit: Int,onBytes: (Int)->Unit = {},interims: Int = 0): Response {
        require(limit in 1..2*1024*1024)
        var headersRead = 0
        fun line(): String {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("Incomplete HTTP headers")
                if (++headersRead > 32768 || out.length > 8192) throw IOException("HTTP headers too large")
                if (c == 10) return out.toString().trimEnd('\r')
                out.append(c.toChar())
            }
        }
        val status = Regex("HTTP/1\\.[01] ([0-9]{3}).*").matchEntire(line())?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Invalid HTTP status")
        val headers = linkedMapOf<String,String>()
        while (true) {
            val s = line(); if (s.isEmpty()) break
            val name = s.substringBefore(':').lowercase(Locale.ROOT)
            if (!s.contains(':') || (headers.containsKey(name) && name in listOf("content-length","transfer-encoding","content-range"))) throw IOException("Invalid/duplicate HTTP header")
            headers[name] = s.substringAfter(':').trim()
        }
        if (status in 100..199) {
            if (status == 101 || interims >= 3) throw IOException("Unsupported informational response")
            return read(input,limit,onBytes,interims+1)
        }
        if (headers["content-encoding"]?.let { !it.equals("identity",true) } == true) throw IOException("Unsupported content encoding")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        fun consume(count: Long) {
            var left = count
            while (left > 0 && out.size() < limit) {
                val n = input.read(buffer,0,minOf(buffer.size.toLong(),left,(limit-out.size()).toLong()).toInt())
                if (n < 0) throw EOFException("Truncated HTTP body")
                if (n == 0) continue
                out.write(buffer,0,n); onBytes(n); left -= n
            }
        }
        if (status in listOf(204,304) || status in 100..199) return Response(status,headers,byteArrayOf())
        val transfer = headers["transfer-encoding"]
        if (transfer != null) {
            if (!transfer.equals("chunked",true) || headers.containsKey("content-length")) throw IOException("Ambiguous HTTP framing")
            while (true) {
                val size = line().substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("Invalid chunk length")
                if (size < 0 || size > 16*1024*1024L) throw IOException("Oversized HTTP chunk")
                if (size == 0L) { while (line().isNotEmpty()) { }; break }
                consume(size)
                if (out.size() == limit) return Response(status,headers,out.toByteArray(),true)
                if (line().isNotEmpty()) throw IOException("Invalid chunk terminator")
            }
        } else {
            val length = headers["content-length"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw IOException("Invalid content length") }
            if (length != null) {
                consume(length)
                return Response(status,headers,out.toByteArray(),length > limit)
            }
            while (out.size() < limit) {
                val n = input.read(buffer,0,minOf(buffer.size,limit-out.size()))
                if (n < 0) break
                if (n > 0) { out.write(buffer,0,n); onBytes(n) }
            }
        }
        return Response(status,headers,out.toByteArray(),out.size() == limit)
    }
}

class ProbeHttp(private val port: Int,private val timeout: Int,private val session: TvProbe.Session,private val onBytes: (Int)->Unit = {}) {
    data class Transfer(val response: HttpCodec.Response,val firstByteMs: Long,val elapsedMs: Long)
    fun get(uri: URI,limit: Int,range: LongRange? = null,redirects: Int = 0): Transfer {
        require(MediaLinks.allowedRequest(uri)) { "HTTP target rejected" }
        if (session.cancelled()) throw java.util.concurrent.CancellationException()
        val start = System.nanoTime()
        val raw = TargetProbe.socket(uri.host,443,port,12000,session::register)
        raw.use {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,uri.host,443,true) as SSLSocket
            tls.use {
                tls.soTimeout = timeout
                if (Build.VERSION.SDK_INT >= 24) tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                if (Build.VERSION.SDK_INT < 24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.host,tls.session)) throw IOException("TLS hostname mismatch")
                val path = uri.rawPath.ifEmpty { "/" }+(uri.rawQuery?.let { "?$it" } ?: "")
                val request = "GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nUser-Agent: Mozilla/5.0\r\nAccept-Encoding: identity\r\nAccept: */*\r\n"+
                    (range?.let { "Range: bytes=${it.first}-${it.last}\r\n" } ?: "")+"Connection: close\r\n\r\n"
                tls.outputStream.write(request.toByteArray(Charsets.US_ASCII)); tls.outputStream.flush()
                val input = PushbackInputStream(BufferedInputStream(tls.inputStream),1)
                val first = input.read(); if (first < 0) throw EOFException("No HTTP response")
                val latency = ((System.nanoTime()-start)/1000000).coerceAtLeast(1)
                input.unread(first)
                val response = HttpCodec.read(input,limit,onBytes)
                if (response.status in listOf(301,302,303,307,308)) {
                    if (redirects >= 3) throw IOException("Too many redirects")
                    val next = uri.resolve(response.headers["location"] ?: throw IOException("Redirect without location"))
                    if (!MediaLinks.allowedRequest(next)) throw IOException("Redirect target rejected")
                    return get(next,limit,range,redirects+1)
                }
                return Transfer(response,latency,((System.nanoTime()-start)/1000000).coerceAtLeast(1))
            }
        }
    }
}
