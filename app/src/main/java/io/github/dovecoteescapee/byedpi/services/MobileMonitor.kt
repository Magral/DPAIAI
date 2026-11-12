package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.os.SystemClock
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Rare, persisted cycles. Candidate probes do not restart the other service. */
class MobileMonitor(private val context: Context,private val scope: CoroutineScope,private val runtime: MobileRuntime) {
    private var task: Job? = null
    private var session: ServiceProbe.Session? = null
    private val health = listOf("instagram","youtube").associateWith { FlowHealth() }
    private val consumed = MobileFeedback.consumed
    private val reports = linkedMapOf<String,JSONObject>()
    private var revision = MobileFeedback.requestVersion()
    private fun superseded() = revision != MobileFeedback.requestVersion()
    private var cancellation = MobileFeedback.cancelToken()
    private var active = context.getPreferences().getString("mobile_command","").orEmpty()
    private val prefs get() = context.getPreferences()
    private fun automatic() = prefs.getBoolean("mobile_auto_three_days",true)
    private fun scanKey(id: String,profile: String) = "mobile_scan.$id.$profile"
    private var connection = ""
    private fun same(profile: String) = connection == MobileStrategyPolicy.connection(context) && MobileStrategyPolicy.connected(context) && MobileStrategyPolicy.profile(context) == profile
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
    fun start() {
        MobileFeedback.clearSignals()
        ServiceTargets.individual.forEach { MobilePresentation.ready(it.id,MobilePlan.command(active,it.id)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        task = scope.launch {
            var profile = MobileStrategyPolicy.profile(context)
            while (isActive) {
                try {
                    if (!MobileStrategyPolicy.modern(prefs)) { publish("Перезапустите VPN после изменения настроек");return@launch }
                    if (!MobileStrategyPolicy.connected(context)) { publish("Нет физической сети; исключения не меняются");MobileFeedback.waitFor(15000);continue }
                    connection = MobileStrategyPolicy.connection(context)
                    val currentProfile = MobileStrategyPolicy.profile(context)
                    if (profile != currentProfile) { profile = currentProfile;reports.clear();health.values.forEach { it.reset() };MobilePresentation.clear() }
                    runtime.reap()
                    cancellation = MobileFeedback.cancelToken()
                    revision = MobileFeedback.requestVersion()
                    for (target in ServiceTargets.individual) {
                        if (!same(profile) || cancellation != MobileFeedback.cancelToken() || superseded()) break
                        val id = target.id
                        val store = MobileHistory.store(context,id,profile)
                        if (prefs.getString("mobile_reset.$id",null) == profile) {
                            store.resetExclusions();prefs.edit().remove("mobile_reset.$id").apply()
                        }
                        val token = MobileFeedback.token(id)
                        val selection = runCatching { JSONObject(prefs.getString("mobile_selected.$id",null).orEmpty()) }.getOrNull()
                        val selected = selection?.takeIf { it.optString("profile") == profile }?.optString("command")
                        if (selection != null && selected == null) prefs.edit().remove("mobile_selected.$id").apply()
                        val replaceRequested = prefs.getString("mobile_force_replace.$id",null) == profile && selected == null
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
                        val cached = selected?.let { store.freshResult(it) }
                        if (selected != null && cached != null) {
                            applyVerified(id,selected,cached,profile,token,waitForSlot=true)
                            prefs.edit().remove("mobile_selected.$id").apply()
                            continue
                        }
                        val current = if (!store.excluded(previousCommand)) probe(id,previousCommand,previews=true) else JSONObject().put("error","Исключённый вариант не проверяется")
                        if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) continue
                        reports[id] = current
                        val results = mutableListOf<Pair<String,JSONObject>>()
                        val failures = mutableListOf<Pair<String,JSONObject>>()
                        val currentGood = ProbeAssessment.evaluate(id,current).eligible
                        if (ProbeAssessment.evaluate(id,current).eligible) {
                            store.accept(previousCommand,current);if (!replaceRequested) results.add(previousCommand to current)
                        } else if (!store.excluded(previousCommand)) { store.defer(previousCommand,current);failures.add(previousCommand to current) }
                        val commands = if (selected != null) listOf(selected).filter { !store.excluded(it) && io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol.validCommand(it) }
                            else MobileStrategyPolicy.window(context,target)
                        var firstApplied = currentGood && !replaceRequested
                        var previewCandidates = 0 // At most 3 reserves + the current route per cycle.
                        for ((index,command) in commands.withIndex()) {
                            if (command == previousCommand && !runtime.fault(id)) continue
                            if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded() || (!forced && !automatic())) break
                            publish("${target.name}: ${index+1}/${commands.size}; рабочая стратегия второго сервиса сохраняется")
                            MobilePresentation.start(id,command)
                            val result = try { awaitResult(id,profile,token) { withTimeout(215000) {
                                MobileCandidate.run(context,id,command,known=store.known(command),previews=previewCandidates < 3) {
                                    MobilePresentation.progress(id,command,it)
                                }
                            } } }
                                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive();JSONObject().put("error","Лимит процесса проверки") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { JSONObject().put("error",e.javaClass.simpleName) }
                            if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) break
                            MobilePresentation.finish(id,command,result)
                            if (result.has("preview")) previewCandidates++
                            if (ProbeAssessment.evaluate(id,result).eligible) {
                                if (store.accept(command,result)) {
                                    results.add(command to result)
                                    if (!firstApplied) firstApplied = applyVerified(id,command,result,profile,token)
                                }
                            } else {
                                store.defer(command,result);failures.add(command to result)
                                publish("${target.name}: вариант не прошёл; ${probeFailureDetail(result)}")
                            }
                            MobileStrategyPolicy.advance(context,id,command)
                        }
                        if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) continue
                        if (!forced && !automatic()) continue
                        val best = if (selected != null) results.firstOrNull { it.first == selected } else MobileScanPolicy.fastest(results,id)
                        prefs.edit().remove("mobile_selected.$id").remove("mobile_imported.$id").remove("mobile_force_replace.$id").apply()
                        if (best == null) { publish("${target.name}: полного результата нет; исключения не записаны — возможен общий сбой сети");continue }
                        // Commit negative evidence only beside a fresh successful control on this same network.
                        if (currentGood && failures.isNotEmpty()) {
                            val controlCommand = MobilePlan.command(active,id)
                            val control = probe(id,controlCommand)
                            if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) continue
                            if (ProbeAssessment.evaluate(id,control).eligible)
                                failures.forEach { (command,result) -> store.reject(command,result) }
                            else { store.defer(controlCommand,control);publish("${target.name}: контроль не прошёл; массовые исключения отменены");continue }
                        }
                        if (best.first == MobilePlan.command(active,id) && !runtime.fault(id)) {
                            MobileStrategyPolicy.remember(context,id,best.first);MobilePresentation.finish(id,best.first,best.second)
                            publish("${target.name}: текущая стратегия выбрана среди полных результатов");continue
                        }
                        applyVerified(id,best.first,best.second,profile,token,waitForSlot=true)
                    }
                    val wait = if (automatic()) ServiceTargets.individual.minOf { MobileScanPolicy.remaining(prefs.getLong(scanKey(it.id,profile),0),System.currentTimeMillis()) } else Long.MAX_VALUE
                    publish(if (automatic()) "Следующий автоподбор через ${wait/3600000} ч; по требованию — сразу" else "Проверки только по требованию")
                    if (!same(profile) || superseded() || ServiceTargets.individual.any { MobileFeedback.token(it.id) != (consumed[it.id] ?: 0L) }) MobileFeedback.wake()
                    MobileFeedback.waitFor(wait.coerceAtLeast(1000))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish("Ошибка контроля: ${e.javaClass.simpleName}");MobileFeedback.waitFor(60000) }
            }
        }
    }
    /** Full evidence comes from this scan or the network-scoped cache; never probe again here. */
    private suspend fun applyVerified(id: String,command: String,result: JSONObject,profile: String,token: Long,waitForSlot: Boolean = false): Boolean {
        fun valid() = same(profile) && MobileFeedback.token(id) == token && cancellation == MobileFeedback.cancelToken() && !superseded()
        if (!ProbeAssessment.evaluate(id,result).eligible || !valid()) return false
        if (MobilePlan.command(active,id) == command && !runtime.fault(id)) return true
        val previous = runtime.router.active(id) ?: return false
        var candidate = runtime.stage(id,command)
        // Keep established connections alive until the retired native slot drains.
        while (candidate == null && waitForSlot && valid()) {
            publish("${ServiceTargets.target(id).name}: рабочая стратегия включена; ожидаем завершения соединений для ускорения")
            delay(1000);runtime.reap();candidate = runtime.stage(id,command)
        }
        val route = candidate ?: return false
        var applied = false
        try {
            if (!valid()) return false
            runtime.activate(id,route)
            val next = MobilePlan.replace(active,id,command)
            MobileStrategyPolicy.apply(prefs,MobilePlan.BOTH,next)
            active = next
            reports[id] = result
            MobileStrategyPolicy.remember(context,id,command)
            health.getValue(id).reset()
            applied = true
            runtime.commit(previous)
            publish("${ServiceTargets.target(id).name}: проверенная стратегия применена")
            return true
        } finally {
            if (!applied) withContext(NonCancellable) { runtime.rollback(id,previous,route) }
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
                if (!same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) {
                    session?.cancel();pending.cancelAndJoin();return@coroutineScope JSONObject().put("cancelled",true)
                }
                delay(200)
            }
            pending.await()
        } finally { if (!pending.isCompleted) { session?.cancel();pending.cancelAndJoin() } }
    }
    private suspend fun probe(id: String,command: String,previews: Boolean = false): JSONObject {
        val current = ServiceProbe.Session();session = current
        val profile = MobileStrategyPolicy.profile(context);val token = MobileFeedback.token(id)
        MobilePresentation.start(id,command)
        return try {
            val result = awaitResult(id,profile,token) { withContext(Dispatchers.IO) {
                withTimeout(205000) { runInterruptible {
                    ServiceProbe.check(id,command,1080,current,known=MobileHistory.store(context,id,profile).known(command),previews=previews) {
                        MobilePresentation.progress(id,command,it)
                    }
                } }
            } }
            if (same(profile) && cancellation == MobileFeedback.cancelToken() && !superseded()) MobilePresentation.finish(id,command,result)
            result
        } finally { current.cancel();session = null }
    }
    suspend fun stop() { prefs.unregisterOnSharedPreferenceChangeListener(listener);session?.cancel();task?.cancelAndJoin();task=null;MobilePresentation.pause() }
}
