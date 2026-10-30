package io.github.dovecoteescapee.byedpi.utility

import java.net.*
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object TargetProbe {
    private fun socket(host: String, port: Int) = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 19080))).apply {
        soTimeout = 3500
        try { connect(InetSocketAddress.createUnresolved(host,port),3500) }
        catch (e: Exception) { close(); throw e }
    }
    fun https(host: String): Boolean = socket(host,443).use { raw ->
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(raw,host,443,true) as SSLSocket).use { tls ->
            tls.soTimeout = 3500
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            tls.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val first = ByteArray(9)
            DataInputStream(tls.inputStream).readFully(first)
            String(first,Charsets.US_ASCII).startsWith("HTTP/1.")
        }
    }
    data class Dc(val id: Int,val ip: String)
    // Bootstrap addresses from official Telegram Android, production DC1–DC5.
    val telegramDcs = listOf(Dc(1,"149.154.175.50"),Dc(2,"149.154.167.51"),
        Dc(3,"149.154.175.100"),Dc(4,"149.154.167.91"),Dc(5,"149.154.171.5"))
    data class TelegramResult(val ok: Boolean,val detail: String)
    fun telegram(dc: Dc): TelegramResult {
        val failures = mutableListOf<String>()
        for (obfuscated in listOf(true,false)) {
            val mode = if (obfuscated) "obfuscated" else "abridged"
            try {
                if (telegramExchange(dc.ip,obfuscated)) return TelegramResult(true,mode)
                failures.add("$mode: invalid MTProto response")
            } catch (e: Exception) { failures.add("$mode: ${e.javaClass.simpleName}") }
        }
        return TelegramResult(false,failures.joinToString("; "))
    }
    private fun telegramExchange(ip: String,obfuscated: Boolean): Boolean = socket(ip,443).use { tcp ->
        val random = SecureRandom()
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val now = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(((now/1000) shl 32) or (((now%1000) shl 32)/1000 and -4L))
            .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
        val input = DataInputStream(tcp.inputStream)
        val reply: ByteArray
        if (obfuscated) {
            val transport = TelegramObfuscation()
            val padding = ByteArray(random.nextInt(16)).also { random.nextBytes(it) }
            val framed = ByteBuffer.allocate(4+payload.size+padding.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.size+padding.size).put(payload).put(padding).array()
            tcp.outputStream.write(transport.header + transport.encrypt(framed))
            tcp.outputStream.flush()
            val lengthBytes = ByteArray(4).also { input.readFully(it) }
            val length = ByteBuffer.wrap(transport.decrypt(lengthBytes)).order(ByteOrder.LITTLE_ENDIAN).int
            if (length !in 56..4096) return@use false
            val encrypted = ByteArray(length).also { input.readFully(it) }
            reply = transport.decrypt(encrypted)
        } else {
            tcp.outputStream.write(byteArrayOf(0xef.toByte(),10) + payload)
            tcp.outputStream.flush()
            val header = input.readUnsignedByte()
            val words = if (header == 127) input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16) else header
            if (header > 127 || words !in 14..1024) return@use false
            reply = ByteArray(words*4).also { input.readFully(it) }
        }
        validTelegramReply(reply,nonce,obfuscated)
    }
    internal fun validTelegramReply(reply: ByteArray, nonce: ByteArray, padded: Boolean = false): Boolean {
        if (reply.size < 56 || nonce.size != 16) return false
        val buffer = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        val body = buffer.getInt(16)
        val padding = reply.size.toLong()-20-body
        if (body < 36 || body % 4 != 0 || padding !in 0L..(if (padded) 15L else 0L)) return false
        return buffer.getLong(0) == 0L &&
            buffer.getInt(20) == 0x05162463 && reply.copyOfRange(24,40).contentEquals(nonce)
    }
}
