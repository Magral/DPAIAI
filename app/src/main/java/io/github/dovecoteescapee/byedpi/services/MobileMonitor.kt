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
    private var generating: MobileGenerator.Request? = null
    private fun permitted(id: String) = generating?.let { it.target == id && MobileGenerator.valid(it) } ?: (MobileScanPolicy.enabled(prefs,id) || prefs.contains("mobile_selected.$id"))
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
        if (key == "mobile_auto_three_days" || key?.startsWith("mobile_reset.") == true || key?.startsWith("mobile_scan_enabled.") == true) MobileFeedback.wake()
    }
    fun start() {
        MobileFeedback.clearSignals()
        ServiceTargets.individual.forEach { if (MobileScanPolicy.enabled(prefs,it.id)) MobilePresentation.ready(it.id,MobilePlan.command(active,it.id)) else MobilePresentation.disabled(it.id) }
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
                    MobileGenerator.take()?.let { runGenerator(it,profile) }
                    for (target in ServiceTargets.individual) {
                        if (!same(profile) || cancellation != MobileFeedback.cancelToken() || superseded()) break
                        val id = target.id
                        if (!permitted(id)) { MobilePresentation.disabled(id);continue }
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
                        val commands = if (selected != null) listOf(selected).filter { !store.excluded(it) && io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol.validCommand(it) }
                            else MobileStrategyPolicy.window(context,target)
                        val planned = ((if (!store.excluded(previousCommand)) listOf(previousCommand) else emptyList())+commands).distinct()
                        MobilePresentation.beginScan(id,planned.size)
                        val results = mutableListOf<Pair<String,JSONObject>>()
                        val failures = mutableListOf<Pair<String,JSONObject>>()
                        val deferred = mutableListOf<String>()
                        var currentGood = false
                        var firstApplied = false
                        fun validScan() = permitted(id) && same(profile) && MobileFeedback.token(id) == token && cancellation == MobileFeedback.cancelToken() && !superseded() && (forced || automatic())
                        suspend fun test(command: String,fast: Boolean): JSONObject {
                            if (command == MobilePlan.command(active,id) && !runtime.fault(id)) return probe(id,command,fast=fast)
                            MobilePresentation.start(id,command)
                            val result = candidate(id,command,profile,token,fast)
                            if (validScan()) MobilePresentation.finish(id,command,result)
                            return result
                        }
                        suspend fun accept(command: String,result: JSONObject) {
                            if (!store.accept(command,result)) return
                            if (command == previousCommand) currentGood = true
                            if (!replaceRequested || command != previousCommand) results.add(command to result)
                            if (command == MobilePlan.command(active,id)) MobilePresentation.applied(id)
                            if (!firstApplied && (!replaceRequested || command != previousCommand))
                                firstApplied = applyVerified(id,command,result,profile,token)
                        }
                        // Fast failures are never exclusions, and no confirming retry blocks this pass.
                        for (command in planned) {
                            if (!validScan()) break
                            val result = test(command,true)
                            if (!validScan()) break
                            if (ProbeAssessment.evaluate(id,result).eligible) accept(command,result)
                            else { deferred.add(command);store.defer(command,result) }
                            MobilePresentation.checked(id,deferred.size)
                            MobileStrategyPolicy.advance(context,id,command)
                        }
                        // A full attempt stands alone; partial fast and slow results are never combined.
                        for ((index,command) in deferred.withIndex()) {
                            if (!validScan()) break
                            MobilePresentation.secondPass(id,deferred.size-index)
                            val result = test(command,false)
                            if (!validScan()) break
                            if (ProbeAssessment.evaluate(id,result).eligible) accept(command,result)
                            else { store.defer(command,result);failures.add(command to result) }
                            MobilePresentation.secondPass(id,deferred.size-index-1)
                        }
                        if (validScan()) MobilePresentation.endScan(id) else MobilePresentation.interruptScan(id)
                        if (!permitted(id) || !same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) continue
                        if (!forced && !automatic()) continue
                        val best = if (selected != null) results.firstOrNull { it.first == selected } else MobileScanPolicy.fastest(results,id)
                        prefs.edit().remove("mobile_selected.$id").remove("mobile_imported.$id").remove("mobile_force_replace.$id").apply()
                        if (best == null) { publish("${target.name}: полного результата нет; исключения не записаны — возможен общий сбой сети");continue }
                        // Commit negative evidence only beside a fresh successful control on this same network.
                        if (currentGood && failures.isNotEmpty()) {
                            val controlCommand = MobilePlan.command(active,id)
                            val control = probe(id,controlCommand)
                            if (!permitted(id) || !same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) continue
                            if (ProbeAssessment.evaluate(id,control).eligible)
                                failures.forEach { (command,result) -> store.reject(command,result) }
                            else { store.defer(controlCommand,control);publish("${target.name}: контроль не прошёл; массовые исключения отменены");continue }
                        }
                        if (best.first == MobilePlan.command(active,id) && !runtime.fault(id)) {
                            MobileStrategyPolicy.remember(context,id,best.first);MobilePresentation.finish(id,best.first,best.second)
                            publish("${target.name}: текущая стратегия выбрана среди полных результатов")
                        } else applyVerified(id,best.first,best.second,profile,token,waitForSlot=true)
                        // Optional image quality is measured after application; never repeat the full test.
                        if (id == "youtube" && same(profile) && !superseded()) {
                            val quality = awaitResult(id,profile,token) { withTimeout(40000) {
                                MobileCandidate.run(context,id,best.first,qualityOnly=true)
                            } }
                            if (validScan()) quality.optJSONArray("preview")?.let { best.second.put("preview",it);store.accept(best.first,best.second) }
                        }
                    }
                    val enabledTargets = ServiceTargets.individual.filter { MobileScanPolicy.enabled(prefs,it.id) }
                    val wait = if (automatic()) enabledTargets.minOfOrNull { MobileScanPolicy.remaining(prefs.getLong(scanKey(it.id,profile),0),System.currentTimeMillis()) } ?: Long.MAX_VALUE else Long.MAX_VALUE
                    publish(if (enabledTargets.isEmpty()) "Подбор отключён для обоих приложений" else if (automatic()) "Следующий автоподбор через ${wait/3600000} ч; по требованию — сразу" else "Проверки только по требованию")
                    if (!same(profile) || superseded() || enabledTargets.any { MobileFeedback.token(it.id) != (consumed[it.id] ?: 0L) }) MobileFeedback.wake()
                    MobileFeedback.waitFor(wait.coerceAtLeast(1000))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish("Ошибка контроля: ${e.javaClass.simpleName}");MobileFeedback.waitFor(60000) }
            }
        }
    }
    private suspend fun candidate(id: String,command: String,profile: String,token: Long,fast: Boolean): JSONObject =
        awaitResult(id,profile,token) { withTimeout(if (fast) 30000L else 215000L) {
            MobileCandidate.run(context,id,command,known=MobileHistory.store(context,id,profile).known(command),previews=false,fast=fast) {
                MobilePresentation.progress(id,command,it)
            }
        } }

    /** Uses the same serial isolated worker, so generator and regular scan cannot fight for its port. */
    private suspend fun runGenerator(request: MobileGenerator.Request,profile: String) {
        generating = request
        val id = request.target
        val token = MobileFeedback.token(id)
        val store = MobileHistory.store(context,id,profile)
        val seeds = store.choices().map { it.optString("command") }+ServiceTargets.target(id).preferred
        val excluded = store.entries().filter { store.excluded(it.optString("command")) }.map { it.optString("command") }
        val commands = StrategyGenerator.candidates(seeds,io.github.dovecoteescapee.byedpi.catalog.RuntimeCatalog.commands.toSet()+excluded)
        val deadline = SystemClock.elapsedRealtime()+request.minutes*60000L
        val results = mutableListOf<Pair<String,JSONObject>>()
        val deferred = mutableListOf<String>()
        var checked = 0
        fun valid() = MobileGenerator.valid(request) && same(profile) && cancellation == MobileFeedback.cancelToken() && !superseded() && MobileFeedback.token(id) == token
        fun update(phase: String,remaining: Int = deferred.size) = MobileGenerator.update(request,checked,commands.size,results.size,remaining,phase)
        suspend fun test(command: String,fast: Boolean): JSONObject? {
            if (!valid() || SystemClock.elapsedRealtime() >= deadline) return null
            MobilePresentation.start(id,command)
            return store.freshResult(command) ?: withTimeoutOrNull((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                candidate(id,command,profile,token,fast)
            }
        }
        suspend fun accept(command: String,result: JSONObject) {
            if (!valid() || !store.accept(command,result)) return
            results.add(command to result)
            if (results.size == 1) applyVerified(id,command,result,profile,token)
        }
        try {
            update("Быстрый проход")
            for (command in commands) {
                val result = test(command,true) ?: break
                if (!valid()) break
                checked++
                if (ProbeAssessment.evaluate(id,result).eligible) accept(command,result) else { deferred.add(command);store.defer(command,result) }
                update("Быстрый проход")
            }
            for ((index,command) in deferred.withIndex()) {
                update("Полная перепроверка",deferred.size-index)
                val result = test(command,false) ?: break
                if (!valid()) break
                if (ProbeAssessment.evaluate(id,result).eligible) accept(command,result) else store.defer(command,result)
                update("Полная перепроверка",deferred.size-index-1)
            }
            if (valid()) {
                MobileScanPolicy.fastest(results,id)?.let { applyVerified(id,it.first,it.second,profile,token) }
                MobileGenerator.finish(request,if (SystemClock.elapsedRealtime() >= deadline) "Лимит времени; результаты сохранены" else "Поиск завершён; результаты сохранены")
            } else MobileGenerator.finish(request,"Поиск прерван; результаты сохранены")
        } finally { generating = null;MobileGenerator.finish(request,"Поиск прерван; результаты сохранены") }
    }
    /** Full evidence comes from this scan or the network-scoped cache; never probe again here. */
    private suspend fun applyVerified(id: String,command: String,result: JSONObject,profile: String,token: Long,waitForSlot: Boolean = false): Boolean {
        fun valid() = permitted(id) && same(profile) && MobileFeedback.token(id) == token && cancellation == MobileFeedback.cancelToken() && !superseded()
        if (!ProbeAssessment.evaluate(id,result).eligible || !valid()) return false
        if (MobilePlan.command(active,id) == command && !runtime.fault(id)) { MobilePresentation.applied(id);return true }
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
            MobilePresentation.applied(id)
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
                if (!permitted(id) || !same(profile) || MobileFeedback.token(id) != token || cancellation != MobileFeedback.cancelToken() || superseded()) {
                    session?.cancel();pending.cancelAndJoin();return@coroutineScope JSONObject().put("cancelled",true)
                }
                delay(200)
            }
            pending.await()
        } finally { if (!pending.isCompleted) { session?.cancel();pending.cancelAndJoin() } }
    }
    private suspend fun probe(id: String,command: String,previews: Boolean = false,fast: Boolean = false): JSONObject {
        val current = ServiceProbe.Session();session = current
        val profile = MobileStrategyPolicy.profile(context);val token = MobileFeedback.token(id)
        MobilePresentation.start(id,command)
        return try {
            val result = awaitResult(id,profile,token) { withContext(Dispatchers.IO) {
                withTimeout(205000) { runInterruptible {
                    ServiceProbe.check(id,command,1080,current,known=MobileHistory.store(context,id,profile).known(command),previews=previews,fast=fast) {
                        MobilePresentation.progress(id,command,it)
                    }
                } }
            } }
            if (same(profile) && cancellation == MobileFeedback.cancelToken() && !superseded()) MobilePresentation.finish(id,command,result)
            result
        } finally { current.cancel();session = null;if (!MobileScanPolicy.enabled(prefs,id)) MobilePresentation.disabled(id) }
    }
    suspend fun stop() { prefs.unregisterOnSharedPreferenceChangeListener(listener);session?.cancel();task?.cancelAndJoin();task=null;MobileGenerator.stop();MobilePresentation.pause() }
}
