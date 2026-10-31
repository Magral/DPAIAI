package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.catalog.*
import io.github.dovecoteescapee.byedpi.utility.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import org.json.JSONObject
import org.json.JSONArray

class MobileCatalogTest {
    @Test fun instagramIsDefaultAndProbeTargetsStaySeparated() {
        assertEquals(listOf("instagram","youtube"),ServiceTargets.targets.map { it.id })
        assertTrue(ServiceTargets.target("instagram").endpoints.all { it.host.endsWith("instagram.com") || it.host.endsWith("cdninstagram.com") })
        assertFalse(ServiceTargets.target("instagram").packages.intersect(ServiceTargets.target("youtube").packages).isNotEmpty())
        assertTrue(StrategyCatalog.commands.all { CatalogProtocol.validCommand(it) })
        assertTrue(StrategyCatalog.commands.size >= 279)
    }
    @Test fun boundedWindowsKeepSavedIdentityAndEventuallyReachTheWholeBank() {
        val ids = StrategyCatalog.ids; val commands = StrategyCatalog.commands
        val saved = ids[10]; val seen = mutableSetOf<Int>(); var cursor = 0
        repeat(commands.size) {
            val window = MobileStrategyPolicy.ordered(ids,ServiceTargets.target("instagram").preferred,commands,saved,cursor)
            assertEquals(10,window.first()); assertTrue(window.size <= 12); assertEquals(window.distinct().size,window.size)
            seen.addAll(window); cursor = (window.last()+1)%commands.size
        }
        assertEquals(commands.indices.toSet(),seen)
        assertTrue(MobileStrategyPolicy.timeout(commands[7]) > 24000)
    }
    @Test fun anonymousRestrictionsAreNotPublicContentAndHtmlCannotPassAsPreview() {
        assertFalse(ServiceProbe.publicResponse("instagram",403,"text/html",byteArrayOf()))
        assertFalse(ServiceProbe.publicResponse("instagram",429,"text/html",byteArrayOf()))
        assertTrue(ServiceProbe.publicResponse("instagram",200,"text/html","login page".toByteArray()))
        assertFalse(ServiceProbe.publicResponse("youtube",200,"text/html",byteArrayOf()))
        assertTrue(ServiceProbe.publicResponse("youtube",204,"",byteArrayOf()))
        assertFalse(ServiceProbe.publicResponse("image",206,"image/jpeg","<html>blocked</html>".toByteArray()))
        assertTrue(ServiceProbe.publicResponse("image",206,"image/jpeg",byteArrayOf(255.toByte(),216.toByte(),255.toByte())+ByteArray(100)))
    }
    @Test fun idleOrHealthyTrafficDoesNotLookLikeDemandWithoutReplies() {
        assertFalse(TuningScore.lackOfReplies(3,512,500))
        assertFalse(TuningScore.lackOfReplies(30,20000,2000000))
        assertFalse(TuningScore.lackOfReplies(-1,20000,0))
        assertTrue(TuningScore.lackOfReplies(25,5000,0))
        assertTrue(TuningScore.lackOfReplies(10,20000,300))
    }
    @Test fun stoppingAProbeClosesSocketAndRejectsLateRegistration() {
        val session = ServiceProbe.Session(); val first = java.net.Socket(); session.register(first); session.cancel(); assertTrue(first.isClosed)
        val late = java.net.Socket()
        try { session.register(late); fail("Stopped probe accepted socket") } catch (_: java.util.concurrent.CancellationException) { assertTrue(late.isClosed) }
    }
    @Test fun deadlineRejectsLateSocketAndClosesAnExistingOne() {
        val guard = ProbeDeadline(60000)
        val first = java.net.Socket(); guard.register(first)
        guard.expire(); assertTrue(first.isClosed); assertTrue(guard.timedOut)
        val late = java.net.Socket()
        try { guard.register(late); fail("Expired deadline accepted a socket") }
        catch (_: java.net.SocketTimeoutException) { assertTrue(late.isClosed) }
        guard.close()
    }
    @Test fun silentSocksPeerCannotHoldFirstCandidateUntilReadTimeout() {
        java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = java.util.concurrent.CompletableFuture<java.net.Socket>()
            val peer = Thread { runCatching { server.accept().also { accepted.complete(it) } } }
            peer.isDaemon = true; peer.start()
            val stages = mutableListOf<String>()
            val start = System.nanoTime()
            ProbeDeadline(500).use { guard ->
                try {
                    TargetProbe.socket("www.youtube.com",443,server.localPort,10000,guard::register,stages::add).close()
                    fail("Silent SOCKS endpoint passed")
                } catch (_: java.io.IOException) { assertTrue(guard.timedOut) }
            }
            accepted.get(2,java.util.concurrent.TimeUnit.SECONDS).close(); peer.join(2000)
            assertTrue(stages.contains("SOCKS authentication"))
            assertTrue("Hard deadline did not interrupt the read",(System.nanoTime()-start)/1000000 < 5000)
        }
    }
    @Test fun realSignedChannelUsesSamePinnedEngineAndProtectsRollback() {
        val directory = System.getenv("CATALOG_TEST_FIXTURE")?.let { File(it) } ?: return
        val key = File(directory,"certificate.der").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it).publicKey }
        val payload = File(directory,"previous.json").readBytes(); val signature = File(directory,"previous.sig").readBytes()
        val update = CatalogProtocol.verify(payload,signature,key,0)
        assertTrue(update.entries.size >= StrategyCatalog.commands.size)
        assertTrue(runCatching { CatalogProtocol.verify(payload,signature,key,update.sequence+1) }.isFailure)
        assertTrue(runCatching { CatalogProtocol.verify(payload.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },signature,key,0) }.isFailure)
        val merged = RuntimeCatalog.merge(RuntimeCatalog.Snapshot(StrategyCatalog.commands.indices.map { CatalogEntry(StrategyCatalog.ids[it],StrategyCatalog.commands[it],StrategyCatalog.origins[it]) },StrategyCatalog.version,StrategyCatalog.sources),update)
        assertEquals(StrategyCatalog.ids,merged.ids.take(StrategyCatalog.ids.size))
    }
    @Test fun signedDataCannotIntroduceOperationalFlagsOrWrongEngine() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun verify(command: String,engine: String): Boolean {
            val payload = JSONObject().put("schema",1).put("sequence",1).put("version","test").put("engine_commit",engine)
                .put("sources",JSONArray()).put("strategies",JSONArray().put(JSONObject().put("args",command).put("id",CatalogProtocol.id(command)).put("sources",JSONArray()))).toString().toByteArray()
            val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(CatalogProtocol.prefix.toByteArray()); update(payload); sign() }
            return runCatching { CatalogProtocol.verify(payload,signature,pair.public,0) }.isSuccess
        }
        assertTrue(verify("--split 1",CatalogProtocol.engine))
        assertFalse(verify("--ip 0.0.0.0",CatalogProtocol.engine))
        assertFalse(verify("--fake-data /tmp/file",CatalogProtocol.engine))
        assertFalse(verify("--udp-fake 99999",CatalogProtocol.engine))
        assertFalse(verify("--split 1","other-engine"))
    }
}
