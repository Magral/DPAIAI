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
                r.optInt("last_passed",-1),r.optBoolean("last_complete",false),PreviewQuality.decode(r.optJSONArray("previews")),r.optInt("reports"))
        }
        // v0.6.7 mixed preview quality failures with transport exclusions. Repair these once.
        val marker = "quality_access_schema.$profile"
        if (prefs.getString(marker,null) != "4") {
            var restored = 0
            for ((id,r) in records) if (TvPolicy.indexOfId(id) != null && r.excluded && r.reports == 0) {
                r.excluded = false;r.lastComplete = false;restored++
            }
            val edit = prefs.edit().putString(marker,"4")
            if (restored > 0) edit.putString("quality_repair_pending.$profile","1")
            flush(edit)
        }
    }
    fun consumeRepair(): Boolean {
        val key = "quality_repair_pending.$profile"
        if (prefs.getString(key,null) != "1") return false
        prefs.edit().remove(key).apply();return true
    }
    private fun record(index: Int) = records.getOrPut(RuntimeCatalog.ids[index]) { QualityRecord() }
    fun approved(index: Int,quality: ProbeQuality,now: Long) {
        if (!quality.eligible) { failed(index,quality=quality); return }
        val r = record(index); if (r.excluded) return
        r.successes++; r.lastSeen = now; r.latencyMs = quality.latencyMs
        r.lastPassed = quality.passed; r.lastComplete = true; r.preview = quality.preview
        flush()
    }
    fun defer(index: Int,quality: ProbeQuality) {
        val r = record(index);r.lastComplete = false;r.lastPassed = quality.passed
        r.preview = quality.preview;flush()
    }
    fun failed(index: Int,stall: Boolean = false,rollback: Boolean = false,quality: ProbeQuality? = null,user: Boolean = false) {
        val r = record(index); r.lastComplete = false; r.lastPassed = quality?.passed?.coerceIn(0,6) ?: -1
        r.preview = quality?.preview ?: PreviewQuality(); if (user) r.reports++
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
    fun quality(index: Int): ProbeQuality = records[RuntimeCatalog.ids[index]]?.let { ProbeQuality(it.lastComplete,it.lastPassed,it.latencyMs,it.preview) } ?: ProbeQuality(false,0,0)
    fun preview(index: Int) = quality(index).preview
    fun preferred(legacy: List<Int>): List<Int> = choices(legacy).take(3)
    fun choices(legacy: List<Int>): List<Int> = records.entries.filter { it.value.fullSuccess }
        .mapNotNull { TvPolicy.indexOfId(it.key) }
        .sortedWith(compareBy<Int> { preview(it).score }.thenBy { latency(it) }.thenBy { it })
    fun describe(index: Int): String {
        val r = records[RuntimeCatalog.ids[index]] ?: return "Последняя полная проверка: не подтверждена"
        val last = if (r.lastPassed >= 0) "узлы ${r.lastPassed}/6" else "узлы не проверены"
        return "Последняя проверка: $last; ${r.preview.text()}; ${if (r.fullSuccess) "проверки доступности пройдены" else "проверки доступности не пройдены"}; жалоб ${r.reports}; задержка HTTPS: ${r.latencyMs} мс; в истории успешных ${r.successes}, отказов ${r.failures}; полезный обмен ${r.usefulSeconds/60} мин"

    }
    fun summary(): String = records.entries.filter { it.value.fullSuccess && TvPolicy.indexOfId(it.key) != null }.sortedWith(compareBy<Map.Entry<String,QualityRecord>> { it.value.preview.score }.thenBy { it.value.latencyMs }).take(5).joinToString("\n") { (id,r) ->
        "№${(TvPolicy.indexOfId(id) ?: -1)+1}: последний тест 6/6; ${r.preview.text()}; в истории успешных ${r.successes}, отказов ${r.failures}; полезный обмен ${r.usefulSeconds/60} мин; зависания ${r.stalls}; откаты ${r.rollbacks}; задержка ${r.latencyMs} мс"
    }.ifEmpty { "История качества ещё не накоплена" }
    fun flush(edit: SharedPreferences.Editor = prefs.edit()) {
        if (profile.isEmpty()) return
        val root = JSONObject()
        for ((id,r) in records) root.put(id,JSONObject().put("ok",r.successes).put("fail",r.failures).put("stall",r.stalls).put("rollback",r.rollbacks)
            .put("useful",r.usefulSeconds).put("active",r.activeSeconds).put("latency",r.latencyMs).put("seen",r.lastSeen).put("excluded",r.excluded).put("last_passed",r.lastPassed).put("last_complete",r.lastComplete).put("previews",r.preview.encode()).put("reports",r.reports))
        edit.putString("quality.$profile",root.toString()).apply()
        TvState.qualityHistory = summary()
    }
}
