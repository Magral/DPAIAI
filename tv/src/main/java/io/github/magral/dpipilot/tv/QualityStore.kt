package io.github.magral.dpipilot.tv

import android.content.SharedPreferences
import org.json.JSONObject

/** One persisted record per stable command ID and network profile; no signed URLs or viewing history. */
class QualityStore(private val prefs: SharedPreferences) {
    private var profile = ""
    private val records = linkedMapOf<String,QualityRecord>()
    fun network(key: String) {
        if (key == profile) return
        flush(); records.clear(); profile = key
        val root = runCatching { JSONObject(prefs.getString("quality.$profile","{}").orEmpty()) }.getOrDefault(JSONObject())
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next(); if (TvPolicy.indexOfId(id) == null) continue
            val r = root.optJSONObject(id) ?: continue
            records[id] = QualityRecord(r.optInt("ok").coerceAtLeast(0),r.optInt("fail").coerceAtLeast(0),r.optInt("stall").coerceAtLeast(0),r.optInt("rollback").coerceAtLeast(0),
                r.optLong("useful").coerceAtLeast(0),r.optLong("active").coerceAtLeast(0),r.optLong("kbps").coerceAtLeast(0),r.optLong("latency").coerceAtLeast(0),r.optLong("seen").coerceAtLeast(0))
        }
    }
    private fun record(index: Int) = records.getOrPut(StrategyCatalog.ids[index]) { QualityRecord() }
    fun approved(index: Int,quality: ProbeQuality,now: Long) {
        val r = record(index); r.successes++; r.lastSeen = now; r.latencyMs = quality.latencyMs
        if (quality.media.status == MediaStatus.PASSED) r.minKbps = quality.media.minKbps
        flush()
    }
    fun failed(index: Int,stall: Boolean = false,rollback: Boolean = false) {
        val r = record(index); r.failures++; if (stall) r.stalls++; if (rollback) r.rollbacks++
        flush()
    }
    fun observe(index: Int,elapsedSeconds: Long,useful: Boolean) {
        if (elapsedSeconds !in 1..15) return
        val r = record(index); r.activeSeconds += elapsedSeconds
        if (useful) r.usefulSeconds += elapsedSeconds
    }
    fun preferred(legacy: List<Int>): List<Int> = ((records.entries.filter { it.value.successes > 0 || (it.value.usefulSeconds >= 300 && it.value.stalls == 0 && it.value.rollbacks == 0) }
        .sortedByDescending { it.value.rank() }.mapNotNull { TvPolicy.indexOfId(it.key) })+legacy).distinct().take(3)
    fun summary(): String = records.entries.sortedByDescending { it.value.rank() }.take(5).joinToString("\n") { (id,r) ->
        "№${(TvPolicy.indexOfId(id) ?: -1)+1}: тесты ${r.successes}/${r.successes+r.failures}; полезный обмен ${r.usefulSeconds/60} мин; зависания ${r.stalls}; откаты ${r.rollbacks}; ${r.minKbps} кбит/с"
    }.ifEmpty { "История качества ещё не накоплена" }
    fun flush() {
        if (profile.isEmpty()) return
        val root = JSONObject()
        for ((id,r) in records) root.put(id,JSONObject().put("ok",r.successes).put("fail",r.failures).put("stall",r.stalls).put("rollback",r.rollbacks)
            .put("useful",r.usefulSeconds).put("active",r.activeSeconds).put("kbps",r.minKbps).put("latency",r.latencyMs).put("seen",r.lastSeen))
        prefs.edit().putString("quality.$profile",root.toString()).apply()
        TvState.qualityHistory = summary()
    }
}
