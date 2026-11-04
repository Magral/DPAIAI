package io.github.dovecoteescapee.byedpi.activities

import android.content.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.services.*
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.catalog.*
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.*
import org.json.JSONObject

class ServiceTuningActivity : AppCompatActivity() {
    private val exportProfile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getPreferences(); val plan = MobilePlan.saved(prefs)
            val text = ProfileWire.encode(listOf("instagram","youtube").associateWith { MobilePlan.command(plan,it) })
            val success = runCatching { contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(text) } }.isSuccess
            withContext(Dispatchers.Main) { Toast.makeText(this@ServiceTuningActivity,if (success) "Профиль сохранён" else "Не удалось сохранить профиль",Toast.LENGTH_LONG).show() }
        }
    }
    private val importProfile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val commands = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream();val buffer = ByteArray(1024)
                        while (true) { val n = input.read(buffer);if (n < 0) break;require(out.size()+n <= 16384);out.write(buffer,0,n) }
                        ProfileWire.decode(out.toString("UTF-8"))
                    }
                }
                androidx.appcompat.app.AlertDialog.Builder(this@ServiceTuningActivity).setTitle("Профиль для проверки")
                    .setMessage(commands.entries.joinToString("\n\n") { "${it.key}: ${it.value.ifEmpty { "контроль" }}" }+"\n\nЭто локальный файл. Команды будут испытаны перед сохранением в рабочий резерв.")
                    .setPositiveButton("Добавить к подбору") { _,_ ->
                        val edit = getPreferences().edit();commands.forEach { (id,command) -> edit.putString("mobile_imported.$id",command) };edit.apply()
                        commands.keys.forEach { MobileFeedback.request(it) }
                        Toast.makeText(this@ServiceTuningActivity,"Профиль добавлен. Включите VPN для испытания.",Toast.LENGTH_LONG).show()
                    }.setNegativeButton("Отмена",null).show()
            } catch (_: Exception) { Toast.makeText(this@ServiceTuningActivity,"Файл повреждён или несовместим с движком",Toast.LENGTH_LONG).show() }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var base: TextView
    private val refresh = object : Runnable {
        override fun run() {
            val profile = MobileStrategyPolicy.profile(this@ServiceTuningActivity)
            val prefs = getPreferences()
            val next = ServiceTargets.individual.joinToString("\n") { t ->
                val started = prefs.getLong("mobile_scan.${t.id}.$profile",0)
                val remaining = MobileScanPolicy.remaining(started,System.currentTimeMillis())
                "${t.name}: ${if (!prefs.getBoolean("mobile_auto_three_days",true)) "по требованию" else if (remaining == 0L) "проверка доступна" else "следующий подбор через ${remaining/3600000} ч"}"
            }
            base.text = "DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME} · База ${RuntimeCatalog.commands.size}\n${MobileCatalogUpdater.status}\n${MobileStrategyPolicy.state}\n$next\n${MobileNetwork.summary(this@ServiceTuningActivity)}"
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        MobileCatalogUpdater.load(this);CatalogUpdateJob.schedule(this)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        base = TextView(this).apply { setPadding(0,12,0,16);box.addView(this) }
        fun button(label: String,action: ()->Unit) { Button(this).apply { text=label;isAllCaps=false;box.addView(this);setOnClickListener { action() } } }
        Switch(this).apply {
            text="Автоподбор раз в 3 дня";isChecked=getPreferences().getBoolean("mobile_auto_three_days",true);box.addView(this)
            setOnCheckedChangeListener { _,enabled -> getPreferences().edit().putBoolean("mobile_auto_three_days",enabled).apply() }
        }
        TextView(this).apply { text="Instagram и YouTube работают вместе. В рабочих — только полный тест: Instagram 6/6, YouTube 8/8. Узлы одной стратегии проверяются параллельно; при первом отказе оставшиеся тесты прекращаются. Задержка сортируется только среди полных результатов.";setPadding(0,16,0,16);box.addView(this) }
        button("Проверить Instagram + YouTube сейчас") {
            if (appStatus.first == AppStatus.Halted) Toast.makeText(this,"Включите VPN на главном экране; проверка выполняется в фоне",Toast.LENGTH_LONG).show()
            else { ServiceTargets.individual.forEach { MobileFeedback.request(it.id) };Toast.makeText(this,"Подбор запрошен; окно можно закрыть",Toast.LENGTH_LONG).show() }
        }
        button("Остановить текущий подбор") { MobileFeedback.cancelScans(this);Toast.makeText(this,"Подбор остановлен; прежние маршруты сохраняются",Toast.LENGTH_LONG).show() }
        ServiceTargets.individual.forEach { target -> button("${target.name}: рабочие стратегии по задержке") { choose(target) } }
        button("Обновить базу стратегий") { lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity,true) } }
        button("Источники и состояние базы") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(RuntimeCatalog.version).setMessage(RuntimeCatalog.sources.joinToString("\n\n")).setPositiveButton("Закрыть",null).show()
        }
        button("Сохранить профиль в файл") { exportProfile.launch("DPI-Pilot-profile.json") }
        button("Импорт профиля с ТВ / другого устройства") { importProfile.launch(arrayOf("application/json","text/plain")) }
        button("Скопировать диагностику") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot",base.text.toString()+"\n"+MobileStrategyPolicy.lastProbe+"\n"+MobileFeedback.traffic+"\n"+MobileHistory.summary(this)))
            Toast.makeText(this,"Диагностика скопирована",Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity) }
    }
    private fun choose(target: ServiceTargets.Target) {
        val profile = MobileStrategyPolicy.profile(this)
        val store = MobileHistory.store(this,target.id,profile)
        val choices = store.choices()
        val excluded = store.excludedIds().size
        val title = "${target.name} · полные результаты · исключено $excluded"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
            .setNeutralButton("Сбросить исключения") { _,_ ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Разрешить повторные тесты?")
                    .setMessage("Только для ${target.name} в этой сети. Сброс сам не запускает проверки и не делает неудачные варианты рабочими.")
                    .setPositiveButton("Сбросить") { _,_ -> store.resetExclusions();Toast.makeText(this,"Исключения сброшены; можно запросить проверку",Toast.LENGTH_LONG).show() }
                    .setNegativeButton("Отмена",null).show()
            }.setNegativeButton("Закрыть",null)
        if (choices.isEmpty()) dialog.setMessage("Нет подтверждённых полных результатов. Старые записи требуют новой проверки. Включите VPN и нажмите «Проверить Instagram + YouTube сейчас».")
        else dialog.setItems(choices.map { row -> "${row.optLong("latencyMs")} мс · ${row.optInt("passed")}/${row.optInt("total")} · ${io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol.id(row.optString("command"))}" }.toTypedArray()) { _,index ->
            val row = choices[index];val command = row.getString("command")
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(target.name)
                .setMessage("Последняя проверка ${row.optInt("passed")}/${row.optInt("total")}; задержка HTTPS ${row.optLong("latencyMs")} мс\n\n${command.ifEmpty { "Контроль без изменений" }}\n\nПеред применением — повторная проверка. Другой сервис продолжит работу.")
                .setPositiveButton("Проверить и применить") { _,_ ->
                    getPreferences().edit().putString("mobile_selected.${target.id}",JSONObject().put("command",command).put("profile",profile).toString()).apply()
                    MobileFeedback.request(target.id)
                    Toast.makeText(this,if (appStatus.first == AppStatus.Halted) "Выбор сохранён; включите VPN" else "Выбранная стратегия проверяется в фоне",Toast.LENGTH_LONG).show()
                }.setNegativeButton("Назад") { _,_ -> choose(target) }.show()
        }
        dialog.show()
    }
    override fun onStart() { super.onStart();handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh);super.onStop() }
}
