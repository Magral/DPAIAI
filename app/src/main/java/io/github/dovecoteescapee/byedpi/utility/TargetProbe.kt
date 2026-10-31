package io.github.dovecoteescapee.byedpi.utility

import java.net.*
import java.io.DataInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object TargetProbe {
    // Explicit SOCKS5 negotiation gives exact failure stages and reply codes on Android.
    internal fun socket(host: String, port: Int, proxyPort: Int = 19080, readTimeout: Int = 5000, onSocket: (Socket) -> Unit = {}, onStage: (String) -> Unit = {}): Socket {
        val tcp = Socket(Proxy.NO_PROXY)
        var stage = "local proxy connect"
        try {
            onSocket(tcp)
            onStage(stage)
            tcp.connect(InetSocketAddress("127.0.0.1",proxyPort),1000)
            tcp.soTimeout = readTimeout
            val input = DataInputStream(tcp.inputStream)
            val output = tcp.outputStream
            stage = "SOCKS authentication"
            onStage(stage)
            output.write(byteArrayOf(5,1,0)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "SOCKS method rejected" }
            val numeric = host.split('.').takeIf { it.size == 4 && it.all { part -> (part.toIntOrNull() ?: -1) in 0..255 } }
            val address = if (numeric != null) byteArrayOf(1)+numeric.map { it.toInt().toByte() }.toByteArray()
                else java.net.IDN.toASCII(host).toByteArray(Charsets.US_ASCII).let { name ->
                    require(name.size in 1..255); byteArrayOf(3,name.size.toByte())+name
                }
            stage = "SOCKS upstream connect"
            onStage(stage)
            output.write(byteArrayOf(5,1,0)+address+byteArrayOf((port ushr 8).toByte(),port.toByte())); output.flush()
            check(input.readUnsignedByte() == 5) { "Invalid SOCKS version" }
            val reply = input.readUnsignedByte()
            input.readUnsignedByte()
            if (reply != 0) throw java.io.IOException("SOCKS reply=$reply (1=general, 3=network unreachable, 4=host unreachable/timeout, 5=refused, 8=address type)")
            val length = when (input.readUnsignedByte()) { 1 -> 4; 3 -> input.readUnsignedByte(); 4 -> 16; else -> error("Invalid SOCKS address") }
            ByteArray(length+2).also { input.readFully(it) }
            return tcp
        } catch (e: Exception) {
            tcp.close()
            throw java.io.IOException("$stage: ${e.javaClass.simpleName}: ${e.message?.take(180)}",e)
        }
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
}
