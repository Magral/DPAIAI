package io.github.dovecoteescapee.byedpi.warp

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.services.*
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

/** Explicit manual transport. Native operation lock spans start through final close across instances. */
class WarpVpnService : LifecycleVpnService() {
    private val cm by lazy {getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager}
    private val control=WarpControl()
    private val desired get()=control.desired
    private var tunnelJob: Job?=null
    private var changeJob: Job?=null
    private var registered=false
    private var signature=""
    @Volatile private var physical: Network?=null
    private val callback=object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network)=changed()
        override fun onLost(network: Network)=changed()
        override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties)=changed()
        override fun onCapabilitiesChanged(network: Network,caps: NetworkCapabilities)=changed()
    }
    override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"WARP YouTube + Instagram",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        super.onStartCommand(intent,flags,startId)
        if(intent?.action==STOP_ACTION){end();return START_NOT_STICKY}
        if(intent?.action!=START_ACTION && !(intent==null && PhoneRecovery.desired(this) && WarpState.selected(this))){stopSelf();return START_NOT_STICKY}
        notifyStatus("WARP: подготовка подключения")
        if(desired)return START_STICKY
        control.start();WarpState.active=true;getPreferences().edit().putBoolean("mobile_enabled",true).apply();PhoneRecovery.schedule(this)
        setStatus(AppStatus.Running,Mode.VPN);broadcast(STARTED_BROADCAST)
        signature=networkSignature();physical=pickNetwork()
        try {cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);registered=true}
        catch(_: Exception){notifyStatus("Android не разрешил наблюдение сети; повторное подключение — кнопкой")}
        connect();return START_STICKY
    }
    private fun pickNetwork(): Network? {
        val candidates=cm.allNetworks.filter {n->cm.getNetworkCapabilities(n)?.let {it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
        if(Build.VERSION.SDK_INT>=23)cm.activeNetwork?.takeIf {it in candidates}?.let {return it}
        return candidates.sortedWith(compareByDescending<Network> {cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}.thenByDescending {cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}).firstOrNull()
    }
    private fun networkSignature(): String=pickNetwork()?.let {n->"$n:${cm.getLinkProperties(n)?.linkAddresses?.map {it.toString()}?.sorted()}"}.orEmpty()
    private fun changed(){lifecycleScope.launch {
        if(!desired)return@launch
        changeJob?.cancel();changeJob=lifecycleScope.launch {delay(700);val next=networkSignature();if(next!=signature){signature=next;physical=pickNetwork();connect()}}
    }}
    /** Called by JNI before any outer UDP packet. A failed bind/protect is a hard failure. */
    @Suppress("unused") fun protectWarpSocket(fd: Int): Boolean = runCatching {
        val network=physical
        if(network==null)false else {
            ParcelFileDescriptor.fromFd(fd).use {network.bindSocket(it.fileDescriptor)}
            protect(fd)
        }
    }.getOrDefault(false)
    private fun connect(){
        WarpState.session.invalidate()
        tunnelJob?.cancel()
        tunnelJob=lifecycleScope.launch {
            nativeLock.withLock {
                try {
                    currentCoroutineContext().ensureActive();if(!desired)return@withLock
                    val network=physical
                    if(network==null){notifyStatus("WARP: ждём Wi-Fi или мобильную сеть");return@withLock}
                    val apps=WarpRecoveryPolicy.routedApps(TargetApps.installed(this@WarpVpnService).map {it.packageName}.toSet(),packageName)
                    val profile=withContext(Dispatchers.IO){WarpStore(this@WarpVpnService).load()} ?: throw IllegalStateException("profile missing")
                    val host=profile.endpoint.substringBefore(':')
                    val endpoints=(listOf(profile.endpoint)+listOf(2408,500,4500,1701,443,988).map {"$host:$it"}).distinct()
                    val modes=if(profile.options.isEmpty())listOf("plain","junk","reserved") else listOf("plain","reserved")
                    val saved=getPreferences().getString("warp_last_attempt","").orEmpty()
                    val attempts=endpoints.flatMap {endpoint->modes.map {endpoint to it}}.sortedBy {if("${it.first}|${it.second}"==saved)0 else 1}
                    val queue=WarpAttempts(attempts)
                    var failures=0
                    while(desired) {
                    var success=false
                    val sweepStart=SystemClock.elapsedRealtime()
                    for(position in attempts.indices) {
                        currentCoroutineContext().ensureActive();if(!desired || !WarpRecoveryPolicy.canAttempt(SystemClock.elapsedRealtime()-sweepStart))break
                        val (endpoint,mode)=queue.next()
                        val ticket=WarpState.session.restart()
                        notifyStatus("WARP: подключение ${position+1}/${attempts.size}")
                        WarpState.detail="Сервер: $endpoint · режим $mode\nПриложения: ${apps.joinToString()}\nОжидаем подтверждённое рукопожатие"
                        var tun: ParcelFileDescriptor?=null
                        try {
                            val builder=Builder().setSession("DPI Pilot · WARP").setMtu(1280).setBlocking(true).addDnsServer("1.1.1.1")
                                .setConfigureIntent(PendingIntent.getActivity(this@WarpVpnService,0,Intent(this@WarpVpnService,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
                            for(address in profile.addresses)builder.addAddress(address.substringBefore('/'),address.substringAfter('/').toInt())
                            builder.addRoute("0.0.0.0",0);if(profile.addresses.any {it.contains(':')})builder.addRoute("::",0)
                            // Empty apps throws above. No fallback to whole-device routing is allowed.
                            for(pkg in apps)builder.addAllowedApplication(pkg)
                            builder.setUnderlyingNetworks(arrayOf(network));tun=builder.establish() ?: throw IllegalStateException("VPN permission missing")
                            val duplicate=ParcelFileDescriptor.dup(tun.fileDescriptor)
                            val ipc=profile.ipc(endpoint).let {if(mode=="junk")it.replace("replace_peers=true\n","replace_peers=true\njc=3\njmin=40\njmax=70\n") else it}
                            val error=duplicate.use {copy->withContext(Dispatchers.IO){WarpNative.start(copy.detachFd(),ipc,JSONArray(profile.reserved).toString(),mode!="reserved",this@WarpVpnService)}}
                            require(error.isEmpty()) {"native start failed"}
                            val until=minOf(SystemClock.elapsedRealtime()+WarpRecoveryPolicy.HANDSHAKE_MS,sweepStart+WarpRecoveryPolicy.SWEEP_MS)
                            while(SystemClock.elapsedRealtime()<until) {
                                delay(350);val stats=withContext(Dispatchers.IO){JSONObject(WarpNative.stats())}
                                if(stats.optLong("handshake")>0) {
                                    currentCoroutineContext().ensureActive();success=true;WarpState.session.connected(ticket)
                                    getPreferences().edit().putString("warp_last_attempt","$endpoint|$mode").apply()
                                    notifyStatus("WARP: рукопожатие подтверждено")
                                    while(desired) {
                                        val counters=withContext(Dispatchers.IO){JSONObject(WarpNative.stats())}
                                        WarpState.detail="Сервер: $endpoint · режим $mode\nYouTube, Instagram и диагностика DPI Pilot · MTU 1280\nОтправлено: ${counters.optLong("tx")} Б; получено: ${counters.optLong("rx")} Б\nПоследнее рукопожатие: ${counters.optLong("handshake")} (Unix)\nЭто счётчики туннеля; проверьте видео в приложении."
                                        delay(10000)
                                    }
                                    break
                                }
                            }
                            if(!success)WarpState.detail="Сервер: $endpoint · режим $mode\nРукопожатие не получено за отведённое время. Ключ сохранён."
                        } catch(e: CancellationException){throw e}
                        catch(e: Exception){WarpState.detail="Сервер: $endpoint · режим $mode\nСбой: ${failureCategory(e)}. Ключ сохранён."}
                        finally {WarpState.session.invalidate();withContext(NonCancellable+Dispatchers.IO){runCatching {WarpNative.stop()};tun?.close()}}
                        if(success)break
                    }
                    if(success || !desired)break
                    val pause=WarpRecoveryPolicy.retryDelay(failures++)
                    notifyStatus("WARP: нет рукопожатия; повтор через ${pause/1000} с. Ключ сохранён")
                    delay(pause)
                    }
                } catch(e: CancellationException){throw e}
                catch(_: LinkageError){notifyStatus("Ядро WARP недоступно на устройстве")}
                catch(_: Exception){notifyStatus("WARP: проверьте сохранённый профиль и наличие YouTube или Instagram")}
                finally {withContext(NonCancellable+Dispatchers.IO){runCatching {WarpNative.stop()}}}
            }
        }
    }
    private fun failureCategory(e: Exception): String = when(e) {
        is java.net.SocketTimeoutException -> "таймаут сети"
        is java.net.SocketException -> "сокет или физическая сеть"
        is SecurityException -> "разрешение Android VPN"
        else -> "подготовка туннеля (${e.javaClass.simpleName})"
    }
    private fun notifyStatus(text: String){
        WarpState.status=text
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,WarpVpnService::class.java).setAction(STOP_ACTION),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("DPI Pilot · бесплатный WARP")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(0,"Выключить",stop).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID,notification)
    }
    private fun broadcast(action: String){sendBroadcast(Intent(action).putExtra(SENDER,Sender.VPN.ordinal).setPackage(packageName))}
    private fun end(){
        WarpState.session.invalidate()
        val ticket=control.stop();val stopping=tunnelJob
        getPreferences().edit().putBoolean("mobile_enabled",false).apply();changeJob?.cancel();stopping?.cancel()
        lifecycleScope.launch {
            stopping?.join()
            if(!control.canFinishStop(ticket))return@launch
            WarpState.active=false;WarpState.status="WARP выключен";setStatus(AppStatus.Halted,Mode.VPN);broadcast(STOPPED_BROADCAST);stopForeground(true);stopSelf()
        }
    }
    override fun onRevoke(){end()}
    override fun onDestroy(){
        if(desired)WarpState.status="WARP остановлен Android"
        WarpState.session.invalidate()
        control.stop();changeJob?.cancel();tunnelJob?.cancel();if(registered)runCatching {cm.unregisterNetworkCallback(callback)}
        runCatching {WarpNative.stop()};WarpState.active=false;setStatus(AppStatus.Halted,Mode.VPN);super.onDestroy()
    }
    companion object {private val nativeLock=Mutex();private const val CHANNEL="DpiPilotWarp";private const val ID=72}
}
