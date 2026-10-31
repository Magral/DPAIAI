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
            base.text = "DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME} · База ${RuntimeCatalog.commands.size} · ${RuntimeCatalog.version}\n${MobileCatalogUpdater.status}\nФоновый контроль VPN: ${MobileStrategyPolicy.state}\n${MobileNetwork.summary(this@ServiceTuningActivity)}"
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
        choice.setSelection(ServiceTargets.targets.indexOfFirst { it.id == prefs.getString("mobile_target",MobilePlan.BOTH) }.coerceAtLeast(0))
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
            note.text = target.note+"\nПодбор — до 12 вариантов на каждый сервис за блок. Можно продолжить со следующей позиции; сохранённые варианты остаются в приоритете."
            apply.text = if (target.id == MobilePlan.BOTH) "Применить Instagram + YouTube" else "Применить сохранённый результат"
            val stored = if (target.id == MobilePlan.BOTH) MobilePlan.describe(MobilePlan.saved(prefs)) else prefs.getString("tuned_"+target.id,null)
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
            val scanTargets = if (target.id == MobilePlan.BOTH) ServiceTargets.individual else listOf(target)
            val count = scanTargets.sumOf { MobileStrategyPolicy.window(this,it).size }
            val profile = MobileStrategyPolicy.profile(this)
            report.clear(); record("DPI Pilot ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME}; target=${target.id}; base=${RuntimeCatalog.version}; engine=17.3")
            stopReason = "нажата кнопка остановки"
            progress("Подбор ${target.name} запущен; вариантов в блоке: ${count}"); saveReport()
            job = lifecycleScope.launch {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                choice.isEnabled = false; run.isEnabled = false; apply.isEnabled = false; cancel.isEnabled = true
                output.text = "${target.name}: блок до ${count} вариантов. Не закрывайте экран подбора до завершения.\n"
                try {
                    for (service in scanTargets) {
                        val commands = MobileStrategyPolicy.window(this@ServiceTuningActivity,service)
                        val results = mutableListOf<Pair<String,JSONObject>>()
                        for ((index,command) in commands.withIndex()) {
                            output.append("\n${service.name} ${index+1}/${commands.size}: ${command.ifEmpty { "контроль" }}…")
                            progress("${service.name} · вариант ${index+1}/${commands.size}: ${command.ifEmpty { "контроль" }}")
                            val result = try { MobileCandidate.run(this@ServiceTuningActivity,service.id,command) { text -> progress("${service.name} · вариант ${index+1}/${commands.size} · $text") } }
                                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); JSONObject().put("error","Превышен лимит процесса; последний этап: $stage") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { JSONObject().put("error",e.javaClass.simpleName+": "+e.message.orEmpty().take(200)) }
                            if (MobileStrategyPolicy.profile(this@ServiceTuningActivity) != profile) error("Сеть изменилась")
                            results.add(command to result); MobileStrategyPolicy.advance(this@ServiceTuningActivity,service.id,command)
                            output.append(" узлы ${result.optInt("success")}/${result.optInt("total")}; публичные ответы ${result.optInt("publicSuccess")}/${result.optInt("publicTotal")}")
                            result.optString("error").takeIf { it.isNotEmpty() }?.let { output.append("; ошибка: $it") }
                            record("${service.id} · Strategy: ${command.ifEmpty { "control" }}\n$result"); saveReport()
                            val assessment = ProbeAssessment.evaluate(service.id,result)
                            val passed = assessment.eligible
                            output.append("; "+assessment.text())
                            if (passed && assessment.complete && (target.id == MobilePlan.BOTH || (command.isEmpty() && result.optInt("publicTotal") > 0 && result.optInt("publicSuccess") == result.optInt("publicTotal")))) break
                        }
                        val best = results.filter { (_,r) -> ProbeAssessment.evaluate(service.id,r).eligible }
                            .sortedWith(compareByDescending<Pair<String,JSONObject>> { ProbeAssessment.evaluate(service.id,it.second).extraSuccess }.thenByDescending { it.second.optInt("publicSuccess") }.thenBy { it.second.optLong("elapsed",Long.MAX_VALUE) }).firstOrNull()
                        if (best == null) output.append("\n${service.name}: основные проверки не пройдены; прежняя стратегия сохранена.")
                        else {
                            MobileStrategyPolicy.remember(this@ServiceTuningActivity,service.id,best.first)
                            output.append("\n${service.name}: кандидат сохранён: основные публичные узлы доступны. Видео и аккаунт не проверялись.")
                        }
                    }
                    if (target.id == MobilePlan.BOTH) {
                        val plan = MobilePlan.saved(prefs)
                        progress("Проверка совместной конфигурации Instagram + YouTube")
                        val combined = MobileCandidate.run(this@ServiceTuningActivity,MobilePlan.BOTH,plan,::progress)
                        if (MobileStrategyPolicy.profile(this@ServiceTuningActivity) != profile) error("Сеть изменилась")
                        record("Совместная конфигурация: ${MobilePlan.describe(plan)}\n$combined")
                        output.append("\nСовместная проверка: ${ProbeAssessment.evaluate(MobilePlan.BOTH,combined).text()}.\n"+MobilePlan.describe(plan))
                    }
                    output.append("\nПримените результат и включите VPN. В общем режиме работают оба приложения; аккаунт, лента и видео проверяются в самих клиентах.")
                    progress("Подбор завершён"); record(output.text.toString())
                } catch (_: CancellationException) {
                    output.append("\nПодбор остановлен: $stopReason; непроверенные варианты не сохраняются.")
                    progress("Подбор остановлен: $stopReason")
                }
                catch (e: Exception) {
                    output.append("\nПодбор прерван (${e.javaClass.simpleName}: ${e.message}); непроверенные варианты не сохраняются.")
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
            val args = if (target.id == MobilePlan.BOTH) MobilePlan.saved(prefs) else prefs.getString("tuned_"+target.id,null)
            val selected = TargetApps.installed(this).map { it.packageName }.filter { it in target.packages }.toSet()
            when {
                appStatus.first != AppStatus.Halted -> output.text = "Сначала выключите обработку"
                args == null -> output.text = "Сначала выполните подбор для ${target.name}"
                target.id == MobilePlan.BOTH && ServiceTargets.individual.any { it.packages.intersect(selected).isEmpty() } -> output.text = "Для общего режима установите клиенты Instagram и YouTube"
                selected.isEmpty() -> output.text = "Стандартный клиент ${target.name} не установлен. VPN для всех приложений автоматически не включается."
                !MobilePlan.valid(args) -> output.text = "Прежняя команда несовместима с базой; выполните новый подбор"
                else -> {
                    prefs.edit().putStringSet(TargetApps.KEY,selected).apply(); MobileStrategyPolicy.apply(prefs,target.id,args)
                    output.text = "Выбран ${target.name}; клиенты добавлены в VPN. На главном экране включите VPN: проверка и поиск замены будут работать в фоне."
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@ServiceTuningActivity) }
    }
    override fun onStart() { super.onStart(); handler.post(refreshUi) }
    override fun onStop() { handler.removeCallbacks(refreshUi); stopReason = "экран подбора скрыт"; job?.cancel(); super.onStop() }
}
