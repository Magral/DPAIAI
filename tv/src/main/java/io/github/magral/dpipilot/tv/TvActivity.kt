package io.github.magral.dpipilot.tv

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.widget.*

class TvActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stop: Button
    private val update = object : Runnable {
        override fun run() {
            status.text = TvState.status
            stop.isEnabled = (TvState.running || TvState.prefs(this@TvActivity).getBoolean("enabled",false)) && !TvState.alwaysOn
            handler.postDelayed(this,1000)
        }
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = TvState.prefs(this)
        if (!TvState.running) TvState.status = prefs.getString("last_status","Нажмите «Включить авторежим»")!!
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48),dp(24),dp(48),dp(24)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(box) })
        fun text(value: String,size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0,dp(8),0,dp(8)); box.addView(this)
        }
        text("DPI Pilot TV ${BuildConfig.VERSION_NAME} · YouTube",30f)
        status = text(TvState.status,22f)
        text("Один раз разрешите VPN. При старте, включении экрана и смене сети стратегия проверяется автоматически. Проверка доступности не гарантирует воспроизведение каждого видео.",17f)
        fun button(label: String,click: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 20f; isAllCaps = false; setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.tv_button)
            isFocusable = true
            box.addView(this,LinearLayout.LayoutParams(-1,dp(60)).apply { topMargin = dp(8) })
            setOnClickListener { click() }
        }
        val start = button("Включить авторежим / проверить сейчас") {
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission,10) else enable()
        }
        button("Подобрать другую стратегию") {
            if (TvState.running) TvVpnService.start(this,"different")
            else Toast.makeText(this,"Сначала включите авторежим",Toast.LENGTH_LONG).show()
        }
        button("Открыть YouTube") {
            if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) TvVpnService.start(this)
            val intent = TvVpnService.installed(this).firstNotNullOfOrNull { pkg ->
                packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            }
            if (intent == null) Toast.makeText(this,"Официальный YouTube не установлен",Toast.LENGTH_LONG).show()
            else startActivity(intent)
        }
        Switch(this).apply {
            text = "Автозапуск при включении телевизора"; textSize = 20f; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("boot",true)
            setPadding(dp(12),dp(16),dp(12),dp(16)); box.addView(this)
            setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean("boot",checked).apply() }
        }
        stop = button("Остановить и отключить авторежим") {
            prefs.edit().putBoolean("enabled",false).apply()
            TvVpnService.start(this,"stop")
            TvState.update(this,"Авторежим отключён")
        }
        button("Показать отчёт") {
            AlertDialog.Builder(this).setTitle("YouTube: автопроверки").setMessage(TvState.report.ifBlank { TvState.status })
                .setPositiveButton("Закрыть",null).show()
        }
        button("Настройки VPN Android") {
            try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            catch (_: ActivityNotFoundException) { Toast.makeText(this,"Этот телевизор не предоставляет экран настроек VPN",Toast.LENGTH_LONG).show() }
        }
        text("Для максимальной автономности можно включить постоянный VPN в настройках Android, если телевизор это поддерживает. Не включайте блокировку соединений без VPN: обработка предназначена только для YouTube.",16f)
        start.requestFocus()
        if (prefs.getBoolean("enabled",false) && VpnService.prepare(this) == null) runCatching { TvVpnService.start(this) }
    }
    private fun enable() {
        TvState.prefs(this).edit().putBoolean("enabled",true).apply()
        TvVpnService.start(this)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 10 && resultCode == RESULT_OK) enable()
    }
    override fun onStart() { super.onStart(); handler.post(update) }
    override fun onStop() { handler.removeCallbacks(update); super.onStop() }
}
