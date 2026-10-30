package io.github.magral.dpipilot.tv

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remote-friendly control panel; autonomous service does not depend on this activity. */
class TvActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var journal: TextView
    private lateinit var toggle: Button
    private lateinit var open: Button
    private lateinit var check: Button
    private lateinit var reselect: Button
    private val prefs get() = TvState.prefs(this)
    private var renderedReport = ""
    private val update = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this,1000)
        }
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        TvState.restore(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36),dp(20),dp(36),dp(30))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(box) })
        fun text(value: String,size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(dp(8),dp(10),dp(8),dp(10)); box.addView(this)
        }
        text("DPI Pilot TV ${BuildConfig.VERSION_NAME} · YouTube",30f).apply {
            isFocusable = true; setOnLongClickListener { serviceMenu(); true }
        }
        status = text("Состояние VPN…",25f)
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            box.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
        fun button(parent: LinearLayout,label: String,click: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 21f; isAllCaps = false; setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.tv_button); isFocusable = true
            setPadding(dp(10),dp(8),dp(10),dp(8)); minimumHeight = dp(68)
            parent.addView(this,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(6),dp(4),dp(6)) })
            setOnClickListener { click() }
        }
        val primary = row()
        toggle = button(primary,"Включить VPN") {
            if (prefs.getBoolean("enabled",false)) disable() else configure()
        }
        open = button(primary,"Открыть YouTube") { openYouTube() }
        val actions = row()
        check = button(actions,"Проверить сейчас") { requestCheck(false) }
        reselect = button(actions,"Подобрать заново") { requestCheck(true) }
        button(actions,"Полный отчёт") { showReport() }
        details = text("",21f).apply { isFocusable = true }
        val boot = Switch(this).apply {
            text = "Автозапуск при загрузке приставки"; textSize = 22f; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("boot",true); isFocusable = true
            setPadding(dp(8),dp(16),dp(8),dp(16))
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("boot",enabled).apply()
                if (prefs.getBoolean("enabled",false)) MaintenanceJob.schedule(this@TvActivity)
                TvState.update(this@TvActivity,if (enabled) "Автозапуск при загрузке включён" else "Автозапуск при загрузке выключен; текущая служба продолжает работу")
            }
        }
        box.addView(boot,LinearLayout.LayoutParams(-1,-2))
        val settings = row()
        button(settings,"Фоновая работа") { requestBackground() }
        button(settings,"Настройки VPN") { vpnSettings() }
        text("После первого разрешения VPN служба работает без открытого окна. Для автозапуска оставьте VPN включённым. После явного выключения он останется выключенным и при перезагрузке. Системный постоянный VPN настраивается отдельно.",18f)
        text("Журнал событий · сохраняется после перезапуска",23f)
        journal = text("",18f).apply { typeface = Typeface.MONOSPACE; isFocusable = true }
        if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) startHelper()
        if (prefs.getBoolean("enabled",false)) open.requestFocus() else toggle.requestFocus()
        render()
    }
    private fun time(value: Long): String = if (value <= 0L) "ещё не было" else
        SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(Date(value))
    private fun bytes(value: Long): String = when {
        value >= 1024*1024 -> String.format(Locale.getDefault(),"%.2f МБ",value/1048576.0)
        value >= 1024 -> String.format(Locale.getDefault(),"%.1f КБ",value/1024.0)
        else -> "$value Б"
    }
    private fun information(full: Boolean = false): String {
        val index = TvState.strategyIndex.takeIf { it in TvPolicy.strategies.indices }
        val command = index?.let { TvPolicy.strategies[it].ifEmpty { "Без модификаций (контроль)" } } ?: "Нет активной стратегии"
        val remaining = ((TvState.nextCheckAt-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0)
        val next = if (TvState.nextCheckAt > 0) "через ${remaining/60} мин ${remaining%60} с" else "выполняется / не назначена"
        val saved = (TvPolicy.indexOfId(prefs.getString("good_id",null)) ?: prefs.getInt("good",-1).takeIf { it in 0..7 })?.let { "№${it+1}" } ?: "нет"
        val boot = when {
            !prefs.getBoolean("boot",true) -> "выключен"
            !prefs.getBoolean("enabled",false) -> "разрешён, но VPN вручную выключен"
            else -> "включён"
        }
        val recent = TvState.results.entries.sortedByDescending { TvState.resultTimes[it.key] ?: 0L }
            .let { if (full) it else it.take(8) }.sortedBy { it.key }
        val resultText = recent.joinToString("\n") { "№${it.key+1}: ${if (full) it.value else it.value.take(260)}" }.ifEmpty { "ещё нет" }
        return "База: ${TvPolicy.strategies.size} вариантов · ${StrategyCatalog.version}\n${StrategyCatalog.engine}\nЭтап: ${TvState.phase}\n${TvState.status}\n\n"+
            "Стратегия: ${index?.let { "№${it+1} из ${TvPolicy.strategies.size}" } ?: "не запущена"}\n$command\nИсточник: ${index?.let { StrategyCatalog.origins[it] } ?: "нет"}\nПозиция расширенного подбора: ${TvState.scanCursor+1}/${TvPolicy.strategies.size} (блок до 12)\n"+
            "Диагностика стратегии ${if (TvState.probeIndex >= 0) "№${TvState.probeIndex+1}" else "(предыдущий запуск)"}: ${TvState.probeDetail}\nСледующая проверка: $next\n"+
            "Последняя проверка: ${time(TvState.lastCheckAt)}\nПоследний успех: ${time(prefs.getLong("last_success",0))}; сохранено: $saved\n\n"+
            "Служба: ${if (TvState.running) "работает" else "остановлена"}; движок: ${if (TvState.engineReady) "готов" else "не готов"}\n"+
            "Интерфейс VPN: ${if (TvState.vpnEstablished) "создан" else "не создан"}; разрешение: ${if (VpnService.prepare(this) == null) "есть" else "нужно подтвердить"}\n"+
            "Сеть: ${TvState.network}\n"+
            "Трафик текущего туннеля: отправлено ${bytes(TvState.sentBytes)} (${TvState.sentPackets} пак.); получено ${bytes(TvState.receivedBytes)} (${TvState.receivedPackets} пак.)\n"+
            "Последнее измерение трафика: ${time(TvState.trafficAt)}\n\n"+
            "Автозапуск: $boot\nСобытие загрузки Android: ${time(prefs.getLong("boot_event_at",0))}\n"+
            "Последний запуск службы: ${time(prefs.getLong("service_start_at",0))}; ${prefs.getString("service_start_reason","нет")}\n"+
            "Фоновое восстановление: ${if (MaintenanceJob.backgroundAllowed(this)) "разрешено" else "нужно разрешить фоновую работу"}; постоянный VPN: ${TvState.alwaysOn}\n"+
            "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"+
            "YouTube: ${TvVpnService.installed(this).joinToString().ifEmpty { "официальный клиент не найден" }}\n\n"+
            "${if (full) "Все результаты за запуск" else "Последние 8 результатов (все — в полном отчёте)"}:\n$resultText\n\n"+
            "Успешные проверки узлов не гарантируют воспроизведение каждого ролика. Счётчики относятся к YouTube в текущем туннеле; сбрасываются при смене стратегии."
    }
    private fun render() {
        val enabled = prefs.getBoolean("enabled",false)
        status.text = when {
            !enabled -> "VPN выключен"
            VpnService.prepare(this) != null -> "Нужно подтвердить разрешение VPN"
            TvState.vpnEstablished && TvState.verified -> "VPN включён · проверки YouTube пройдены"
            TvState.vpnEstablished -> "VPN включён · доступ YouTube проверяется"
            TvState.running -> "VPN ещё не создан · ${TvState.phase}"
            else -> "VPN не запущен · смотрите причину ниже"
        }
        status.setTextColor(if (TvState.vpnEstablished && TvState.verified) Color.rgb(121,225,165) else Color.rgb(255,211,133))
        toggle.text = if (enabled) "Выключить VPN" else "Включить VPN"
        check.isEnabled = enabled; reselect.isEnabled = enabled
        details.text = information()
        if (renderedReport != TvState.report) {
            renderedReport = TvState.report
            journal.text = renderedReport.ifBlank { "Событий пока нет" }
        }
    }
    private fun openYouTube() {
        if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) startHelper()
        val intent = TvVpnService.installed(this).firstNotNullOfOrNull { pkg ->
            packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        }
        if (intent == null) Toast.makeText(this,"Официальный YouTube не установлен",Toast.LENGTH_LONG).show() else startActivity(intent)
    }
    private fun startHelper(action: String = "check") {
        try { MaintenanceJob.schedule(this); TvVpnService.start(this,action) }
        catch (e: Exception) {
            TvState.phase = "Ошибка запуска"
            TvState.update(this,"Запуск VPN отклонён: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            AlertDialog.Builder(this).setTitle("Не удалось запустить VPN").setMessage(TvState.status)
                .setPositiveButton("Закрыть",null).show()
        }
    }
    private fun requestCheck(different: Boolean) {
        if (VpnService.prepare(this) != null) { configure(); return }
        TvState.update(this,if (different) "Запрошен ручной переподбор" else "Запрошена проверка сейчас")
        startHelper(if (different) "different" else "check")
    }
    private fun configure() {
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission,10) else enable()
    }
    private fun enable() {
        // Preserve an explicitly disabled boot preference; it defaults to true at installation.
        prefs.edit().putBoolean("enabled",true).apply()
        startHelper("manual")
        if (Build.VERSION.SDK_INT >= 23 && !(getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) &&
            !prefs.getBoolean("background_prompted",false)) {
            prefs.edit().putBoolean("background_prompted",true).apply(); requestBackground()
        }
    }
    private fun disable() {
        if (TvState.alwaysOn) {
            AlertDialog.Builder(this).setTitle("Включён постоянный VPN Android")
                .setMessage("Сначала отключите постоянный VPN в системных настройках, иначе Android снова запустит службу.")
                .setPositiveButton("Настройки VPN") { _,_ -> vpnSettings() }.setNegativeButton("Закрыть",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Выключить VPN?")
            .setMessage("Автоматическое восстановление и запуск при следующей загрузке остановятся до нажатия «Включить VPN».")
            .setNegativeButton("Отмена",null).setPositiveButton("Выключить") { _,_ ->
                prefs.edit().putBoolean("enabled",false).apply()
                MaintenanceJob.cancel(this)
                TvState.phase = "Остановка"
                TvState.update(this,"VPN выключен пользователем; автоматический запуск приостановлен")
                // Stop the existing service without starting a new foreground service just to stop it.
                if (!stopService(Intent(this,TvVpnService::class.java))) TvState.phase = "Остановлено"
                render()
            }.show()
    }
    private fun requestBackground() {
        if (Build.VERSION.SDK_INT < 23) return
        try { startActivityForResult(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName")),11) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(this,"Разрешите фоновую работу в настройках приставки, если такой пункт доступен",Toast.LENGTH_LONG).show()
        }
    }
    private fun vpnSettings() {
        try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this,"Настройки VPN скрыты прошивкой",Toast.LENGTH_LONG).show() }
    }
    private fun showReport() {
        val report = "DPI Pilot TV ${BuildConfig.VERSION_NAME}\n${information(true)}\n\nЖурнал:\n${TvState.report}"
        AlertDialog.Builder(this).setTitle("Полный отчёт").setMessage(report)
            .setNeutralButton("Скопировать") { _,_ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot TV",report))
                Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show()
            }.setPositiveButton("Закрыть",null).show()
    }
    private fun serviceMenu() {
        AlertDialog.Builder(this).setTitle("Действия")
            .setItems(arrayOf("Полный отчёт","Разрешение VPN / включить","Фоновая работа","Настройки VPN","Источники базы стратегий")) { _,which ->
                when (which) { 0 -> showReport(); 1 -> configure(); 2 -> requestBackground(); 3 -> vpnSettings(); 4 -> AlertDialog.Builder(this).setTitle("Источники · ${StrategyCatalog.version}").setMessage(StrategyCatalog.sources.joinToString("\n\n")).setPositiveButton("Закрыть",null).show() }
            }.setNegativeButton("Закрыть",null).show()
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { serviceMenu(); return true }
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 10) {
            if (resultCode == RESULT_OK) enable() else TvState.update(this,"Пользователь не подтвердил разрешение VPN")
        }
        if (requestCode == 11) TvState.update(this,"Настройки фоновой работы закрыты; восстановление разрешено: ${MaintenanceJob.backgroundAllowed(this)}")
    }
    override fun onStart() { super.onStart(); handler.post(update) }
    override fun onStop() { handler.removeCallbacks(update); super.onStop() }
}
