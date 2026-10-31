package io.github.dovecoteescapee.byedpi.utility

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.catalog.CatalogProtocol
import org.json.JSONObject

/** Local configuration only: signed catalog entries remain individual restricted commands. */
object MobilePlan {
    const val BOTH = "both"
    val domains = linkedMapOf(
        "instagram" to listOf("instagram.com","cdninstagram.com","fbcdn.net","facebook.com","fbsbx.com","ig.me"),
        "youtube" to listOf("youtube.com","youtu.be","googlevideo.com","ytimg.com","ggpht.com","googleusercontent.com","googleapis.com","gstatic.com","google.com")
    )
    fun dual(value: String) = value.startsWith("{")
    private fun decode(value: String): JSONObject {
        require(value.length <= 12000)
        val data = JSONObject(value)
        require(data.length() == 3 && data.getInt("schema") == 1)
        domains.keys.forEach { require(CatalogProtocol.validCommand(data.getString(it))) }
        return data
    }
    fun command(value: String,target: String): String = if (dual(value)) decode(value).getString(target) else value
    fun create(instagram: String,youtube: String): String {
        val value = JSONObject().put("schema",1).put("instagram",instagram).put("youtube",youtube).toString()
        arguments(value,1080) // Enforce native argv and group-mask limits before storing it.
        return value
    }
    fun replace(value: String,target: String,replacement: String): String {
        require(target in domains)
        return create(if (target == "instagram") replacement else command(value,"instagram"),if (target == "youtube") replacement else command(value,"youtube"))
    }
    fun valid(value: String): Boolean = runCatching { arguments(value,1080); true }.getOrDefault(false)
    fun arguments(value: String,port: Int): Array<String> {
        require(port == 1080 || port == 19080)
        val args = mutableListOf("ciadpi","--ip","127.0.0.1","--port",port.toString(),"--proto","udp","--pf","53")
        if (!dual(value)) {
            require(CatalogProtocol.validCommand(value))
            args.addAll(listOf("--auto","none"))
            if (value.isNotEmpty()) args.addAll(value.split(' '))
        } else {
            val data = decode(value)
            for ((target,hosts) in domains) {
                args.addAll(listOf("--auto","none","--hosts",":"+hosts.joinToString(" ")))
                val tokens = data.getString(target).takeIf { it.isNotEmpty() }?.split(' ').orEmpty()
                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i++]; args.add(token)
                    if (token == "--auto") {
                        args.add(tokens[i++]); args.addAll(listOf("--hosts",":"+hosts.joinToString(" ")))
                    }
                }
            }
            args.addAll(listOf("--auto","none")) // Other hosts in the selected clients pass unchanged.
        }
        require(args.size <= 512 && args.count { it == "--auto" }+1 <= 30)
        return args.toTypedArray()
    }
    fun saved(prefs: SharedPreferences): String {
        val active = prefs.getString("mobile_command","").orEmpty()
        fun savedFor(id: String,fallback: String): String = prefs.getString("tuned_$id",null)?.takeIf { CatalogProtocol.validCommand(it) }
            ?: runCatching { if (dual(active)) command(active,id) else active.takeIf { prefs.getString("mobile_target",null) == id && CatalogProtocol.validCommand(it) } }.getOrNull()
            ?: fallback
        val instagram = savedFor("instagram","--split 1"); val youtube = savedFor("youtube","--disorder 1")
        return runCatching { create(instagram,youtube) }.getOrElse {
            runCatching { create(instagram,"--disorder 1") }.getOrElse { create("--split 1","--disorder 1") }
        }
    }
    fun describe(value: String): String = if (dual(value) && valid(value)) domains.keys.joinToString("\n") {
        "${if (it == "instagram") "Instagram" else "YouTube"}: ${command(value,it).ifEmpty { "контроль" }}"
    } else value.ifEmpty { "контроль" }
}
