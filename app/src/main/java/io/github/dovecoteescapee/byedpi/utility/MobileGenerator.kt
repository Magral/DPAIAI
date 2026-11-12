package io.github.dovecoteescapee.byedpi.utility

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import java.util.concurrent.atomic.AtomicLong

/** Small, deterministic grammar of known engine methods. No source text is executed. */
object StrategyGenerator {
    const val MAX_CANDIDATES = 120
    fun candidates(seeds: List<String>,skip: Set<String> = emptySet(),limit: Int = MAX_CANDIDATES): List<String> {
        val found = linkedSetOf<String>()
        fun offer(command: String) {
            if (command !in skip && command.isNotBlank() && CatalogProtocol.validCommand(command)) found.add(command)
        }
        // Vary positions in simple known-good shapes, never mutate trigger or file arguments.
        val positions = listOf("1","2","3","1+s","3+s","0+sm","1+sm","5+s")
        for (position in positions) for (method in listOf("split","disorder","tlsrec","oob")) offer("--$method $position")
        for (position in positions) for (record in listOf("1+s","3+s","0+sm")) {
            offer("--split $position --tlsrec $record")
            offer("--disorder $position --tlsrec $record")
        }
        for (seed in seeds.take(12)) {
            val tokens = seed.split(' ')
            for (index in tokens.indices) {
                if (tokens[index] !in setOf("--split","--disorder","--tlsrec") || index+1 >= tokens.size) continue
                for (position in positions.take(4)) {
                    val copy = tokens.toMutableList();copy[index+1] = position;offer(copy.joinToString(" "))
                }
            }
        }
        // TTL-bound fakes are network-specific candidates, never assumed working.
        for (ttl in listOf(3,4,5,6,8)) for (position in listOf("1+s","3+s"))
            offer("--fake -1 --ttl $ttl --split $position")
        return found.take(limit.coerceIn(0,MAX_CANDIDATES))
    }
}

object MobileGenerator {
    data class Request(val token: Long,val target: String,val minutes: Int)
    data class State(val target: String = "",val running: Boolean = false,val checked: Int = 0,val total: Int = 0,
        val good: Int = 0,val deferred: Int = 0,val phase: String = "Готов к запуску")
    private val serial = AtomicLong()
    @Volatile private var pending: Request? = null
    @Volatile var state = State();private set
    @Synchronized fun request(target: String,minutes: Int) {
        require(target in listOf("youtube","instagram") && minutes in listOf(2,5,10))
        val request = Request(serial.incrementAndGet(),target,minutes)
        pending = request;state = State(target,true,phase="Ожидаем начала")
        MobileFeedback.interruptScan()
    }
    @Synchronized fun take(): Request? = pending.also { pending = null }
    fun valid(request: Request) = request.token == serial.get()
    @Synchronized fun update(request: Request,checked: Int,total: Int,good: Int,deferred: Int,phase: String) {
        if (valid(request)) state = State(request.target,true,checked,total,good,deferred,phase)
    }
    @Synchronized fun finish(request: Request,message: String) {
        if (valid(request) && state.running) state = state.copy(running=false,phase=message)
    }
    @Synchronized fun stop() {
        serial.incrementAndGet();pending = null;state = state.copy(running=false,phase="Остановлен; найденные результаты сохранены")
        MobileFeedback.wake()
    }
    fun text(): String {
        val s = state
        return "${s.target.takeIf { it.isNotEmpty() }?.let { ServiceTargets.target(it).name+"\n" }.orEmpty()}${s.phase}\nПроверено ${s.checked} из ${s.total}\nГодных: ${s.good} · Отложено: ${s.deferred}"
    }
}
