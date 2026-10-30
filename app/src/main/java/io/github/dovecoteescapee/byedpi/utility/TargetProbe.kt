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
    // DC2 bootstrap address from official Telegram Android ConnectionsManager.cpp.
    // Plain req_pq_multi is a non-authenticated reachability probe, not server authentication.
    fun telegram(): Boolean = socket("149.154.167.51",443).use { tcp ->
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong((System.currentTimeMillis()/1000) shl 32)
            .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
        tcp.outputStream.write(byteArrayOf(0xef.toByte(),10) + payload)
        tcp.outputStream.flush()
        val input = DataInputStream(tcp.inputStream)
        val header = input.readUnsignedByte()
        val words = if (header == 127) input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16) else header
        if (header > 127 || words !in 14..1024) return@use false
        val reply = ByteArray(words*4)
        input.readFully(reply)
        validTelegramReply(reply,nonce)
    }
    internal fun validTelegramReply(reply: ByteArray, nonce: ByteArray): Boolean {
        if (reply.size < 56 || nonce.size != 16) return false
        val buffer = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.getLong(0) == 0L && buffer.getInt(16) == reply.size-20 &&
            buffer.getInt(20) == 0x05162463 && reply.copyOfRange(24,40).contentEquals(nonce)
    }
}
