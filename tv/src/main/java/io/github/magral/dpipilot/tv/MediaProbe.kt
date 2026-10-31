package io.github.magral.dpipilot.tv

import java.net.URI
import org.json.JSONObject

/** Only direct URLs supplied for a public sample; no account, JavaScript execution or signature deciphering. */
object MediaLinks {
    fun allowedRequest(uri: URI): Boolean {
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1,443) && uri.rawFragment == null &&
            (host == "www.youtube.com" || host == "i.ytimg.com" || host.endsWith(".googlevideo.com"))
    }
    fun media(uri: URI): Boolean = allowedRequest(uri) && uri.host.endsWith(".googlevideo.com") && uri.path == "/videoplayback"
    fun playerJson(html: String): String? {
        val marker = "ytInitialPlayerResponse"
        var from = html.indexOf(marker)
        while (from >= 0) {
            val start = html.indexOf('{',from+marker.length)
            if (start < 0 || start-from > 100) return null
            var depth = 0; var quoted = false; var escaped = false
            for (i in start until html.length) {
                val c = html[i]
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else if (c == '"') quoted = true
                else if (c == '{') depth++
                else if (c == '}' && --depth == 0) return html.substring(start,i+1)
            }
            from = html.indexOf(marker,from+marker.length)
        }
        return null
    }
    fun direct(html: String): URI? {
        val json = playerJson(html) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val data = root.optJSONObject("streamingData") ?: return null
        val links = mutableListOf<Pair<Int,URI>>()
        for (key in listOf("formats","adaptiveFormats")) {
            val formats = data.optJSONArray(key) ?: continue
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                if (!format.optString("mimeType").startsWith("video/")) continue
                val raw = format.optString("url")
                val uri = runCatching { URI(raw) }.getOrNull() ?: continue
                val length = format.optString("contentLength").toLongOrNull()
                if (media(uri) && (length == null || length >= 128*1024)) links.add(format.optInt("height",10000) to uri)
            }
        }
        return links.filter { it.first >= 144 }.minByOrNull { it.first }?.second
    }
    fun validRange(response: HttpCodec.Response,start: Long,wanted: Int): Boolean {
        if (response.status != 206 || response.body.size != wanted) return false
        if (response.headers["content-length"]?.toLongOrNull()?.let { it != wanted.toLong() } == true) return false
        val type = response.headers["content-type"].orEmpty()
        if (!type.startsWith("video/",true)) return false
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.headers["content-range"].orEmpty()) ?: return false
        val first = match.groupValues[1].toLongOrNull() ?: return false
        val last = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return first == start && last == start+wanted-1 && total > last
    }
}

enum class MediaStatus { PASSED, FAILED, UNAVAILABLE }
data class MediaResult(val status: MediaStatus,val minKbps: Long = 0,val latencyMs: Long = 0,val consistency: Int = 0,val bytes: Long = 0,val detail: String = "Медиатест не выполнялся")

object MediaProbe {
    private var cached: URI? = null
    private var validUntil = 0L
    fun invalidate() { cached = null; validUntil = 0 }
    fun check(http: ProbeHttp,now: Long,refresh: Boolean = false): MediaResult {
        var read = 0L
        var resolved = false
        try {
            var link = if (!refresh && now < validUntil) cached else null
            if (link == null) {
                val page = http.get(URI("https://www.youtube.com/watch?v=jNQXAC9IVRw&hl=en"),2*1024*1024)
                read += page.response.body.size
                if (page.response.status != 200) return MediaResult(MediaStatus.UNAVAILABLE,bytes=read,detail="Метаданные ролика: HTTP ${page.response.status}; не считается отказом стратегии")
                link = MediaLinks.direct(page.response.body.toString(Charsets.UTF_8))
                if (link == null) return MediaResult(MediaStatus.UNAVAILABLE,bytes=read,detail="YouTube не выдал прямую публичную видеоссылку; медиадоставка не оценена")
                cached = link; validUntil = now+180000
            }
            resolved = true
            val rates = mutableListOf<Long>(); val latencies = mutableListOf<Long>()
            val chunk = 128*1024
            for (round in 0..1) {
                val start = 0L // Repeat the same bounded range on a fresh connection for comparable measurements.
                val transfer = http.get(link,chunk,start..(start+chunk-1))
                val response = transfer.response; read += response.body.size
                if (response.status in listOf(401,403,404,410,416,429)) {
                    invalidate()
                    return MediaResult(MediaStatus.UNAVAILABLE,bytes=read,detail="Видеоссылка отклонена сервисом (HTTP ${response.status}); обновится в следующей проверке")
                }
                val total = response.headers["content-range"]?.substringAfterLast('/')?.toLongOrNull()
                if (response.status == 206 && total != null && total < chunk)
                    return MediaResult(MediaStatus.UNAVAILABLE,bytes=read,detail="Публичный видеообразец меньше тестового фрагмента; стратегия не признана неисправной")
                if (!MediaLinks.validRange(response,start,chunk)) return MediaResult(MediaStatus.FAILED,bytes=read,detail="Видеофрагмент не доставлен полностью или неверный HTTP Range/type")
                rates.add(chunk.toLong()*8/transfer.elapsedMs.coerceAtLeast(1)); latencies.add(transfer.firstByteMs)
            }
            val low = rates.minOrNull()!!; val high = rates.maxOrNull()!!.coerceAtLeast(1)
            return MediaResult(MediaStatus.PASSED,low,latencies.maxOrNull()!!,(low*100/high).toInt(),read,"Два видеофрагмента по 128 КиБ: минимум $low кбит/с; повторяемость ${low*100/high}%")
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: ProbeBudget.Exhausted) { return MediaResult(MediaStatus.UNAVAILABLE,bytes=read,detail="Лимит диагностического трафика исчерпан") }
        catch (_: Exception) { return MediaResult(if (resolved) MediaStatus.FAILED else MediaStatus.UNAVAILABLE,bytes=read,detail=if (resolved) "Таймаут/обрыв доставки медиаданных; требуется повторная проверка" else "Публичные метаданные недоступны; медиадоставка не оценена") }
    }
}

/** Media diagnostics, including metadata: maximum 32 MiB in a rolling 24-hour window. */
class ProbeBudget(var start: Long,var spent: Long) {
    class Exhausted : java.io.IOException("Media diagnostic budget exhausted")
    fun prepare(now: Long): Boolean {
        if (now >= start && now-start >= 86400000L) { start = now; spent = 0 }
        if (start <= 0 || now < start) start = now
        return spent < 32*1024*1024L
    }
    fun charge(bytes: Int) { spent += bytes.coerceAtLeast(0); if (spent > 32*1024*1024L) throw Exhausted() }
}
