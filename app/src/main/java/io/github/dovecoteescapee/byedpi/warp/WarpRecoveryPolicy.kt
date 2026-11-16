package io.github.dovecoteescapee.byedpi.warp

object WarpRecoveryPolicy {
    const val HANDSHAKE_MS=12000L
    const val SWEEP_MS=120000L
    fun retryDelay(failures: Int): Long = when(failures) {0->30000L;1->120000L;else->600000L}
    fun canAttempt(elapsedMs: Long)=elapsedMs in 0 until SWEEP_MS
    fun routedApps(installed: Set<String>,ownPackage: String): Set<String> = WarpPolicy.apps(installed)+ownPackage
}

/** Invalidates diagnostics on every reconnect, stop and start; handshake is not media success. */
class WarpSession {
    private var generation=0L
    private var ready=false
    @Synchronized fun restart(): Long {generation++;ready=false;return generation}
    @Synchronized fun connected(ticket: Long) {if(ticket==generation)ready=true}
    @Synchronized fun valid(ticket: Long)=ready && ticket==generation
    @Synchronized fun snapshot(): Long?=generation.takeIf {ready}
    @Synchronized fun invalidate(){generation++;ready=false}
}

/** Cursor survives timed-out sweeps so later ports are eventually attempted. */
class WarpAttempts<T>(private val candidates: List<T>) {
    private var cursor=0
    init {require(candidates.isNotEmpty())}
    fun next(): T=candidates[cursor].also {cursor=(cursor+1)%candidates.size}
}

/** A completed report stays attached to its tunnel, not merely to the activity. */
data class WarpDiagnosticReport(val ticket: Long,val text: String) {
    fun display(session: WarpSession): String = if(session.valid(ticket))text else
        "РЕЗУЛЬТАТ УСТАРЕЛ: WARP выключен или переподключён. Запустите новую проверку.\nИсторический отчёт:\n$text"
}
