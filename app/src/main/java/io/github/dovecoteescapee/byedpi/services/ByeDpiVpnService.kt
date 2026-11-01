package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false
    private var mobileRuntime: MobileRuntime? = null
    private var mobileCommand = ""
    private var mobileMonitor: MobileMonitor? = null
    private var networkJob: Job? = null

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                getPreferences().edit().putBoolean("mobile_enabled",true).apply()
                PhoneRecovery.schedule(this)
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                getPreferences().edit().putBoolean("mobile_enabled",false).apply()
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            else -> {
                if (action == null && PhoneRecovery.desired(this)) { lifecycleScope.launch { start() }; START_STICKY }
                else START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        getPreferences().edit().putBoolean("mobile_enabled",false).apply()
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            return
        }

        try {
            startForeground()
            MobileSetup.prepare(this)
            mutex.withLock {
                startProxy()
                startTun2Socks()
            }
            updateStatus(ServiceStatus.Connected)
            if (mobileRuntime != null) {
                mobileMonitor = MobileMonitor(this,lifecycleScope,mobileRuntime!!).also { it.start() }
                networkJob = lifecycleScope.launch {
                    var signature = MobileNetwork.signature(this@ByeDpiVpnService)
                    while (isActive) {
                        delay(5000)
                        val next = MobileNetwork.signature(this@ByeDpiVpnService)
                        if (next.isEmpty() || next == signature) continue
                        try {
                            mutex.withLock { stopTun2Socks(); startTun2Socks() }
                            signature = next
                            MobileStrategyPolicy.state = "Физическая сеть изменилась; VPN и DNS обновлены"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            lifecycleScope.launch { updateStatus(ServiceStatus.Failed); stop() }
                            return@launch
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        mutex.withLock {
            stopping = true
            try {
                networkJob?.cancel(); networkJob = null
                mobileMonitor?.stop(); mobileMonitor = null
                stopTun2Socks()
                stopProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        if (MobileStrategyPolicy.modern(getPreferences())) {
            mobileCommand = getPreferences().getString("mobile_command","").orEmpty()
            check(mobileRuntime == null)
            val runtime = MobileRuntime(this); mobileRuntime = runtime
            try { runtime.open(mobileCommand) } catch (e: Exception) { runtime.close(); mobileRuntime = null; throw e }
            return
        }
        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxy(preferences)

            withContext(Dispatchers.Main) {
                if (code != 0) {
                    Log.e(TAG, "Proxy stopped with code $code")
                    updateStatus(ServiceStatus.Failed)
                } else {
                    if (!stopping) {
                        stop()
                        updateStatus(ServiceStatus.Disconnected)
                    }
                }
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        if (mobileRuntime != null) { mobileRuntime?.close(); mobileRuntime = null; return }
        Log.i(TAG, "Stopping proxy")

        if (status == ServiceStatus.Disconnected) {
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        byeDpiProxy.stopProxy()
        proxyJob?.join() ?: throw IllegalStateException("ProxyJob field null")
        proxyJob = null

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val port = sharedPreferences.getString("byedpi_proxy_port", null)?.toInt() ?: 1080
        val modern = MobileStrategyPolicy.modern(sharedPreferences)
        val network = MobileNetwork.physical(this)
        val dns = MobileNetwork.dns(this,network,modern && sharedPreferences.getBoolean("mobile_network_dns",true),sharedPreferences.getStringNotNull("dns_ip", "1.1.1.1"))
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   address: 127.0.0.1
        |   port: $port
        |   udp: udp
        """.trimMargin("| ")

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6,network).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd
        MobileNetwork.tunnel = "VPN: MTU 1500 · DNS: ${dns.joinToString().ifEmpty { "не задан" }}"

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        TProxyService.TProxyStopService()

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        tunFd?.close() ?: Log.w(TAG, "VPN not running")
        tunFd = null
        MobileNetwork.tunnel = "VPN выключен"

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            ByeDpiVpnService::class.java,
        )

    private fun createBuilder(dns: List<String>, ipv6: Boolean,network: android.net.Network?): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("DPI Pilot").setMtu(1500)
        if (Build.VERSION.SDK_INT >= 22 && network != null) builder.setUnderlyingNetworks(arrayOf(network))
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        for (server in dns) builder.addDnsServer(server)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // An empty allowlist means ALL apps in Android. Never establish it accidentally.
        val targets = TargetApps.selected(this)
        require(targets.isNotEmpty()) { "Select at least one application" }
        var added = 0
        for (packageName in targets) {
            try {
                builder.addAllowedApplication(packageName)
                added++
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "Selected application is no longer installed")
            }
        }
        check(added > 0) { "Selected applications are no longer installed" }
        // The utility is absent from the allowlist: its native outbound sockets remain outside TUN.

        return builder
    }
}
