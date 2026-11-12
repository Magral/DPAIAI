package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.*

/** A manually started search, independent from scheduled catalog scans. */
class StrategyGeneratorActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: LinearLayout
    private var shown = ""
    private lateinit var targets: RadioGroup
    private val refresh = object : Runnable {
        override fun run() {
            status.text = MobileGenerator.text()
            val state = MobileGenerator.state
            progress.max = state.total.coerceAtLeast(1);progress.progress = state.checked
            val id = if (targets.checkedRadioButtonId == 1) "youtube" else "instagram"
            val profile = MobileStrategyPolicy.profile(this@StrategyGeneratorActivity)
            val rows = MobileHistory.store(this@StrategyGeneratorActivity,id,profile).choices()
                .filter { it.optString("command") !in io.github.dovecoteescapee.byedpi.catalog.RuntimeCatalog.commands }
            val signature = id+profile+rows.joinToString { it.toString() }
            if (shown != signature) {
                shown = signature;results.removeAllViews()
                TextView(this@StrategyGeneratorActivity).apply { text="Найденные свои стратегии: ${rows.size}";results.addView(this) }
                rows.forEachIndexed { index,row ->
                    Button(this@StrategyGeneratorActivity).apply {
                        val command = row.getString("command")
                        text="Вариант ${index+1} · ${row.optLong("latencyMs")} мс · Применить";isAllCaps=false;results.addView(this)
                        setOnClickListener {
                            if (appStatus.first == AppStatus.Halted) Toast.makeText(context,"Сначала включите защиту",Toast.LENGTH_LONG).show()
                            else if (MobileHistory.store(context,id,profile).freshResult(command) == null)
                                Toast.makeText(context,"Результат устарел. Запустите генератор для новой проверки",Toast.LENGTH_LONG).show()
                            else {
                                MobileGenerator.stop()
                                // Explicit application remains available when scheduled selection is disabled.
                                getPreferences().edit().putString("mobile_selected.$id",org.json.JSONObject().put("command",command).put("profile",profile).toString()).apply()
                                MobileFeedback.request(id)
                                Toast.makeText(context,"Применяем проверенную стратегию",Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved);title="Генератор стратегий"
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(box) })
        TextView(this).apply {
            text="Отдельный поиск новых сочетаний параметров. Годны только полные результаты: YouTube — 8 из 8, Instagram — 6 из 6. Поиск расходует трафик; медленные варианты перепроверяются. Первая годная стратегия включается сразу."
            box.addView(this)
        }
        targets = RadioGroup(this).apply {
            orientation=RadioGroup.HORIZONTAL;box.addView(this)
            addView(RadioButton(this@StrategyGeneratorActivity).apply { id=1;text="YouTube" })
            addView(RadioButton(this@StrategyGeneratorActivity).apply { id=2;text="Instagram" })
            check(if (saved?.getInt("target",1) == 2) 2 else 1)
        }
        val minutes = Spinner(this).apply { adapter=ArrayAdapter(this@StrategyGeneratorActivity,android.R.layout.simple_spinner_dropdown_item,listOf("2 минуты","5 минут","10 минут"));setSelection(1);box.addView(this) }
        Button(this).apply {
            text="Начать генерацию";isAllCaps=false;box.addView(this)
            setOnClickListener {
                if (appStatus.first == AppStatus.Halted) Toast.makeText(context,"Включите защиту на главном экране",Toast.LENGTH_LONG).show()
                else {
                    MobileGenerator.request(if (targets.checkedRadioButtonId == 1) "youtube" else "instagram",listOf(2,5,10)[minutes.selectedItemPosition])
                    Toast.makeText(context,"Генератор запущен; окно можно закрыть",Toast.LENGTH_SHORT).show()
                }
            }
        }
        Button(this).apply { text="Остановить генератор";isAllCaps=false;box.addView(this);setOnClickListener { MobileGenerator.stop() } }
        status=TextView(this).apply { textSize=18f;setPadding(0,16,0,16);box.addView(this) }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { box.addView(this) }
        results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;box.addView(this) }
    }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("target",targets.checkedRadioButtonId);super.onSaveInstanceState(out) }
    override fun onStart() { super.onStart();handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh);super.onStop() }
}
