package io.github.magral.dpipilot.tv

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.*

/** Viewer screen: no strategies or stop controls. Installation tools are behind Menu/long OK. */
class TvActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var setup: Button
    private lateinit var open: Button
    private val prefs get() = TvState.prefs(this)
    private val update = object : Runnable {
        override fun run() {
            setup.visibility = if (prefs.getBoolean("setup_complete",false)) View.GONE else View.VISIBLE
            val enabled = prefs.getBoolean("enabled",false)
            status.text = when {
                !enabled -> "Требуется первоначальная настройка помощником"
                VpnService.prepare(this@TvActivity) != null -> "Помощнику нужно один раз разрешить работу приложения"
                TvState.status.startsWith("Ожидаю сеть") -> "Ожидаем подключение к интернету"
                TvState.vpnEstablished && TvState.verified -> "VPN включён · проверки YouTube пройдены"
                TvState.vpnEstablished -> "VPN включён · подбираем соединение для YouTube"
                TvState.running -> "VPN ещё не создан · запускаем соединение"
                else -> "VPN не запущен · требуется проверка установщиком"
            }
            handler.postDelayed(this,1000)
        }
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!TvState.running) TvState.status = prefs.getString("last_status","")!!
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(56),dp(30),dp(56),dp(30)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(box) })
        fun text(value: String,size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0,dp(12),0,dp(12)); box.addView(this)
        }
        text("YouTube · автоматический помощник",32f).apply {
            isFocusable = true
            setOnLongClickListener { serviceMenu(); true }
        }
        status = text("Запускаемся…",26f)
        text("Приложение само следит за соединением и восстанавливает его.",22f)
        fun button(label: String,click: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 24f; isAllCaps = false; setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.tv_button); isFocusable = true
            box.addView(this,LinearLayout.LayoutParams(-1,dp(72)).apply { topMargin = dp(16) })
            setOnClickListener { click() }
        }
        open = button("Открыть YouTube") { openYouTube() }
        setup = button("Первоначальная настройка — один раз") { configure() }
        if (prefs.getBoolean("setup_complete",false)) { setup.visibility = View.GONE; open.requestFocus() }
        else setup.requestFocus()
        if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) startHelper()
    }
    private fun openYouTube() {
        if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) startHelper()
        val intent = TvVpnService.installed(this).firstNotNullOfOrNull { pkg ->
            packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        }
        if (intent == null) Toast.makeText(this,"YouTube не установлен",Toast.LENGTH_LONG).show() else startActivity(intent)
    }
    private fun startHelper() {
        try { MaintenanceJob.schedule(this); TvVpnService.start(this) }
        catch (e: Exception) {
            TvState.update(this,"Запуск VPN отклонён: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            AlertDialog.Builder(this).setTitle("Не удалось запустить VPN").setMessage(TvState.status)
                .setPositiveButton("Закрыть",null).show()
        }
    }
    private fun configure() {
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission,10) else enable()
    }
    private fun enable() {
        prefs.edit().putBoolean("enabled",true).putBoolean("boot",true).apply()
        startHelper()
        if (Build.VERSION.SDK_INT >= 23 && !(getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) &&
            !prefs.getBoolean("background_prompted",false)) {
            prefs.edit().putBoolean("background_prompted",true).apply()
            requestBackground(); return
        }
        finishSetup()
    }
    private fun finishSetup() {
        prefs.edit().putBoolean("setup_complete",true).apply()
        setup.visibility = View.GONE; open.requestFocus()
    }
    private fun requestBackground() {
        if (Build.VERSION.SDK_INT < 23) { finishSetup(); return }
        try {
            startActivityForResult(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName")),11)
        } catch (_: ActivityNotFoundException) {
            finishSetup()
            Toast.makeText(this,"Разрешите фоновую работу в настройках телевизора, если такая настройка доступна",Toast.LENGTH_LONG).show()
        }
    }
    private fun serviceMenu() {
        val entries = arrayOf("Завершить первоначальную настройку","Проверить соединение","Переподобрать автоматически","Отчёт для установщика","Разрешить фоновую работу","Постоянный VPN Android","Отключить помощник")
        AlertDialog.Builder(this).setTitle("Сервисное меню · DPI Pilot TV ${BuildConfig.VERSION_NAME}")
            .setItems(entries) { _,which -> when (which) {
                0 -> configure()
                1 -> if (prefs.getBoolean("enabled",false)) TvVpnService.start(this)
                2 -> if (prefs.getBoolean("enabled",false)) TvVpnService.start(this,"different")
                3 -> AlertDialog.Builder(this).setTitle("Технический отчёт").setMessage(
                    "DPI Pilot TV ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}\nРазрешение VPN: ${VpnService.prepare(this) == null}; служба: ${TvState.running}; интерфейс VPN: ${TvState.vpnEstablished}; проверки: ${TvState.verified}\nYouTube: ${TvVpnService.installed(this).joinToString().ifEmpty { "не найден" }}\nАвтозапуск: ${prefs.getBoolean("boot",true)}; фоновое восстановление: ${MaintenanceJob.backgroundAllowed(this)}; постоянный VPN: ${TvState.alwaysOn}\n\n"+TvState.report.ifBlank { TvState.status })
                    .setPositiveButton("Закрыть",null).show()
                4 -> requestBackground()
                5 -> try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(this,"Настройка постоянного VPN скрыта прошивкой",Toast.LENGTH_LONG).show() }
                6 -> AlertDialog.Builder(this).setTitle("Отключить автоматическое восстановление?")
                    .setMessage("Если включён постоянный VPN, сначала отключите его в настройках Android.")
                    .setNegativeButton("Оставить включённым",null)
                    .setPositiveButton("Отключить") { _,_ -> if (!TvState.alwaysOn) {
                        prefs.edit().putBoolean("enabled",false).putBoolean("setup_complete",false).apply()
                        MaintenanceJob.cancel(this); TvVpnService.start(this,"stop")
                    } }.show()
            } }.setNegativeButton("Закрыть",null).show()
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { serviceMenu(); return true }
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 10 && resultCode == RESULT_OK) enable()
        if (requestCode == 11) finishSetup()
    }
    override fun onStart() { super.onStart(); handler.post(update) }
    override fun onStop() { handler.removeCallbacks(update); super.onStop() }
}
