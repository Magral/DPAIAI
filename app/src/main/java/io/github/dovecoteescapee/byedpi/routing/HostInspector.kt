package io.github.dovecoteescapee.byedpi.routing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** Reads only plaintext HTTP headers or the public TLS ClientHello. Never terminates TLS. */
object HostInspector {
    data class Prefix(val bytes: ByteArray,val host: String?)
    private val domains = mapOf("instagram" to listOf("instagram.com","cdninstagram.com","fbcdn.net","facebook.com","fbsbx.com","ig.me"),
            "youtube" to listOf("youtube.com","youtu.be","googlevideo.com","ytimg.com","ggpht.com","googleusercontent.com","googleapis.com","gstatic.com","google.com"))
    fun service(host: String?): String? {
        val name = host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        return domains.entries.firstOrNull { (_,roots) -> roots.any { name == it || name.endsWith(".$it") } }?.key
    }
    fun read(input: InputStream): Prefix {
        val out = ByteArrayOutputStream()
        var lastByte = -1
        fun bytes(n: Int): Boolean {
            if (n < 0 || out.size()+n > 65536) return false
            repeat(n) { val next = input.read(); if (next < 0) return false; out.write(next);lastByte=next }
            return true
        }
        try {
            if (!bytes(1)) return Prefix(out.toByteArray(),null)
            if (out.toByteArray()[0].toInt() == 22) {
                val handshake = ByteArrayOutputStream()
                repeat(16) {
                    val offset = out.size()-1
                    if (!bytes(4)) return Prefix(out.toByteArray(),null)
                    val header = out.toByteArray()
                    if (header[offset].toInt() != 22 || header[offset+1].toInt() != 3) return Prefix(header,null)
                    val size = ((header[offset+3].toInt() and 255) shl 8) or (header[offset+4].toInt() and 255)
                    val bodyAt = out.size()
                    if (!bytes(size)) return Prefix(out.toByteArray(),null)
                    handshake.write(out.toByteArray(),bodyAt,size)
                    val body = handshake.toByteArray()
                    if (body.size >= 4) {
                        val needed = ((body[1].toInt() and 255) shl 16) or ((body[2].toInt() and 255) shl 8) or (body[3].toInt() and 255)
                        if (body.size >= needed+4) return Prefix(out.toByteArray(),sni(body))
                    }
                    if (!bytes(1)) return Prefix(out.toByteArray(),null)
                }
            } else if (out.toByteArray()[0].toInt().toChar() in "GPHDCOT") {
                var tail = lastByte
                while (out.size() < 32768) {
                    if (!bytes(1)) break
                    tail = (tail shl 8) or lastByte
                    if (out.size() >= 4 && tail == 0x0d0a0d0a) {
                        val value = out.toByteArray()
                        val host = String(value,Charsets.ISO_8859_1).lineSequence().drop(1).firstOrNull { it.startsWith("Host:",true) }
                            ?.substringAfter(':')?.trim()?.substringBefore(':')
                        return Prefix(value,host)
                    }
                }
            }
        } catch (_: java.io.IOException) { /* Preserve every consumed byte on incomplete inspection. */ }
        return Prefix(out.toByteArray(),null)
    }
    private fun sni(bytes: ByteArray): String? = runCatching {
        require(bytes[0].toInt() == 1)
        var p = 4+2+32
        fun u8(): Int { require(p < bytes.size); return bytes[p++].toInt() and 255 }
        fun u16() = (u8() shl 8) or u8()
        fun skip(n: Int) { p += n; require(p <= bytes.size) }
        val sid = u8(); skip(sid); val suites = u16(); skip(suites); val compression = u8(); skip(compression)
        val extensionEnd = p+2+u16(); require(extensionEnd <= bytes.size)
        while (p+4 <= extensionEnd) {
            val type = u16(); val length = u16(); val end = p+length; require(end <= extensionEnd)
            if (type == 0) {
                val listEnd = p+2+u16(); require(listEnd == end)
                while (p+3 <= listEnd) {
                    val nameType = u8(); val n = u16(); require(p+n <= listEnd)
                    if (nameType == 0) return@runCatching String(bytes,p,n,Charsets.US_ASCII)
                    skip(n)
                }
            }
            p = end
        }
        null
    }.getOrNull()
}
