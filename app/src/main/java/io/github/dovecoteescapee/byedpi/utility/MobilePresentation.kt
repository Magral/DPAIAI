package io.github.dovecoteescapee.byedpi.utility

import io.github.dovecoteescapee.byedpi.catalog.RuntimeCatalog
import org.json.JSONObject

/** Structured progress, never endpoint messages or engine command lines. */
data class ProbeProgress(val passed: Int,val total: Int,val phase: String = "checking") {
    fun encode(): String = JSONObject().put("passed",passed).put("total",total).put("phase",phase).toString()
    companion object {
        fun decode(text: String): ProbeProgress {
            val row = JSONObject(text)
            return ProbeProgress(row.optInt("passed"),row.optInt("total"),row.optString("phase","checking"))
        }
    }
}

object MobilePresentation {
    data class Status(val command: String,val passed: Int,val total: Int,val phase: String)
    private val statuses = linkedMapOf<String,Status>()
    data class Scan(val total: Int,val checked: Int = 0,val deferred: Int = 0,val phase: String = "Быстрый проход",val done: Boolean = false)
    private val scans = linkedMapOf<String,Scan>()
    private val verified = linkedSetOf<String>()
    @Synchronized fun beginScan(id: String,total: Int) { scans[id] = Scan(total.coerceAtLeast(0)) }
    @Synchronized fun checked(id: String,deferred: Int) { scans[id]?.let { scans[id] = it.copy(checked=(it.checked+1).coerceAtMost(it.total),deferred=deferred) } }
    @Synchronized fun secondPass(id: String,remaining: Int) { scans[id]?.let { scans[id] = it.copy(deferred=remaining,phase="Перепроверяем медленные варианты") } }
    @Synchronized fun endScan(id: String) { scans[id]?.let { scans[id] = it.copy(done=true,phase="Подбор завершён") } }
    @Synchronized fun interruptScan(id: String) { scans[id]?.let { scans[id] = it.copy(done=true,phase="Подбор прерван") } }
    @Synchronized fun applied(id: String) { verified.add(id) }
    @Synchronized fun scan(id: String) = scans[id]
    fun scanText(scan: Scan): String = "Проверено ${scan.checked} из ${scan.total}, осталось ${(scan.total-scan.checked).coerceAtLeast(0)}"+
        "\n${scan.phase}"+if (scan.deferred > 0) " · Отложено: ${scan.deferred}" else ""

    fun strategy(command: String): String {
        val index = RuntimeCatalog.commands.indexOf(command)
        return if (index < 0) "Своя стратегия" else "Стратегия №${index+1}"
    }
    @Synchronized fun clear() { statuses.clear();scans.clear();verified.clear() }
    @Synchronized fun start(id: String,command: String) {
        statuses[id] = Status(command,0,ServiceTargets.target(id).endpoints.size*2,"checking")
    }
    @Synchronized fun progress(id: String,command: String,value: ProbeProgress) {
        val current = statuses[id] ?: return
        if (current.command != command || current.phase !in setOf("checking","retry","preview","video")) return
        val expected = ServiceTargets.target(id).endpoints.size*2
        statuses[id] = Status(command,value.passed.coerceIn(0,expected),expected,
            value.phase.takeIf { it in setOf("checking","retry","preview","video") } ?: "checking")
    }
    @Synchronized fun finish(id: String,command: String,result: JSONObject) {
        val score = ProbeAssessment.evaluate(id,result)
        statuses[id] = Status(command,score.requiredSuccess+score.extraSuccess,score.requiredTotal+score.extraTotal,
            if (score.eligible) "good" else if (result.optBoolean("deferred") || result.optBoolean("inconclusive")) "deferred" else "failed")
    }
    @Synchronized fun ready(id: String,command: String) { statuses[id] = Status(command,0,0,"saved") }
    @Synchronized fun disabled(id: String) { statuses[id] = Status("",0,0,"disabled") }
    @Synchronized fun pause() {
        statuses.keys.toList().forEach { id ->
            val s = statuses.getValue(id)
            scans[id]?.let { scans[id] = it.copy(done=true,phase="Подбор остановлен") }
            if (s.phase in setOf("checking","retry","preview","video")) statuses[id] = s.copy(phase="paused")
        }
    }
    fun text(status: Status): String {
        val count = "Пройдено ${status.passed} из ${status.total}"
        val state = when (status.phase) {
            "good" -> "$count · Годна"
            "failed" -> "$count · Не прошла"
            "deferred" -> "$count · Отложена для перепроверки"
            "video" -> "$count · Проверяем загрузку видео"
            "preview" -> "$count · Проверяем качество"
            "retry" -> "$count · Повторная проверка"
            "saved" -> "Сохранённая стратегия"
            "paused" -> "Подбор остановлен"
            else -> count
        }
        return if (status.phase == "disabled") "Подбор отключён" else "${strategy(status.command)}\n$state"
    }
    @Synchronized fun summary(): String = ServiceTargets.individual.joinToString("\n\n") { t ->
        "${t.name}\n"+(if (t.id in verified) "Уже работает"+(if (scans[t.id]?.done == false) " · Ищем более быструю" else "")+"\n" else "")+
            (statuses[t.id]?.let(::text) ?: "Сохранённая стратегия")+(scans[t.id]?.let { "\n"+scanText(it) } ?: "")
    }
}
