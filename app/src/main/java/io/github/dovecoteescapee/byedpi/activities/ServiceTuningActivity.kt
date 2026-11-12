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
                    .setMessage(commands.entries.joinToString("\n") { "${ServiceTargets.target(it.key).name}: ${MobilePresentation.strategy(it.value)}" }+"\n\nПеред применением стратегии пройдут проверку.")
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
            base.text = if (appStatus.first == AppStatus.Halted) "Включите защиту на главном экране" else MobilePresentation.summary()+"\n\n$next"
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        MobileCatalogUpdater.load(this);CatalogUpdateJob.schedule(this)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        title = "Подбор стратегий"
        base = TextView(this).apply { textSize=18f;setPadding(0,12,0,16);box.addView(this) }
        fun button(label: String,action: ()->Unit) { Button(this).apply { text=label;isAllCaps=false;box.addView(this);setOnClickListener { action() } } }
        Switch(this).apply {
            text="Автоподбор раз в 3 дня";isChecked=getPreferences().getBoolean("mobile_auto_three_days",true);box.addView(this)
            setOnCheckedChangeListener { _,enabled -> getPreferences().edit().putBoolean("mobile_auto_three_days",enabled).apply() }
        }
        TextView(this).apply { text="Годна только стратегия, прошедшая все проверки: YouTube — 8 из 8, Instagram — 6 из 6.";setPadding(0,16,0,16);box.addView(this) }
        button("Проверить сейчас") {
            if (appStatus.first == AppStatus.Halted) Toast.makeText(this,"Включите VPN на главном экране; проверка выполняется в фоне",Toast.LENGTH_LONG).show()
            else { ServiceTargets.individual.forEach { MobileFeedback.request(it.id) };Toast.makeText(this,"Подбор запрошен; окно можно закрыть",Toast.LENGTH_LONG).show() }
        }
        button("Остановить подбор") { MobileFeedback.cancelScans(this);Toast.makeText(this,"Подбор остановлен",Toast.LENGTH_LONG).show() }
        ServiceTargets.individual.forEach { target -> button("${target.name}: годные стратегии") { choose(target) } }
        button("Дополнительно") { advanced() }
        lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity) }
    }
    private fun choose(target: ServiceTargets.Target) {
        val profile = MobileStrategyPolicy.profile(this)
        val store = MobileHistory.store(this,target.id,profile)
        val choices = store.choices()
        val title = "${target.name}: годные стратегии"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
            .setNeutralButton("Сбросить исключения") { _,_ ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Разрешить повторные тесты?")
                    .setMessage("Только для ${target.name} в этой сети. Сброс сам не запускает проверки и не делает неудачные варианты рабочими.")
                    .setPositiveButton("Сбросить") { _,_ -> store.resetExclusions();Toast.makeText(this,"Исключения сброшены; можно запросить проверку",Toast.LENGTH_LONG).show() }
                    .setNegativeButton("Отмена",null).show()
            }.setNegativeButton("Закрыть",null)
        if (choices.isEmpty()) dialog.setMessage("Нет подтверждённых полных результатов. Старые записи требуют новой проверки. Включите VPN и нажмите «Проверить Instagram + YouTube сейчас».")
        else dialog.setItems(choices.map { row -> "${MobilePresentation.strategy(row.optString("command"))} · ${row.optInt("passed")} из ${row.optInt("total")}" }.toTypedArray()) { _,index ->
            val row = choices[index];val command = row.getString("command")
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(target.name)
                .setMessage("${MobilePresentation.strategy(command)}\nПройдено ${row.optInt("passed")} из ${row.optInt("total")}\n\nПроверить и применить эту стратегию?")
                .setPositiveButton("Проверить и применить") { _,_ ->
                    getPreferences().edit().putString("mobile_selected.${target.id}",JSONObject().put("command",command).put("profile",profile).toString()).apply()
                    MobileFeedback.request(target.id)
                    Toast.makeText(this,if (appStatus.first == AppStatus.Halted) "Выбор сохранён; включите VPN" else "Выбранная стратегия проверяется в фоне",Toast.LENGTH_LONG).show()
                }.setNegativeButton("Назад") { _,_ -> choose(target) }.show()
        }
        dialog.show()
    }
    private fun advanced() {
        val items = arrayOf("Обновить базу","Источники базы","Сохранить профиль","Импорт профиля с ТВ","Скопировать диагностику")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Дополнительно").setItems(items) { _,index ->
            when (index) {
                0 -> lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity,true) }
                1 -> androidx.appcompat.app.AlertDialog.Builder(this).setTitle(RuntimeCatalog.version)
                    .setMessage(MobileCatalogUpdater.status+"\n\n"+RuntimeCatalog.sources.joinToString("\n\n")).setPositiveButton("Закрыть",null).show()
                2 -> exportProfile.launch("DPI-Pilot-profile.json")
                3 -> importProfile.launch(arrayOf("application/json","text/plain"))
                4 -> {
                    val detail = "DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME}\n"+
                        MobileStrategyPolicy.state+"\n"+MobileNetwork.summary(this)+"\n"+MobileStrategyPolicy.lastProbe+"\n"+
                        MobileFeedback.traffic+"\n"+MobileHistory.summary(this)
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot",detail))
                    Toast.makeText(this,"Диагностика скопирована",Toast.LENGTH_SHORT).show()
                }
            }
        }.setNegativeButton("Закрыть",null).show()
    }
    override fun onStart() { super.onStart();handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh);super.onStop() }
}
