package io.github.dovecoteescapee.byedpi.warp

import org.json.JSONObject
import java.security.SecureRandom

/** Pure enrollment protocol. A successful HTTP response must also confirm WARP activation. */
class WarpRegistration(private val request: (String,JSONObject,String?,String)->JSONObject) {
    fun enroll(secret: String,publicKey: String,stamp: String): WarpProfile {
        require(WarpEncoding.decode(secret).size==32 && WarpEncoding.decode(publicKey).size==32)
        val body=JSONObject().put("key",publicKey).put("install_id","").put("fcm_token","").put("tos",stamp)
            .put("model","Android").put("serial_number",ByteArray(8).also {SecureRandom().nextBytes(it)}.joinToString("") {"%02x".format(it.toInt() and 255)}).put("os_version","")
            .put("key_type","curve25519").put("tunnel_type","wireguard").put("locale","en-US")
        val response=request("/v0a4471/reg",body,null,"POST")
        val root=response.optJSONObject("result") ?: response
        if(!root.optBoolean("warp_enabled")) {
            val id=root.getString("id");val token=root.getString("token")
            require(Regex("[A-Za-z0-9-]{1,64}").matches(id) && token.length in 1..2048 && token.all {it in '!'..'~'})
            val activation=request("/v0a4471/reg/$id",JSONObject().put("warp_enabled",true),token,"PATCH")
            require((activation.optJSONObject("result") ?: activation).optBoolean("warp_enabled")) {"WARP activation not confirmed"}
        }
        return WarpProfile.enrolled(secret,response)
    }
}
