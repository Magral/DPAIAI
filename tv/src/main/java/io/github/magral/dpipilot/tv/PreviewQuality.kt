package io.github.magral.dpipilot.tv

import org.json.JSONArray
import org.json.JSONObject

/** Complete preview transfers, not an estimate of video-stream bitrate. */
data class PreviewSample(val source: String,val round: Int,val bytes: Int,val elapsedMs: Long,val gapMs: Long,val valid: Boolean)
data class PreviewQuality(val samples: List<PreviewSample> = emptyList()) {
    companion object {
        val sources = setOf("image","image-jpeg","image-webp")
        const val LIMIT = 512*1024
        fun decode(rows: JSONArray?): PreviewQuality = PreviewQuality(if (rows == null) emptyList() else
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { r ->
                PreviewSample(r.optString("source"),r.optInt("round"),r.optInt("bytes"),r.optLong("ms"),r.optLong("gap"),r.optBoolean("valid"))
            })
    }
    val complete: Boolean get() = samples.size == 6 && sources.all { source -> (1..2).all { round ->
        samples.count { it.source == source && it.round == round } == 1
    } }
    val passed: Int get() = samples.count { it.valid && it.bytes in 1024..LIMIT && it.elapsedMs in 1..8000 && it.gapMs in 0..3000 }
    val medianMs: Long get() = samples.map { it.elapsedMs }.sorted().takeIf { it.size == 6 }?.let { it[2]+(it[3]-it[2])/2 } ?: Long.MAX_VALUE
    val worstMs: Long get() = samples.maxOfOrNull { it.elapsedMs } ?: Long.MAX_VALUE
    val gapMs: Long get() = samples.maxOfOrNull { it.gapMs } ?: Long.MAX_VALUE
    val usable: Boolean get() = complete && passed == 6 && medianMs <= 4000
    val score: Long get() = if (usable) medianMs*2+worstMs+gapMs else Long.MAX_VALUE
    fun text(): String = if (!complete) "полная загрузка превью ещё не подтверждена" else
        "превью $passed/6; обычно ${medianMs} мс, максимум ${worstMs} мс; пауза ${gapMs} мс"
    fun encode(): JSONArray = JSONArray(samples.map { s -> JSONObject().put("source",s.source).put("round",s.round)
        .put("bytes",s.bytes).put("ms",s.elapsedMs).put("gap",s.gapMs).put("valid",s.valid) })
}
