package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound secret stored outside Android/cloud/device-transfer backups. */
class WarpStore(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"warp-profile.bin"))
    fun exists()=file.baseFile.exists()
    private fun key(): SecretKey {
        check(android.os.Build.VERSION.SDK_INT>=24)
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): WarpProfile? = synchronized(lock) {
        if(!exists())return@synchronized null
        require(file.baseFile.length() in 29..32768)
        val bytes=file.openRead().use {it.readBytes()};require(bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))}
        WarpProfile.decode(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
    }
    fun save(profile: WarpProfile) = synchronized(lock) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        require(cipher.iv.size==12)
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(profile.encode().toByteArray(Charsets.UTF_8))
        val out=file.startWrite()
        try {out.write(bytes);file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    companion object {private val lock=Any();private const val ALIAS="dpipilot-warp-v1"}
}
