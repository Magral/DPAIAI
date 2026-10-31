package io.github.magral.dpipilot.tv

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TvState {
    @Volatile var running = false
    @Volatile var alwaysOn = false
    @Volatile var vpnEstablished = false
    @Volatile var verified = false
    @Volatile var mediaVerified = false
    @Volatile var status = "Нажмите «Включить авторежим»"
    @Volatile var report = ""
    @Volatile var lastBeat = 0L
    @Volatile var engineReady = false
    @Volatile var strategyIndex = -1
    @Volatile var phase = "Остановлено"
    @Volatile var network = "ещё не определена"
    @Volatile var qualityHistory = "История качества ещё не накоплена"
    @Volatile var mediaDetail = "Медиатест не выполнялся"
    @Volatile var trialDetail = "Пробное переключение не выполняется"
    @Volatile var stallDetail = "Признаков зависания нет"
    @Volatile var scanCursor = 0
    @Volatile var probeIndex = -1
    @Volatile var probeDetail = "ещё не выполнялись"
    @Volatile var lastCheckAt = 0L
    @Volatile var nextCheckAt = 0L
    @Volatile var sentBytes = 0L
    @Volatile var receivedBytes = 0L
    @Volatile var sentPackets = 0L
    @Volatile var receivedPackets = 0L
    @Volatile var trafficAt = 0L
    val results = java.util.concurrent.ConcurrentHashMap<Int,String>()
    val resultTimes = java.util.concurrent.ConcurrentHashMap<Int,Long>()
    @Synchronized fun restore(context: Context) {
        if (report.isEmpty()) report = prefs(context).getString("last_report","").orEmpty()
        if (!running) {
            status = prefs(context).getString("last_status","Нажмите «Включить VPN»").orEmpty()
            if (lastCheckAt == 0L) {
                lastCheckAt = prefs(context).getLong("last_check_at",0)
                probeDetail = prefs(context).getString("last_probe","ещё не выполнялись").orEmpty()
            }
        }
    }
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("tv_auto",Context.MODE_PRIVATE)
    fun beat(context: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now-lastBeat >= 30000L || lastBeat == 0L) {
            lastBeat = now
            prefs(context).edit().putLong("heartbeat",now).apply()
        }
    }
    @Synchronized fun update(context: Context,text: String) {
        restore(context)
        beat(context)
        status = text
        val time = SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date())
        report = (report.lines().takeLast(79)+"$time $text").joinToString("\n")
        prefs(context).edit().putString("last_status",text).putString("last_report",report).apply()
    }
}
