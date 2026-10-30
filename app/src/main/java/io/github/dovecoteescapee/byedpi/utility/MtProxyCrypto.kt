package io.github.dovecoteescapee.byedpi.utility

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Translates only transport obfuscation; account MTProto encryption is never decrypted. */
internal class MtProxyCrypto(clientHeader: ByteArray, secret: ByteArray) {
    val dc: Int
    val media: Boolean
    val protocolTag: Int
    val relayHeader: ByteArray
    private val clientIn: Cipher
    private val clientOut: Cipher
    private val relayIn: Cipher
    private val relayOut: Cipher
    init {
        require(clientHeader.size == 64 && secret.size == 16)
        clientIn = cipher(clientHeader,secret)
        clientOut = cipher(clientHeader.reversedArray(),secret)
        val decoded = clientIn.update(clientHeader)
        val b = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        val tag = b.getInt(56)
        protocolTag = tag
        require(tag in setOf(0xefefefef.toInt(),0xeeeeeeee.toInt(),0xdddddddd.toInt())) { "Invalid local proxy authentication" }
        val signedDc = b.getShort(60).toInt()
        dc = kotlin.math.abs(signedDc)
        require(dc in 1..5) { "Unsupported Telegram datacenter" }
        media = signedDc < 0
        val nonce = TelegramObfuscation().header.copyOf() // Fresh random prefix, valid obfuscated2 constraints.
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putInt(56,tag).putShort(60,signedDc.toShort())
        relayOut = cipher(nonce,null)
        relayIn = cipher(nonce.reversedArray(),null)
        val encrypted = relayOut.update(nonce)
        relayHeader = nonce.apply { encrypted.copyInto(this,56,56,64) }
    }
    fun decodeUpstream(bytes: ByteArray): ByteArray = clientIn.update(bytes)
    fun encodeUpstream(bytes: ByteArray): ByteArray = relayOut.update(bytes)
    fun upstream(bytes: ByteArray): ByteArray = encodeUpstream(decodeUpstream(bytes))
    fun downstream(bytes: ByteArray): ByteArray = clientOut.update(relayIn.update(bytes))
    companion object {
        internal fun cipher(header: ByteArray,secret: ByteArray?): Cipher {
            val prekey = header.copyOfRange(8,40)
            val key = if (secret == null) prekey else MessageDigest.getInstance("SHA-256").digest(prekey+secret)
            return Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(header.copyOfRange(40,56)))
            }
        }
    }
}
