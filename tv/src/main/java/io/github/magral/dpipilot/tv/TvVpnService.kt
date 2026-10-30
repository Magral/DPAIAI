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
    private var scoutLease: EngineLease? = null
    private var tun: ParcelFileDescriptor? = null
    private var config: File? = null
    private val requested = AtomicBoolean(true)
    private val different = AtomicBoolean(false)
    private val stallRequested = AtomicBoolean(false)
    private var activeIndex: Int? = null
    private var networkId: String? = null
    private var monitor: ConnectivityManager.NetworkCallback? = null
    private var screen: BroadcastReceiver? = null
    private val traffic = TrafficWatchdog()
    private val playback = PlaybackGuard()
    private var sampleAt = 0L
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
            TvState.results.clear(); TvState.resultTimes.clear()
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
    private fun sampleTraffic() {
        val now = SystemClock.elapsedRealtime()
        if (tun == null || now < sampleAt) return
        sampleAt = now+5000L
        val stats = TProxyService.TProxyGetStats()
        if (stats.size < 4) return
        TvState.sentPackets = stats[0]; TvState.sentBytes = stats[1]
        TvState.receivedPackets = stats[2]; TvState.receivedBytes = stats[3]
        TvState.trafficAt = System.currentTimeMillis()
        playback.observe(now,stats[3])
        if (stats[3] >= lastReceived && stats[3]-lastReceived >= 1024*1024) activeIndex?.let { blocked.remove(it) }
        lastReceived = stats[3]
        if (traffic.stalled(TrafficWatchdog.Sample(now,stats[0],stats[1],stats[3])) &&
            (activeIndex?.let { it !in blocked } == true) && !playback.recent(now)) {
            activeIndex?.let { blocked[it] = now+15*60*1000L }
            TvState.update(this,"Повторные запросы YouTube без данных; запрошен фоновый подбор")
            stallRequested.set(true); requested.set(true)
        }
    }
    private suspend fun waitForCheck(milliseconds: Long) {
        TvState.phase = "Ожидание следующей проверки"
        val end = SystemClock.elapsedRealtime()+milliseconds
        TvState.nextCheckAt = end
        while (SystemClock.elapsedRealtime() < end && !requested.get()) {
            TvState.beat(this); sampleTraffic(); delay(1000)
        }
        TvState.nextCheckAt = 0
    }
    private suspend fun healthy(index: Int,port: Int = 19080): TvProbe.Result = coroutineScope {
        TvState.phase = if (port == 19081) "Фоновая проверка стратегии №${index+1}" else "Проверка текущей стратегии №${index+1}"
        TvState.probeIndex = index
        TvState.nextCheckAt = 0
        TvState.probeDetail = "Проверка началась; порт $port"
        val session = TvProbe.Session()
        try {
            val pending = async(Dispatchers.IO) {
                TvProbe.check(port,index,session) { row ->
                    TvState.probeDetail = "№${index+1}: $row"
                    TvState.update(this@TvVpnService,"Тест №${index+1}: $row")
                }
            }
            while (!pending.isCompleted) {
                TvState.beat(this@TvVpnService); sampleTraffic(); delay(250)
            }
            val result = pending.await()
            currentCoroutineContext().ensureActive()
            TvState.lastCheckAt = System.currentTimeMillis()
            TvState.probeDetail = result.detail
            TvState.resultTimes[index] = System.currentTimeMillis()
            TvState.results[index] = (if (result.ok) "OK: " else "Частично/отказ: ")+result.detail
            prefs.edit().putLong("last_check_at",TvState.lastCheckAt).putString("last_probe",result.detail).apply()
            result
        } finally { session.cancel() }
    }
    private fun confirm(index: Int) {
        val known = TvPolicy.savedHistory(prefs.getString("history_ids.$profile",null),prefs.getString("history.$profile",null).orEmpty())
        prefs.edit().putInt("good",index).putString("good_id",StrategyCatalog.ids[index])
            .putString("history_ids.$profile",TvPolicy.remember(known,index).joinToString(",") { StrategyCatalog.ids[it] })
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
            scoutLease?.close(); scoutLease = null
            lease?.close(); lease = null
            activeIndex = null
            traffic.reset(); playback.reset(); sampleAt = 0; lastReceived = 0
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
            blocked.entries.removeAll { it.value <= SystemClock.elapsedRealtime() }
            val old = TvPolicy.indexOfId(prefs.getString("good_id",null)) ?: prefs.getInt("good",-1).takeIf { it in 0..7 }
            val known = TvPolicy.savedHistory(prefs.getString("history_ids.$profile",null),prefs.getString("history.$profile",null) ?: old?.toString().orEmpty())
            // Establish once at startup. Every alternative is tested in :scout while this tunnel stays up.
            if (tun == null || !TvState.engineReady || networkId != network.toString()) {
                release()
                val initial = known.firstOrNull() ?: TvPolicy.adaptiveIndex
                open(initial); establish(network); networkId = network.toString()
                TvState.update(this,"Включена стратегия №${initial+1}; предварительный режим до проверки")
            }
            val active = activeIndex!!
            var manual = different.getAndSet(false)
            var stalled = stallRequested.getAndSet(false)
            var force = manual || ((stalled || active in blocked) && !playback.recent(SystemClock.elapsedRealtime()))
            val current = healthy(active)
            if (physical() != network) { requested.set(true); continue }
            stalled = stallRequested.getAndSet(false) || stalled
            force = manual || ((stalled || active in blocked) && !playback.recent(SystemClock.elapsedRealtime()))
            if (current.ok) {
                confirm(active); failures = 0
                if (!force) {
                    TvState.update(this,"Проверки текущей стратегии №${active+1} пройдены; VPN не пересоздавался")
                    waitForCheck(300000); continue
                }
            } else {
                TvState.verified = false
                TvState.update(this,"Проверки текущей стратегии: ${current.score} успешных; VPN оставлен включённым")
                if (!force && playback.recent(SystemClock.elapsedRealtime())) {
                    TvState.update(this,"YouTube получает данные: сохраняю текущий туннель несмотря на отказ диагностики")
                    waitForCheck(60000); continue
                }
            }
            var confirmed: Int? = null
            var trial: Int? = null
            var bestScore = -1
            val cursor = TvPolicy.indexOfId(prefs.getString("scan.next_id.$profile",null)) ?: 0
            TvState.scanCursor = cursor
            val window = TvPolicy.scanWindow(known,active,blocked.keys,cursor)
            TvState.update(this,"База ${TvPolicy.strategies.size}: фоновый блок до ${window.size} вариантов; позиция ${cursor+1}")
            for (index in window) {
                currentCoroutineContext().ensureActive()
                if (physical() != network) { requested.set(true); break }
                force = manual || ((stalled || active in blocked) && !playback.recent(SystemClock.elapsedRealtime()))
                if (!force && playback.recent(SystemClock.elapsedRealtime())) break
                TvState.update(this,"Фоновый тест №${index+1}; активна №${active+1}, VPN не отключается")
                val next = EngineLease(this,scout = true)
                scoutLease = next
                try {
                    next.open(index)
                    val result = healthy(index,19081)
                    if (result.score > bestScore) { bestScore = result.score; trial = index }
                    if (result.ok) { confirmed = index; break }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    TvState.resultTimes[index] = System.currentTimeMillis()
                    TvState.results[index] = "FAIL: таймаут тестового движка"
                    TvState.update(this,"Фоновая стратегия №${index+1}: таймаут; активный VPN сохранён")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    TvState.resultTimes[index] = System.currentTimeMillis()
                    TvState.results[index] = "FAIL: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                    TvState.update(this,"Фоновая стратегия №${index+1}: ${TvState.results[index]}")
                } finally {
                    next.close(); scoutLease = null
                    if (currentCoroutineContext().isActive) {
                        val nextIndex = (index+1)%TvPolicy.strategies.size
                        prefs.edit().putString("scan.next_id.$profile",StrategyCatalog.ids[nextIndex]).apply()
                        TvState.scanCursor = nextIndex
                    }
                }
            }
            if (physical() != network) { requested.set(true); continue }
            manual = different.getAndSet(false) || manual
            stalled = stallRequested.getAndSet(false) || stalled
            force = manual || ((stalled || active in blocked) && !playback.recent(SystemClock.elapsedRealtime()))
            val replacement = SelectionPolicy.replacement(active,confirmed,trial,force,playback.recent(SystemClock.elapsedRealtime()))
            if (replacement != null) {
                if (force) blocked[active] = SystemClock.elapsedRealtime()+15*60*1000L
                TvState.update(this,"Переключение №${active+1} → №${replacement+1}: ${if (confirmed != null) "запасной вариант прошёл тесты" else "пробная смена после зависания/ручного запроса; успех не подтверждён"}")
                release(); open(replacement); establish(network); networkId = network.toString()
                val result = healthy(replacement)
                if (physical() != network) { requested.set(true); continue }
                if (result.ok) {
                    confirm(replacement); failures = 0
                    TvState.update(this,"Стратегия №${replacement+1} проверена и сохранена")
                    waitForCheck(300000); continue
                }
            }
            failures++
            TvState.update(this,"VPN сохранён без перебора туннелей; доступ YouTube полностью не подтверждён. Повтор через ${TvPolicy.backoffMillis(failures)/60000} мин.")
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
