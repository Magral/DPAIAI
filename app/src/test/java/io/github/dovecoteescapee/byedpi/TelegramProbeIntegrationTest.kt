package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class TelegramProbeIntegrationTest {
    @Test fun obfuscatedProbeCompletesThroughLocalSocksWithFragmentedServerReply() {
        // Entire test is local. This SOCKS fixture never forwards to a real Telegram server.
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(19080).use { listener ->
            listener.soTimeout = 7000
            val server = pool.submit {
                listener.accept().use { socket ->
                    socket.soTimeout = 7000
                    val input = DataInputStream(socket.getInputStream())
                    val out = socket.getOutputStream()
                    assertEquals(5,input.readUnsignedByte())
                    ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    out.write(byteArrayOf(5,0)); out.flush()
                    assertEquals(5,input.readUnsignedByte())
                    assertEquals(1,input.readUnsignedByte())
                    input.readUnsignedByte()
                    val length = when (input.readUnsignedByte()) { 1 -> 4; 3 -> input.readUnsignedByte(); 4 -> 16; else -> error("SOCKS atyp") }
                    ByteArray(length+2).also { input.readFully(it) }
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); out.flush()
                    val header = ByteArray(64).also { input.readFully(it) }
                    fun cipher(bytes: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE,SecretKeySpec(bytes.copyOfRange(8,40),"AES"),IvParameterSpec(bytes.copyOfRange(40,56)))
                    }
                    val rx = cipher(header)
                    val tag = rx.update(header).copyOfRange(56,60)
                    assertArrayEquals(ByteArray(4) { 0xdd.toByte() },tag)
                    val wireLength = ByteArray(4).also { input.readFully(it) }
                    val size = ByteBuffer.wrap(rx.update(wireLength)).order(ByteOrder.LITTLE_ENDIAN).int
                    assertTrue(size in 40..55)
                    val request = rx.update(ByteArray(size).also { input.readFully(it) })
                    val body = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(20,body.getInt(16))
                    assertEquals(0xbe7e8ef1.toInt(),body.getInt(20))
                    val response = ByteBuffer.allocate(107).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(0).putLong(1).putInt(80).putInt(0x05162463).put(request.copyOfRange(24,40)).array()
                    val frame = ByteBuffer.allocate(111).order(ByteOrder.LITTLE_ENDIAN).putInt(response.size).put(response).array()
                    val tx = cipher(header.reversedArray())
                    val encrypted = tx.update(frame)
                    out.write(encrypted,0,2); out.flush()
                    out.write(encrypted,2,5); out.flush()
                    out.write(encrypted,7,encrypted.size-7); out.flush()
                }
            }
            try {
                val result = TargetProbe.telegram(TargetProbe.Dc(2,"149.154.167.51"))
                server.get(10,TimeUnit.SECONDS)
                assertTrue(result.detail,result.ok)
                assertEquals("obfuscated",result.detail)
            } finally { pool.shutdownNow() }
        }
    }
}
