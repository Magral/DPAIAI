package io.github.magral.dpipilot.tv

/** Bounded fallback order; the saved strategy is committed only after all probes pass. */
object TvPolicy {
    val strategies get() = RuntimeCatalog.commands
    fun order(saved: Int?,forceDifferent: Boolean): List<Int> {
        val good = saved?.takeIf { it in strategies.indices }
        val rest = strategies.indices.filter { it != good }
        return if (good == null) rest else if (forceDifferent) rest+good else listOf(good)+rest
    }
    val adaptiveIndex: Int get() = 7
    fun history(raw: String): List<Int> = raw.split(',').mapNotNull { it.toIntOrNull() }
        .filter { it in strategies.indices }.distinct().take(3)
    fun remember(old: List<Int>,confirmed: Int): List<Int> =
        (listOf(confirmed)+old).filter { it in strategies.indices }.distinct().take(3)
    fun candidates(known: List<Int>,failed: Int?,blocked: Set<Int>): List<Int> {
        val base = (known+listOf(adaptiveIndex)+strategies.indices).filter { it in strategies.indices }.distinct()
        return base.filter { it != failed && it !in blocked } + base.filter { it == failed && it !in blocked }
    }
    fun indexOfId(id: String?): Int? = id?.let { RuntimeCatalog.ids.indexOf(it).takeIf { n -> n >= 0 } }
    fun savedHistory(ids: String?,legacy: String): List<Int> = if (ids == null) history(legacy).filter { it <= 7 }
        else ids.split(',').mapNotNull { indexOfId(it) }.distinct().take(3)
    /** Persisted successes are scoped by caller to a network; catalog positions are never persisted. */
    fun selectable(successIds: List<String>,historyIds: List<String>): List<Int> =
        (successIds+historyIds).mapNotNull { indexOfId(it) }.distinct()
    fun scanWindow(known: List<Int>,active: Int,blocked: Set<Int>,cursor: Int,limit: Int = 12): List<Int> {
        require(limit > 0)
        val priority = (known+adaptiveIndex).filter { it in strategies.indices && it != active && it !in blocked }.distinct().take(limit)
        val start = ((cursor % strategies.size)+strategies.size)%strategies.size
        val rest = strategies.indices.map { (start+it)%strategies.size }
            .filter { it != active && it !in blocked && it !in priority }
        return priority+rest.take((limit-priority.size).coerceAtLeast(0))
    }
    // Three native fallback waits of eight seconds must fit inside the TLS diagnostic deadline.
    fun probeReadTimeout(index: Int): Int {
        val command = strategies.getOrElse(index) { "" }
        val seconds = Regex("--timeout ([0-9]+)").findAll(command).map { it.groupValues[1].toLongOrNull() ?: 0L }.maxOrNull() ?: 0L
        val groups = command.split("--auto ").size
        return (seconds.coerceAtMost(60)*groups*1000L+3000L).coerceIn(12000L,60000L).toInt()
    }
    fun backoffMillis(failures: Int): Long = when {
        failures <= 1 -> 60_000L
        failures == 2 -> 120_000L
        failures == 3 -> 300_000L
        else -> 600_000L
    }
    fun acceptsPreview(status: Int,type: String,bytes: ByteArray): Boolean {
        if (!accepts("image",status,type,bytes.size) || bytes.size < 16) return false
        fun prefix(vararg expected: Int) = expected.indices.all { (bytes[it].toInt() and 255) == expected[it] }
        return when (type.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)) {
            "image/jpeg" -> prefix(255,216,255)
            "image/png" -> prefix(137,80,78,71,13,10,26,10)
            "image/webp" -> bytes.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP"
            else -> false
        }
    }
    fun accepts(kind: String,status: Int,contentType: String,bodyBytes: Int): Boolean = when (kind) {
        "youtube" -> status == 204
        "image" -> status in listOf(200,206) && contentType.startsWith("image/",true) && bodyBytes > 0
        else -> false
    }
}
