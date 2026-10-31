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
        if (target == MobilePlan.BOTH) { startDual(active); return }
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
                    if (eligible(target,current) && !force) {
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
                        if (!eligible(target,result)) { denied[command] = SystemClock.elapsedRealtime()+1800000; continue }
                        if (!force && usefulAt > 0 && SystemClock.elapsedRealtime()-usefulAt < 90000) break
                        val previous = active
                        replace(command)
                        val verified = probe(target,command)
                        if (eligible(target,verified)) {
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
    /** Each service owns a command and cursor; one failing service must not suppress the other's search. */
    private fun startDual(initial: String) {
        val prefs = context.getPreferences()
        var active = initial
        val reports = linkedMapOf<String,JSONObject>()
        fun publish(message: String = "") {
            MobileStrategyPolicy.state = ServiceTargets.individual.joinToString("; ") { target ->
                reports[target.id]?.let { "${target.name}: ${ProbeAssessment.evaluate(target.id,it).text()}" } ?: "${target.name}: ожидает проверки"
            } + if (message.isEmpty()) " · общий VPN" else "\n$message"
            MobileStrategyPolicy.lastProbe = JSONObject().put("mode",MobilePlan.BOTH).put("strategies",JSONObject(active))
                .put("services",JSONObject(reports as Map<*,*>)).put("state",MobileStrategyPolicy.state).toString()
        }
        task = scope.launch {
            while (isActive) {
                try {
                    if (!MobileStrategyPolicy.modern(prefs) || prefs.getString("mobile_command",null) != active || prefs.getString("mobile_target",null) != MobilePlan.BOTH) {
                        MobileStrategyPolicy.state = "Настройки изменены; перезапустите общий VPN"; return@launch
                    }
                    if (!MobileStrategyPolicy.connected(context)) { publish("Нет физической сети; стратегии сохранены"); delay(15000); continue }
                    val profile = MobileStrategyPolicy.profile(context)
                    for (target in ServiceTargets.individual) {
                        publish("Проверяется ${target.name}; оба приложения остаются в VPN")
                        val current = probe(target.id,MobilePlan.command(active,target.id)); reports[target.id] = current; publish()
                        if (MobileStrategyPolicy.profile(context) != profile) break
                    }
                    if (MobileStrategyPolicy.profile(context) != profile) continue
                    for (target in ServiceTargets.individual) {
                        if (reports[target.id]?.let { eligible(target.id,it) } == true) continue
                        for (command in MobileStrategyPolicy.window(context,target)) {
                            val key = "${target.id}:$command"
                            if (command == MobilePlan.command(active,target.id) || (denied[key] ?: 0) > SystemClock.elapsedRealtime()) continue
                            if (MobileStrategyPolicy.profile(context) != profile || !MobileStrategyPolicy.connected(context)) break
                            val proposal = runCatching { MobilePlan.replace(active,target.id,command) }.getOrNull() ?: continue
                            publish("Поиск замены для ${target.name}; стратегия другого сервиса сохранена")
                            val result = try { MobileCandidate.run(context,target.id,command) { publish("${target.name}: $it") } }
                                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject() }
                            MobileStrategyPolicy.advance(context,target.id,command)
                            if (MobileStrategyPolicy.profile(context) != profile) break
                            if (!eligible(target.id,result)) { denied[key] = SystemClock.elapsedRealtime()+1800000; continue }
                            // Test real domain routing before replacing the live native process.
                            val combined = try { MobileCandidate.run(context,MobilePlan.BOTH,proposal) { publish("Совместная проверка: $it") } }
                                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject() }
                            val other = ServiceTargets.individual.first { it.id != target.id }
                            val protectOther = reports[other.id]?.let { eligible(other.id,it) } == true
                            if (!eligible(target.id,combined) || (protectOther && !eligible(other.id,combined))) {
                                denied[key] = SystemClock.elapsedRealtime()+1800000; continue
                            }
                            if (MobileStrategyPolicy.profile(context) != profile) break
                            val previous = active
                            var committed = false
                            try {
                                replace(proposal)
                                val verified = probe(target.id,command)
                                val otherVerified = if (protectOther) probe(other.id,MobilePlan.command(proposal,other.id)) else null
                                if (eligible(target.id,verified) && (otherVerified == null || eligible(other.id,otherVerified)) && MobileStrategyPolicy.profile(context) == profile) {
                                    active = proposal
                                    MobileStrategyPolicy.apply(prefs,MobilePlan.BOTH,active); MobileStrategyPolicy.remember(context,target.id,command)
                                    reports[target.id] = verified
                                    if (otherVerified != null) reports[other.id] = otherVerified
                                    committed = true; publish("Обновлена стратегия ${target.name}; оба приложения включены")
                                }
                            } finally {
                                if (!committed && currentCoroutineContext().isActive) replace(previous)
                            }
                            if (committed) break
                            denied[key] = SystemClock.elapsedRealtime()+1800000; publish("Кандидат не подтвердился; прежняя пара восстановлена")
                        }
                    }
                    val healthy = ServiceTargets.individual.all { reports[it.id]?.let { row -> eligible(it.id,row) } == true }
                    publish(if (healthy) "Основные публичные проверки пройдены; видео и аккаунт не проверялись" else "Повтор проверки и поиска через минуту")
                    delay(if (healthy) 300000 else 60000)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish("Ошибка контроля: ${e.javaClass.simpleName}: ${e.message?.take(100)}; повтор через минуту"); delay(60000) }
            }
        }
    }
    private suspend fun probe(target: String,command: String): JSONObject = coroutineScope {
        val current = ServiceProbe.Session(); session = current
        val pending = async(Dispatchers.IO) { ServiceProbe.check(target,command,1080,current) { MobileStrategyPolicy.state = "${ServiceTargets.target(target).name}: $it" } }
        try {
            val result = try { withTimeout(240000) { pending.await() } } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject().put("success",0).put("total",ServiceTargets.target(target).endpoints.size*2).put("error","probe_timeout") }
            MobileStrategyPolicy.lastProbe = result.toString(); result
        } finally { current.cancel(); session = null }
    }
    private fun eligible(target: String,result: JSONObject) = ProbeAssessment.evaluate(target,result).eligible
    private fun summary(target: String,result: JSONObject): String =
        "${ServiceTargets.target(target).name}: ${ProbeAssessment.evaluate(target,result).text()}. Аккаунт/лента/видео не проверялись"
    suspend fun stop() { session?.cancel(); task?.cancelAndJoin(); sample?.cancelAndJoin(); task = null; sample = null }
}
