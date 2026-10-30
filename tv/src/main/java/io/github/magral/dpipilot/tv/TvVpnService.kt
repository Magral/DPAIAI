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
        TvState.restore(this)
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
            TvState.phase = "Нет разрешения VPN"
            TvState.update(this,"Нужно один раз разрешить VPN: откройте DPI Pilot TV")
            stopSelf(); return START_NOT_STICKY
        }
        if (TvState.alwaysOn || intent?.action == SERVICE_INTERFACE) prefs.edit().putBoolean("enabled",true).apply()
        if (!prefs.getBoolean("enabled",false)) { stopSelf(); return START_NOT_STICKY }
        if (installed(this).isEmpty()) {
            TvState.phase = "YouTube не найден"
            TvState.update(this,"Официальный YouTube не установлен")
            stopSelf(); return START_NOT_STICKY
        }
        MaintenanceJob.schedule(this)
        requested.set(true)
        if (intent?.action == "different") different.set(true)
        if (controller == null) {
            TvState.results.clear()
            val reason = when (intent?.action) {
                "boot" -> "загрузка системы"
                "update" -> "обновление приложения"
                "manual" -> "кнопка включения"
                "watchdog" -> "фоновое восстановление"
                SERVICE_INTERFACE -> "системный VPN"
                null -> "восстановление Android"
                else -> "открытие приложения / запрос проверки"
            }
            prefs.edit().putLong("service_start_at",System.currentTimeMillis()).putString("service_start_reason",reason).apply()
            TvState.phase = "Запуск службы"
            TvState.update(this,"Запуск службы: $reason; автопроверка YouTube")
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
                            TvState.phase = "Ожидание восстановления"
                            TvState.nextCheckAt = SystemClock.elapsedRealtime()+60000L
                            delay(60000)
                            TvState.nextCheckAt = 0
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            TvState.update(this@TvVpnService,"Восстановление после ошибки: ${e.javaClass.simpleName}. Повтор через минуту")
                            TvState.phase = "Ожидание восстановления"
                            TvState.nextCheckAt = SystemClock.elapsedRealtime()+60000L
                            delay(60000)
                            TvState.nextCheckAt = 0
                        }
                    }
                }
                catch (_: CancellationException) { }
                finally {
                    withContext(NonCancellable) { release() }
                    TvState.running = false
                    TvState.phase = "Остановлено"
                    TvState.nextCheckAt = 0
                    TvState.update(this@TvVpnService,"Служба остановлена; VPN-интерфейс закрыт")
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
        TvState.phase = "Ожидание следующей проверки"
        val end = SystemClock.elapsedRealtime()+milliseconds
        TvState.nextCheckAt = end
        var sampleAt = 0L
        while (SystemClock.elapsedRealtime() < end && !requested.get()) {
            TvState.beat(this)
            val now = SystemClock.elapsedRealtime()
            if (tun != null && now >= sampleAt) {
                sampleAt = now+5000L
                val stats = TProxyService.TProxyGetStats()
                if (stats.size >= 4) {
                    TvState.sentPackets = stats[0]; TvState.sentBytes = stats[1]
                    TvState.receivedPackets = stats[2]; TvState.receivedBytes = stats[3]
                    TvState.trafficAt = System.currentTimeMillis()
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
        TvState.nextCheckAt = 0
    }
    private suspend fun healthy(): TvProbe.Result {
        TvState.phase = "Проверка YouTube"
        TvState.nextCheckAt = 0
        TvState.probeDetail = "Проверка началась"
        val result = TvProbe.check({ !scope.isActive }) { row ->
            TvState.probeDetail = row
            TvState.update(this,row)
        }
        TvState.lastCheckAt = System.currentTimeMillis()
        TvState.probeDetail = result.detail
        activeIndex?.let { TvState.results[it] = (if (result.ok) "OK: " else "FAIL: ")+result.detail }
        prefs.edit().putLong("last_check_at",TvState.lastCheckAt).putString("last_probe",result.detail).apply()
        currentCoroutineContext().ensureActive()
        return result
    }
    private fun confirm(index: Int) {
        val known = TvPolicy.history(prefs.getString("history.$profile",null).orEmpty())
        prefs.edit().putInt("good",index)
            .putString("history.$profile",TvPolicy.remember(known,index).joinToString(","))
            .putLong("last_success",System.currentTimeMillis()).apply()
        TvState.verified = true
    }
    private suspend fun open(index: Int) {
        TvState.phase = "Запуск движка"
        TvState.strategyIndex = index
        val next = EngineLease(this) {
            TvState.engineReady = false
            TvState.verified = false
            TvState.update(this,"Процесс движка завершился; запрошено восстановление")
            requested.set(true)
        }
        lease = next
        next.open(index)
        activeIndex = index
        TvState.engineReady = true
    }
    private suspend fun release() {
        TvState.phase = "Закрытие предыдущего туннеля"
        TvState.vpnEstablished = false
        TvState.verified = false
        TvState.engineReady = false
        TvState.strategyIndex = -1
        TvState.sentBytes = 0; TvState.receivedBytes = 0
        TvState.sentPackets = 0; TvState.receivedPackets = 0; TvState.trafficAt = 0
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
        TvState.phase = "Создание VPN-интерфейса"
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
        TvState.vpnEstablished = true
        TvState.update(this,"VPN-интерфейс создан; приложения: ${targets.joinToString()}. Доступ YouTube ещё проверяется")
    }
    private suspend fun control() {
        var failures = 0
        while (currentCoroutineContext().isActive && prefs.getBoolean("enabled",false)) {
            requested.set(false)
            TvState.beat(this)
            val network = physical()
            if (network == null) {
                release()
                TvState.network = "нет подключения Wi-Fi / Ethernet"
                TvState.update(this,"Ожидаю сеть; сохранённая стратегия не изменена")
                waitForCheck(10000); continue
            }
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                else -> "другая физическая сеть"
            }
            val link = cm.getLinkProperties(network)
            TvState.network = "$transport · ${link?.interfaceName ?: "?"} · DNS: ${link?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString() ?: "нет"}"
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
                    confirm(activeIndex!!)
                    traffic.reset() // Discard the pre-sleep sampling window after a fresh health check.
                    failures = 0
                    TvState.update(this,"YouTube: соединение проверено. Стратегия ${activeIndex!!+1}/${TvPolicy.strategies.size}. Следующая проверка через 5 минут")
                    waitForCheck(300000); continue
                }
                TvState.verified = false
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
                if (physical() != network) { release(); requested.set(true); break }
                TvState.beat(this)
                TvState.update(this,"Подбор ${index+1}/${TvPolicy.strategies.size}: ${TvPolicy.strategies[index].ifEmpty { "контроль" }}")
                try {
                    // Route YouTube immediately. A failed diagnostic must not prevent first-time VPN creation.
                    open(index)
                    establish(network)
                    val result = healthy()
                    TvState.update(this,"Стратегия ${index+1}: ${result.detail}")
                    if (result.ok) {
                        if (physical() != network) { release(); requested.set(true); break }
                        confirm(index)
                        networkId = network.toString()
                        selected = true; failures = 0
                        TvState.update(this,"YouTube: подключено. Стратегия ${index+1}/${TvPolicy.strategies.size}; автопроверка каждые 5 минут")
                        break
                    }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    TvState.results[index] = "FAIL: таймаут движка"
                    TvState.update(this,"Стратегия ${index+1}: таймаут движка")
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    TvState.results[index] = "FAIL: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                    TvState.update(this,"Стратегия ${index+1}: ${TvState.results[index]}")
                }
                release()
            }
            if (selected) { waitForCheck(300000); continue }
            failures++
            if (physical() == network) {
                // No previous success on a new installation: still leave an adaptive tunnel active.
                // This is provisional and must never be stored as a confirmed result.
                val fallback = saved ?: TvPolicy.adaptiveIndex
                try { open(fallback); establish(network); networkId = network.toString() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    TvState.update(this,"Не удалось создать VPN: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
                    release()
                }
            }
            TvState.update(this,"${if (tun != null) "VPN включён, доступ YouTube не подтверждён" else "VPN не создан"}; история сохранена. Повтор через ${TvPolicy.backoffMillis(failures)/60000} мин.")
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
        TvState.vpnEstablished = false
        TvState.verified = false
        TvState.running = false
        super.onDestroy()
    }
}
