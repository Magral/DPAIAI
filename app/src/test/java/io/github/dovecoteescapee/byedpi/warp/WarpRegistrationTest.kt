package io.github.dovecoteescapee.byedpi.warp
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WarpRegistrationTest {
    private val secret=java.util.Base64.getEncoder().encodeToString(ByteArray(32){7})
    private val publicKey=java.util.Base64.getEncoder().encodeToString(ByteArray(32){8})
    private fun enrolled()=JSONObject().put("id","device-123").put("token","PRIVATE_TOKEN").put("warp_enabled",false).put("config",JSONObject()
        .put("client_id","AQID").put("interface",JSONObject().put("addresses",JSONObject().put("v4","172.16.0.2").put("v6","2606:4700:110::2")))
        .put("peers",JSONArray().put(JSONObject().put("public_key",publicKey).put("endpoint",JSONObject().put("v4","162.159.192.1:2408")))))
    @Test fun registrationSendsOnlyPublicKeyAndRequiresExplicitActivation() {
        var activated=false
        val flow=WarpRegistration {path,body,token,method->
            assertFalse(body.toString().contains(secret))
            if(method=="POST") {assertEquals("/v0a4471/reg",path);assertEquals(publicKey,body.getString("key"));assertTrue(Regex("[0-9a-f]{16}").matches(body.getString("serial_number")));assertNull(token);enrolled()}
            else {assertEquals("PATCH",method);assertEquals("/v0a4471/reg/device-123",path);assertEquals("PRIVATE_TOKEN",token);activated=body.getBoolean("warp_enabled");JSONObject().put("warp_enabled",true)}
        }
        val profile=flow.enroll(secret,publicKey,"2026-11-15T00:00:00.000Z")
        assertTrue(activated);assertFalse(profile.encode().contains("PRIVATE_TOKEN"))
    }
    @Test fun httpSuccessWithoutActivationCannotSaveAUsableProfile() {
        val flow=WarpRegistration {_,_,_,method->if(method=="POST")enrolled() else JSONObject().put("warp_enabled",false)}
        try {flow.enroll(secret,publicKey,"test");fail("Unactivated identity qualified")}catch(_: IllegalArgumentException) { }
    }
}
