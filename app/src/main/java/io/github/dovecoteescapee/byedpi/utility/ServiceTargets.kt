package io.github.dovecoteescapee.byedpi.utility

/** Probe lists are versioned with the app. They are evidence of transport reachability only. */
object ServiceTargets {
    data class Target(val id: String, val name: String, val hosts: List<String>, val strategies: List<String>, val note: String)
    val targets = listOf(
        Target("youtube", "YouTube", listOf("www.youtube.com", "i.ytimg.com", "youtubei.googleapis.com"),
            listOf("", "--disorder 1", "--split 1", "--disorder 1 --tlsrec 1+s", "--tlsrec 1+s"),
            "Проверяются сайт, API и изображения. Видеопоток googlevideo и QUIC требуют проверки воспроизведением в YouTube."),
        Target("instagram", "Instagram", listOf("www.instagram.com", "i.instagram.com", "static.cdninstagram.com"),
            listOf("", "--split 1", "--tlsrec 1+s", "--disorder 1 --tlsrec 1+s", "--disorder 1"),
            "Проверяются сайт, API и CDN. Вход в аккаунт, лента, Reels и загрузка файлов здесь не проверяются."),
        Target("telegram", "Telegram", listOf("web.telegram.org", "telegram.org"),
            listOf("", "--split 1", "--split 1 --split 16 --split 32", "--disorder 1",
                "--split 1 --disorder 64", "--oob 1", "--fake -1 --ttl 8", "--fake -1 --ttl 5"),
            "Проверяются пять дата-центров Telegram: обфусцированный MTProto и резервный обычный транспорт. Подбор может занять несколько минут. После применения проверьте сообщения и файлы в Telegram. Если локальный обход не сработает, можно подключить свой MTProxy.")
    )
}
