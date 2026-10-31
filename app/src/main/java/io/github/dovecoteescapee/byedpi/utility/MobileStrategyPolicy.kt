package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.dovecoteescapee.byedpi.catalog.*
import java.security.MessageDigest

object MobileStrategyPolicy {
    @Volatile var state = "Автоматический контроль ещё не запускался"
    @Volatile var lastProbe = "Нет результатов"
    fun modern(prefs: SharedPreferences): Boolean = prefs.getBoolean("mobile_catalog_mode",false) &&
        prefs.getBoolean("byedpi_enable_cmd_settings",false) && prefs.getString("byedpi_cmd_args",null) == prefs.getString("mobile_command",null) &&
        (prefs.getString("mobile_command",null)?.let { MobilePlan.valid(it) } == true)
    fun profile(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val links = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true }
            .mapNotNull { cm.getLinkProperties(it) }.map { "${it.interfaceName}|${it.dnsServers.mapNotNull { a -> a.hostAddress }.sorted()}" }.sorted().joinToString(";")
        return MessageDigest.getInstance("SHA-256").digest(links.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun connected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { val c = cm.getNetworkCapabilities(it); c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    }
    fun ordered(ids: List<String>,preferred: List<String>,commands: List<String>,saved: String?,cursor: Int,limit: Int = 12): List<Int> {
        require(ids.size == commands.size && commands.isNotEmpty())
        val priority = (listOfNotNull(saved)+preferred.map { CatalogProtocol.id(it) }+listOf(CatalogProtocol.id(""))+ids.getOrNull(7).orEmpty())
            .mapNotNull { ids.indexOf(it).takeIf { n -> n >= 0 } }.distinct()
        val start = cursor.coerceAtLeast(0)%commands.size
        val rest = commands.indices.map { (start+it)%commands.size }.filter { it !in priority }
        return (priority+rest).take(limit)
    }
    fun window(context: Context,target: ServiceTargets.Target): List<String> {
        val prefs = context.getPreferences(); val key = "${target.id}.${profile(context)}"
        val saved = prefs.getString("mobile_good.$key",null) ?: prefs.getString("tuned_${target.id}",null)?.let { CatalogProtocol.id(it) }
        val commands = RuntimeCatalog.commands; val ids = RuntimeCatalog.ids.take(commands.size)
        return ordered(ids,target.preferred,commands,saved,prefs.getInt("mobile_cursor.$key",0)).map { commands[it] }
    }
    fun advance(context: Context,target: String,command: String) {
        val i = RuntimeCatalog.ids.indexOf(CatalogProtocol.id(command))
        context.getPreferences().edit().putInt("mobile_cursor.$target.${profile(context)}",(i+1)%RuntimeCatalog.commands.size).apply()
    }
    fun remember(context: Context,target: String,command: String) {
        context.getPreferences().edit().putString("tuned_$target",command)
            .putString("mobile_good.$target.${profile(context)}",CatalogProtocol.id(command)).apply()
    }
    fun apply(prefs: SharedPreferences,target: String,command: String) {
        require(MobilePlan.valid(command) && (target == MobilePlan.BOTH) == MobilePlan.dual(command))
        prefs.edit().putBoolean("mobile_catalog_mode",true).putString("mobile_target",target).putString("mobile_command",command)
            .putBoolean("byedpi_enable_cmd_settings",true).putString("byedpi_cmd_args",command)
            .putString("byedpi_proxy_ip","127.0.0.1").putString("byedpi_proxy_port","1080").putString("byedpi_mode","vpn").apply()
    }
    fun timeout(command: String): Int {
        val seconds = Regex("--timeout ([0-9]+)").findAll(command).mapNotNull { it.groupValues[1].toLongOrNull() }.maxOrNull() ?: 0
        return (seconds.coerceAtMost(60)*(command.split("--auto ").size)*1000+3000).coerceIn(12000,60000).toInt()
    }
}
