package io.github.dovecoteescapee.byedpi.utility

/** Only public transport evidence, never an account/feed/Reels availability claim. */
object ServiceTargets {
    data class Endpoint(val host: String,val kind: String,val path: String = "/")
    data class Target(val id: String,val name: String,val endpoints: List<Endpoint>,val preferred: List<String>,val packages: Set<String>,val note: String)
    val targets = listOf(
        Target("instagram","Instagram",listOf(Endpoint("www.instagram.com","instagram"),Endpoint("i.instagram.com","tls"),Endpoint("static.cdninstagram.com","tls")),
            listOf("--split 1","--tlsrec 1+s","--disorder 1 --tlsrec 1+s","--disorder 1"),setOf("com.instagram.android","com.instagram.lite"),
            "Приоритет — Instagram. Проверяются HTTPS сайта и TLS к API/CDN, затем наблюдается реальный трафик выбранного клиента. Вход, лента, Reels и Direct без аккаунта не проверяются. HTTP 403/429 показываются отдельно и не доказывают отказ обхода."),
        Target("youtube","YouTube",listOf(Endpoint("www.youtube.com","youtube","/generate_204"),Endpoint("i.ytimg.com","image","/vi/jNQXAC9IVRw/hqdefault.jpg"),Endpoint("redirector.googlevideo.com","tls")),
            listOf("--disorder 1","--split 1","--disorder 1 --tlsrec 1+s","--tlsrec 1+s"),setOf("com.google.android.youtube","com.google.android.youtube.tv"),
            "Проверяются YouTube, превью и TLS к CDN. Воспроизведение проверяется в самом клиенте; тест прямой публичной видеоссылки не используется.")
    )
    fun target(id: String) = targets.first { it.id == id }
}
