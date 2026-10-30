package io.github.magral.dpipilot.tv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.core.TProxyService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

class TvVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var controller: Job? = null
    private var lease: EngineLease? = null
    private var tun: ParcelFileDescriptor? = null
    private var config: File? = null
    private val requested = AtomicBoolean(true)
    private val different = AtomicBoolean(false)
    private var activeIndex: Int? = null
    private var networkId: String? = null
    private var monitor: ConnectivityManager.NetworkCallback? = null
    private var screen: BroadcastReceiver? = null
    private val traffic = TrafficWatchdog()
    private val blocked = mutableMapOf<Int,Long>()
    private var profile = ""
    private var lastReceived = 0L
    private val cm get() = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs get() = TvState.prefs(this)
    companion object {
        private val lifetime = Mutex()
        val packages = listOf("com.google.android.youtube.tv","com.google.android.youtube")
        fun installed(context: Context): List<String> = packages.filter { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg,0).enabled }.getOrDefault(false)
        }
        fun start(context: Context,action: String = "check") {
            ContextCompat.startForegroundService(context,Intent(context,TvVpnService::class.java).setAction(action))
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        foreground()
        TvState.alwaysOn = Build.VERSION.SDK_INT >= 29 && isAlwaysOn
        if (intent?.action == "stop") {
            if (TvState.alwaysOn) {
                TvState.update(this,"Отключите постоянный VPN в настройках Android")
                return START_STICKY
            }
            prefs.edit().putBoolean("enabled",false).apply()
            MaintenanceJob.cancel(this)
            stopSelf(); return START_NOT_STICKY
        }
        if (prepare(this) != null) {
            TvState.update(this,"Нужно один раз разрешить VPN: откройте DPI Pilot TV")
            stopSelf(); return START_NOT_STICKY
        }
        if (TvState.alwaysOn || intent?.action == SERVICE_INTERFACE) prefs.edit().putBoolean("enabled",true).apply()
        if (!prefs.getBoolean("enabled",false)) { stopSelf(); return START_NOT_STICKY }
        if (installed(this).isEmpty()) {
            TvState.update(this,"Официальный YouTube не установлен")
            stopSelf(); return START_NOT_STICKY
        }
        MaintenanceJob.schedule(this)
        requested.set(true)
        if (intent?.action == "different") different.set(true)
        if (controller == null) {
            TvState.running = true
            watch()
            controller = scope.launch {
                lifetime.withLock {
                TvState.running = true
                try {
                    while (isActive && prefs.getBoolean("enabled",false)) {
                        try { control(); break }
                        catch (e: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            TvState.update(this@TvVpnService,"Таймаут движка; повтор через минуту")
                            delay(60000)
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            TvState.update(this@TvVpnService,"Восстановление после ошибки: ${e.javaClass.simpleName}. Повтор через минуту")
                            delay(60000)
                        }
                    }
                }
                catch (_: CancellationException) { }
                finally {
                    withContext(NonCancellable) { release() }
                    TvState.running = false
                    stopSelf()
                }
                }
            }
        }
        return START_STICKY
    }
    private fun foreground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("tv_auto","YouTube: авторежим",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,TvActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this,"tv_auto").setSmallIcon(R.drawable.tv_icon)
            .setContentTitle("DPI Pilot TV — YouTube").setContentText("Автопроверка и восстановление соединения")
            .setOngoing(true).setSilent(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1,notification)
    }
    @Suppress("DEPRECATION")
    private fun physical(): Network? = cm.allNetworks.filter { network ->
        val caps = cm.getNetworkCapabilities(network)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && cm.getNetworkInfo(network)?.isConnected == true
    }.minByOrNull { network ->
        val caps = cm.getNetworkCapabilities(network)
        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 0
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
            else -> 2
        }
    }
    private fun profileKey(network: Network): String {
        val link = cm.getLinkProperties(network)
        val source = "${link?.interfaceName}|"+link?.dnsServers?.mapNotNull { it.hostAddress }?.sorted().orEmpty().joinToString(",")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun watch() {
        monitor = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { requested.set(true) }
            override fun onLost(network: Network) { requested.set(true) }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),monitor!!)
        screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context,intent: Intent) { requested.set(true) }
        }
        ContextCompat.registerReceiver(this,screen,IntentFilter(Intent.ACTION_SCREEN_ON),ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private suspend fun waitForCheck(milliseconds: Long) {
        val end = SystemClock.elapsedRealtime()+milliseconds
        var sampleAt = 0L
        while (SystemClock.elapsedRealtime() < end && !requested.get()) {
            TvState.beat(this)
            val now = SystemClock.elapsedRealtime()
            if (tun != null && now >= sampleAt) {
                sampleAt = now+5000L
                val stats = TProxyService.TProxyGetStats()
                if (stats.size >= 4) {
                    if (stats[3] >= lastReceived && stats[3]-lastReceived >= 1024*1024) activeIndex?.let { blocked.remove(it) }
                    lastReceived = stats[3]
                    if (traffic.stalled(TrafficWatchdog.Sample(now,stats[0],stats[1],stats[3])) && (activeIndex?.let { it !in blocked } == true)) {
                        activeIndex?.let { blocked[it] = now+15*60*1000L }
                        TvState.update(this,"YouTube не получает данные; автоматически меняю соединение")
                        different.set(true); requested.set(true)
                    }
                }
            }
            delay(1000)
        }
    }
    private suspend fun healthy(): TvProbe.Result {
        val result = TvProbe.check { !scope.isActive }
        currentCoroutineContext().ensureActive()
        return result
    }
    private suspend fun open(index: Int) {
        val next = EngineLease(this) { requested.set(true) }
        lease = next
        next.open(index)
        activeIndex = index
    }
    private suspend fun release() {
        withContext(NonCancellable+Dispatchers.IO) {
            if (tun != null) {
                runCatching { TProxyService.TProxyStopService() }
                runCatching { tun?.close() }
                tun = null
            }
            config?.delete(); config = null
            lease?.close(); lease = null
            activeIndex = null
            traffic.reset(); lastReceived = 0
        }
    }
    private fun establish(network: Network) {
        val targets = installed(this)
        check(targets.isNotEmpty()) { "YouTube removed" }
        val dns = cm.getLinkProperties(network)?.dnsServers?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf("1.1.1.1")
        val builder = Builder().setSession("DPI Pilot TV — YouTube").setMtu(1500).addAddress("10.10.10.10",32)
        builder.addRoute("0.0.0.0",0)
        for (ip in dns) builder.addDnsServer(ip)
        for (pkg in targets) builder.addAllowedApplication(pkg)
        if (Build.VERSION.SDK_INT >= 22) builder.setUnderlyingNetworks(arrayOf(network))
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,Intent(this,TvActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
        val path = File.createTempFile("tv-tunnel",".yml",cacheDir)
        config = path
        path.writeText("misc:\n  task-stack-size: 81920\nsocks5:\n  address: 127.0.0.1\n  port: 19080\n  udp: udp\n")
        tun = builder.establish() ?: error("VPN permission lost")
        TProxyService.TProxyStartService(path.absolutePath,tun!!.fd)
    }
    private suspend fun control() {
        var failures = 0
        while (currentCoroutineContext().isActive && prefs.getBoolean("enabled",false)) {
            requested.set(false)
            TvState.beat(this)
            val network = physical()
            if (network == null) {
                release()
                TvState.update(this,"Ожидаю сеть; сохранённая стратегия не изменена")
                waitForCheck(10000); continue
            }
            val currentProfile = profileKey(network)
            if (profile != currentProfile) { blocked.clear(); profile = currentProfile }
            val now = SystemClock.elapsedRealtime()
            blocked.entries.removeAll { it.value <= now }
            val force = different.getAndSet(false) || (activeIndex?.let { it in blocked } == true)
            var failedIndex = if (force) activeIndex else null
            val changedNetwork = networkId != network.toString()
            if (tun != null && !changedNetwork && !force) {
                TvState.update(this,"Проверяю текущую стратегию…")
                var current = healthy()
                if (!current.ok) { delay(2000); current = healthy() }
                if (current.ok) {
                    failures = 0
                    TvState.update(this,"YouTube: соединение проверено. Стратегия ${activeIndex!!+1}/${TvPolicy.strategies.size}. Следующая проверка через 5 минут")
                    waitForCheck(300000); continue
                }
                TvState.update(this,"Текущая стратегия не прошла повторную проверку: ${current.detail}")
                failedIndex = activeIndex
            }
            release()
            val old = prefs.getInt("good",-1).takeIf { it in TvPolicy.strategies.indices }
            val known = TvPolicy.history(prefs.getString("history.$profile",null) ?: old?.toString().orEmpty())
            val saved = known.firstOrNull()
            var selected = false
            // After a failed current check, try alternatives before revisiting the saved strategy.
            for (index in TvPolicy.candidates(known,failedIndex,blocked.keys)) {
                currentCoroutineContext().ensureActive()
                if (physical() != network) { requested.set(true); break }
                TvState.beat(this)
                TvState.update(this,"Подбор ${index+1}/${TvPolicy.strategies.size}: ${TvPolicy.strategies[index].ifEmpty { "контроль" }}")
                try {
                    open(index)
                    val result = healthy()
                    TvState.update(this,"Стратегия ${index+1}: ${result.detail}")
                    if (result.ok) {
                        if (physical() != network) { requested.set(true); break }
                        establish(network)
                        prefs.edit().putInt("good",index).putString("history.$profile",TvPolicy.remember(known,index).joinToString(",")).putLong("last_success",System.currentTimeMillis()).apply()
                        networkId = network.toString()
                        selected = true; failures = 0
                        TvState.update(this,"YouTube: подключено. Стратегия ${index+1}/${TvPolicy.strategies.size}; автопроверка каждые 5 минут")
                        break
                    }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    TvState.update(this,"Стратегия ${index+1}: таймаут движка")
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { TvState.update(this,"Стратегия ${index+1}: ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
                release()
            }
            if (selected) { waitForCheck(300000); continue }
            failures++
            if (saved != null && physical() == network) {
                try { open(saved); establish(network); networkId = network.toString() }
                catch (_: Exception) { release() }
            }
            TvState.update(this,"Рабочая стратегия не подтверждена; прежний результат сохранён. Повтор через ${TvPolicy.backoffMillis(failures)/60000} мин.")
            waitForCheck(TvPolicy.backoffMillis(failures))
        }
    }
    override fun onRevoke() {
        prefs.edit().putBoolean("enabled",false).apply()
        MaintenanceJob.cancel(this)
        TvState.update(this,"Разрешение VPN отозвано")
        stopSelf()
    }
    override fun onDestroy() {
        monitor?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        screen?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        TvState.running = false
        super.onDestroy()
    }
}
