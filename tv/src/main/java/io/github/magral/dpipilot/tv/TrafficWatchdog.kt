package io.github.magral.dpipilot.tv

/** Conservative byte counters only. It never inspects accounts, video titles or payloads. */
class TrafficWatchdog {
    data class Sample(val at: Long,val sentPackets: Long,val sentBytes: Long,val receivedBytes: Long)
    private var baseline: Sample? = null
    private var slowWindows = 0
    var reason: String = "Нет устойчивого дефицита данных"
        private set
    fun reset() { baseline = null; slowWindows = 0; reason = "Новый интервал наблюдения" }
    fun stalled(now: Sample): Boolean {
        val old = baseline
        if (old == null || now.at < old.at || now.sentPackets < old.sentPackets ||
            now.sentBytes < old.sentBytes || now.receivedBytes < old.receivedBytes) {
            reset(); baseline = now; return false
        }
        // A sleep/wake gap is not an observed continuous stall.
        if (now.at-old.at > 90000L) { reset(); baseline = now; return false }
        if (now.at-old.at < 45000L) return false
        baseline = now
        val tx = now.sentBytes-old.sentBytes
        val packets = now.sentPackets-old.sentPackets
        val rx = now.receivedBytes-old.receivedBytes
        // Idle/buffered playback and small telemetry exchanges must not trigger a reset.
        val stopped = (packets >= 20 && rx == 0L) || (tx >= 16*1024 && rx < 2048L)
        val demand = packets >= 40 || tx >= 16*1024
        slowWindows = if (demand && rx < 64*1024) slowWindows+1 else 0
        reason = when {
            stopped -> "Исходящие запросы без достаточных ответов за 45 секунд"
            slowWindows >= 2 -> "Два интервала запросов с крайне малым входящим потоком"
            slowWindows == 1 -> "Подозрение на медленный обмен; ожидаю повторное подтверждение"
            else -> "Простой/буферизация либо входящий поток без признаков зависания"
        }
        return stopped || slowWindows >= 2
    }
}

object RecoveryPolicy {
    const val STALE_MILLIS = 180000L
    const val MIN_RESTART_GAP = 1800000L
    fun hung(now: Long,heartbeat: Long): Boolean = heartbeat > 0 && now >= heartbeat && now-heartbeat > STALE_MILLIS
    fun allowRestart(now: Long,previous: Long): Boolean = previous <= 0 || now < previous || now-previous >= MIN_RESTART_GAP
}
