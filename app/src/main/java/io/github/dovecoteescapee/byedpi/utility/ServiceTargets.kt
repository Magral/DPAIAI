package io.github.dovecoteescapee.byedpi.utility

import io.github.dovecoteescapee.byedpi.catalog.StrategyCatalog

/** Only public transport evidence, never an account/feed/Reels availability claim. */
object ServiceTargets {
    data class Endpoint(val host: String,val kind: String,val path: String = "/",val required: Boolean = true)
    data class Target(val id: String,val name: String,val endpoints: List<Endpoint>,val preferred: List<String>,val packages: Set<String>,val note: String)
    // Stable catalog IDs from the working TV report: active #36, long-use #10, saved #51.
    val youtubeTvPreferred = listOf("665dfb8a8725932b","b55013dea302bbb1","af0c588ecde5769d").mapNotNull { id ->
        StrategyCatalog.commands.getOrNull(StrategyCatalog.ids.indexOf(id))
    }
    val youtubeStartup get() = youtubeTvPreferred.firstOrNull() ?: "--disorder 1"
    val individual = listOf(
        Target("instagram","Instagram",listOf(Endpoint("www.instagram.com","instagram"),Endpoint("i.instagram.com","tls"),Endpoint("static.cdninstagram.com","tls")),
            listOf("--split 1","--tlsrec 1+s","--disorder 1 --tlsrec 1+s","--disorder 1"),setOf("com.instagram.android","com.instagram.lite"),
            "Приоритет — Instagram. Проверяются HTTPS сайта и TLS к API/CDN, затем наблюдается реальный трафик выбранного клиента. Вход, лента, Reels и Direct без аккаунта не проверяются. HTTP 403/429 показываются отдельно и не доказывают отказ обхода."),
        Target("youtube","YouTube",listOf(Endpoint("www.youtube.com","youtube","/generate_204"),Endpoint("i.ytimg.com","image","/vi/jNQXAC9IVRw/hqdefault.jpg"),Endpoint("redirector.googlevideo.com","tls",required = false),Endpoint("youtubei.googleapis.com","tls",required = false)),
            (youtubeTvPreferred+listOf("--disorder 1","--split 1","--disorder 1 --tlsrec 1+s","--tlsrec 1+s")),setOf("com.google.android.youtube","com.google.android.youtube.tv"),
            "Основные проверки: сайт YouTube и JPEG-превью в двух раундах. Дополнительные: TLS к API и redirector.googlevideo.com; их сбой показывается отдельно и не запрещает испытать кандидата. Это не проверка видеодоставки. В приоритете варианты из отчёта работающего ТВ.")
    )
    val combined = Target(MobilePlan.BOTH,"Instagram + YouTube",individual.flatMap { it.endpoints },emptyList(),individual.flatMap { it.packages }.toSet(),
        "Оба приложения работают через один VPN одновременно. Для каждого сервиса сохраняется собственная стратегия; подбор и фоновая проверка выполняются отдельно. Выбор сервиса не требует переключения VPN. Лента, Reels и воспроизведение проверяются в самих приложениях.")
    val targets = listOf(combined)+individual
    fun target(id: String) = targets.first { it.id == id }
}
