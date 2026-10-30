package io.github.dovecoteescapee.byedpi.activities

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.services.*
import kotlinx.coroutines.*

class TelegramBridgeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var updater: Job? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        TextView(this).apply { text = "Telegram через WebSocket"; textSize = 24f; box.addView(this) }
        TextView(this).apply {
            text = "Если подбор ByeDPI не помог, попробуйте этот режим. Он передаёт поток через защищённое WebSocket-соединение к официальным серверам Telegram. Root и свой сервер не нужны. Входить в аккаунт здесь не нужно.\n\n1. Выключите обработку ByeDPI и другие VPN.\n2. Запустите режим ниже.\n3. Откройте Telegram кнопкой и подтвердите подключение прокси.\n\nРабота сообщений и файлов зависит от доступности серверов. Звонки этим режимом не обеспечиваются. После остановки отключите этот прокси в Telegram."
            setPadding(0,20,0,20); box.addView(this)
        }
        Button(this).apply {
            text = "1. Запустить режим Telegram"; box.addView(this)
            setOnClickListener {
                if (appStatus.first != AppStatus.Halted) {
                    Toast.makeText(this@TelegramBridgeActivity,"Сначала выключите обработку ByeDPI",Toast.LENGTH_LONG).show()
                } else ContextCompat.startForegroundService(this@TelegramBridgeActivity,Intent(this@TelegramBridgeActivity,TelegramBridgeService::class.java))
            }
        }
        Button(this).apply {
            text = "2. Подключить в Telegram"; box.addView(this)
            setOnClickListener {
                if (!TelegramBridgeService.running) {
                    Toast.makeText(this@TelegramBridgeActivity,"Сначала запустите режим",Toast.LENGTH_SHORT).show()
                } else {
                    val link = Uri.Builder().scheme("tg").authority("proxy").appendQueryParameter("server","127.0.0.1")
                        .appendQueryParameter("port","1443").appendQueryParameter("secret","dd"+TelegramBridgeService.secret(this@TelegramBridgeActivity)).build()
                    try { startActivity(Intent(Intent.ACTION_VIEW,link)) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(this@TelegramBridgeActivity,"Установите Telegram",Toast.LENGTH_SHORT).show() }
                }
            }
        }
        Button(this).apply {
            text = "Остановить"; box.addView(this)
            setOnClickListener { stopService(Intent(this@TelegramBridgeActivity,TelegramBridgeService::class.java)) }
        }
        status = TextView(this).apply { setTextIsSelectable(true); setPadding(0,20,0,20); box.addView(this) }
        Button(this).apply {
            text = "Скопировать диагностику Telegram"; box.addView(this)
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Telegram WSS",TelegramBridgeService.diagnostics))
                Toast.makeText(this@TelegramBridgeActivity,"Скопировано",Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun onStart() {
        super.onStart()
        updater = lifecycleScope.launch { while (isActive) { status.text = TelegramBridgeService.diagnostics; delay(700) } }
    }
    override fun onStop() { updater?.cancel(); super.onStop() }
}
