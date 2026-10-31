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
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var base: TextView
    private val refreshUi = object : Runnable {
        override fun run() {
            base.text = "DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME} · База ${RuntimeCatalog.commands.size} · ${RuntimeCatalog.version}\n${MobileCatalogUpdater.status}\n${MobileStrategyPolicy.state}"
            handler.postDelayed(this,2000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        MobileCatalogUpdater.load(this); CatalogUpdateJob.schedule(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        base = TextView(this).apply { setPadding(0,10,0,16); box.addView(this) }
        Button(this).apply {
            text = "Обновить базу стратегий"; box.addView(this)
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity,true) } }
        }
        val choice = Spinner(this).apply {
            adapter = ArrayAdapter(this@ServiceTuningActivity,android.R.layout.simple_spinner_dropdown_item,ServiceTargets.targets.map { it.name }); box.addView(this)
        }
        val prefs = getPreferences()
        choice.setSelection(ServiceTargets.targets.indexOfFirst { it.id == prefs.getString("mobile_target","instagram") }.coerceAtLeast(0))
        val note = TextView(this).apply { setPadding(0,16,0,16); box.addView(this) }
        val run = Button(this).apply { text = "Подобрать / продолжить следующий блок"; box.addView(this) }
        val cancel = Button(this).apply { text = "Остановить подбор"; isEnabled = false; box.addView(this) }
        val output = TextView(this).apply { setPadding(0,16,0,16); box.addView(this) }
        val apply = Button(this).apply { text = "Применить сохранённый результат"; box.addView(this) }
        val report = StringBuilder()
        Button(this).apply {
            text = "Скопировать диагностику"; box.addView(this)
            setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot",base.text.toString()+"\n"+report+"\n"+MobileStrategyPolicy.lastProbe)); Toast.makeText(this@ServiceTuningActivity,"Диагностика скопирована",Toast.LENGTH_SHORT).show() }
        }
        fun showNote() {
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            note.text = target.note+"\nПодбор — до 12 вариантов за блок. Можно продолжить со следующей позиции; сохранённый вариант остаётся в приоритете. Одновременно действует одна стратегия выбранного сервиса."
            val stored = prefs.getString("tuned_"+target.id,null)
            output.text = stored?.let { "Сохранён результат: "+it.ifEmpty { "без изменений" } } ?: "Подтверждённых транспортных проверок пока нет"
        }
        choice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?,view: android.view.View?,position: Int,id: Long) { showNote() }
        }
        cancel.setOnClickListener { job?.cancel() }
        run.setOnClickListener {
            if (appStatus.first != AppStatus.Halted) { output.text = "Сначала выключите обработку на главном экране. После включения VPN контроль будет работать в фоне."; return@setOnClickListener }
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) } == true) { output.text = "Для подбора в вашей сети отключите другой VPN"; return@setOnClickListener }
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            val commands = MobileStrategyPolicy.window(this,target)
            val profile = MobileStrategyPolicy.profile(this)
            report.clear(); report.append("DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME}; target=${target.id}; base=${RuntimeCatalog.version}; engine=17.3\n")
            job = lifecycleScope.launch {
                choice.isEnabled = false; run.isEnabled = false; apply.isEnabled = false; cancel.isEnabled = true
                output.text = "${target.name}: блок до ${commands.size} вариантов…\n"
                val results = mutableListOf<Pair<String,JSONObject>>()
                try {
                    for ((index,command) in commands.withIndex()) {
                        output.append("\n${index+1}/${commands.size}: ${command.ifEmpty { "контроль" }}…")
                        val result = try { MobileCandidate.run(this@ServiceTuningActivity,target.id,command) }
                            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject().put("error","timeout") }
                        if (MobileStrategyPolicy.profile(this@ServiceTuningActivity) != profile) error("Сеть изменилась")
                        results.add(command to result); MobileStrategyPolicy.advance(this@ServiceTuningActivity,target.id,command)
                        output.append(" узлы ${result.optInt("success")}/${result.optInt("total")}; публичные ответы ${result.optInt("publicSuccess")}/${result.optInt("publicTotal")}")
                        report.append("\nStrategy: ${command.ifEmpty { "control" }}\n$result\n")
                        if (command.isEmpty() && TuningScore.eligible(result.optInt("success"),result.optInt("total")) && result.optInt("publicTotal") > 0 && result.optInt("publicSuccess") == result.optInt("publicTotal")) break
                    }
                    val best = results.filter { (_,r) -> TuningScore.eligible(r.optInt("success"),r.optInt("total")) }
                        .sortedWith(compareByDescending<Pair<String,JSONObject>> { it.second.optInt("publicSuccess") }.thenBy { it.second.optLong("elapsed",Long.MAX_VALUE) }).firstOrNull()
                    if (best == null) output.append("\nПолные транспортные проверки не пройдены; прежний результат сохранён. Можно продолжить следующий блок.")
                    else {
                        MobileStrategyPolicy.remember(this@ServiceTuningActivity,target.id,best.first)
                        output.append("\nСохранён результат для ${target.name}. Узлы доступны; аккаунт, лента и видео требуют проверки в самом приложении. Примените результат и включите VPN на главном экране.")
                    }
                } catch (_: CancellationException) { output.append("\nПодбор остановлен; прежний результат сохранён.") }
                catch (e: Exception) { output.append("\nПодбор прерван (${e.javaClass.simpleName}); прежний результат сохранён.") }
                finally { choice.isEnabled = true; run.isEnabled = true; apply.isEnabled = true; cancel.isEnabled = false }
            }
        }
        apply.setOnClickListener {
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            val args = prefs.getString("tuned_"+target.id,null)
            val selected = TargetApps.installed(this).map { it.packageName }.filter { it in target.packages }.toSet()
            when {
                appStatus.first != AppStatus.Halted -> output.text = "Сначала выключите обработку"
                args == null -> output.text = "Сначала выполните подбор для ${target.name}"
                selected.isEmpty() -> output.text = "Стандартный клиент ${target.name} не установлен. VPN для всех приложений автоматически не включается."
                !CatalogProtocol.validCommand(args) -> output.text = "Прежняя команда несовместима с базой; выполните новый подбор"
                else -> {
                    prefs.edit().putStringSet(TargetApps.KEY,selected).apply(); MobileStrategyPolicy.apply(prefs,target.id,args)
                    output.text = "Выбран ${target.name} и установленный клиент. На главном экране включите VPN: проверка и поиск замены будут работать в фоне."
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity) }
    }
    override fun onStart() { super.onStart(); handler.post(refreshUi) }
    override fun onStop() { handler.removeCallbacks(refreshUi); job?.cancel(); super.onStop() }
}
