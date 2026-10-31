package io.github.magral.dpipilot.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.net.URL

class CatalogUpdateTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun payload(command: String = "--split 1",sequence: Long = 10,engine: String = CatalogProtocol.engine): ByteArray =
        JSONObject().put("schema",1).put("sequence",sequence).put("version","test-10").put("engine_commit",engine)
            .put("strategies",JSONArray().put(JSONObject().put("id",CatalogProtocol.id(command)).put("args",command).put("sources",JSONArray().put("fixture"))))
            .put("sources",JSONArray().put(JSONObject().put("title","Fixture").put("url","https://example.com/source"))).toString().toByteArray()
    private fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(pair.private);update(CatalogProtocol.prefix.toByteArray());update(data);sign()
    }
    private fun rejected(data: ByteArray,signature: ByteArray = sign(data),minimum: Long = 0) {
        assertTrue(runCatching { CatalogProtocol.verify(data,signature,pair.public,minimum) }.isFailure)
    }
    @Test fun acceptsAuthenticDataAndRejectsTamperingWrongKeyAndRollback() {
        val data = payload(); val signature = sign(data)
        assertEquals("--split 1",CatalogProtocol.verify(data,signature,pair.public,10).entries.single().args)
        rejected(payload("--split 2"),signature)
        rejected(data,signature,11)
        val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { CatalogProtocol.verify(data,signature,wrong.public,0) }.isFailure)
        rejected(payload(engine="another-engine"))
        rejected(ByteArray(CatalogProtocol.maxBytes+1),signature)
    }
    @Test fun rejectsOperationsFilesShellAndExcessiveResourceOptionsEvenWhenSigned() {
        for (command in listOf("--ip 0.0.0.0","--port 1080","--fake-data /tmp/data","--split 1; id","--timeout 9999","--udp-fake 99","--buf-size 999999","--auto r ".repeat(17).trim())) {
            assertFalse(command,CatalogProtocol.validCommand(command));rejected(payload(command))
        }
        assertTrue(StrategyCatalog.commands.all { CatalogProtocol.validCommand(it) })
    }
    @Test fun downloadedOrderCannotChangeExistingIndicesOrInFlightSnapshots() {
        val initial = RuntimeCatalog.Snapshot(listOf(CatalogEntry("a","--split 1","old"),CatalogEntry("b","--split 2","old")),"old",emptyList())
        val update = CatalogUpdate(1,"new",listOf(initial.entries[1],CatalogEntry("c","--split 3","new")),emptyList())
        val merged = RuntimeCatalog.merge(initial,update)
        assertEquals(listOf("a","b","c"),merged.ids)
        assertEquals(listOf("a","b"),initial.ids)
        assertEquals("--split 1",merged.commands[0])
        assertEquals("new",merged.version)
    }
    @Test fun downloadRedirectsRequireTrustedHttpsHostsAndRefreshIsBounded() {
        assertTrue(CatalogUpdater.allowedDownload(URL("https://release-assets.githubusercontent.com/file?token=abc")))
        for (url in listOf("http://github.com/a","https://github.com.attacker.net/a","https://user@github.com/a","https://github.com:444/a"))
            assertFalse(CatalogUpdater.allowedDownload(URL(url)))
        assertFalse(CatalogUpdater.due(100000,100000,0))
        assertFalse(CatalogUpdater.due(10000000,0,10000000))
        assertTrue(CatalogUpdater.due(10000000,0,0))
    }
}
