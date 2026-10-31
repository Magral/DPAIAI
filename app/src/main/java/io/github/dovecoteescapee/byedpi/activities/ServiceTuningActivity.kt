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
    private lateinit var stageView: TextView
    private var stage = "Подбор ещё не запускался"
    private var stageAt = 0L
    private var stopReason = "нажата кнопка остановки"
    private val refreshUi = object : Runnable {
        override fun run() {
            base.text = "DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME} · База ${RuntimeCatalog.commands.size} · ${RuntimeCatalog.version}\n${MobileCatalogUpdater.status}\nФоновый контроль VPN: ${MobileStrategyPolicy.state}"
            stageView.text = stage + if (job?.isActive == true && stageAt > 0) " · ожидание ${(SystemClock.elapsedRealtime()-stageAt)/1000} с" else ""
            handler.postDelayed(this,1000)
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
        stageView = TextView(this).apply { setPadding(0,12,0,12); box.addView(this) }
        val output = TextView(this).apply { setPadding(0,16,0,16); box.addView(this) }
        val apply = Button(this).apply { text = "Применить сохранённый результат"; box.addView(this) }
        val report = StringBuilder(prefs.getString("mobile_tuning_report","").orEmpty())
        fun record(text: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date())
            report.append("[$time] $text\n")
        }
        fun progress(text: String) { stage = text; stageAt = SystemClock.elapsedRealtime(); stageView.text = text; record(text) }
        fun saveReport() { prefs.edit().putString("mobile_tuning_report",report.toString()).apply() }
        Button(this).apply {
            text = "Скопировать диагностику"; box.addView(this)
            setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DPI Pilot",base.text.toString()+"\nТекущее состояние: "+stageView.text+"\nЭкран: "+output.text+"\nЖурнал подбора:\n"+report+"\nПоследняя проверка фонового контроля VPN:\n"+MobileStrategyPolicy.lastProbe)); Toast.makeText(this@ServiceTuningActivity,"Диагностика скопирована",Toast.LENGTH_SHORT).show() }
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
        cancel.setOnClickListener { stopReason = "нажата кнопка остановки"; job?.cancel() }
        run.setOnClickListener {
            if (appStatus.first != AppStatus.Halted) { output.text = "Сначала выключите обработку на главном экране. После включения VPN контроль будет работать в фоне."; return@setOnClickListener }
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) } == true) { output.text = "Для подбора в вашей сети отключите другой VPN"; return@setOnClickListener }
            val target = ServiceTargets.targets[choice.selectedItemPosition]
            val commands = MobileStrategyPolicy.window(this,target)
            val profile = MobileStrategyPolicy.profile(this)
            report.clear(); record("DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME}; target=${target.id}; base=${RuntimeCatalog.version}; engine=17.3")
            stopReason = "нажата кнопка остановки"
            progress("Подбор ${target.name} запущен; вариантов в блоке: ${commands.size}"); saveReport()
            job = lifecycleScope.launch {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                choice.isEnabled = false; run.isEnabled = false; apply.isEnabled = false; cancel.isEnabled = true
                output.text = "${target.name}: блок до ${commands.size} вариантов. Не закрывайте экран подбора до завершения.\n"
                val results = mutableListOf<Pair<String,JSONObject>>()
                try {
                    for ((index,command) in commands.withIndex()) {
                        output.append("\n${index+1}/${commands.size}: ${command.ifEmpty { "контроль" }}…")
                        progress("Вариант ${index+1}/${commands.size}: ${command.ifEmpty { "контроль" }}")
                        val result = try { MobileCandidate.run(this@ServiceTuningActivity,target.id,command) { text -> progress("Вариант ${index+1}/${commands.size} · $text") } }
                            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject().put("error","Превышен лимит процесса проверки; последний этап: $stage") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { JSONObject().put("error",e.javaClass.simpleName+": "+e.message.orEmpty().take(200)) }
                        if (MobileStrategyPolicy.profile(this@ServiceTuningActivity) != profile) error("Сеть изменилась")
                        results.add(command to result); MobileStrategyPolicy.advance(this@ServiceTuningActivity,target.id,command)
                        output.append(" узлы ${result.optInt("success")}/${result.optInt("total")}; публичные ответы ${result.optInt("publicSuccess")}/${result.optInt("publicTotal")}")
                        result.optString("error").takeIf { it.isNotEmpty() }?.let { output.append("; ошибка: $it") }
                        record("Strategy: ${command.ifEmpty { "control" }}\n$result"); saveReport()
                        if (command.isEmpty() && TuningScore.eligible(result.optInt("success"),result.optInt("total")) && result.optInt("publicTotal") > 0 && result.optInt("publicSuccess") == result.optInt("publicTotal")) break
                    }
                    val best = results.filter { (_,r) -> TuningScore.eligible(r.optInt("success"),r.optInt("total")) }
                        .sortedWith(compareByDescending<Pair<String,JSONObject>> { it.second.optInt("publicSuccess") }.thenBy { it.second.optLong("elapsed",Long.MAX_VALUE) }).firstOrNull()
                    if (best == null) output.append("\nПолные транспортные проверки не пройдены; прежний результат сохранён. Можно продолжить следующий блок.")
                    else {
                        MobileStrategyPolicy.remember(this@ServiceTuningActivity,target.id,best.first)
                        output.append("\nСохранён результат для ${target.name}. Узлы доступны; аккаунт, лента и видео требуют проверки в самом приложении. Примените результат и включите VPN на главном экране.")
                    }
                    progress("Подбор завершён"); record(output.text.toString())
                } catch (_: CancellationException) {
                    output.append("\nПодбор остановлен: $stopReason; прежний результат сохранён.")
                    progress("Подбор остановлен: $stopReason")
                }
                catch (e: Exception) {
                    output.append("\nПодбор прерван (${e.javaClass.simpleName}: ${e.message}); прежний результат сохранён.")
                    progress("Ошибка: ${e.javaClass.simpleName}: ${e.message}")
                }
                finally {
                    saveReport(); window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    choice.isEnabled = true; run.isEnabled = true; apply.isEnabled = true; cancel.isEnabled = false
                }
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
    override fun onStop() { handler.removeCallbacks(refreshUi); stopReason = "экран подбора скрыт"; job?.cancel(); super.onStop() }
}
