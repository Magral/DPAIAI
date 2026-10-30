package io.github.magral.dpipilot.tv

/** Bounded fallback order; the saved strategy is committed only after all probes pass. */
object TvPolicy {
    val strategies = listOf("", "--split 1", "--disorder 1", "--tlsrec 1+s",
        "--disorder 1 --tlsrec 1+s", "--split 2", "--split 1 --tlsrec 1+s",
        "--timeout 8 --cache-ttl 600 --split 1 --auto torst,ssl_err --disorder 1 --auto torst,ssl_err --tlsrec 1+s --auto torst,ssl_err --disorder 1 --tlsrec 1+s")
    fun order(saved: Int?,forceDifferent: Boolean): List<Int> {
        val good = saved?.takeIf { it in strategies.indices }
        val rest = strategies.indices.filter { it != good }
        return if (good == null) rest else if (forceDifferent) rest+good else listOf(good)+rest
    }
    val adaptiveIndex: Int get() = strategies.lastIndex
    fun history(raw: String): List<Int> = raw.split(',').mapNotNull { it.toIntOrNull() }
        .filter { it in strategies.indices }.distinct().take(3)
    fun remember(old: List<Int>,confirmed: Int): List<Int> =
        (listOf(confirmed)+old).filter { it in strategies.indices }.distinct().take(3)
    fun candidates(known: List<Int>,failed: Int?,blocked: Set<Int>): List<Int> {
        val base = (known+listOf(adaptiveIndex)+strategies.indices).filter { it in strategies.indices }.distinct()
        return base.filter { it != failed && it !in blocked } + base.filter { it == failed && it !in blocked }
    }
    fun backoffMillis(failures: Int): Long = when {
        failures <= 1 -> 60_000L
        failures == 2 -> 120_000L
        failures == 3 -> 300_000L
        else -> 600_000L
    }
    fun accepts(kind: String,status: Int,contentType: String,bodyBytes: Int): Boolean = when (kind) {
        "youtube" -> status == 204
        "image" -> status in listOf(200,206) && contentType.startsWith("image/",true) && bodyBytes > 0
        else -> false
    }
}
