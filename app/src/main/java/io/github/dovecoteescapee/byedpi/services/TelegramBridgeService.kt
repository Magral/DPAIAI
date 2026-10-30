package io.github.dovecoteescapee.byedpi.services

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.TelegramBridgeActivity
import io.github.dovecoteescapee.byedpi.utility.TelegramWsBridge
import java.security.SecureRandom

class TelegramBridgeService : Service() {
    private var bridge: TelegramWsBridge? = null
    companion object {
        @Volatile var running = false
        @Volatile var diagnostics = "Режим Telegram пока не запущен"
        private fun append(line: String) { synchronized(TelegramBridgeService::class.java) {
            diagnostics = (diagnostics.lines().takeLast(60)+line).joinToString("\n")
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
                diagnostics = "Telegram WSS: проверка сертификатов включена; только официальные домены Telegram"
                bridge = TelegramWsBridge(bytes,log = ::append).also { it.start() }
                running = true
            } catch (e: Exception) {
                append("Не удалось запустить: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                bridge?.close(); bridge = null; running = false; stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        bridge?.close(); bridge = null; running = false; append("Локальный MTProxy остановлен")
        super.onDestroy()
    }
}
