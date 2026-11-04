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
            val id = keys.next() // Keep missing IDs so a later catalog cannot silently revive an exclusion.
            val r = root.optJSONObject(id) ?: continue
            records[id] = QualityRecord(r.optInt("ok").coerceAtLeast(0),r.optInt("fail").coerceAtLeast(0),r.optInt("stall").coerceAtLeast(0),r.optInt("rollback").coerceAtLeast(0),
                r.optLong("useful").coerceAtLeast(0),r.optLong("active").coerceAtLeast(0),r.optLong("latency").coerceAtLeast(0),r.optLong("seen").coerceAtLeast(0),
                r.optBoolean("excluded",r.optInt("fail") > 0 && r.optInt("ok") == 0),
                r.optInt("last_passed",-1),r.optBoolean("last_complete",false))
        }
    }
    private fun record(index: Int) = records.getOrPut(RuntimeCatalog.ids[index]) { QualityRecord() }
    fun approved(index: Int,quality: ProbeQuality,now: Long) {
        if (!quality.eligible) { failed(index,quality=quality); return }
        val r = record(index); if (r.excluded) return
        r.successes++; r.lastSeen = now; r.latencyMs = quality.latencyMs
        r.lastPassed = quality.passed; r.lastComplete = true
        flush()
    }
    fun failed(index: Int,stall: Boolean = false,rollback: Boolean = false,quality: ProbeQuality? = null) {
        val r = record(index); r.lastComplete = false; r.lastPassed = quality?.passed?.coerceIn(0,6) ?: -1
        r.lastSeen = System.currentTimeMillis(); r.excluded = true; r.failures++; if (stall) r.stalls++; if (rollback) r.rollbacks++
        flush()
    }
    fun observe(index: Int,elapsedSeconds: Long,useful: Boolean) {
        if (elapsedSeconds !in 1..15) return
        val r = record(index); r.activeSeconds += elapsedSeconds
        if (useful) r.usefulSeconds += elapsedSeconds
    }
    fun excluded(): Set<Int> = records.filterValues { it.excluded }.keys.mapNotNull { TvPolicy.indexOfId(it) }.toSet()
    fun exclude(index: Int) { record(index).apply { excluded = true; lastComplete = false }; flush() }
    fun resetExclusions() { records.values.forEach { it.excluded = false }; flush() }
    fun latency(index: Int): Long = records[RuntimeCatalog.ids[index]]?.latencyMs ?: 0L
    fun preferred(legacy: List<Int>): List<Int> = choices(legacy).take(3)
    fun choices(legacy: List<Int>): List<Int> = TvCheckPolicy.ordered(TvPolicy.selectable(
        records.entries.filter { it.value.fullSuccess }.map { it.key },
        emptyList()),::latency) // Legacy history alone cannot prove a complete six-step test.
    fun describe(index: Int): String {
        val r = records[RuntimeCatalog.ids[index]] ?: return "Последняя полная проверка: не подтверждена"
        val last = if (r.lastComplete && r.lastPassed == 6) "6/6 · пройдена" else if (r.lastPassed >= 0) "${r.lastPassed}/6 · не пройдена" else "не подтверждена"
        return "Последняя проверка: $last; задержка HTTPS: ${r.latencyMs.takeIf { it > 0 }?.let { "$it мс" } ?: "не измерена"}; история полных проверок: успешных ${r.successes}, отказов ${r.failures}; полезный обмен: ${r.usefulSeconds/60} мин; откаты: ${r.rollbacks}"
    }
    fun summary(): String = records.entries.filter { it.value.fullSuccess && TvPolicy.indexOfId(it.key) != null }.sortedBy { it.value.latencyMs.takeIf { n -> n > 0 } ?: Long.MAX_VALUE }.take(5).joinToString("\n") { (id,r) ->
        "№${(TvPolicy.indexOfId(id) ?: -1)+1}: последний тест 6/6; в истории успешных ${r.successes}, отказов ${r.failures}; полезный обмен ${r.usefulSeconds/60} мин; зависания ${r.stalls}; откаты ${r.rollbacks}; задержка ${r.latencyMs} мс"
    }.ifEmpty { "История качества ещё не накоплена" }
    fun flush() {
        if (profile.isEmpty()) return
        val root = JSONObject()
        for ((id,r) in records) root.put(id,JSONObject().put("ok",r.successes).put("fail",r.failures).put("stall",r.stalls).put("rollback",r.rollbacks)
            .put("useful",r.usefulSeconds).put("active",r.activeSeconds).put("latency",r.latencyMs).put("seen",r.lastSeen).put("excluded",r.excluded).put("last_passed",r.lastPassed).put("last_complete",r.lastComplete))
        prefs.edit().putString("quality.$profile",root.toString()).apply()
        TvState.qualityHistory = summary()
    }
}
