package io.github.magral.dpipilot.tv

import org.json.JSONObject

/** A deliberately unsigned user file: data only, same strict command bounds as the signed catalog. */
object ProfileWire {
    fun encode(commands: Map<String,String>): String {
        require(commands.isNotEmpty() && commands.keys.all { it in listOf("instagram","youtube") })
        commands.values.forEach { require(CatalogProtocol.validCommand(it)) }
        return JSONObject().put("format","DPI-Pilot-profile").put("schema",1).put("engine",CatalogProtocol.engine)
            .put("strategies",JSONObject(commands)).toString()
    }
    fun decode(text: String): Map<String,String> {
        require(text.toByteArray().size <= 16384)
        val data = JSONObject(text)
        require(data.length() == 4 && data.getString("format") == "DPI-Pilot-profile" && data.getInt("schema") == 1 && data.getString("engine") == CatalogProtocol.engine)
        val commands = data.getJSONObject("strategies");require(commands.length() in 1..2)
        val result = linkedMapOf<String,String>()
        commands.keys().forEach { id -> require(id in listOf("instagram","youtube")); result[id] = commands.getString(id).also { require(CatalogProtocol.validCommand(it)) } }
        return result
    }
}
