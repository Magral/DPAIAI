package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.catalog.*
import io.github.dovecoteescapee.byedpi.services.CatalogUpdateJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val catalogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val catalogUi = object : Runnable {
        override fun run() {
            binding.mobileCatalogStatus.text = "База ${RuntimeCatalog.commands.size} · ${RuntimeCatalog.version}\n${MobileCatalogUpdater.status}\n${MobileStrategyPolicy.state}\n${MobileFeedback.traffic}" + if (getPreferences().getString("mobile_target",null) == MobilePlan.BOTH) "\n"+MobilePlan.describe(getPreferences().getString("mobile_command","").orEmpty()) else ""
            catalogHandler.postDelayed(this,2000)
        }
    }
    override fun onStart() { super.onStart(); catalogHandler.post(catalogUi) }
    override fun onStop() { catalogHandler.removeCallbacks(catalogUi); super.onStop() }
    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TargetApps.removeLegacyTarget(this)
        MobileCatalogUpdater.load(this)
        CatalogUpdateJob.schedule(this)
        io.github.dovecoteescapee.byedpi.services.PhoneRecovery.schedule(this)
        lifecycleScope.launch(Dispatchers.IO) { MobileCatalogUpdater.refresh(this@MainActivity) }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.youtubeProblem.setOnClickListener { MobileFeedback.request("youtube"); Toast.makeText(this,"Запрошена замена стратегии YouTube",Toast.LENGTH_SHORT).show() }
        binding.instagramProblem.setOnClickListener { MobileFeedback.request("instagram"); Toast.makeText(this,"Запрошена замена стратегии Instagram",Toast.LENGTH_SHORT).show() }
        binding.serviceTuningButton.setOnClickListener { startActivity(Intent(this, ServiceTuningActivity::class.java)) }
        binding.statusButton.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }
        }

        val theme = getPreferences()
            .getString("app_theme", null)
        MainSettingsFragment.setTheme(theme ?: "system")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu?.add(0, 9001, 0, "Профили и проверка")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            9001 -> {
                startActivity(Intent(this, ProfilesActivity::class.java))
                true
            }
            R.id.action_settings -> {
                if (status == AppStatus.Halted) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT)
                        .show()
                }
                true
            }

            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        try { MobileSetup.prepare(this) }
        catch (e: Exception) { Toast.makeText(this,e.message ?: "Установите YouTube или Instagram",Toast.LENGTH_LONG).show(); return }
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val targets = TargetApps.selected(this)
        @Suppress("DEPRECATION")
        val labels = targets.map { pkg ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: PackageManager.NameNotFoundException) { pkg }
        }.sorted()
        binding.targetsSummary.text = "Instagram + YouTube\n"+if (labels.isEmpty()) "Клиенты ещё не обнаружены" else labels.joinToString(", ")
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)

        when (status) {
            AppStatus.Halted -> {
                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                        binding.statusButton.setText(R.string.vpn_connect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                        binding.statusButton.setText(R.string.proxy_start)
                    }
                }
                binding.statusButton.isEnabled = true
            }

            AppStatus.Running -> {
                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                        binding.statusButton.setText(R.string.vpn_disconnect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                        binding.statusButton.setText(R.string.proxy_stop)
                    }
                }
                binding.statusButton.isEnabled = true
            }
        }
    }
}