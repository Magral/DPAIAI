package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Local profiles. Connectivity probes explicitly use SOCKS: the app itself is VPN-excluded. */
class ProfilesActivity : AppCompatActivity() {
    private val presets = linkedMapOf(
        "Без изменений (контроль)" to "",
        "Разделение TCP" to "--split 1",
        "Изменение порядка TCP" to "--disorder 1",
        "Разделение TLS" to "--tlsrec 1+s",
        "TCP + TLS" to "--disorder 1 --tlsrec 1+s"
    )
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = getPreferences()
        val saved = getSharedPreferences("pilot_profiles", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        fun text(value: String) = TextView(this).apply { text = value; setPadding(0,16,0,8); box.addView(this) }
        fun input(hintText: String, value: String) = EditText(this).apply { hint = hintText; setText(value); box.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; box.addView(this); setOnClickListener { action() } }
        text("DPI Pilot "+BuildConfig.VERSION_NAME).textSize = 24f
        text("Локальная обработка. Внешний IP не меняется. Профили экспериментальные: доступность зависит от сети.")
        val names = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProfilesActivity, android.R.layout.simple_spinner_dropdown_item, presets.keys.toList())
            box.addView(this)
        }
        val args = input("Параметры ByeDPI", prefs.getString("byedpi_cmd_args", "--disorder 1") ?: "")
        button("Взять готовый профиль") { args.setText(presets.values.elementAt(names.selectedItemPosition)) }
        val slot = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProfilesActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Домашний Wi-Fi", "Мобильная сеть", "Резерв"))
            box.addView(this)
        }
        val result = text("Профили переключаются вручную. После применения включите обработку на главном экране.")
        button("Сохранить профиль") {
            saved.edit().putString("slot_"+slot.selectedItemPosition,args.text.toString()).apply()
            result.text = "Профиль сохранён на устройстве"
        }
        button("Загрузить профиль") {
            val value = saved.getString("slot_"+slot.selectedItemPosition,null)
            if (value == null) result.text = "Этот профиль ещё не сохранён" else args.setText(value)
        }
        button("Применить параметры") {
            if (appStatus.first != AppStatus.Halted) result.text = "Сначала выключите обработку на главном экране"
            else {
                prefs.edit().putBoolean("byedpi_enable_cmd_settings",true)
                    .putString("byedpi_cmd_args",args.text.toString())
                    .putString("byedpi_proxy_ip","127.0.0.1").putString("byedpi_proxy_port","1080").apply()
                result.text = "Параметры применены. Можно включить обработку"
            }
        }
        val host = input("Домен для HTTPS-проверки", "www.wikipedia.org")
        val check = Button(this).apply { text = "Проверить HTTPS через ByeDPI"; box.addView(this) }
        check.setOnClickListener {
            val domain = host.text.toString().trim()
            if (!domain.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"))) {
                result.text = "Введите домен без https:// и пути"; return@setOnClickListener
            }
            if (appStatus.first != AppStatus.Running) {
                result.text = "Сначала включите обработку на главном экране"; return@setOnClickListener
            }
            val port = prefs.getString("byedpi_proxy_port","1080")?.toIntOrNull() ?: 1080
            val address = prefs.getString("byedpi_proxy_ip","127.0.0.1") ?: "127.0.0.1"
            check.isEnabled = false
            result.text = "Проверка TLS и ответа HTTPS…"
            lifecycleScope.launch {
                try {
                    result.text = withContext(Dispatchers.IO) { probe(domain,address,port) }
                } finally { check.isEnabled = true }
            }
        }
        text("Проверка подтверждает TLS и ответ веб-сервера, но не воспроизведение видео и не звонки. Сертификат проверяется. Запрос идёт к указанному сайту; отчёты никуда не отправляются.")
    }
    private fun probe(host: String, address: String, port: Int): String {
        val start = System.nanoTime()
        return try {
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(address,port))).use { socket ->
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress.createUnresolved(host,443),5000)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket,host,443,true) as SSLSocket).use { tls ->
                    tls.soTimeout = 5000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.outputStream.write(("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    // Bound header read: never buffer an unbounded server response.
                    val line = StringBuilder()
                    while (line.length < 512) {
                        val next = tls.inputStream.read()
                        if (next < 0 || next == 10) break
                        if (next != 13) line.append(next.toChar())
                    }
                    check(line.startsWith("HTTP/1.")) { "Invalid HTTP response" }
                    "TLS проверен; $line; ${(System.nanoTime()-start)/1_000_000} мс. Ответ сервера не гарантирует доступ к содержимому."
                }
            }
        } catch (e: Exception) {
            "Проверка не пройдена (${e.javaClass.simpleName}). Попробуйте другой профиль или домен."
        }
    }
}
