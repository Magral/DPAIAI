package io.github.magral.dpipilot.tv

import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Proves delivery of player JSON, never playback or account access. */
object YouTubeApi {
    data class Answer(val ok: Boolean,val unavailable: Boolean,val detail: String,val timedOut: Boolean=false)
    data class Check(val answers: List<Answer>) {
        val ok get()=confirmed(answers,answers.size) && answers.isNotEmpty()
        val unavailable get()=answers.any { it.unavailable }
        val timedOut get()=answers.any { it.timedOut }
        val detail get()=answers.mapIndexed { i,a -> "${i+1}: ${a.detail}" }.joinToString("; ")
    }
    fun body(video: String="jNQXAC9IVRw",version: String="2.20240709.01.00"): String =
        JSONObject().put("videoId",video).put("context",JSONObject().put("client",JSONObject().put("clientName","WEB").put("clientVersion",version).put("hl","en"))).toString()
    fun assess(status: Int,type: String,body: ByteArray): Answer {
        if(status!=200)return Answer(false,true,"API HTTP $status: источник проверки недоступен")
        val json=if(type.substringBefore(';').trim().equals("application/json",true))runCatching {JSONObject(String(body,Charsets.UTF_8))}.getOrNull() else null
        val play=json?.optJSONObject("playabilityStatus")
        val state=play?.optString("status").orEmpty()
        if(!Regex("[A-Z_]{1,40}").matches(state))return Answer(false,true,"API: содержательный player JSON не получен")
        val renderer=play?.optJSONObject("errorScreen")?.optJSONObject("playerErrorMessageRenderer")
        fun text(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifEmpty {
            val runs=value?.optJSONArray("runs")
            if(runs==null)"" else (0 until runs.length()).joinToString("") {runs.optJSONObject(it)?.optString("text").orEmpty()}
        }
        val reason=listOf(play?.optString("reason").orEmpty(),text(renderer?.optJSONObject("reason")),text(renderer?.optJSONObject("subreason")))
            .filter {it.isNotBlank()}.distinct().joinToString("; ")
            .replace(Regex("https?://\\S+",RegexOption.IGNORE_CASE),"[URL скрыт]")
            .replace(Regex("[\\p{Cntrl}]")," ").take(240)
        return Answer(true,false,"API JSON: $state${if(reason.isEmpty())"" else "; $reason"} (транспорт, не видео)")
    }
    fun confirmed(observations: List<Answer>,required: Int): Boolean = required>0 && observations.size==required && observations.all {it.ok}
    fun check(port: Int,session: ProbeSession,timeout: Int,attempts: Int=2,transport: (PlaybackHttp.Request,ProbeSession,(String)->Unit)->HttpCodec.Response=PlaybackHttp::request): Check {
        require(attempts in 1..2)
        val answers=(1..attempts).map {
            session.checkActive()
            try {
                val response=transport(PlaybackHttp.Request("youtubei.googleapis.com","/youtubei/v1/player?prettyPrint=false",port,body(),256*1024,timeoutMs=timeout),session) {}
                assess(response.status,response.headers["content-type"].orEmpty(),response.body)
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {
                session.checkActive()
                Answer(false,false,"API: ${e.javaClass.simpleName}",generateSequence(e as Throwable?) {it.cause}.any {it is SocketTimeoutException})
            }
        }
        return Check(answers)
    }
}
