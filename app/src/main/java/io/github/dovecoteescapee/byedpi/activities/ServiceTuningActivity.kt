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
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            job = lifecycleScope.launch {
                choice.isEnabled = false; run.isEnabled = false; apply.isEnabled = false; cancel.isEnabled = true
                output.text = "${target.name}: проверяем контрольный режим и четыре стратегии…\n"
                val results = mutableListOf<Pair<Int,JSONObject>>()
                try {
                    target.strategies.indices.forEach { index ->
                        output.append("\n${index+1}/${target.strategies.size}: ${target.strategies[index].ifEmpty { "без изменений" }}…")
                        val result = candidate(target.id,index)
                        results.add(index to result)
                        output.append(" ${result.optInt("success")}/${result.optInt("total")} проверок")
                    }
                    // Only fully repeatable results can be saved; never recommend Telegram based on web alone.
                    val eligible = results.filter { (_,r) -> r.optInt("total") > 0 && r.optInt("success") == r.optInt("total") && r.optBoolean("native") }
                    val best = eligible.firstOrNull { it.first == 0 } ?: eligible.minByOrNull { it.second.optLong("elapsed",Long.MAX_VALUE) }
                    if (best == null) output.append("\nУстойчивый результат не найден. Прежняя стратегия сохранена. Если недоступен IP, локальный обход может не помочь.")
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
            return JSONObject(withTimeout(90000) { result.await() })
        } finally {
            withContext(NonCancellable) {
                unbindService(connection)
                if (binder != null && binder!!.isBinderAlive) withTimeout(5000) { died.await() }
            }
        }
    }
}
