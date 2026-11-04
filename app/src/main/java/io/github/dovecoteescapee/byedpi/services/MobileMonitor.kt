package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.os.SystemClock
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Rare, persisted cycles. Candidate probes do not restart the other service. */
class MobileMonitor(private val context: Context,private val scope: CoroutineScope,private val runtime: MobileRuntime) {
    private var task: Job? = null
    private var sample: Job? = null
    private var session: ServiceProbe.Session? = null
    private val health = listOf("instagram","youtube").associateWith { FlowHealth() }
    private val consumed = MobileFeedback.consumed
    private val reports = linkedMapOf<String,JSONObject>()
    @Volatile private var trialRunning = false
    private var cancellation = MobileFeedback.cancelToken()
    private var active = context.getPreferences().getString("mobile_command","").orEmpty()
    private val prefs get() = context.getPreferences()
    private fun automatic() = prefs.getBoolean("mobile_auto_three_days",true)
    private fun scanKey(id: String,profile: String) = "mobile_scan.$id.$profile"
    private fun same(profile: String) = MobileStrategyPolicy.connected(context) && MobileStrategyPolicy.profile(context) == profile
    private fun publish(message: String = "") {
        MobileStrategyPolicy.state = ServiceTargets.individual.joinToString("; ") { t ->
            reports[t.id]?.let { "${t.name}: ${ProbeAssessment.evaluate(t.id,it).text()}" } ?: "${t.name}: сохранённый маршрут"
        }+if (message.isEmpty()) "" else "\n$message"
        MobileStrategyPolicy.lastProbe = JSONObject().put("strategies",JSONObject(active)).put("services",JSONObject(reports as Map<*,*>))
            .put("state",MobileStrategyPolicy.state).put("traffic",runtime.diagnostic()).toString()
    }
    private var lastProgress = 0L
    private fun progress(text: String) { val now = SystemClock.elapsedRealtime();if (now-lastProgress >= 300) { lastProgress=now;publish(text) } }
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if (key == "mobile_auto_three_days" || key?.startsWith("mobile_reset.") == true) MobileFeedback.wake()
    }
    private fun sampling() {
        if (sample?.isActive == true) return
        sample = scope.launch {
            while (isActive && trialRunning) {
                delay(5000)
                health.forEach { (id,watch) ->
                    val s = runtime.router.active(id)?.let { runtime.router.stats(it) } ?: runtime.router.stats(id)
                    watch.observe(FlowHealth.Sample(SystemClock.elapsedRealtime(),s.tx,s.rx,s.writes))
                    if (watch.stalled || runtime.fault(id)) MobileFeedback.wake()
                }
            }
        }
    }
    fun start() {
        MobileFeedback.clearSignals()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        task = scope.launch {
            var profile = MobileStrategyPolicy.profile(context)
            while (isActive) {
                try {
                    if (!MobileStrategyPolicy.modern(prefs)) { publish("Перезапустите VPN после изменения настроек");return@launch }
                    if (!MobileStrategyPolicy.connected(context)) { publish("Нет физической сети; исключения не меняются");MobileFeedback.waitFor(15000);continue }
                    val currentProfile = MobileStrategyPolicy.profile(context)
                    if (profile != currentProfile) { profile = currentProfile;reports.clear();health.values.forEach { it.reset() } }
                    runtime.reap()
                    cancellation = MobileFeedback.cancelToken()
                    for (target in ServiceTargets.individual) {
                        if (!same(profile) || cancellation != MobileFeedback.cancelToken()) break
                        val id = target.id
                        val store = MobileHistory.store(context,id,profile)
                        if (prefs.getString("mobile_reset.$id",null) == profile) {
                            store.resetExclusions();prefs.edit().remove("mobile_reset.$id").apply()
                        }
                        val token = MobileFeedback.token(id)
                        val selection = runCatching { JSONObject(prefs.getString("mobile_selected.$id",null).orEmpty()) }.getOrNull()
                        val selected = selection?.takeIf { it.optString("profile") == profile }?.optString("command")
                        if (selection != null && selected == null) prefs.edit().remove("mobile_selected.$id").apply()
                        val forced = token != (consumed[id] ?: 0L) || selected != null || prefs.contains("mobile_imported.$id")
                        val key = scanKey(id,profile)
                        // Native worker recovery is local maintenance and does not bypass the probe budget.
                        if (runtime.fault(id)) {
                            val previous = runtime.router.active(id)
                            val candidate = runtime.stage(id,MobilePlan.command(active,id))
                            if (candidate != null) { runtime.activate(id,candidate);previous?.let { runtime.commit(it) } }
                        }
                        if (!MobileScanPolicy.due(automatic(),forced,prefs.getLong(key,0),System.currentTimeMillis())) continue
                        consumed[id] = token
                        prefs.edit().putLong(key,System.currentTimeMillis()).commit()
                        val previousCommand = MobilePlan.command(active,id)
                        val current = if (!store.excluded(previousCommand)) probe(id,previousCommand) else JSONObject().put("error","Исключённый вариант не проверяется")
                        if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken()) continue
                        reports[id] = current
                        val results = mutableListOf<Pair<String,JSONObject>>()
                        if (ProbeAssessment.evaluate(id,current).eligible) {
                            store.accept(previousCommand,current);results.add(previousCommand to current)
                        } else if (!store.excluded(previousCommand)) store.reject(previousCommand,current)
                        val commands = if (selected != null) listOf(selected).filter { !store.excluded(it) && io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol.validCommand(it) }
                            else MobileStrategyPolicy.window(context,target)
                        for ((index,command) in commands.withIndex()) {
                            if (command == previousCommand && !runtime.fault(id)) continue
                            if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || (!forced && !automatic())) break
                            publish("${target.name}: ${index+1}/${commands.size}; рабочая стратегия второго сервиса сохраняется")
                            val result = try { awaitResult(id,profile,token) { withTimeout(170000) { MobileCandidate.run(context,id,command) { progress("${target.name}: $it") } } } }
                                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive();JSONObject().put("error","Лимит процесса проверки") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { JSONObject().put("error",e.javaClass.simpleName) }
                            if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken()) break
                            if (store.accept(command,result)) results.add(command to result)
                            MobileStrategyPolicy.advance(context,id,command)
                        }
                        if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken()) continue
                        if (!forced && !automatic()) continue
                        val best = if (selected != null) results.firstOrNull { it.first == selected } else MobileScanPolicy.fastest(results,id)
                        prefs.edit().remove("mobile_selected.$id").remove("mobile_imported.$id").apply()
                        if (best == null) { publish("${target.name}: полного результата нет; неудачные варианты исключены");continue }
                        if (best.first == previousCommand && !runtime.fault(id)) {
                            MobileStrategyPolicy.remember(context,id,previousCommand);publish("${target.name}: текущая стратегия быстрее остальных полных результатов");continue
                        }
                        trial(id,best.first,profile,token,store)
                    }
                    val wait = if (automatic()) ServiceTargets.individual.minOf { MobileScanPolicy.remaining(prefs.getLong(scanKey(it.id,profile),0),System.currentTimeMillis()) } else Long.MAX_VALUE
                    publish(if (automatic()) "Следующий автоподбор через ${wait/3600000} ч; по требованию — сразу" else "Проверки только по требованию")
                    if (!same(profile) || ServiceTargets.individual.any { MobileFeedback.token(it.id) != (consumed[it.id] ?: 0L) }) MobileFeedback.wake()
                    MobileFeedback.waitFor(wait.coerceAtLeast(1000))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish("Ошибка контроля: ${e.javaClass.simpleName}");MobileFeedback.waitFor(60000) }
            }
        }
    }
    private suspend fun trial(id: String,command: String,profile: String,token: Long,store: MobileStrategyStore): Boolean {
        val previous = runtime.router.active(id) ?: return false
        val previousCommand = MobilePlan.command(active,id)
        val candidate = runtime.stage(id,command) ?: run { publish("Резервный слот занят; рабочий поток сохранён");return false }
        var accepted = false
        var failed = false
        prefs.edit().putString("mobile_trial.$id",JSONObject().put("previous",previousCommand).put("candidate",command).toString()).commit()
        val usefulBefore = health.getValue(id).usefulMillis
        runtime.activate(id,candidate);health.getValue(id).reset();trialRunning = true;sampling()
        try {
            val deadline = SystemClock.elapsedRealtime()+120000
            publish("${ServiceTargets.target(id).name}: испытание до 2 минут; второй сервис не перезапускается")
            var verified = probe(id,command)
            fun decision() = TrialGuard.decide(SystemClock.elapsedRealtime(),deadline,ProbeAssessment.evaluate(id,verified).eligible,
                health.getValue(id).stalled,!runtime.fault(id),same(profile),MobileFeedback.token(id) == token && cancellation == MobileFeedback.cancelToken())
            while (decision() == TrialGuard.Decision.WAIT) MobileFeedback.waitFor(minOf(10000,deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))
            if (decision() == TrialGuard.Decision.CONFIRM) verified = probe(id,command)
            if (decision() == TrialGuard.Decision.CONFIRM) {
                active = MobilePlan.replace(active,id,command)
                MobileStrategyPolicy.apply(prefs,MobilePlan.BOTH,active);MobileStrategyPolicy.remember(context,id,command)
                store.accept(command,verified,(health.getValue(id).usefulMillis-usefulBefore).coerceAtLeast(0));runtime.commit(previous)
                reports[id] = verified;accepted = true
            } else failed = same(profile) && MobileFeedback.token(id) == token && cancellation == MobileFeedback.cancelToken()
            return accepted
        } finally {
            prefs.edit().remove("mobile_trial.$id").apply()
            trialRunning = false;sample?.cancelAndJoin();sample = null
            if (!accepted && currentCoroutineContext().isActive) {
                runtime.rollback(id,previous,candidate)
                if (failed) store.reject(command,rollback=true)
                health.getValue(id).reset();publish("Выполнен откат ${ServiceTargets.target(id).name}; второй сервис сохранён")
            }
        }
    }
    private suspend fun awaitResult(id: String,profile: String,token: Long,action: suspend ()->JSONObject): JSONObject = coroutineScope {
        val pending = async {
            try { action() }
            catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive();JSONObject().put("error","Лимит проверки") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { JSONObject().put("error",e.javaClass.simpleName) }
        }
        try {
            while (!pending.isCompleted) {
                if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken()) {
                    session?.cancel();pending.cancelAndJoin();return@coroutineScope JSONObject().put("cancelled",true)
                }
                delay(200)
            }
            pending.await()
        } finally { if (!pending.isCompleted) { session?.cancel();pending.cancelAndJoin() } }
    }
    private suspend fun probe(id: String,command: String): JSONObject {
        val current = ServiceProbe.Session();session = current
        val profile = MobileStrategyPolicy.profile(context);val token = MobileFeedback.token(id)
        return try {
            awaitResult(id,profile,token) { withContext(Dispatchers.IO) {
                withTimeout(150000) { ServiceProbe.check(id,command,1080,current) { text -> scope.launch { progress("${ServiceTargets.target(id).name}: $text") } } }
            } }
        } finally { current.cancel();session = null }
    }
    suspend fun stop() { prefs.unregisterOnSharedPreferenceChangeListener(listener);session?.cancel();task?.cancelAndJoin();sample?.cancelAndJoin();task=null;sample=null }
}
