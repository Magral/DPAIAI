package io.github.magral.dpipilot.tv

import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import java.net.URI
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** A manual experiment, not a strategy scorer. Never mutates the catalog, cache, exclusions or VPN. */
class PlaybackDiagnostics(
    private val transport: (PlaybackHttp.Request,ProbeSession,(String)->Unit)->HttpCodec.Response=PlaybackHttp::request,
    private val resolve: (String,ProbeSession)->List<String> = PlaybackHttp::resolve,
    private val ensureRoute: ()->Unit = {}
) {
    fun run(proxyPort: Int?,session: ProbeSession,progress: (String)->Unit = {}): String {
        val lines=mutableListOf("YouTube: диагностика меню и медиадоставки v1",
            "Маршрут: "+if(proxyPort==null)"системный маршрут этого приложения (может отличаться от YouTube)" else "локальный SOCKS5 127.0.0.1:$proxyPort",
            "Проверки узлов и API сами по себе не подтверждают видео. Отчёт не изменяет стратегии.")
        fun check() {session.checkActive();ensureRoute();session.checkActive()}
        fun add(text: String) {check();lines.add(text);progress(text)}
        fun request(label: String,req: PlaybackHttp.Request): HttpCodec.Response? {
            check();add("$label: начало")
            val start=System.nanoTime();var stage="начало"
            return try {
                transport(req,session) { check();stage=it;progress(it) }.also {session.checkActive();add("$label: HTTP ${it.status}; ${it.body.size} байт; ${(System.nanoTime()-start)/1000000} мс")}
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {session.checkActive();add("$label: ОШИБКА ${e.javaClass.simpleName}; стадия $stage");null}
        }
        for(host in listOf("youtubei.googleapis.com","redirector.googlevideo.com")) {
            session.checkActive()
            try {add("DNS устройства $host: "+resolve(host,session).joinToString().ifEmpty {"нет адресов"})}
            catch(e: CancellationException) {throw e}
            catch(e: Exception) {session.checkActive();add("DNS устройства $host: ${e.javaClass.simpleName}")}
            val dns=request("Публичный DNS (через системный маршрут)",PlaybackHttp.Request("dns.google","/resolve?name=$host&type=A",limit=16384))
            if(dns!=null) {
                val json=runCatching {JSONObject(String(dns.body,Charsets.UTF_8))}.getOrNull()
                val answers=json?.optJSONArray("Answer")
                val ips=if(answers==null)emptyList() else (0 until answers.length()).mapNotNull {answers.optJSONObject(it)?.takeIf {a->a.optInt("type")==1}?.optString("data")}
                add("DNS Google $host: "+ips.filter {Regex("[0-9.]{7,15}").matches(it)}.joinToString().ifEmpty {"ответ не получен"})
            }
        }
        request("Контроль интернета (системный маршрут)",PlaybackHttp.Request("www.gstatic.com","/generate_204",limit=1))
        val mapping=request("Карта CDN",PlaybackHttp.Request("redirector.googlevideo.com","/report_mapping?di=no",proxyPort,limit=4096))
        if(mapping!=null) {
            // report_mapping may include the viewer's public IP. Keep only the server-side cache label.
            val label=String(mapping.body,Charsets.UTF_8).substringAfter("=>","").trim()
            if(label.matches(Regex("[a-zA-Z0-9 _.,()=-]{1,160}")))add("CDN mapping: $label")
        }
        val urls=mutableListOf<YouTubePlayback.Track>()
        var apiResponses=0
        for(video in listOf("jNQXAC9IVRw","M7lc1UVf-VE")) {
            val page=request("Публичная страница $video",PlaybackHttp.Request("www.youtube.com","/watch?v=$video&hl=en",proxyPort,limit=2*1024*1024))
            val html=page?.let {String(it.body,Charsets.UTF_8)}.orEmpty()
            val embedded=YouTubePlayback.player(html)
            val version=Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"").find(html)?.groupValues?.get(1) ?: "2.20240709.01.00"
            val body=JSONObject().put("videoId",video).put("context",JSONObject().put("client",JSONObject().put("clientName","WEB").put("clientVersion",version).put("hl","en"))).toString()
            val api=request("API player $video",PlaybackHttp.Request("youtubei.googleapis.com","/youtubei/v1/player?prettyPrint=false",proxyPort,body,1024*1024))
            val json=api?.let {runCatching {JSONObject(String(it.body,Charsets.UTF_8))}.getOrNull()}
            if(api!=null) {
                val answer=YouTubeApi.assess(api.status,api.headers["content-type"].orEmpty(),api.body)
                if(answer.ok)apiResponses++
                add(answer.detail)
            } else add("API: содержательный ответ player не подтверждён; один HTTP/TLS не считается успехом")
            urls.addAll((YouTubePlayback.samples(embedded)+YouTubePlayback.samples(json)).distinctBy {it.kind})
            if(YouTubePlayback.urls(json).isEmpty() && YouTubePlayback.urls(embedded).isEmpty())add("$video: публичный адрес медиа не выдан (вход/consent/подпись/SABR); медиатест НЕ ЗАВЕРШЁН")
        }
        val samples=mutableListOf<Pair<String,YouTubePlayback.Sample>>()
        for(track in urls.distinctBy {it.url}.take(4)) {
            check();val uri=URI(track.url);val start=System.nanoTime()
            val response=request("Медиадиапазон ${uri.host}",PlaybackHttp.Request(uri.host,uri.rawPath+"?"+uri.rawQuery.orEmpty(),proxyPort,limit=256*1024,range=true))
            val ms=(System.nanoTime()-start)/1000000
            val sample=if(response==null) YouTubePlayback.Sample(YouTubePlayback.State.FAILED,0,ms,0,"Запрос медиа не завершился; причина выше")
                else YouTubePlayback.assess(response.status,response.headers["content-type"].orEmpty(),response.body,ms)
            samples.add(track.kind to sample);add("${track.kind} ${uri.host}: ${sample.state}; ${sample.bytes} байт / ${sample.elapsedMs} мс; ${sample.kbps} Кбит/с; ${sample.detail}")
        }
        add("API: $apiResponses/2 содержательных ответов; состояние относится к публичным образцам, не к аккаунту")
        val video=YouTubePlayback.overall(samples.filter {it.first=="video"}.map {it.second})
        val audio=YouTubePlayback.overall(samples.filter {it.first=="audio"}.map {it.second})
        add("ВИДЕО: $video; АУДИО: $audio (UNAVAILABLE = не проверено)")
        val overall=if(video==YouTubePlayback.State.CONFIRMED && audio==YouTubePlayback.State.CONFIRMED)YouTubePlayback.State.CONFIRMED
            else if(video==YouTubePlayback.State.FAILED || audio==YouTubePlayback.State.FAILED)YouTubePlayback.State.FAILED else YouTubePlayback.State.UNAVAILABLE
        add(when(overall) {
            YouTubePlayback.State.CONFIRMED -> "МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН. Это не гарантия всех роликов/Shorts и воспроизведения в приложении."
            YouTubePlayback.State.FAILED -> "МЕДИА: ЕСТЬ СБОЙ ИЛИ НИЗКАЯ СКОРОСТЬ. Доступность сайта/TLS не доказывает медиадоставку."
            YouTubePlayback.State.UNAVAILABLE -> "МЕДИА: НЕ ПРОВЕРЕНО. Тестовый адрес недоступен/не выдан; стратегии не исключались."
        })
        add("Сравните два отчёта на этом устройстве: обычная сеть и платный VPN. Совпадение/различие DNS само по себе не доказывает подмену; CDN выдаёт разные адреса штатно.")
        return lines.joinToString("\n")
    }
}
