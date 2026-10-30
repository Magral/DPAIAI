package io.github.dovecoteescapee.byedpi.services

import android.app.*
import android.content.*
import android.os.*
import android.net.*
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.activities.TelegramBridgeActivity
import io.github.dovecoteescapee.byedpi.utility.TelegramWsBridge
import io.github.dovecoteescapee.byedpi.utility.TelegramWsProbe
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

class TelegramBridgeService : Service() {
    private var bridge: TelegramWsBridge? = null
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var probeJob: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var lastNetwork: Network? = null
    companion object {
        @Volatile var running = false
        @Volatile var probing = false
        @Volatile private var eventLog = "Режим Telegram пока не запущен"
        @Volatile private var activeBridge: TelegramWsBridge? = null
        @Volatile private var lastSummary = ""
        @Volatile private var networkDescription = "определяется"
        @Volatile private var probeReport = "Проверка сети ещё не запускалась"
        val diagnostics: String get() = "DPI Pilot ${BuildConfig.VERSION_NAME}; Telegram WSS v3\nСеть: $networkDescription\n" +
            (activeBridge?.diagnosticSummary ?: lastSummary)+"\n\nПроверка серверов:\n$probeReport\n\nСобытия:\n$eventLog"
        private fun append(line: String) { synchronized(TelegramBridgeService::class.java) {
            eventLog = (eventLog.lines().takeLast(59)+line).joinToString("\n")
        } }
        fun secret(context: Context): String {
            val prefs = context.getSharedPreferences("telegram_bridge",Context.MODE_PRIVATE)
            val existing = prefs.getString("secret",null)
            if (existing?.matches(Regex("[0-9a-f]{32}")) == true) return existing
            val value = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            prefs.edit().putString("secret",value).commit()
            return value
        }
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val channel = "telegram_bridge"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"Соединение Telegram",NotificationManager.IMPORTANCE_LOW))
        val content = PendingIntent.getActivity(this,440,Intent(this,TelegramBridgeActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,441,Intent(this,TelegramBridgeService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this,channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DPI Pilot: Telegram").setContentText("Локальный MTProxy → защищённый WebSocket Telegram")
            .setOngoing(true).setSilent(true).setContentIntent(content).addAction(0,"Остановить",stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(44,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(44,notification)
        if (bridge == null) {
            try {
                val hex = secret(this)
                val bytes = ByteArray(16) { hex.substring(it*2,it*2+2).toInt(16).toByte() }
                eventLog = "Telegram WSS: проверка сертификатов включена; только официальные домены Telegram"
                bridge = TelegramWsBridge(bytes,log = ::append).also { it.start() }
                activeBridge = bridge
                running = true
                watchNetwork()
            } catch (e: Exception) {
                append("Не удалось запустить: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                bridge?.close(); bridge = null; running = false; stopSelf()
            }
        }
        if (intent?.action == "probe" && running) probeNetwork()
        return START_NOT_STICKY
    }
    private fun networkChanged(description: String) {
        networkDescription = description
        if (probing) {
            probeJob?.cancel()
            probeReport = "Сеть изменилась. Запустите проверку заново."
        }
        bridge?.networkChanged()
    }
    private fun describe(cm: ConnectivityManager,network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "нет данных"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            else -> "другая"
        }
    }
    private fun watchNetwork() {
        if (callback != null || receiver != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val main = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 24) {
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post {
                    if (!running) return@post
                    val description = describe(cm,network)
                    val old = lastNetwork
                    lastNetwork = network
                    if (old != null && old != network) networkChanged(description)
                    else networkDescription = description
                } }
                override fun onLost(network: Network) { main.post {
                    if (running && lastNetwork == network) {
                        lastNetwork = null
                        networkChanged("соединение потеряно")
                    }
                } }
            }
            cm.registerDefaultNetworkCallback(callback!!)
        } else {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context,intent: Intent) {
                    @Suppress("DEPRECATION")
                    val description = cm.activeNetworkInfo?.let { "${it.typeName}; connected=${it.isConnected}" } ?: "нет сети"
                    if (description != networkDescription) networkChanged(description)
                }
            }
            @Suppress("DEPRECATION")
            ContextCompat.registerReceiver(this,receiver,IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
    private fun probeNetwork() {
        if (probeJob?.isActive == true) return
        probing = true
        probeReport = "Проверяем обмен MTProto с официальными серверами…"
        probeJob = scope.launch {
            val rows = CopyOnWriteArrayList<String>()
            val workingRoutes = ConcurrentHashMap<String,String>()
            val gate = Semaphore(4)
            try {
                coroutineScope {
                    (1..5).flatMap { dc -> listOf(false,true).flatMap { media ->
                        TelegramWsBridge.officialEndpoints(dc,media).take(2).map { endpoint -> async {
                            gate.withPermit {
                                ensureActive()
                                val result = TelegramWsProbe.check(endpoint) { !isActive }
                                if (isActive) {
                                    if (result.startsWith("OK:")) workingRoutes.putIfAbsent("$dc:$media",endpoint)
                                    rows.add("DC$dc ${if (media) "файлы" else "сообщения"}; ${Uri.parse(endpoint).host}: $result")
                                    probeReport = "Проверено ${rows.size}/20\n"+rows.sorted().joinToString("\n")
                                }
                            }
                        } }
                    } }.awaitAll()
                }
                ensureActive()
                bridge?.useProbedRoutes(workingRoutes)
                probeReport += "\nПроверка завершена. resPQ проверяет транспорт без входа в аккаунт; доставка сообщений и файлов не проверяется."
            } finally { probing = false }
        }
    }
    override fun onDestroy() {
        running = false
        callback?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        receiver?.let { unregisterReceiver(it) }
        callback = null; receiver = null
        scope.cancel(); probing = false
        lastSummary = bridge?.diagnosticSummary ?: lastSummary
        activeBridge = null
        bridge?.close(); bridge = null; append("Локальный MTProxy остановлен")
        super.onDestroy()
    }
}
