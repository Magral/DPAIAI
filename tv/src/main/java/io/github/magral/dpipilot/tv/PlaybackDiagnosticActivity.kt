package io.github.magral.dpipilot.tv

import android.app.Activity
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Bundle
import android.os.Build
import android.widget.*
import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.text.SimpleDateFormat
import java.util.*

/** Manual-only diagnostic; opening this screen never starts or stops any VPN. */
@Suppress("DEPRECATION")
class PlaybackDiagnosticActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"playback-diagnostics").apply {isDaemon=true} }
    private var running: ProbeSession?=null
    private lateinit var output: TextView
    private var report=""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);title="YouTube: меню и видео"
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16)}
        fun button(text: String,action: ()->Unit) {box.addView(Button(this).apply {this.text=text;isFocusable=true;setOnClickListener {action()}})}
        box.addView(TextView(this).apply {text="Тест по требованию, до нескольких минут. Стратегии, VPN и DNS не меняются. Для сравнения сначала выключите DPI Pilot, проверьте обычную сеть; затем включите платный VPN и повторите системный тест. Приложение диагностики должно быть включено в маршрут этого VPN.\nПроверки узлов и API — доступность сети; реальный медиадиапазон проверяется отдельно.";textSize=18f})
        button("Проверить текущую сеть / платный VPN") {start(null)}
        button("Проверить работающий DPI Pilot") {start(intent.getIntExtra("proxy_port",19080))}
        button("Остановить тест") {running?.cancel()}
        button("Скопировать отчёт") {(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("YouTube diagnostics",report));Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show()}
        button("Сохранить отчёт") {startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="text/plain";putExtra(Intent.EXTRA_TITLE,"DPI-Pilot-YouTube-diagnostic.txt")},41)}
        output=TextView(this).apply {textSize=16f;setTextIsSelectable(true);setPadding(0,12,0,12)}
        report=getSharedPreferences("playback_diagnostic",MODE_PRIVATE).getString("report","").orEmpty()
        output.text=report.ifEmpty {"Диагностика ещё не запускалась"};box.addView(output)
        setContentView(ScrollView(this).apply {isFillViewport=true;addView(box)})
    }
    private fun start(port: Int?) {
        running?.cancel()
        val session=ProbeSession();running=session
        val stamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(Date())
        val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=if(Build.VERSION.SDK_INT>=23)cm.activeNetwork else cm.allNetworks.firstOrNull {cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true}
        val caps=network?.let {cm.getNetworkCapabilities(it)}
        val version=packageManager.getPackageInfo(packageName,0).versionName
        val snapshot="Снимок настроек при открытии экрана (не фиксация работающего движка):\nСтратегия YouTube: ${intent.getStringExtra("youtube_command") ?: "не передана"}\nDNS: ${intent.getStringExtra("youtube_dns") ?: "системный"}\n"
        val meta=snapshot+"DPI Pilot $version · $packageName · $stamp · Android ${Build.VERSION.RELEASE}\nСистемная сеть: VPN=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}; Wi-Fi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}; мобильная=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true}\nЛокальный путь: ${if(port==null)"не используется" else "127.0.0.1:$port"}\n"
        fun route(): String {
            val networks=if(Build.VERSION.SDK_INT>=23)listOfNotNull(cm.activeNetwork) else cm.allNetworks.toList()
            return networks.sortedBy {it.toString()}.joinToString("|") { n ->
                val properties=cm.getLinkProperties(n);val c=cm.getNetworkCapabilities(n)
                val transports=listOf(NetworkCapabilities.TRANSPORT_VPN,NetworkCapabilities.TRANSPORT_WIFI,NetworkCapabilities.TRANSPORT_CELLULAR,NetworkCapabilities.TRANSPORT_ETHERNET).filter {c?.hasTransport(it)==true}
                "$n:$transports:${properties?.interfaceName}:${properties?.dnsServers?.mapNotNull {it.hostAddress}?.sorted()}:${properties?.linkAddresses?.mapNotNull {it.address.hostAddress}?.sorted()}"
            }
        }
        val initialRoute=route();val routeChanged=AtomicBoolean(false)
        fun checkRoute() {if(route()!=initialRoute) {routeChanged.set(true);session.cancel()}}
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {checkRoute()}
            override fun onLost(network: Network) {checkRoute()}
            override fun onLinkPropertiesChanged(network: Network,properties: android.net.LinkProperties) {checkRoute()}
        }
        report=meta;output.text=meta
        worker.submit {
            var registered=false
            try {
                session.checkActive()
                cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);registered=true
                val result=PlaybackDiagnostics(ensureRoute={checkRoute();session.checkActive()}).run(port,session) { text ->
                    runOnUiThread {if(running===session && !isFinishing) {report+="\n"+text;output.text=report}}
                }
                runOnUiThread {if(running===session && !isFinishing) {report=meta+result;output.text=report;getSharedPreferences("playback_diagnostic",MODE_PRIVATE).edit().putString("report",report).apply()}}
            } catch(_: CancellationException) {runOnUiThread {if(running===session && !isFinishing) {report+=if(routeChanged.get())"\nСЕТЬ ИЛИ VPN ИЗМЕНИЛИСЬ; результат недействителен, запустите новый тест" else "\nТЕСТ ОСТАНОВЛЕН; итог не определён";output.text=report}}}
            catch(e: Exception) {runOnUiThread {if(running===session && !isFinishing) {report+="\nТест не завершён: ${e.javaClass.simpleName}";output.text=report}}}
            finally {if(registered)runCatching {cm.unregisterNetworkCallback(callback)};session.cancel();runOnUiThread {if(running===session)running=null}}
        }
    }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request==41 && result==RESULT_OK)data?.data?.let {uri ->
            try {requireNotNull(contentResolver.openOutputStream(uri)).use {it.write(report.toByteArray(Charsets.UTF_8))};Toast.makeText(this,"Отчёт сохранён",Toast.LENGTH_SHORT).show()}
            catch(_: Exception) {Toast.makeText(this,"Не удалось сохранить отчёт",Toast.LENGTH_LONG).show()}
        }
    }
    override fun onDestroy() {running?.cancel();worker.shutdownNow();super.onDestroy()}
}
