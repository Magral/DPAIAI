package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import io.github.dovecoteescapee.byedpi.routing.ServiceRouter
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*

/** Two native slots per service. Route changes affect new connections only. */
class MobileRuntime(private val context: Context) {
    private data class Slot(val id: String,val port: Int,val type: Class<out MobileEngineService>,var command: String? = null,var lease: MobileEngineLease? = null,var generation: Long = 0,@Volatile var alive: Boolean = false)
    private val slots = listOf(Slot("instagram",19410,InstagramAService::class.java),Slot("instagram",19411,InstagramBService::class.java),
        Slot("youtube",19420,YoutubeAService::class.java),Slot("youtube",19421,YoutubeBService::class.java))
    val router = ServiceRouter()
    private var serial = 0L
    private val maintenance = CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val protected = mutableSetOf<ServiceRouter.Route>()
    init { router.onDrained = { maintenance.launch { reap() } } }
    suspend fun open(plan: String) {
        // Active preferences are committed only after a trial. Interrupted trials restart on the old pair.
        context.getPreferences().edit().remove("mobile_trial.instagram").remove("mobile_trial.youtube").apply()
        try {
            for (id in listOf("instagram","youtube")) {
                val slot = slots.first { it.id == id }; launch(slot,MobilePlan.command(plan,id))
                router.route(id,ServiceRouter.Route(slot.port,slot.generation))
            }
            router.start()
            MobileFeedback.provider = { diagnostic() }
        } catch (e: Exception) { close(); throw e }
    }
    private suspend fun launch(slot: Slot,command: String) {
        slot.lease?.close(); slot.lease = null; slot.alive = false
        val lease = MobileEngineLease(context,slot.type) { slot.alive = false; MobileFeedback.wake() }
        try { lease.open(command); slot.lease = lease; slot.command = command; slot.generation = ++serial; slot.alive = true }
        catch (e: Exception) { lease.close(); throw e }
    }
    fun fault(id: String): Boolean = slots.firstOrNull { it.id == id && it.port == router.active(id)?.port }?.alive != true
    suspend fun stage(id: String,command: String): ServiceRouter.Route? {
        val active = router.active(id)
        val reusable = slots.firstOrNull { it.id == id && it.port != active?.port && it.alive && it.command == command }
        if (reusable != null) { active?.let { protected.add(it) }; return ServiceRouter.Route(reusable.port,reusable.generation) }
        val slot = slots.firstOrNull { it.id == id && it.port != active?.port && router.connections(ServiceRouter.Route(it.port,it.generation)) == 0 } ?: return null
        launch(slot,command)
        active?.let { protected.add(it) }
        return ServiceRouter.Route(slot.port,slot.generation)
    }
    fun activate(id: String,route: ServiceRouter.Route) { router.route(id,route) }
    suspend fun rollback(id: String,previous: ServiceRouter.Route,candidate: ServiceRouter.Route) {
        router.route(id,previous); protected.remove(previous); router.closeRoute(candidate)
        slots.firstOrNull { it.port == candidate.port }?.let { it.lease?.close(); it.lease = null; it.alive = false; it.command = null }
    }
    suspend fun commit(previous: ServiceRouter.Route) { protected.remove(previous); reap() }
    suspend fun reap() {
        for (slot in slots) if (slot.lease != null && slot.port != router.active(slot.id)?.port && ServiceRouter.Route(slot.port,slot.generation) !in protected && router.connections(ServiceRouter.Route(slot.port,slot.generation)) == 0) {
            slot.lease?.close(); slot.lease = null; slot.alive = false; slot.command = null
        }
    }
    fun diagnostic() = listOf("instagram","youtube","unknown").joinToString("\n") { id ->
        val s = router.stats(id); "$id: tx=${s.tx}; rx=${s.rx}; записей=${s.writes}; соединений=${s.connections}; ошибок подключения=${s.failures}"
    }
    suspend fun close() = withContext(NonCancellable) {
        maintenance.cancel(); MobileFeedback.provider = null
        router.close()
        for (slot in slots) { runCatching { slot.lease?.close() }; slot.lease = null; slot.alive = false }
    }
}
