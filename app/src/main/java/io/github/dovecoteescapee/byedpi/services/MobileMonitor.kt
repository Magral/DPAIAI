package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.os.SystemClock
import io.github.dovecoteescapee.byedpi.catalog.MobileCatalogUpdater
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Monitors only the app allowlist carried by the current TUN, never account data. */
class MobileMonitor(private val context: Context,private val scope: CoroutineScope,private val replace: suspend (String)->Unit) {
    private var task: Job? = null
    private var sample: Job? = null
    private var session: ServiceProbe.Session? = null
    private var usefulAt = 0L
    private var stalled = false
    private val denied = mutableMapOf<String,Long>()
    fun start() {
        val prefs = context.getPreferences(); val target = prefs.getString("mobile_target","instagram") ?: "instagram"
        var active = prefs.getString("mobile_command","") ?: ""
        sample = scope.launch {
            var at = SystemClock.elapsedRealtime(); var rx = 0L; var tx = 0L; var packets = 0L; var bad = 0
            var recentAt = at; var recentBytes = 0L
            while (isActive) {
                delay(5000)
                val now = SystemClock.elapsedRealtime(); val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: continue
                if (stats.size < 4) continue
                if (!MobileStrategyPolicy.connected(context) || stats[3] < rx || now-at > 90000) { at = now; rx = stats[3]; tx = stats[1]; packets = stats[0]; bad = 0; usefulAt = 0 }
                if (stats[3]-recentBytes >= 256*1024 && now-recentAt <= 30000) { usefulAt = now; recentAt = now; recentBytes = stats[3] }
                else if (now-recentAt > 30000 || stats[3] < recentBytes) { recentAt = now; recentBytes = stats[3] }
                if (now-at >= 45000) {
                    bad = if (TuningScore.lackOfReplies(stats[0]-packets,stats[1]-tx,stats[3]-rx)) bad+1 else 0
                    if (bad >= 2 && now-usefulAt > 90000) { stalled = true; bad = 0 }
                    at = now; rx = stats[3]; tx = stats[1]; packets = stats[0]
                }
            }
        }
        task = scope.launch {
            var failures = 0
            while (isActive) {
            try {
                while (isActive) {
                    if (!MobileStrategyPolicy.modern(prefs) || prefs.getString("mobile_command",null) != active || prefs.getString("mobile_target",null) != target) {
                        MobileStrategyPolicy.state = "Ручные настройки изменены; автоконтроль приостановлен до перезапуска VPN"
                        return@launch
                    }
                    if (!MobileStrategyPolicy.connected(context)) { MobileStrategyPolicy.state = "Нет физической сети; стратегия сохранена"; delay(15000); continue }
                    MobileStrategyPolicy.state = "Проверяется ${ServiceTargets.target(target).name}"
                    val networkProfile = MobileStrategyPolicy.profile(context)
                    val current = probe(target,active)
                    if (MobileStrategyPolicy.profile(context) != networkProfile) continue
                    val force = stalled; stalled = false
                    if (eligible(current) && !force) {
                        MobileStrategyPolicy.state = summary(target,current); failures = 0
                        delay(300000); continue
                    }
                    if (!force && SystemClock.elapsedRealtime()-usefulAt < 90000 && usefulAt > 0) {
                        MobileStrategyPolicy.state = "Клиент получает данные; текущая стратегия сохранена"; delay(60000); continue
                    }
                    var changed = false
                    for (command in MobileStrategyPolicy.window(context,ServiceTargets.target(target))) {
                        if (command == active || (denied[command] ?: 0) > SystemClock.elapsedRealtime()) continue
                        if (!MobileStrategyPolicy.connected(context)) break
                        MobileStrategyPolicy.state = "Фоновый подбор для ${ServiceTargets.target(target).name}; текущий VPN сохранён"
                        val result = try { MobileCandidate.run(context,target,command) } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject() }
                        if (MobileStrategyPolicy.profile(context) != networkProfile) break
                        MobileStrategyPolicy.advance(context,target,command)
                        if (!eligible(result)) { denied[command] = SystemClock.elapsedRealtime()+1800000; continue }
                        if (!force && usefulAt > 0 && SystemClock.elapsedRealtime()-usefulAt < 90000) break
                        val previous = active
                        replace(command)
                        val verified = probe(target,command)
                        if (eligible(verified)) {
                            active = command; MobileStrategyPolicy.apply(prefs,target,active); MobileStrategyPolicy.remember(context,target,active)
                            changed = true; MobileStrategyPolicy.state = summary(target,verified); break
                        }
                        replace(previous); denied[command] = SystemClock.elapsedRealtime()+1800000
                        MobileStrategyPolicy.state = "Кандидат не подтвердился; прежняя стратегия восстановлена"
                    }
                    failures = if (changed) 0 else failures+1
                    delay(if (changed) 300000 else when (failures) {1->60000;2->120000;3->300000;else->600000})
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { MobileStrategyPolicy.state = "Ошибка контроля: ${e.javaClass.simpleName}; повтор через минуту"; delay(60000) }
            }
        }
    }
    private suspend fun probe(target: String,command: String): JSONObject = coroutineScope {
        val current = ServiceProbe.Session(); session = current
        val pending = async(Dispatchers.IO) { ServiceProbe.check(target,command,1080,current) }
        try {
            val result = try { withTimeout(240000) { pending.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject().put("success",0).put("total",6).put("error","probe_timeout") }
            MobileStrategyPolicy.lastProbe = result.toString(); result
        } finally { current.cancel(); session = null }
    }
    private fun eligible(result: JSONObject) = TuningScore.eligible(result.optInt("success"),result.optInt("total"))
    private fun summary(target: String,result: JSONObject): String =
        "${ServiceTargets.target(target).name}: узлы ${result.optInt("success")}/${result.optInt("total")}; публичные ответы ${result.optInt("publicSuccess")}/${result.optInt("publicTotal")}. Аккаунт/лента/видео не проверялись"
    suspend fun stop() { session?.cancel(); task?.cancelAndJoin(); sample?.cancelAndJoin(); task = null; sample = null }
}
