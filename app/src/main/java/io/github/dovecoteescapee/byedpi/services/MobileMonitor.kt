package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.os.SystemClock
import io.github.dovecoteescapee.byedpi.routing.ServiceRouter
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Independent public probes, traffic observation and two-minute route trials. */
class MobileMonitor(private val context: Context,private val scope: CoroutineScope,private val runtime: MobileRuntime) {
    private var task: Job? = null
    private var sample: Job? = null
    private var session: ServiceProbe.Session? = null
    private val health = listOf("instagram","youtube").associateWith { FlowHealth() }
    private val denied = mutableMapOf<String,Long>()
    private val consumed = mutableMapOf<String,Long>()
    private val reports = linkedMapOf<String,JSONObject>()
    @Volatile private var trialRunning = false
    private var active = context.getPreferences().getString("mobile_command","").orEmpty()
    private fun publish(message: String = "") {
        MobileStrategyPolicy.state = ServiceTargets.individual.joinToString("; ") { t ->
            reports[t.id]?.let { "${t.name}: ${ProbeAssessment.evaluate(t.id,it).text()}" } ?: "${t.name}: ожидает проверки"
        }+if (message.isEmpty()) "" else "\n$message"
        MobileStrategyPolicy.lastProbe = JSONObject().put("mode","independent").put("strategies",JSONObject(active))
            .put("services",JSONObject(reports as Map<*,*>)).put("state",MobileStrategyPolicy.state).put("traffic",runtime.diagnostic())
            .put("history",MobileHistory.summary(context)).toString()
    }
    private fun periodic() = context.getPreferences().getBoolean("mobile_periodic",false)
    private fun sampling() {
        if (sample?.isActive == true) return
        sample = scope.launch {
            while (isActive && (trialRunning || periodic())) {
                delay(5000)
                val now = SystemClock.elapsedRealtime()
                health.forEach { (id,watch) ->
                    val s = runtime.router.active(id)?.let { runtime.router.stats(it) } ?: runtime.router.stats(id)
                    watch.observe(FlowHealth.Sample(now,s.tx,s.rx,s.writes))
                    if (watch.stalled || runtime.fault(id)) MobileFeedback.wake()
                }
                MobileFeedback.traffic = runtime.diagnostic()
            }
        }
    }
    fun start() {
        sampling()
        task = scope.launch {
            var profile = MobileStrategyPolicy.profile(context)
            while (isActive) {
                try {
                    if (!MobileStrategyPolicy.modern(context.getPreferences())) { publish("Настройки изменены; перезапустите VPN"); return@launch }
                    if (!MobileStrategyPolicy.connected(context)) { publish("Нет физической сети"); MobileFeedback.waitFor(15000); continue }
                    val nextProfile = MobileStrategyPolicy.profile(context)
                    if (profile != nextProfile) { profile = nextProfile; denied.clear(); reports.clear(); health.values.forEach { it.reset() } }
                    runtime.reap()
                    for (target in ServiceTargets.individual) {
                        val id = target.id
                        val token = MobileFeedback.token(id)
                        val forced = token != (consumed[id] ?: 0L) || context.getPreferences().contains("mobile_imported.$id")
                        val current = probe(id); reports[id] = current
                        val stalled = health.getValue(id).stalled || runtime.fault(id)
                        if (eligible(id,current) && !forced && !stalled) continue
                        val previous = runtime.router.active(id) ?: continue
                        val previousCommand = MobilePlan.command(active,id)
                        val useful = health.getValue(id).usefulAt
                        if (!forced && !stalled && useful > 0 && SystemClock.elapsedRealtime()-useful < 90000) { publish("${target.name} получает данные; замена отложена"); continue }
                        consumed[id] = token
                        for (command in MobileStrategyPolicy.window(context,target).take(if (periodic() || forced) 12 else 3)) {
                            val key = "$profile:$id:$command"
                            if (command == previousCommand && !runtime.fault(id)) continue
                            if ((denied[key] ?: 0L) > SystemClock.elapsedRealtime()) continue
                            if (MobileStrategyPolicy.profile(context) != profile) break
                            publish("${target.name}: проверяю резерв; второй сервис продолжает работать")
                            val result = try { MobileCandidate.run(context,id,command) { publish("${target.name}: $it") } }
                                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject() }
                            MobileStrategyPolicy.advance(context,id,command)
                            if (!eligible(id,result)) { denied[key] = SystemClock.elapsedRealtime()+1800000; MobileHistory.record(context,id,command,false); continue }
                            val candidate = runtime.stage(id,command)
                            if (candidate == null) { publish("Резервный обработчик занят прежним потоком; переключение отложено"); break }
                            var accepted = false
                            val beforeUseful = health.getValue(id).usefulMillis
                            context.getPreferences().edit().putString("mobile_trial.$id",JSONObject().put("previous",previousCommand).put("candidate",command).toString()).commit()
                            runtime.activate(id,candidate)
                            trialRunning = true; sampling()
                            health.getValue(id).reset()
                            try {
                                val deadline = SystemClock.elapsedRealtime()+120000
                                publish("${target.name}: испытание до 2 минут; другой сервис не перезапускается")
                                var verified = probe(id,command)
                                while (eligible(id,verified) && !health.getValue(id).stalled && !runtime.fault(id) &&
                                    SystemClock.elapsedRealtime() < deadline && MobileStrategyPolicy.profile(context) == profile && MobileFeedback.token(id) == token) {
                                    MobileFeedback.waitFor(minOf(15000,deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))
                                    if (SystemClock.elapsedRealtime() >= deadline) break
                                    if (periodic()) verified = probe(id,command)
                                }
                                verified = probe(id,command)
                                if (SystemClock.elapsedRealtime() >= deadline && eligible(id,verified) && !health.getValue(id).stalled && !runtime.fault(id) && MobileStrategyPolicy.profile(context) == profile && MobileFeedback.token(id) == token) {
                                    active = MobilePlan.replace(active,id,command)
                                    MobileStrategyPolicy.apply(context.getPreferences(),MobilePlan.BOTH,active)
                                    MobileStrategyPolicy.remember(context,id,command)
                                    context.getPreferences().edit().remove("mobile_imported.$id").remove("mobile_trial.$id").apply()
                                    MobileHistory.record(context,id,command,true,(health.getValue(id).usefulMillis-beforeUseful).coerceAtLeast(0))
                                    reports[id] = verified; accepted = true; runtime.commit(previous)
                                    publish("${target.name}: испытание пройдено; резерв сохранён")
                                }
                            } finally {
                                context.getPreferences().edit().remove("mobile_trial.$id").apply()
                                trialRunning = false
                                if (!periodic()) { sample?.cancelAndJoin(); sample = null }
                                if (!accepted && currentCoroutineContext().isActive) {
                                    runtime.rollback(id,previous,candidate)
                                    MobileHistory.record(context,id,command,false,rollback = true)
                                    denied[key] = SystemClock.elapsedRealtime()+1800000
                                    health.getValue(id).reset(); publish("${target.name}: выполнен откат; второй сервис сохранён")
                                }
                            }
                            if (accepted) break
                        }
                    }
                    val healthy = ServiceTargets.individual.all { reports[it.id]?.let { r -> eligible(it.id,r) } == true }
                    publish(if (healthy) "Работает · плановые сетевые проверки выключены" else "Проверка завершена; сохранён прежний маршрут. Для продолжения нажмите «не работает»")
                    MobileFeedback.waitFor(if (periodic()) { if (healthy) 3600000 else 600000 } else Long.MAX_VALUE)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish("Ошибка контроля: ${e.javaClass.simpleName}: ${e.message?.take(120)}"); MobileFeedback.waitFor(if (periodic()) 600000 else Long.MAX_VALUE) }
            }
        }
    }
    private suspend fun probe(id: String,command: String = MobilePlan.command(active,id)): JSONObject = coroutineScope {
        val current = ServiceProbe.Session(); session = current
        val pending = async(Dispatchers.IO) { ServiceProbe.check(id,command,1080,current) { publish("${ServiceTargets.target(id).name}: $it") } }
        try { withTimeout(205000) { pending.await() } }
        finally { current.cancel(); session = null }
    }
    private fun eligible(id: String,result: JSONObject) = ProbeAssessment.evaluate(id,result).eligible
    suspend fun stop() { session?.cancel(); task?.cancelAndJoin(); sample?.cancelAndJoin(); task = null; sample = null }
}
