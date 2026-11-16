package io.github.dovecoteescapee.byedpi.warp

import android.content.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/** Explicit setup owns enrollment/import. Merely opening this screen makes no network request. */
class WarpActivity : AppCompatActivity() {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"warp-setup").apply {isDaemon=true}}
    private val generation=WarpGeneration()
    private var future: Future<*>?=null
    private val enrollment=AtomicReference<WarpEnrollment?>()
    private val stream=AtomicReference<java.io.InputStream?>()
    private var diagnostic: io.github.dovecoteescapee.byedpi.utility.ProbeSession?=null
    private var diagnosticReport=""
    private var busy=false
    private lateinit var info: TextView
    private lateinit var local: Button
    private lateinit var warp: Button
    private lateinit var create: Button
    private lateinit var importButton: Button
    private var message=""
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object: Runnable {override fun run(){render();handler.postDelayed(this,1500)}}
    private val importFile=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null && idle())work {check->
            check()
            val input=requireNotNull(contentResolver.openInputStream(uri));stream.set(input)
            try {
                check()
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(2048)
                input.use {while(true){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException();val n=it.read(buf);if(n<0)break;require(out.size()+n<=16384);out.write(buf,0,n)}}
                WarpProfile.parse(out.toString("UTF-8"))
            } finally {stream.compareAndSet(input,null);runCatching {input.close()}}
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);title="Режим подключения"
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,24)}
        fun text(value: String){box.addView(TextView(this).apply {text=value;textSize=17f;setPadding(0,8,0,12)})}
        fun button(value: String,action: ()->Unit): Button=Button(this).apply {text=value;isAllCaps=false;setOnClickListener {action()};box.addView(this)}
        text("Локальный обход сохраняет прежние стратегии. Неофициальный клиент бесплатного WARP передаёт трафик YouTube и Instagram через Cloudflare. Собственные запросы диагностики и обновлений DPI Pilot также идут через туннель; другие приложения не включаются. Доступность зависит от сети и Cloudflare; воспроизведение проверяется в YouTube.")
        local=button("Выбрать локальный обход") {choose(false)}
        warp=button("Выбрать бесплатный WARP") {choose(true)}
        text("Первичная настройка WARP: создайте собственный ключ Cloudflare или импортируйте свой профиль WARP (.conf). Платная подписка и ключ WARP+ не нужны. Данные подключения остаются зашифрованными на устройстве.")
        button("Условия Cloudflare") {startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.cloudflare.com/application/terms/")))}
        create=button("Создать бесплатное подключение Cloudflare") {
            if(idle())AlertDialog.Builder(this).setTitle("Создать собственное подключение?")
                .setMessage("Будет создан новый ключ устройства. Только открытый ключ отправляется Cloudflare. Нажимая «Создать», вы принимаете условия Cloudflare. Прежнее подключение заменится только после успешной регистрации и сохранения.")
                .setPositiveButton("Создать") {_,_->if(idle())work {check->WarpEnrollment().also {enrollment.set(it);check()}.enroll()}}
                .setNegativeButton("Отмена",null).show()
        }
        importButton=button("Импортировать свой WARP-профиль .conf") {if(idle())importFile.launch(arrayOf("text/*","application/octet-stream","application/x-wireguard-profile"))}
        button("Отменить подготовку") {cancel();message="Подготовка отменена. Сохранённый профиль не менялся.";render()}
        text("Если регистрация Cloudflare недоступна: временно включите рабочий WARP в Nova и добавьте DPI Pilot в приложения его туннеля, затем повторите создание подключения. После сохранения отключите Nova. Для обычного запуска DPI Pilot она больше не нужна.")
        text("После настройки вернитесь на главный экран и нажмите «Включить». Режим переключается только при выключенном DPI Pilot. WARP доступен начиная с Android 7.")
        button("Проверить видео через работающий WARP") {checkPlayback()}
        button("Остановить проверку видео") {diagnostic?.cancel()}
        button("Скопировать безопасный статус") {(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot WARP",WarpState.report()+"\n\n"+diagnosticReport));Toast.makeText(this,"Статус скопирован без ключей",Toast.LENGTH_SHORT).show()}
        info=TextView(this).apply {textSize=16f;setTextIsSelectable(true)};box.addView(info)
        setContentView(ScrollView(this).apply {addView(box)})
        render()
    }
    private fun checkPlayback(){
        if(busy || diagnostic!=null)return
        val ticket=WarpState.session.snapshot()
        val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network=if(Build.VERSION.SDK_INT>=23)cm.activeNetwork else null
        if(ticket==null || network==null || cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)!=true){message="Сначала включите WARP и дождитесь рукопожатия";render();return}
        val session=io.github.dovecoteescapee.byedpi.utility.ProbeSession();diagnostic=session
        diagnosticReport="Проверка по требованию: DNS и сокеты привязаны к WARP VPN. До нескольких минут."
        fun check(){session.checkActive();if(!WarpState.session.valid(ticket)){session.cancel();session.checkActive()}}
        render()
        worker.submit {
            val callback=object: android.net.ConnectivityManager.NetworkCallback(){override fun onLost(lost: android.net.Network){if(lost==network)session.cancel()}}
            var registered=false
            try {
                cm.registerNetworkCallback(android.net.NetworkRequest.Builder().removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN).build(),callback);registered=true
                check()
                val test=io.github.dovecoteescapee.byedpi.catalog.PlaybackDiagnostics(
                    transport={req,parent,progress->check();io.github.dovecoteescapee.byedpi.catalog.PlaybackHttp.requestOn(network,req,parent,progress).also {check()}},
                    resolve={host,parent->check();io.github.dovecoteescapee.byedpi.catalog.PlaybackHttp.resolveOn(network,host,parent).also {check()}},
                    ensureRoute=::check)
                val result=test.run(null,session) {line->check();runOnUiThread {if(diagnostic===session && WarpState.session.valid(ticket)){diagnosticReport+="\n$line";render()}}}
                check();runOnUiThread {if(diagnostic===session && WarpState.session.valid(ticket)){diagnosticReport="Маршрут: собственный WARP VPN, сеть $network\n$result";render()}}
            } catch(_: java.util.concurrent.CancellationException){runOnUiThread {if(diagnostic===session){diagnosticReport+="\nПроверка остановлена или WARP переподключён. Итог недействителен.";render()}}}
            catch(_: Exception){runOnUiThread {if(diagnostic===session){diagnosticReport+="\nПроверка не завершена. Это не подтверждение видео.";render()}}}
            finally {if(registered)runCatching {cm.unregisterNetworkCallback(callback)};session.cancel();runOnUiThread {if(diagnostic===session)diagnostic=null}}
        }
    }
    private fun idle()=Build.VERSION.SDK_INT>=24 && !busy && !WarpState.active && appStatus.first!=AppStatus.Running
    private fun choose(useWarp: Boolean){
        if(busy || WarpState.active || appStatus.first==AppStatus.Running){message="Сначала выключите подключение на главном экране";render();return}
        if(useWarp && Build.VERSION.SDK_INT<24)return
        getPreferences().edit().putString("pilot_transport",if(useWarp)"warp" else "local").putString("byedpi_mode","vpn").apply()
        message=if(useWarp)"Выбран WARP. Нужен сохранённый профиль; запуск — на главном экране." else "Выбран локальный обход; стратегии сохранены."
        render()
    }
    private fun work(task: (() -> Unit)->WarpProfile){
        if(!idle())return
        busy=true;val ticket=generation.next();message="Подготовка собственного подключения…";render()
        future=worker.submit {
            try {
                fun check(){if(!generation.current(ticket) || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()}
                check();val profile=task(::check);check()
                runOnUiThread {
                    if(!generation.current(ticket)||isFinishing||isDestroyed)return@runOnUiThread
                    try {WarpStore(this).save(profile);message="Подключение сохранено. Сервер: ${profile.endpoint}. Отключите Nova и включите WARP в DPI Pilot."}
                    catch(_: Exception){message="Не удалось безопасно сохранить подключение. Прежний профиль сохранён."}
                    busy=false;enrollment.set(null);render()
                }
            } catch(_: Exception) {runOnUiThread {
                if(generation.current(ticket) && !isFinishing && !isDestroyed){busy=false;enrollment.set(null);message="Подготовка не завершилась. Проверьте доступ к Cloudflare или формат своего профиля. Сохранённый ключ не удалён.";render()}
            }} catch(_: LinkageError){runOnUiThread {if(generation.current(ticket)&&!isFinishing&&!isDestroyed){busy=false;message="Ядро WARP недоступно на устройстве";render()}}}
        }
    }
    private fun cancel(){diagnostic?.cancel();diagnostic=null;generation.next();enrollment.getAndSet(null)?.cancel();runCatching {stream.getAndSet(null)?.close()};future?.cancel(true);future=null;busy=false}
    private fun render(){
        if(!::info.isInitialized)return
        val off=appStatus.first!=AppStatus.Running && !WarpState.active
        local.isEnabled=off&&!busy;warp.isEnabled=off&&!busy&&Build.VERSION.SDK_INT>=24;create.isEnabled=idle();importButton.isEnabled=idle()
        val saved=if(WarpStore(this).exists())"Профиль WARP сохранён" else "Профиль WARP ещё не создан"
        info.text="Режим: ${if(WarpState.selected(this))"бесплатный WARP" else "локальный обход"}\n$saved\n$message\n\n${WarpState.report()}\n\n$diagnosticReport"
    }
    override fun onResume(){super.onResume();handler.post(refresh)}
    override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
    override fun onDestroy(){cancel();worker.shutdownNow();super.onDestroy()}
}
