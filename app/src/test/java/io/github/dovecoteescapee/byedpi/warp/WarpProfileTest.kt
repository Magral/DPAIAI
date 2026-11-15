package io.github.dovecoteescapee.byedpi.warp

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WarpProfileTest {
    private val key=java.util.Base64.getEncoder().encodeToString(ByteArray(32){(it+1).toByte()})
    private fun config(extra: String="")="""[Interface]
PrivateKey = $key
Address = 172.16.0.2/32, 2606:4700:110:1234::2/128
DNS = 1.1.1.1
$extra
[Peer]
PublicKey = $key
Endpoint = 162.159.192.1:2408
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
"""
    @Test fun profileProducesRestrictedIpcAndNeverPrintsPrivateKey() {
        val profile=WarpProfile.parse(config("Jc = 3\nJmin = 40\nJmax = 70\nReserved = 1,2,3"))
        assertEquals(listOf(1,2,3),profile.reserved.toList())
        assertTrue(profile.ipc().contains("jc=3\n"));assertTrue(profile.ipc().contains("allowed_ip=::/0\n"))
        assertFalse(profile.toString().contains(key));assertFalse(profile.toString().contains(profile.privateHex()))
        assertEquals(profile.encode(),WarpProfile.decode(profile.encode()).encode())
    }
    @Test fun directivesDuplicatesForeignProvidersAndMalformedKeysAreRejected() {
        val bad=listOf(config("PostUp = curl evil"),config("PrivateKey = $key"),config().replace(key,"bad"),config().replace("162.159.192.1","1.2.3.4"),config().replace("162.159.192.1","localhost"),config().replace("Address = 172.16.0.2/32", "Address = not-a-host/32"),config().replace("[Peer]","[Peer]\n[Peer]"),config("MTU = 90000"),config("Jc = 999999"))
        for(value in bad)try {WarpProfile.parse(value);fail("Accepted malformed profile")}catch(_: IllegalArgumentException) { }
    }
    @Test fun enrollmentExtractsOnlyNeededFieldsAndCannotInjectIpc() {
        val json=JSONObject().put("token","DO_NOT_KEEP").put("id","DO_NOT_KEEP").put("config",JSONObject()
            .put("client_id","AQID").put("interface",JSONObject().put("addresses",JSONObject().put("v4","172.16.0.2").put("v6","2606:4700:110::2")))
            .put("peers",org.json.JSONArray().put(JSONObject().put("public_key",key).put("endpoint",JSONObject().put("v4","162.159.193.1:2408")))))
        val p=WarpProfile.enrolled(key,json)
        assertEquals("162.159.193.1:2408",p.endpoint);assertFalse(p.encode().contains("DO_NOT_KEEP"))
        try {WarpProfile.enrolled(key.replace("=","\nendpoint=1.2.3.4:5"),json);fail("IPC injection")}catch(_: IllegalArgumentException) { }
    }
    @Test fun fixedAppsNeverBecomeGlobalAndCancelledGenerationCannotActivate() {
        assertEquals(setOf("com.google.android.youtube"),WarpPolicy.apps(setOf("com.google.android.youtube","com.android.chrome","com.game")))
        try {WarpPolicy.apps(setOf("com.game"));fail("Empty allowlist became global")}catch(_: IllegalArgumentException) { }
        val gate=WarpGeneration();val first=gate.next();assertTrue(gate.current(first));gate.next();assertFalse(gate.current(first))
    }
}
