package io.github.dovecoteescapee.byedpi.activities

import android.content.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.services.*
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

class ServiceTuningActivity : AppCompatActivity() {
    private var job: Job? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        val choice = Spinner(this).apply {
            adapter = ArrayAdapter(this@ServiceTuningActivity,android.R.layout.simple_spinner_dropdown_item,ServiceTargets.targets.map { it.name })
            box.addView(this)
        }
        val note = TextView(this).apply { setPadding(0,16,0,16); box.addView(this) }
        val run = Button(this).apply { text = "Подобрать стратегию для приложения"; box.addView(this) }
        val cancel = Button(this).apply { text = "Остановить подбор"; isEnabled = false; box.addView(this) }
        val output = TextView(this).apply { setPadding(0,16,0,16); box.addView(this) }
        val apply = Button(this).apply { text = "Применить сохранённый результат"; box.addView(this) }
        val prefs = getPreferences()
        val report = StringBuilder()
        Button(this).apply {
            text = "Скопировать диагностику"
            box.addView(this)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DPI Pilot diagnostics",report.toString()))
                Toast.makeText(this@ServiceTuningActivity,"Диагностика скопирована",Toast.LENGTH_SHORT).show()
            }
        }
        Button(this).apply {
            text = "Другой способ: Telegram через WebSocket"; box.addView(this)
            setOnClickListener { startActivity(Intent(this@ServiceTuningActivity,TelegramBridgeActivity::class.java)) }
        }
        Button(this).apply {
            text = "Подключить свой MTProxy для Telegram"
            box.addView(this)
            setOnClickListener { openTelegramProxy() }
        }
        fun showNote() {
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            note.text = target.note + "\nПодбор отправляет тестовые запросы только к выбранному сервису, без аккаунта. Проверка HTTPS учитывает даже ответы 403/429 как достижимость сервера, а не доступ к контенту."
            val stored = prefs.getString("tuned_"+target.id,null)
            output.text = if (stored == null) "Для этого сервиса пока нет подтверждённого результата" else "Сохранён результат: " + (stored.ifEmpty { "без изменений" })
        }
        choice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { showNote() }
        }
        cancel.setOnClickListener { job?.cancel() }
        run.setOnClickListener {
            if (appStatus.first != AppStatus.Halted) {
                output.text = "Сначала выключите обработку на главном экране"; return@setOnClickListener
            }
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val otherVpn = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) } == true
            if (otherVpn) {
                output.text = "Сейчас активен другой VPN. Отключите его для подбора в вашей сети и повторите проверку."
                return@setOnClickListener
            }
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            report.clear(); report.append("DPI Pilot 0.3.0; target=${target.id}; native probe matrix v2\n")
            job = lifecycleScope.launch {
                choice.isEnabled = false; run.isEnabled = false; apply.isEnabled = false; cancel.isEnabled = true
                output.text = "${target.name}: проверяем контрольный режим и ${target.strategies.size-1} стратегий…\n"
                val results = mutableListOf<Pair<Int,JSONObject>>()
                try {
                    target.strategies.indices.forEach { index ->
                        output.append("\n${index+1}/${target.strategies.size}: ${target.strategies[index].ifEmpty { "без изменений" }}…")
                        val result = try { candidate(target.id,index) }
                            catch (_: TimeoutCancellationException) { JSONObject().put("error","candidate_timeout") }
                        results.add(index to result)
                        if (target.id == "telegram") {
                            output.append(" MTProto: ${result.optInt("nativeSuccess")}/${result.optInt("nativeTotal")}; веб: ${result.optInt("webSuccess")}/${result.optInt("webTotal")}")
                        } else output.append(" ${result.optInt("success")}/${result.optInt("total")} проверок")
                        report.append("\nStrategy: ${target.strategies[index].ifEmpty { "control" }}\n$result\n")
                    }
                    // Only fully repeatable results can be saved; never recommend Telegram based on web alone.
                    val eligible = results.filter { (_,r) -> TuningScore.eligible(target.id,r.optInt("success"),r.optInt("total"),r.optInt("nativeSuccess"),r.optInt("nativeTotal")) }
                    val best = eligible.firstOrNull { it.first == 0 } ?: eligible.minByOrNull { it.second.optLong("elapsed",Long.MAX_VALUE) }
                    if (best == null) {
                        output.append(if (target.id == "telegram") "\nНе все дата-центры отвечают устойчиво. Не считаем доступ к сайту работающим Telegram. Скопируйте диагностику. Если IP блокируется целиком, понадобится MTProxy или VPN. Прежние настройки не изменены."
                            else "\nУстойчивый результат не найден; прежние настройки не изменены.")
                    }
                    else {
                        prefs.edit().putString("tuned_"+target.id,target.strategies[best.first]).apply()
                        output.append("\nСохранён результат для ${target.name}: ${target.strategies[best.first].ifEmpty { "без изменений — контроль тоже работает" }}.\nТеперь можно применить его и проверить само приложение. Это измерения в текущей сети.")
                    }
                } catch (_: CancellationException) { output.append("\nПодбор остановлен; действующие настройки не изменены.") }
                catch (_: Exception) { output.append("\nПроверка прервана; действующие настройки не изменены.") }
                finally { choice.isEnabled = true; run.isEnabled = true; apply.isEnabled = true; cancel.isEnabled = false }
            }
        }
        apply.setOnClickListener {
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            val args = prefs.getString("tuned_"+target.id,null)
            if (appStatus.first != AppStatus.Halted) output.text = "Сначала выключите обработку"
            else if (args == null) output.text = "Сначала выполните подбор для ${target.name}"
            else {
                val knownPackages = when (target.id) {
                    "youtube" -> setOf("com.google.android.youtube", "com.google.android.youtube.tv")
                    "instagram" -> setOf("com.instagram.android", "com.instagram.lite")
                    else -> setOf("org.telegram.messenger", "org.telegram.messenger.web")
                }
                val selectedApps = TargetApps.installed(this).map { it.packageName }.filter { it in knownPackages }.toSet()
                prefs.edit().putStringSet(TargetApps.KEY,selectedApps)
                    .putBoolean("byedpi_enable_cmd_settings",true).putString("byedpi_cmd_args",args)
                    .putString("byedpi_proxy_ip","127.0.0.1").putString("byedpi_proxy_port","1080").putString("byedpi_mode","vpn").apply()
                output.text = if (selectedApps.isNotEmpty()) "Применён результат для ${target.name}; установленное приложение уже выбрано. Вернитесь на главный экран и включите обработку. Одновременно действует одна стратегия."
                    else "Стратегия сохранена, но стандартный клиент ${target.name} не установлен. Выберите нужный клиент на главном экране перед включением."
            }
        }
    }
    private fun openTelegramProxy() {
        val input = EditText(this).apply { hint = "tg://proxy?... или https://t.me/proxy?..."; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ваш MTProxy")
            .setMessage("Вставьте ссылку доверенного прокси. Telegram запросит подтверждение подключения. Секрет не сохраняется и не включается в диагностику. Прокси может не поддерживать звонки.")
            .setView(input).setPositiveButton("Открыть в Telegram",null).setNegativeButton(android.R.string.cancel,null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val uri = android.net.Uri.parse(input.text.toString().trim())
            val validLink = (uri.scheme == "tg" && uri.host == "proxy") || (uri.scheme == "https" && uri.host == "t.me" && uri.path == "/proxy")
            if (!validLink || !uri.isHierarchical) {
                input.error = "Нужна ссылка вида tg://proxy?..."
                return@setOnClickListener
            }
            val server = uri.getQueryParameter("server")
            val port = uri.getQueryParameter("port")?.toIntOrNull()
            val secret = uri.getQueryParameter("secret")
            if (!validLink || server.isNullOrBlank() || port == null || port !in 1..65535 || secret.isNullOrBlank()) {
                input.error = "Нужна ссылка MTProxy с server, port и secret"
            } else {
                val safeUri = android.net.Uri.Builder().scheme("tg").authority("proxy")
                    .appendQueryParameter("server",server).appendQueryParameter("port",port.toString()).appendQueryParameter("secret",secret).build()
                try { startActivity(Intent(Intent.ACTION_VIEW,safeUri)); dialog.dismiss() }
                catch (_: ActivityNotFoundException) { input.error = "Установите Telegram" }
            }
        } }
        dialog.show()
    }
    override fun onStop() { job?.cancel(); super.onStop() }

    private suspend fun candidate(target: String,index: Int): JSONObject {
        val connected = CompletableDeferred<IBinder>()
        val result = CompletableDeferred<String>()
        val died = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val callback = Messenger(Handler(Looper.getMainLooper()) { msg ->
            result.complete(msg.data.getString("result") ?: "{}"); true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                try { service.linkToDeath({ died.complete(Unit) },0); connected.complete(service) }
                catch (e: RemoteException) { connected.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                died.complete(Unit); result.completeExceptionally(IllegalStateException("Worker stopped"))
            }
        }
        val bound = bindService(Intent(this,TuningWorkerService::class.java),connection,BIND_AUTO_CREATE)
        check(bound)
        try {
            val remote = Messenger(withTimeout(5000) { connected.await() })
            remote.send(Message.obtain().apply {
                replyTo = callback
                data = Bundle().apply { putString("target",target); putInt("candidate",index) }
            })
            return JSONObject(withTimeout(if (target == "telegram") 120000L else 90000L) { result.await() })
        } finally {
            withContext(NonCancellable) {
                unbindService(connection)
                if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
            }
        }
    }
}
