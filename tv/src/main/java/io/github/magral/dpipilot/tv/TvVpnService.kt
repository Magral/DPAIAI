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
import org.json.JSONObject

class TvVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var controller: Job? = null
        private var lease: EngineLease? = null
    private var scoutLease: EngineLease? = null
    private var tun: ParcelFileDescriptor? = null
    private var config: File? = null
    private val requested = AtomicBoolean(true)
    private val urgentRequested = AtomicBoolean(false)
    private val explicitCheck = AtomicBoolean(false)
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
    private var observedAt = 0L
    private var observedRx = 0L
    private var historyFlushAt = 0L
    private var trial: Trial? = null
    private var manualChoice: Int? = null
    private val reputation by lazy { QualityStore(prefs) }
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
        CatalogUpdater.load(this)
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
        if (intent?.action in listOf("check","different")) explicitCheck.set(true)
        if (intent?.action in listOf("check","different","select","reset","report")) requested.set(true)
        if (controller == null) {
            scope.launch {
                while (isActive) {
                    CatalogUpdater.refresh(this@TvVpnService)
                    delay(15*60*1000L)
                }
            }
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
            .setContentTitle("DPI Pilot TV — YouTube").setContentText("Автоподбор раз в 3 дня · ручной выбор по задержке")
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
    private fun periodic() = prefs.getBoolean("auto_three_days",true)
    private fun sameNetwork(network: Network) = physical() == network && profileKey(network) == profile
    private fun watch() {
        var lastNetwork = physical()?.let { it.toString()+profileKey(it) }
        fun changed() {
            val next = physical()?.let { it.toString()+profileKey(it) }
            if (next != lastNetwork) { lastNetwork = next; requested.set(true) }
        }
        monitor = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changed() }
            override fun onLost(network: Network) { changed() }
            override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties) { changed() }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),monitor!!)
        screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context,intent: Intent) { if (!TvState.engineReady || tun == null) requested.set(true) }
        }
        ContextCompat.registerReceiver(this,screen,IntentFilter(Intent.ACTION_SCREEN_ON),ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun sampleTraffic() {
        val now = SystemClock.elapsedRealtime()
        if (trial == null) return
        if (tun == null || now < sampleAt) return
        sampleAt = now+5000L
        val stats = TProxyService.TProxyGetStats()
        if (stats.size < 4) return
        TvState.sentPackets = stats[0]; TvState.sentBytes = stats[1]
        TvState.receivedPackets = stats[2]; TvState.receivedBytes = stats[3]
        TvState.trafficAt = System.currentTimeMillis()
        if (physical()?.toString() != networkId) {
            traffic.reset(); playback.reset(); observedAt = 0
            TvState.stallDetail = "Физическая сеть отсутствует или меняется; это не отказ стратегии"
            return
        }
        activeIndex?.let { index ->
            if (observedAt > 0 && now-observedAt in 1000L..15000L && stats[3] >= observedRx)
                reputation.observe(index,(now-observedAt)/1000,stats[3]-observedRx >= 32*1024)
        }
        observedAt = now; observedRx = stats[3]
        if (now-historyFlushAt >= 300000L) { reputation.flush(); historyFlushAt = now }
        playback.observe(now,stats[3])
        lastReceived = stats[3]
        val stalledNow = traffic.stalled(TrafficWatchdog.Sample(now,stats[0],stats[1],stats[3]))
        TvState.stallDetail = traffic.reason
        if (stalledNow &&
            (activeIndex?.let { it !in blocked } == true) && !playback.recent(now)) {
            activeIndex?.let { quarantine(it); reputation.failed(it,stall=true) }
            TvState.update(this,"Повторные запросы во время испытания без данных; запрошен откат")
            stallRequested.set(true); requested.set(true)
        }
    }
    private suspend fun waitForCheck(milliseconds: Long) {
        val networkDeadline = SystemClock.elapsedRealtime()+10000L
        while (!requested.get()) {
            val remaining = when {
                milliseconds == 10000L -> (networkDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)
                trial != null -> (120000L-(SystemClock.elapsedRealtime()-trial!!.started)).coerceAtLeast(0)
                periodic() && profile.isNotEmpty() -> TvCheckPolicy.remaining(prefs.getLong("scan_started.$profile",0),System.currentTimeMillis())
                else -> Long.MAX_VALUE
            }
            TvState.nextCheckAt = if (remaining == Long.MAX_VALUE) 0 else SystemClock.elapsedRealtime()+remaining
            TvState.phase = when {
                trial != null -> "Испытание выбранной стратегии · до 2 минут"
                remaining == Long.MAX_VALUE -> "Проверки только по требованию"
                else -> "Стратегия сохранена · следующий автоподбор через ${remaining/3600000} ч"
            }
            if (remaining == 0L) break
            TvState.beat(this)
            if (trial != null) sampleTraffic()
            delay(if (trial != null) 1000 else 5000)
        }
        TvState.nextCheckAt = 0
    }
    /** One confirmation only; never combine successes from separate incomplete attempts. */
    private suspend fun healthy(index: Int,port: Int = 19080): TvProbe.Result {
        val network = physical()
        val known = reputation.quality(index).eligible
        suspend fun attempt(previews: Boolean): TvProbe.Result = try {
            probe(index,port,previews)
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            TvProbe.Result(false,"Таймаут проверки стратегии")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { TvProbe.Result(false,"${e.javaClass.simpleName}: ${e.message?.take(160)}") }
        val first = attempt(true)
        if (!TvCheckPolicy.confirmFailure(first.ok,first.inconclusive,first.score,known)) return first
        if (network == null || !sameNetwork(network) || !prefs.getBoolean("enabled",false) ||
            prefs.contains("manual_request") || prefs.contains("manual_bad_request")) return first
        TvState.update(this,"№${index+1}: первый тест ${first.score}/6; один подтверждающий повтор перед исключением")
        delay(750)
        currentCoroutineContext().ensureActive()
        if (!sameNetwork(network) || prefs.contains("manual_request") || prefs.contains("manual_bad_request")) return first
        // No extra picture downloads on a failure confirmation: access remains the only gate.
        val confirmed = attempt(false)
        return TvProbe.Result(confirmed.ok,
            "Повтор после ${first.score}/6: ${confirmed.detail}",confirmed.score,confirmed.quality,confirmed.inconclusive)
    }
    private suspend fun probe(index: Int,port: Int,previews: Boolean = true): TvProbe.Result = withTimeout(180000) { coroutineScope {
        TvState.phase = if (port == 19081) "Фоновая проверка стратегии №${index+1}" else "Проверка текущей стратегии №${index+1}"
        TvState.probeIndex = index
        TvState.nextCheckAt = 0
        TvState.probeDetail = "Проверка началась; порт $port"
        val session = TvProbe.Session()
        val probeNetwork = physical()
        try {
            val pending = async(Dispatchers.IO) {
                runInterruptible { TvProbe.check(port,index,session,previews) { row ->
                    TvState.probeDetail = "№${index+1}: $row"
                    TvState.update(this@TvVpnService,"Тест №${index+1}: $row")
                } }
            }
            while (!pending.isCompleted) {
                if (probeNetwork == null || !sameNetwork(probeNetwork)) {
                    session.cancel();pending.cancelAndJoin()
                    return@coroutineScope TvProbe.Result(false,"Сеть изменилась; результат отброшен",inconclusive=true)
                }
                TvState.beat(this@TvVpnService); sampleTraffic()
                if (port == 19080 && trial?.rollback(SystemClock.elapsedRealtime(),false,stallRequested.get()) == true) {
                    session.cancel(); pending.cancel(); pending.join()
                    return@coroutineScope TvProbe.Result(false,"Пробная стратегия зависла; запрошен быстрый откат")
                }
                if ((prefs.contains("manual_request") || prefs.contains("manual_bad_request"))) {
                    session.cancel(); pending.cancel(); pending.join()
                    return@coroutineScope TvProbe.Result(false,"Проверка прервана ручным выбором стратегии")
                }
                delay(250)
            }
            val result = pending.await()
            currentCoroutineContext().ensureActive()
            if (probeNetwork == null || !sameNetwork(probeNetwork)) return@coroutineScope TvProbe.Result(false,"Сеть изменилась; результат отброшен",inconclusive=true)
            TvState.lastCheckAt = System.currentTimeMillis()
            TvState.probeDetail = result.detail
            TvState.resultTimes[index] = System.currentTimeMillis()
            TvState.results[index] = (if (result.ok) "OK: " else "Частично/отказ: ")+result.detail
            prefs.edit().putLong("last_check_at",TvState.lastCheckAt).putString("last_probe",result.detail).apply()
            result
        } finally {
            session.cancel()
        }
    } }
    private fun confirm(index: Int,quality: ProbeQuality) {
        val known = TvPolicy.savedHistory(prefs.getString("history_ids.$profile",null),prefs.getString("history.$profile",null).orEmpty())
        val edit = prefs.edit()
        if (prefs.getString("manual_id.$profile",null) != RuntimeCatalog.ids[index]) edit.remove("manual_id.$profile")
        edit.putInt("good",index).putString("good_id",RuntimeCatalog.ids[index]).putString("good_id.$profile",RuntimeCatalog.ids[index])
            .putString("history_ids.$profile",TvPolicy.remember(known,index).joinToString(",") { RuntimeCatalog.ids[it] })
            .putString("history.$profile",TvPolicy.remember(known,index).joinToString(","))
            .putLong("last_success",System.currentTimeMillis()).apply()
        TvState.verified = true
        TvState.deliveryStatus = quality.preview.text()
        reputation.approved(index,quality,System.currentTimeMillis())
    }
    private suspend fun open(index: Int) {
        TvState.phase = "Запуск движка"
        TvState.strategyIndex = index
        TvState.deliveryStatus = reputation.preview(index).text()
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
            reputation.flush()
            traffic.reset(); playback.reset(); sampleAt = 0; lastReceived = 0; observedAt = 0; observedRx = 0
        }
    }
    private fun quarantine(index: Int) {
        blocked[index] = Long.MAX_VALUE
        reputation.exclude(index)
    }
    private fun loadQuarantine() {
        blocked.clear()
        reputation.excluded().forEach { blocked[it] = Long.MAX_VALUE }
    }
    private fun clearTrial() {
        trial = null; manualChoice = null; prefs.edit().remove("trial.$profile").apply()
        TvState.trialDetail = "Пробное переключение не выполняется"
    }
    private fun beginTrial(previous: Int,candidate: Int,baseline: ProbeQuality,restorable: Boolean) {
        manualChoice = null
        trial = Trial(previous,candidate,SystemClock.elapsedRealtime(),restorable,baseline)
        prefs.edit().putString("trial.$profile",JSONObject().put("previous",RuntimeCatalog.ids[previous])
            .put("candidate",RuntimeCatalog.ids[candidate]).put("time",System.currentTimeMillis()).put("restore",restorable).toString()).commit()
        TvState.trialDetail = "Испытание №${candidate+1} до 2 минут; резерв №${previous+1}"
    }
    private suspend fun rollback(network: Network,exclude: Boolean = true): Boolean {
        val pending = trial ?: return false
        if (!pending.restorable || pending.previous in reputation.excluded()) { clearTrial();return false }
        clearTrial()
        if (exclude) { quarantine(pending.candidate); reputation.failed(pending.candidate,rollback=true) }
        TvState.update(this,"Откат №${pending.candidate+1} → №${pending.previous+1}; ${if (exclude) "новый вариант исключён для этой сети до ручного сброса" else "дополнительная оценка не подтверждена, исключения не менялись"}")
        release(); open(pending.previous); establish(network); networkId = network.toString()
        val result = healthy(pending.previous)
        if (!sameNetwork(network)) { requested.set(true); return true }
        if (result.ok) confirm(pending.previous,result.quality)
        else if (!result.inconclusive) {
            reputation.failed(pending.previous,quality=result.quality)
            urgentRequested.set(true);explicitCheck.set(true);requested.set(true)
        }
        TvState.trialDetail = "Выполнен откат к №${pending.previous+1}; причина и проверки — в журнале"
        waitForCheck(60000)
        return true
    }
    private suspend fun reviewTrial(result: TvProbe.Result,network: Network,stalled: Boolean,manual: Boolean = false): Boolean {
        val pending = trial ?: return false
        val latest = result
        if (result.inconclusive) {
            TvState.update(this,"Источник тестового превью недоступен; вариант не объявляется нерабочим")
            if (pending.restorable && rollback(network,exclude=false)) return true
            clearTrial();waitForCheck(0);return true
        }
        if (pending.candidate != activeIndex) { clearTrial(); return false }
        if (pending.rollback(SystemClock.elapsedRealtime(),false,stalled)) return rollback(network)
        // healthy() has already confirmed an eligible-to-failed regression once.
        if (pending.restorable && pending.rollback(SystemClock.elapsedRealtime(),
                QualityPolicy.regression(pending.baseline,result.quality),false)) return rollback(network)
        if (!pending.expired(SystemClock.elapsedRealtime()) && !manual) {
            TvState.trialDetail = "Наблюдаю пробную №${pending.candidate+1}; при ухудшении вернусь к №${pending.previous+1}"
            waitForCheck((120000L-(SystemClock.elapsedRealtime()-pending.started)).coerceIn(1000L,120000L))
            return true
        }
        val chosenManually = manualChoice == pending.candidate
        clearTrial()
        if (latest.ok && !manual) {
            confirm(pending.candidate,latest.quality)
            if (chosenManually) prefs.edit().putString("manual_id.$profile",RuntimeCatalog.ids[pending.candidate]).apply()
        } else if (!latest.ok) reputation.failed(pending.candidate,quality=latest.quality)
        TvState.update(this,"Испытание №${pending.candidate+1} завершено: ${if (latest.ok) "пробы пройдены; полезный обмен учтён отдельно" else "успех не подтверждён"}")
        if (latest.ok && !manual) { waitForCheck(3600000); return true }
        return false
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
        TvState.update(this,"VPN-интерфейс создан; приложения: ${targets.joinToString()}. Проверки выполняются по расписанию или запросу")
    }
    /** Test the exact user-selected stable ID in :scout before touching the active tunnel. */
    private suspend fun applyManual(network: Network): Boolean {
        val raw = synchronized(TvState) {
            prefs.getString("manual_request",null)?.also { prefs.edit().remove("manual_request").apply() }
        } ?: return false
        val request = runCatching { JSONObject(raw) }.getOrNull()
        val index = TvPolicy.indexOfId(request?.optString("id"))
        val history = TvPolicy.savedHistory(prefs.getString("history_ids.$profile",null),prefs.getString("history.$profile",null).orEmpty())
        if (request?.optString("profile") != profile || index == null || index !in reputation.choices(history)) {
            TvState.update(this,"Ручной выбор отклонён: вариант отсутствует в успешных результатах текущей сети")
            return false
        }
        val active = activeIndex ?: return false
        if (index == active) {
            if (trial == null) prefs.edit().putString("manual_id.$profile",RuntimeCatalog.ids[index]).apply()
            TvState.update(this,"Выбранная №${index+1} уже включена; туннель не перезапускался")
            return false
        }
        prefs.edit().putLong("scan_started.$profile",System.currentTimeMillis()).apply()
        TvState.update(this,"Ручной выбор №${index+1}: отдельная проверка; активна №${active+1}")
        val next = EngineLease(this,scout = true)
        scoutLease = next
        val result = try {
            next.open(index); healthy(index,19081)
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            TvProbe.Result(false,"Таймаут проверки выбранной стратегии")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { TvProbe.Result(false,"${e.javaClass.simpleName}: ${e.message?.take(120)}") }
        finally { next.close(); scoutLease = null }
        if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request"))) { requested.set(true); return true }
        if (!result.ok) {
            if (!result.inconclusive) reputation.failed(index,quality=result.quality)
            TvState.update(this,"Выбранная №${index+1} не прошла проверку; №${active+1} сохранена. ${result.detail}")
            if (trial == null) { waitForCheck(3600000); return true }
            return false
        }
        reputation.approved(index,result.quality,System.currentTimeMillis())
        val reserve = trial?.takeIf { it.restorable && it.previous != index }
        val previous = reserve?.previous ?: active
        val restorable = reserve != null || (trial == null && TvState.verified)
        clearTrial()
        beginTrial(previous,index,reputation.quality(previous),restorable)
        manualChoice = index
        TvState.update(this,"Применяю выбранную №${index+1}; испытание до 2 минут, резерв №${previous+1}. Видео может переподключиться")
        release(); open(index); establish(network); networkId = network.toString()
        return true
    }
    private suspend fun control() {
        while (currentCoroutineContext().isActive && prefs.getBoolean("enabled",false)) {
            requested.set(false)
            TvState.beat(this)
            val network = physical()
            if (network == null) {
                clearTrial(); release(); TvState.network = "нет подключения Wi-Fi / Ethernet"
                TvState.update(this,"Ожидаю сеть; стратегии не исключаются из-за отсутствия подключения")
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
            val nextProfile = profileKey(network)
            val profileChanged = profile != nextProfile
            var recovered: Int? = null
            var urgent = urgentRequested.getAndSet(false)
            var repair = false
            if (profileChanged) {
                trial = null; manualChoice = null; profile = nextProfile
                reputation.network(profile); loadQuarantine()
                val interrupted = runCatching { JSONObject(prefs.getString("trial.$profile",null).orEmpty()) }.getOrNull()
                if (interrupted != null) {
                    if (interrupted.optBoolean("restore")) recovered = TvPolicy.indexOfId(interrupted.optString("previous"))?.takeIf { it !in blocked }
                    prefs.edit().remove("trial.$profile").apply()
                }
            }
            TvState.networkProfile = profile
            prefs.edit().putString("last_profile",profile).apply()
            if (prefs.getString("reset_exclusions",null) == profile) {
                reputation.resetExclusions(); blocked.clear()
                prefs.edit().remove("reset_exclusions").remove("quarantine.$profile").apply()
                TvState.update(this,"Исключения текущей сети сброшены пользователем; повторный тест — по кнопке или по расписанию")
            }
            val complaint = runCatching { JSONObject(prefs.getString("manual_bad_request",null).orEmpty()) }.getOrNull()
            if (complaint != null) {
                prefs.edit().remove("manual_bad_request").apply()
                if (complaint.optString("profile") == profile) {
                    TvPolicy.indexOfId(complaint.optString("id"))?.let { bad ->
                        reputation.failed(bad,user=true)
                        TvState.update(this,"Жалоба на №${bad+1}: тормоза/превью; стратегия исключена для этой сети")
                        urgent = true;explicitCheck.set(true)
                    }
                    if (trial?.candidate == TvPolicy.indexOfId(complaint.optString("id"))) {
                        val previous = trial
                        if (previous?.restorable == true && previous.previous !in reputation.excluded()) {
                            explicitCheck.set(false)
                            if (rollback(network)) continue
                        }
                        clearTrial();explicitCheck.set(true)
                    }
                }
            }
            loadQuarantine()
            if (reputation.consumeRepair()) {
                repair = true;urgent = true;explicitCheck.set(true)
                TvState.update(this,"Исправлены автоматические исключения старого теста; повторная проверка доступности без порогов скорости")
            }
            val history = TvPolicy.savedHistory(prefs.getString("history_ids.$profile",null),prefs.getString("history.$profile",null).orEmpty())
            val known = reputation.preferred(history)
            if (tun == null || !TvState.engineReady || networkId != network.toString() || profileChanged) {
                trial?.takeIf { it.restorable }?.let {
                    recovered = it.previous.takeIf { n -> n !in blocked }
                    quarantine(it.candidate); clearTrial()
                }
                release()
                val preferred = TvPolicy.indexOfId(prefs.getString("manual_id.$profile",null))?.takeIf { it !in blocked }
                val preserved = TvPolicy.indexOfId(prefs.getString("good_id.$profile",prefs.getString("good_id",null)))?.takeIf { it !in blocked }
                val initial = recovered ?: preferred ?: known.firstOrNull() ?: preserved ?: TvPolicy.adaptiveIndex.takeIf { it !in blocked } ?: TvPolicy.strategies.indices.firstOrNull { it !in blocked }
                if (initial == null) {
                    TvState.update(this,"Все варианты исключены для этой сети. Сбросьте исключения в меню стратегий или дождитесь новых команд базы")
                    if (TvCheckPolicy.shouldCheck(periodic(),false,prefs.getLong("scan_started.$profile",0),System.currentTimeMillis()))
                        prefs.edit().putLong("scan_started.$profile",System.currentTimeMillis()).apply()
                    waitForCheck(0); continue
                }
                open(initial); establish(network); networkId = network.toString()
                TvState.verified = initial in reputation.choices(history)
                TvState.update(this,"Включена сохранённая №${initial+1}; перезапуск не сбрасывает срок следующего автоподбора")
            }
            if (applyManual(network)) continue
            val active = activeIndex!!
            if (trial != null) {
                explicitCheck.set(false) // A requested check during a trial is satisfied by this bounded trial check.
                val result = healthy(active)
                if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request"))) { requested.set(true); continue }
                if (reviewTrial(result,network,stallRequested.getAndSet(false))) continue
                if (!result.ok) {
                    reputation.failed(active,quality=result.quality); loadQuarantine()
                    val reserve = reputation.choices(history).firstOrNull { it != active }
                    if (reserve != null) {
                        beginTrial(active,reserve,ProbeQuality(false,0,0),false)
                        TvState.update(this,"Испытание не удалось; применяю следующий рабочий резерв №${reserve+1}")
                        release(); open(reserve); establish(network); networkId = network.toString()
                        continue
                    }
                }
                waitForCheck(0); continue
            }
            val manual = explicitCheck.getAndSet(false)
            stallRequested.set(false)
            val started = prefs.getLong("scan_started.$profile",0)
            if (!TvCheckPolicy.shouldCheck(periodic(),manual,started,System.currentTimeMillis())) {
                waitForCheck(0); continue
            }
            // Stamp attempts before probes: partial runs, failures and device restarts cannot trigger a retry storm.
            prefs.edit().putLong("scan_started.$profile",System.currentTimeMillis()).apply()
            val current = if (active !in blocked) healthy(active) else TvProbe.Result(false,"Вариант исключён; повторная проверка пропущена")
            if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request"))) { requested.set(true); continue }
            if (current.inconclusive) {
                TvState.update(this,"Тестовое превью отсутствует на сервере; рабочий туннель сохранён, исключения не менялись")
                waitForCheck(0);continue
            }
            val failures = mutableListOf<Pair<Int,TvProbe.Result>>()
            if (current.ok) confirm(active,current.quality)
            else { reputation.defer(active,current.quality);failures.add(active to current); TvState.verified = false }
            if (repair && current.ok) {
                TvState.update(this,"Рабочая №${active+1} восстановлена: 6/6. Медленная или недоступная оценка превью не блокирует доступ")
                waitForCheck(0);continue
            }
            val candidates = mutableListOf<Pair<Int,ProbeQuality>>()
            var sourcesUnavailable = false
            val window = TvPolicy.scanWindow((known+history).distinct(),active,blocked.keys,0,TvPolicy.strategies.size)
            TvState.update(this,"Редкий фоновый подбор: ${window.size} вариантов; ${blocked.size} исключены. Сначала стабильная полная загрузка превью, затем задержка")
            for ((position,index) in window.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request")) || explicitCheck.get() || (!manual && !periodic())) break
                TvState.scanCursor = index
                TvState.update(this,"Фоновый тест ${position+1}/${window.size}: №${index+1}; активна №${active+1}")
                val next = EngineLease(this,scout = true); scoutLease = next
                val result = try { next.open(index); healthy(index,19081) }
                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); TvProbe.Result(false,"Таймаут теста") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { TvProbe.Result(false,"${e.javaClass.simpleName}: ${e.message?.take(100)}") }
                finally { next.close(); scoutLease = null }
                if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request")) || explicitCheck.get() || (!manual && !periodic())) break
                if (result.inconclusive) { sourcesUnavailable = true;break }
                if (result.ok) {
                    reputation.approved(index,result.quality,System.currentTimeMillis()); candidates.add(index to result.quality)
                    if (urgent || !current.ok) break // Restore failed access with the first fully verified reserve.
                } else {
                    reputation.defer(index,result.quality);failures.add(index to result)
                    TvState.update(this,"№${index+1} не прошла; исключение ожидает контрольной проверки. ${result.detail.take(300)}")
                }
            }
            if (!sameNetwork(network) || (prefs.contains("manual_request") || prefs.contains("manual_bad_request")) || explicitCheck.get()) { requested.set(true); continue }
            if (!manual && !periodic()) { waitForCheck(0); continue }
            if (sourcesUnavailable) {
                TvState.update(this,"Источник превью недоступен; подбор остановлен без массового исключения стратегий")
                waitForCheck(0);continue
            }
            if (failures.isNotEmpty() && current.ok) {
                val control = healthy(active)
                if (!sameNetwork(network) || prefs.contains("manual_request") || prefs.contains("manual_bad_request") || explicitCheck.get()) { requested.set(true);continue }
                if (control.ok) {
                    failures.forEach { (index,result) -> reputation.failed(index,quality=result.quality) }
                    loadQuarantine()
                } else {
                    reputation.defer(active,control.quality);TvState.verified = false
                    TvState.update(this,"Контрольная стратегия перестала отвечать; массовые исключения отменены, возможен общий сбой сети")
                    waitForCheck(0);continue
                }
            }
            val best = TvCheckPolicy.fastest(if (current.ok) active to current.quality else null,candidates)
            if (best != null && best != active) {
                beginTrial(active,best,current.quality,current.ok)
                TvState.update(this,"Применяю проверенную по стабильности превью №${best+1}: ${reputation.latency(best)} мс HTTPS; испытание до 2 минут")
                release(); open(best); establish(network); networkId = network.toString()
                continue
            }
            TvState.update(this,if (best == active) "№${active+1} — лучший результат полной загрузки превью среди проверенных; туннель сохранён" else "Рабочих вариантов не найдено; новые исключения не записаны — возможен общий сбой сети. Следующий автоподбор не раньше чем через 3 дня или по требованию")
            waitForCheck(0)
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
        if (!prefs.getBoolean("enabled",false) && profile.isNotEmpty()) clearTrial()
        scope.cancel()
        TvState.vpnEstablished = false
        TvState.verified = false
        TvState.running = false
        super.onDestroy()
    }
}
