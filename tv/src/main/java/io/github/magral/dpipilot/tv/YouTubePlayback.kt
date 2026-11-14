package io.github.magral.dpipilot.tv

import java.net.URI
import org.json.JSONObject

/** Anonymous sample evidence only; never a claim about all videos or the signed-in client. */
object YouTubePlayback {
    enum class State { CONFIRMED, FAILED, UNAVAILABLE }
    data class Sample(val state: State,val bytes: Int,val elapsedMs: Long,val kbps: Long,val detail: String)
    fun mediaUrl(value: String): Boolean = runCatching {
        require(value.length in 1..8192 && value.none { it.isISOControl() })
        val u=URI(value)
        u.scheme=="https" && u.userInfo==null && u.fragment==null && u.port in listOf(-1,443) &&
            u.host?.let { host -> host.endsWith(".googlevideo.com") && Regex("[a-zA-Z0-9.-]+\\.googlevideo\\.com").matches(host) }==true && u.path=="/videoplayback"
    }.getOrDefault(false)
    fun player(html: String): JSONObject? {
        for(marker in listOf("ytInitialPlayerResponse = ","ytInitialPlayerResponse=")) {
            val from=html.indexOf(marker);if(from<0)continue
            val start=html.indexOf('{',from+marker.length);if(start<0)continue
            var depth=0;var quoted=false;var escape=false
            for(i in start until html.length) {
                val c=html[i]
                if(quoted) {if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quoted=false}
                else if(c=='"')quoted=true else if(c=='{')depth++ else if(c=='}' && --depth==0)
                    return runCatching {JSONObject(html.substring(start,i+1))}.getOrNull()
            }
        }
        return null
    }
    fun urls(player: JSONObject?): List<String> {
        val data=player?.optJSONObject("streamingData") ?: return emptyList()
        return listOf("formats","adaptiveFormats").flatMap { name ->
            val a=data.optJSONArray(name) ?: return@flatMap emptyList<String>()
            (0 until a.length()).mapNotNull { n -> a.optJSONObject(n)?.takeIf {
                val type=it.optString("mimeType");type.startsWith("video/") || type.startsWith("audio/")
            }?.optString("url")?.takeIf(::mediaUrl) }
        }.distinct()
    }
    data class Track(val kind: String,val url: String)
    fun samples(player: JSONObject?): List<Track> {
        val data=player?.optJSONObject("streamingData") ?: return emptyList()
        val selected=linkedMapOf<String,Track>()
        for(name in listOf("formats","adaptiveFormats")) {
            val formats=data.optJSONArray(name) ?: continue
            for(i in 0 until formats.length()) {
                val f=formats.optJSONObject(i) ?: continue
                val kind=when {f.optString("mimeType").startsWith("video/")->"video";f.optString("mimeType").startsWith("audio/")->"audio";else->continue}
                val url=f.optString("url")
                if(kind !in selected && mediaUrl(url))selected[kind]=Track(kind,url)
            }
        }
        return listOfNotNull(selected["video"],selected["audio"])
    }
    fun assess(status: Int,type: String,body: ByteArray,elapsedMs: Long): Sample {
        val ms=elapsedMs.coerceAtLeast(1);val kbps=body.size*8L/ms
        fun result(state: State,reason: String)=Sample(state,body.size,ms,kbps,reason)
        if(status in listOf(403,404,410,429))return result(State.UNAVAILABLE,"Тестовый адрес отклонён/истёк: HTTP $status; это не доказательство отказа стратегии")
        if(status !in listOf(200,206))return result(State.FAILED,"Неверный HTTP медиасервера: $status")
        if(!(type.startsWith("video/",true)||type.startsWith("audio/",true)))return result(State.FAILED,"Вместо медиа получен другой тип содержимого")
        if(body.size<32768)return result(State.FAILED,"Получено меньше 32 КБ медиаданных")
        val mp4=body.size>=8 && String(body,4,4,Charsets.US_ASCII) in listOf("ftyp","styp")
        val webm=body.take(4).map {it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3)
        if(!mp4 && !webm)return result(State.FAILED,"Не распознан MP4/WebM в начале медиадиапазона")
        if(kbps<512)return result(State.FAILED,"Образец медленнее 512 Кбит/с; возможны буферизация/ограничение скорости")
        return result(State.CONFIRMED,"Медиадиапазон загружен; другие ролики и воспроизведение в клиенте проверяются отдельно")
    }
    fun overall(samples: List<Sample>): State = when {
        samples.any {it.state==State.FAILED} -> State.FAILED
        samples.isNotEmpty() && samples.all {it.state==State.CONFIRMED} -> State.CONFIRMED
        else -> State.UNAVAILABLE
    }
}
