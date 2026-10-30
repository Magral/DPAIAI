package io.github.dovecoteescapee.byedpi.utility

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direct MTProto obfuscated2, padded intermediate, without an MTProxy secret. */
internal class TelegramObfuscation(seed: ByteArray = randomHeader()) {
    private val original = seed.copyOf().also { require(it.size == 64) }
    private val outgoing: Cipher
    private val incoming: Cipher
    val header: ByteArray
    init {
        // Key/IV slices exclude the transport tag. Incoming uses the reversed nonce.
        val reversed = original.reversedArray()
        outgoing = cipher(original)
        incoming = cipher(reversed)
        for (i in 56..59) original[i] = 0xdd.toByte()
        val encrypted = outgoing.update(original)
        header = original.copyOf().apply { encrypted.copyInto(this,56,56,64) }
    }
    fun encrypt(bytes: ByteArray): ByteArray = outgoing.update(bytes)
    fun decrypt(bytes: ByteArray): ByteArray = incoming.update(bytes)
    companion object {
        internal fun cipher(nonce: ByteArray): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(nonce.copyOfRange(8,40),"AES"),IvParameterSpec(nonce.copyOfRange(40,56)))
        }
        private fun randomHeader(): ByteArray {
            val random = SecureRandom()
            val reserved = setOf("HEAD","POST","GET ","PUT ","DELE","OPTI","CONN","TRAC","PATC")
            while (true) {
                val bytes = ByteArray(64).also { random.nextBytes(it) }
                val first = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                if (bytes[0] == 0xef.toByte() || first == 0xdddddddd.toInt() || first == 0xeeeeeeee.toInt() || first == 0x02010316) continue
                if (String(bytes,0,4,Charsets.US_ASCII) in reserved || bytes.copyOfRange(4,8).all { it == 0.toByte() }) continue
                return bytes
            }
        }
    }
}
