package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object MobileDns {
    @Volatile var status="DNS: сохранённые настройки"
    fun key(context: Context,id: String)="mobile_dns.$id.${MobileStrategyPolicy.profile(context)}"
    fun selected(context: Context,id: String)=context.getPreferences().getString(key(context,id),"").orEmpty()
    fun save(context: Context,id: String,result: JSONObject) {
        context.getPreferences().edit().putString(key(context,id),result.optString("dns")).apply()
    }
    fun mode(context: Context)=context.getPreferences().getString("mobile_dns_mode","auto").orEmpty()
    suspend fun shortlist(context: Context,id: String): List<String> = withContext(Dispatchers.IO) {
        val prefs=context.getPreferences();val mode=mode(context)
        if(mode=="network") return@withContext listOf("")
        if(mode=="manual") return@withContext listOf(prefs.getString("mobile_dns_manual","1.1.1.1").orEmpty()).filter { it.isNotEmpty() && DnsResolver.valid(it) }.ifEmpty { listOf("") }
        val key="mobile_dns_candidates_v2.$id.${MobileStrategyPolicy.profile(context)}"
        val saved=runCatching { JSONObject(prefs.getString(key,"{}").orEmpty()) }.getOrDefault(JSONObject())
        val now=System.currentTimeMillis();val at=saved.optLong("at")
        if(at>0 && now>=at && now-at<MobileScanPolicy.INTERVAL) {
            val a=saved.optJSONArray("servers")
            if(a!=null && a.length()>0) return@withContext (0 until a.length()).map { a.getString(it) }.filter { DnsResolver.valid(it) }
        }
        val hosts=if(id=="youtube") listOf("youtubei.googleapis.com","i.ytimg.com","redirector.googlevideo.com") else listOf("i.instagram.com","static.cdninstagram.com")
        status="DNS $id: проверка ${DnsResolver.providers.size} вариантов"
        val ranked=java.util.concurrent.Executors.newFixedThreadPool(4).let { pool ->
            try { pool.invokeAll(DnsResolver.providers.map { p -> java.util.concurrent.Callable {
                val start=System.nanoTime()
                runCatching { hosts.forEach { DnsResolver.resolve(it,p.address) };p.address to (System.nanoTime()-start) }.getOrNull()
            } }).mapNotNull { it.get() }.sortedBy { it.second } } finally { pool.shutdownNow() }
        }
        // Always retain the currently applied resolver, then two fast alternatives.
        val candidates=(ranked.map { it.first }.filter { it==selected(context,id) }+ranked.map { it.first }).distinct().take(3).ifEmpty { listOf("") }
        prefs.edit().putString(key,JSONObject().put("at",now).put("servers",JSONArray(candidates)).toString()).apply()
        status="DNS $id: доступны ${ranked.size}/${DnsResolver.providers.size}; в подборе ${candidates.size}"
        candidates
    }
}
