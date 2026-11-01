package io.github.magral.dpipilot.tv

import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

data class CatalogEntry(val id: String,val args: String,val origin: String)
data class CatalogUpdate(val sequence: Long,val version: String,val entries: List<CatalogEntry>,val sources: List<String>)

/** The signature covers the exact UTF-8 payload, including its channel and engine identity. */
object CatalogProtocol {
    const val engine = "ba532298de7b28cfe854aea83d061369d13ca290"
    const val prefix = "DPI-PILOT-TV-CATALOG-v1\n"
    const val maxBytes = 2*1024*1024
    private val flags = setOf("md5sig","drop-sack","tfo","wait-send")
    private val options = flags+setOf("split","disorder","oob","disoob","fake","ttl","fake-offset","fake-sni","fake-tls-mod","oob-data","mod-http","tlsrec","tlsminor","udp-fake","timeout","auto","auto-mode","cache-ttl","proto","pf","round","buf-size","await-int")
    fun id(command: String): String = MessageDigest.getInstance("SHA-256").digest(command.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun validCommand(command: String): Boolean = runCatching {
        require(command.length <= 4096)
        if (command.isEmpty()) return true
        val tokens = command.split(' ')
        require(tokens.size <= 480 && tokens.none { it.isEmpty() })
        var i = 0; var groups = 0
        while (i < tokens.size) {
            val option = tokens[i++]
            require(option.startsWith("--"))
            val name = option.drop(2); require(name in options)
            if (name in flags) continue
            require(i < tokens.size)
            val value = tokens[i++]
            require(value.length <= 253 && Regex("[A-Za-z0-9_.,:+?*#=-]+").matches(value))
            // Reject pathological allocation/repeat/delay parameters, even in a signed feed.
            Regex("[0-9]+").findAll(value).forEach { require(it.value.toLong() <= 86400) }
            when (name) {
                "buf-size" -> require(value.toInt() in 500..65536)
                "timeout" -> require(value.toInt() in 1..60)
                "ttl" -> require(value.toInt() in 1..255)
                "udp-fake" -> require(value.toInt() in 0..16)
                "cache-ttl" -> require(value.toInt() in 0..86400)
                "auto" -> { groups++; require(groups <= 16) }
                "round" -> require(value.split(':').all { it.toInt() in 0..40 })
                "await-int" -> require(value.toInt() in 0..1000)
                "fake-sni" -> require(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?").matches(value))
            }
        }
        true
    }.getOrDefault(false)
    fun verify(payload: ByteArray,signature: ByteArray,key: PublicKey,minimum: Long): CatalogUpdate {
        require(payload.size in 1..maxBytes && signature.size in 256..1024)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key); verifier.update(prefix.toByteArray(Charsets.UTF_8)); verifier.update(payload)
        require(verifier.verify(signature)) { "signature" }
        val json = JSONObject(String(payload,Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("engine_commit") == engine)
        val sequence = json.getLong("sequence")
        require(sequence > 0 && sequence >= minimum) { "rollback" }
        val version = json.getString("version")
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(version))
        val raw = json.getJSONArray("strategies"); require(raw.length() in 1..2000)
        val entries = (0 until raw.length()).map {
            val entry = raw.getJSONObject(it); val args = entry.getString("args"); val id = entry.getString("id")
            require(validCommand(args) && id == CatalogProtocol.id(args))
            val origins = entry.getJSONArray("sources"); require(origins.length() <= 32)
            val origin = (0 until origins.length()).map { n -> origins.getString(n).also { require(Regex("[a-zA-Z0-9_-]{1,64}").matches(it)) } }.joinToString(", ")
            CatalogEntry(id,args,origin)
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        val metadata = json.getJSONArray("sources"); require(metadata.length() <= 64)
        val sources = (0 until metadata.length()).map {
            val source = metadata.getJSONObject(it)
            val title = source.getString("title"); val url = source.getString("url")
            require(title.length <= 200 && url.length <= 1000 && url.startsWith("https://") && !title.any { c -> c.isISOControl() })
            val state = when (source.optString("fetch_status","snapshot")) {
                "fetched" -> "Источник получен"
                "fetch failed; snapshot retained" -> "Источник недоступен; прежние команды сохранены"
                "no complete commands; snapshot retained" -> "Новых команд не извлечено; прежние сохранены"
                else -> "Сохранённый снимок источника"
            }
            val checked = source.optString("checked_at","").takeIf { Regex("[0-9TZ:.,+\\-]{0,40}").matches(it) }.orEmpty()
            "$title\n$url\n$state${if (checked.isEmpty()) "" else "; $checked"}"
        }
        return CatalogUpdate(sequence,version,entries,sources)
    }
}

/** Append-only indices for this process: a download cannot change an in-flight trial's identity. */
object RuntimeCatalog {
    data class Snapshot(val entries: List<CatalogEntry>,val version: String,val sources: List<String>) {
        val commands = entries.map { it.args }; val ids = entries.map { it.id }; val origins = entries.map { it.origin }
    }
    @Volatile private var state = Snapshot(StrategyCatalog.commands.indices.map { CatalogEntry(StrategyCatalog.ids[it],StrategyCatalog.commands[it],StrategyCatalog.origins[it]) },StrategyCatalog.version,StrategyCatalog.sources)
    val commands get() = state.commands
    val ids get() = state.ids
    val origins get() = state.origins
    val version get() = state.version
    val sources get() = state.sources
    fun merge(old: Snapshot,update: CatalogUpdate): Snapshot {
        val known = old.entries.map { it.id }.toSet()
        val additions = update.entries.filter { it.id !in known }
        require(old.entries.size+additions.size <= 2000)
        return Snapshot(old.entries+additions,update.version,update.sources)
    }
    @Synchronized fun install(update: CatalogUpdate): Int {
        val old = state
        state = merge(old,update)
        return state.entries.size-old.entries.size
    }
}
