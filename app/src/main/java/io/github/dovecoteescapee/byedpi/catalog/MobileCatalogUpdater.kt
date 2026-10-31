package io.github.dovecoteescapee.byedpi.catalog

import io.github.dovecoteescapee.byedpi.BuildConfig

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Data only; authenticated before persistence or use. No engine/APK/code is downloaded. */
object MobileCatalogUpdater {
    fun preferences(context: Context) = context.getSharedPreferences("mobile_catalog",Context.MODE_PRIVATE)
    private const val endpoint = "https://github.com/Magral/DPAIAI/releases/download/strategy-catalog/catalog-v1.json"
    private const val certificatePin = "c445f31d1345b3766a1f269f86f8953997938196eeade34462b61cc7024a8a56"
    private const val daily = 24*60*60*1000L
    private const val retry = 60*60*1000L
    private val busy = AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile var status = "Встроенная база; обновление ещё не проверялось"
        private set
    @Suppress("DEPRECATION")
    private fun key(context: Context): PublicKey {
        val signatures = context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES).signatures
        require(signatures != null && signatures.size == 1)
        val der = signatures[0].toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest == certificatePin) { "certificate" }
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)).publicKey
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun file(context: Context) = AtomicFile(File(context.filesDir,"strategy-catalog-v1.json"))
    private fun decode(bytes: ByteArray,key: PublicKey,minimum: Long): CatalogUpdate {
        require(bytes.size <= 3*1024*1024)
        val envelope = JSONObject(String(bytes,Charsets.UTF_8))
        val payload = Base64.decode(envelope.getString("payload"),Base64.NO_WRAP)
        val signature = Base64.decode(envelope.getString("signature"),Base64.NO_WRAP)
        return CatalogProtocol.verify(payload,signature,key,minimum)
    }
    @Synchronized fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = preferences(context)
        status = prefs.getString("catalog_status",status) ?: status
        runCatching {
            val cache = file(context)
            if (cache.baseFile.exists() || File(cache.baseFile.path+".bak").exists()) {
                val bytes = cache.openRead().use { readBounded(it,3*1024*1024) }
                val update = decode(bytes,key(context),prefs.getLong("catalog_sequence",0))
                RuntimeCatalog.install(update)
                prefs.edit().putLong("catalog_sequence",update.sequence).putString("catalog_digest",digest(bytes)).apply()
            }
        }.onFailure {
            status = "Сохранённая база не прошла проверку; используется встроенная"
            prefs.edit().putLong("catalog_checked",0).putLong("catalog_attempt",0).apply()
        }
    }
    fun due(now: Long,lastSuccess: Long,lastAttempt: Long): Boolean {
        fun elapsed(since: Long,period: Long) = since <= 0 || now < since || now-since >= period
        return elapsed(lastSuccess,daily) && elapsed(lastAttempt,retry)
    }
    /** Called on IO worker; one transaction for UI, service and network wake-ups. */
    fun refresh(context: Context,force: Boolean = false) {
        load(context)
        if (!busy.compareAndSet(false,true)) return
        val prefs = preferences(context)
        try {
            val now = System.currentTimeMillis()
            if (!force && !due(now,prefs.getLong("catalog_checked",0),prefs.getLong("catalog_attempt",0))) return
            if (force && now >= prefs.getLong("catalog_attempt",0) && now-prefs.getLong("catalog_attempt",0) < 15000) return
            prefs.edit().putLong("catalog_attempt",now).apply()
            status = "Проверяем подписанную базу…"
            val bytes = download()
            val update = decode(bytes,key(context),prefs.getLong("catalog_sequence",0))
            val oldSequence = prefs.getLong("catalog_sequence",0)
            if (update.sequence == oldSequence && oldSequence > 0) {
                // Equal sequence must be the exact same signed publication.
                val expected = prefs.getString("catalog_digest",null)
                require(expected == null || expected == digest(bytes)) { "sequence collision" }
            }
            val known = RuntimeCatalog.ids.toSet()
            require(known.size+update.entries.count { it.id !in known } <= 2000)
            val target = file(context)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) }
            catch (e: Exception) { target.failWrite(stream); throw e }
            val added = RuntimeCatalog.install(update)
            check(prefs.edit().putLong("catalog_sequence",update.sequence).putLong("catalog_checked",now)
                .putString("catalog_digest",digest(bytes)).putLong("catalog_updated",if (update.sequence > oldSequence) now else prefs.getLong("catalog_updated",0)).commit())
            status = "Подпись проверена · ${update.version} · новых: $added; следующая проверка через 24 часа"
            prefs.edit().putString("catalog_status",status).apply()
            if (update.sequence > oldSequence) android.util.Log.i("MobileCatalog","База обновлена: ${update.version}; добавлено $added. Рабочий VPN сохранён")
        } catch (e: Exception) {
            status = "Обновление не получено (${e.javaClass.simpleName}); прежняя база сохранена. Повтор через час"
            prefs.edit().putString("catalog_status",status).apply()
        } finally { busy.set(false) }
    }
    internal fun allowedDownload(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
        (url.port == -1 || url.port == 443) && url.ref == null && (url.host == "github.com" || url.host == "release-assets.githubusercontent.com" || url.host == "objects.githubusercontent.com")
    private fun download(): ByteArray {
        var url = URL(endpoint)
        val deadline = android.os.SystemClock.elapsedRealtime()+90000L
        repeat(4) {
            require(allowedDownload(url))
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept","application/json")
                connection.setRequestProperty("Accept-Encoding","identity")
                connection.setRequestProperty("User-Agent","DPI-Pilot-Mobile/${BuildConfig.VERSION_NAME}")
                val code = connection.responseCode
                if (code in listOf(301,302,303,307,308)) {
                    val location = connection.getHeaderField("Location") ?: error("redirect")
                    url = URL(url,location)
                } else {
                    require(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
                    require(connection.contentLength <= 3*1024*1024)
                    return connection.inputStream.use { readBounded(it,3*1024*1024,deadline) }
                }
                check(android.os.SystemClock.elapsedRealtime() < deadline)
            } finally { connection.disconnect() }
        }
        error("redirect limit")
    }
    private fun readBounded(input: java.io.InputStream,limit: Int,deadline: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            check(android.os.SystemClock.elapsedRealtime() < deadline)
            val count = input.read(buffer); if (count < 0) break
            require(out.size()+count <= limit); out.write(buffer,0,count)
        }
        return out.toByteArray()
    }
}
