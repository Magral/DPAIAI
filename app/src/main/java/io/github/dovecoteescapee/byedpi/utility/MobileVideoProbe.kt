package io.github.dovecoteescapee.byedpi.utility

import io.github.dovecoteescapee.byedpi.catalog.HttpCodec
import java.io.BufferedInputStream
import android.os.Build
import javax.net.ssl.HttpsURLConnection
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject

/** Anonymous playback sample. Expired URLs/consent/login responses are inconclusive. */
object MobileVideoProbe {
    private const val LIMIT=256*1024
    internal fun mediaUrl(value: String): Boolean = runCatching {
        val u=URI(value);u.scheme=="https" && u.userInfo==null && u.port in listOf(-1,443) &&
            u.host?.endsWith(".googlevideo.com")==true && u.path=="/videoplayback"
    }.getOrDefault(false)
    internal fun player(html: String): JSONObject? {
        for(marker in listOf("ytInitialPlayerResponse = ","ytInitialPlayerResponse=")) {
            val from=html.indexOf(marker);if(from<0)continue
            val start=html.indexOf('{',from+marker.length);if(start<0)continue
            var depth=0;var quoted=false;var escape=false
            for(i in start until html.length) {
                val c=html[i]
                if(quoted) { if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quoted=false }
                else if(c=='"')quoted=true else if(c=='{')depth++ else if(c=='}' && --depth==0)
                    return runCatching { JSONObject(html.substring(start,i+1)) }.getOrNull()
            }
        }
        return null
    }
    private fun get(host: String,path: String,port: Int,session: ProbeSession,limit: Int,range: Boolean=false): HttpCodec.Response {
        val deadline=ProbeDeadline(12000)
        try {
            TargetProbe.socket(host,443,port,8000,{ session.register(it);deadline.register(it) }).use { raw ->
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,host,443,true) as SSLSocket).use { tls ->
                    tls.soTimeout=8000
                    if(Build.VERSION.SDK_INT>=24)tls.sslParameters=tls.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" }
                    tls.startHandshake()
                    if(Build.VERSION.SDK_INT<24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host,tls.session))throw java.io.IOException("TLS hostname mismatch")
                    tls.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept-Encoding: identity\r\n"+
                        (if(range) "Range: bytes=0-${limit-1}\r\n" else "")+"Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));tls.outputStream.flush()
                    return HttpCodec.read(BufferedInputStream(tls.inputStream),limit).also { deadline.check();session.checkActive() }
                }
            }
        } finally { deadline.close() }
    }
    fun check(port: Int,session: ProbeSession): JSONObject {
        val result=JSONObject().put("status","unavailable")
        try {
            // Public YouTube IFrame API sample, refreshed for each attempt, never a signed URL in the catalog.
            val page=get("www.youtube.com","/watch?v=M7lc1UVf-VE",port,session,2*1024*1024)
            val data=player(String(page.body,Charsets.UTF_8))?.optJSONObject("streamingData")
                ?: return result.put("detail","YouTube не предоставил публичные адреса видео; возможны consent/login/изменение страницы")
            val urls=listOf("formats","adaptiveFormats").flatMap { name -> val a=data.optJSONArray(name)
                if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url")?.takeIf { url -> mediaUrl(url) } }
            }.distinct().take(2)
            if(urls.isEmpty())return result.put("detail","Тестовое видео требует подписи проигрывателя; транспорт не признан нерабочим")
            var passed=0;var elapsed=0L;var bytes=0
            for(url in urls) {
                session.checkActive();val u=URI(url);val started=System.nanoTime()
                val response=get(u.host,u.rawPath+(u.rawQuery?.let { "?$it" } ?: ""),port,session,LIMIT,true)
                if(response.status in listOf(403,404,410,429))return result.put("detail","Тестовый адрес недоступен: HTTP ${response.status}")
                val type=response.headers["content-type"].orEmpty()
                if(response.status !in listOf(200,206) || !(type.startsWith("video/") || type.startsWith("audio/")) || response.body.size<32768)
                    return result.put("status","failed").put("detail","Неполный/неверный ответ видеосервера")
                passed++;bytes+=response.body.size;elapsed+=(System.nanoTime()-started)/1000000
            }
            if(bytes*8L/elapsed.coerceAtLeast(1)<512)return result.put("status","failed").put("detail","Медиадиапазоны загружаются медленнее 512 Кбит/с")
            return result.put("status","confirmed").put("samples",passed).put("bytes",bytes).put("elapsedMs",elapsed)
                .put("kbps",bytes*8L/elapsed.coerceAtLeast(1)).put("detail","Публичные медиадиапазоны загружены; воспроизведение в клиенте проверяется отдельно")
        } catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: Exception) { session.checkActive();return result.put("status","failed").put("detail",e.javaClass.simpleName+": "+e.message.orEmpty().take(160)) }
    }
}
