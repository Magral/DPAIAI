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
    fun strategy(command: String): String {
        val index = RuntimeCatalog.commands.indexOf(command)
        return if (index < 0) "Своя стратегия" else "Стратегия №${index+1}"
    }
    @Synchronized fun clear() { statuses.clear() }
    @Synchronized fun start(id: String,command: String) {
        statuses[id] = Status(command,0,ServiceTargets.target(id).endpoints.size*2,"checking")
    }
    @Synchronized fun progress(id: String,command: String,value: ProbeProgress) {
        val current = statuses[id] ?: return
        if (current.command != command || current.phase !in setOf("checking","retry","preview")) return
        val expected = ServiceTargets.target(id).endpoints.size*2
        statuses[id] = Status(command,value.passed.coerceIn(0,expected),expected,
            value.phase.takeIf { it in setOf("checking","retry","preview") } ?: "checking")
    }
    @Synchronized fun finish(id: String,command: String,result: JSONObject) {
        val score = ProbeAssessment.evaluate(id,result)
        statuses[id] = Status(command,score.requiredSuccess+score.extraSuccess,score.requiredTotal+score.extraTotal,
            if (score.eligible) "good" else "failed")
    }
    @Synchronized fun ready(id: String,command: String) { statuses[id] = Status(command,0,0,"saved") }
    @Synchronized fun disabled(id: String) { statuses[id] = Status("",0,0,"disabled") }
    @Synchronized fun pause() {
        statuses.keys.toList().forEach { id ->
            val s = statuses.getValue(id)
            if (s.phase in setOf("checking","retry","preview")) statuses[id] = s.copy(phase="paused")
        }
    }
    fun text(status: Status): String {
        val count = "Пройдено ${status.passed} из ${status.total}"
        val state = when (status.phase) {
            "good" -> "$count · Годна"
            "failed" -> "$count · Не прошла"
            "preview" -> "$count · Проверяем качество"
            "retry" -> "$count · Повторная проверка"
            "saved" -> "Сохранённая стратегия"
            "paused" -> "Подбор остановлен"
            else -> count
        }
        return if (status.phase == "disabled") "Подбор отключён" else "${strategy(status.command)}\n$state"
    }
    @Synchronized fun summary(): String = ServiceTargets.individual.joinToString("\n\n") { t ->
        "${t.name}\n"+(statuses[t.id]?.let(::text) ?: "Сохранённая стратегия")
    }
}
