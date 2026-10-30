package io.github.magral.dpipilot.tv

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TvState {
    @Volatile var running = false
    @Volatile var alwaysOn = false
    @Volatile var status = "Нажмите «Включить авторежим»"
    @Volatile var report = ""
    @Volatile var lastBeat = 0L
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("tv_auto",Context.MODE_PRIVATE)
    fun beat(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now-lastBeat >= 30000L || lastBeat == 0L) {
            lastBeat = now
            prefs(context).edit().putLong("heartbeat",now).apply()
        }
    }
    @Synchronized fun update(context: Context,text: String) {
        beat(context)
        status = text
        val time = SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date())
        report = (report.lines().takeLast(29)+"$time $text").joinToString("\n")
        prefs(context).edit().putString("last_status",text).apply()
    }
}
