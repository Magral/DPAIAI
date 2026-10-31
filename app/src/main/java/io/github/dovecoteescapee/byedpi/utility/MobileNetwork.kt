package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object MobileNetwork {
    @Volatile var tunnel = "VPN не запускался"
    fun physical(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun suitable(network: Network) = cm.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        return (if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork?.takeIf { suitable(it) } else null) ?: cm.allNetworks.filter { suitable(it) }.sortedByDescending {
            val caps = cm.getNetworkCapabilities(it)
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 10 else 0)+
                (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 2 else 0)
        }.firstOrNull()
    }
    fun signature(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = physical(context) ?: return ""
        val link = cm.getLinkProperties(network)
        return "$network|${link?.interfaceName}|${link?.dnsServers}"
    }
    fun dns(context: Context,network: Network?,automatic: Boolean,manual: String): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val physicalDns = network?.let { cm.getLinkProperties(it)?.dnsServers }?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()
        return selectDns(physicalDns,automatic,manual)
    }
    fun selectDns(physical: List<String>,automatic: Boolean,manual: String): List<String> =
        if (automatic) physical.distinct().ifEmpty { listOf(manual.ifBlank { "1.1.1.1" }) }
        else listOfNotNull(manual.takeIf { it.isNotBlank() })
    fun summary(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = physical(context) ?: return "Физическая сеть отсутствует; $tunnel"
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "мобильная"
        }
        return "Сеть: $transport · ${link?.interfaceName ?: "?"} · DNS сети: ${link?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString()}\n$tunnel"
    }
}
