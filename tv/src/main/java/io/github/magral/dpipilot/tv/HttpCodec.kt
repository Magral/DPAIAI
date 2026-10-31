package io.github.magral.dpipilot.tv

import java.io.*
import java.util.Locale

/** Bounded HTTP/1.1 parser for the YouTube reachability checks. */
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
