package io.github.magral.dpipilot.tv

/** Bounded fallback order; the saved strategy is committed only after all probes pass. */
object TvPolicy {
    val strategies = listOf("", "--split 1", "--disorder 1", "--tlsrec 1+s",
        "--disorder 1 --tlsrec 1+s", "--split 2", "--split 1 --tlsrec 1+s")
    fun order(saved: Int?,forceDifferent: Boolean): List<Int> {
        val good = saved?.takeIf { it in strategies.indices }
        val rest = strategies.indices.filter { it != good }
        return if (good == null) rest else if (forceDifferent) rest+good else listOf(good)+rest
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
