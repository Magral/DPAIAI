package io.github.dovecoteescapee.byedpi

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.catalog.StrategyCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class MobileStoreAndParallelTest {
    private fun prefs(): SharedPreferences {
        val values=mutableMapOf<String,Any?>()
        lateinit var edit: SharedPreferences.Editor
        edit=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,m,a ->
            when(m.name) {
                "putString", "putBoolean" -> { values[a!![0] as String]=a[1];edit }
                "apply" -> null
                else -> error(m.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,m,a ->
            when(m.name) { "getString", "getBoolean" -> values[a!![0] as String] ?: a[1];"edit" -> edit;else -> error(m.name) }
        } as SharedPreferences
    }
    private fun result(id: String,delay: Long,fail: Int = -1): JSONObject {
        val rows=JSONArray()
        for(round in 1..2) for(e in ServiceTargets.target(id).endpoints) rows.put(JSONObject()
            .put("host",e.host).put("kind",e.kind).put("round",round).put("ok",rows.length()!=fail)
            .put("public",true).put("elapsedMs",delay))
        return JSONObject().put("rows",rows)
    }
    @Test fun partialFastResultsCannotQualifyAndResetRequiresNewCompleteEvidence() {
        val prefs=prefs();val store=MobileStrategyStore(prefs,"youtube","wifi")
        val a=StrategyCatalog.commands[1];val b=StrategyCatalog.commands[2]
        assertTrue(store.accept(a,result("youtube",200)))
        assertFalse(store.accept(b,result("youtube",1,3)))
        assertEquals(listOf(a),store.choices().map { it.getString("command") })
        assertTrue(MobileStrategyStore(prefs,"youtube","wifi").excluded(b))
        assertFalse(MobileStrategyStore(prefs,"youtube","mobile").excluded(b))
        assertFalse(MobileStrategyStore(prefs,"instagram","wifi").excluded(b))
        store.resetExclusions()
        assertFalse(store.excluded(b));assertEquals(1,store.choices().size)
        assertTrue(store.accept(b,result("youtube",50)))
        assertEquals(listOf(b,a),store.choices().map { it.getString("command") })
        assertEquals(a,MobileScanPolicy.fastest(listOf(a to result("youtube",200),b to result("youtube",1,0)),"youtube")!!.first)
    }
    @Test fun manualComplaintSurvivesRestartAndPreviewMetricsSurviveCoreOnlyConfirmation() {
        val preferences=prefs();val command=StrategyCatalog.commands[1]
        val store=MobileStrategyStore(preferences,"youtube","wifi")
        val preview=io.github.dovecoteescapee.byedpi.catalog.PreviewQuality(
            io.github.dovecoteescapee.byedpi.catalog.PreviewQuality.sources.flatMap { source ->
                (1..2).map { round -> io.github.dovecoteescapee.byedpi.catalog.PreviewSample(source,round,16000,100,20,true) }
            })
        assertTrue(store.accept(command,result("youtube",200).put("preview",preview.encode())))
        assertTrue(store.accept(command,result("youtube",150)))
        assertEquals(preview,io.github.dovecoteescapee.byedpi.catalog.PreviewQuality.decode(store.choices().single().optJSONArray("preview")))
        store.report(command)
        val restored=MobileStrategyStore(preferences,"youtube","wifi")
        assertTrue(restored.excluded(command));assertTrue(restored.choices().isEmpty())
        assertFalse(restored.accept(command,result("youtube",100)))
        restored.resetExclusions();assertTrue(restored.choices().isEmpty())
        assertTrue(restored.accept(command,result("youtube",100)))
        assertFalse(MobileStrategyStore(preferences,"instagram","wifi").excluded(command))
    }
    @Test fun optionalTargetsPersistIndependentlyAndDefaultToBothEnabled() {
        val preferences=prefs()
        assertTrue(MobileScanPolicy.enabled(preferences,"youtube"))
        assertTrue(MobileScanPolicy.enabled(preferences,"instagram"))
        preferences.edit().putBoolean("mobile_scan_enabled.instagram",false).apply()
        assertFalse(MobileScanPolicy.enabled(preferences,"instagram"))
        assertTrue(MobileScanPolicy.enabled(preferences,"youtube"))
        preferences.edit().putBoolean("mobile_scan_enabled.youtube",false).apply()
        assertFalse(MobileScanPolicy.enabled(preferences,"youtube"))
        preferences.edit().putBoolean("mobile_scan_enabled.instagram",true).apply()
        assertTrue(MobileScanPolicy.enabled(preferences,"instagram"))
        assertFalse(MobileScanPolicy.enabled(preferences,"youtube"))
        MobilePresentation.disabled("youtube")
        assertTrue(MobilePresentation.summary().contains("Подбор отключён"))
        MobilePresentation.clear()
    }
    @Test fun freshFullEvidenceCanBeReusedOnlyForSameServiceNetworkAndBeforeExpiry() {
        val preferences=prefs();val command=StrategyCatalog.commands[1]
        val store=MobileStrategyStore(preferences,"youtube","wifi")
        assertNull(store.freshResult(command))
        assertTrue(store.accept(command,result("youtube",42)))
        val checked=store.entries().single().getLong("checkedAt")
        assertEquals(42L,ProbeAssessment.latency(store.freshResult(command,checked)!!))
        assertNotNull(MobileStrategyStore(preferences,"youtube","wifi").freshResult(command,checked+1))
        assertNull(store.freshResult(command,checked-1))
        assertNull(store.freshResult(command,checked+MobileScanPolicy.INTERVAL))
        assertNull(MobileStrategyStore(preferences,"youtube","cell").freshResult(command,checked))
        assertNull(MobileStrategyStore(preferences,"instagram","wifi").freshResult(command,checked))
        store.defer(command,result("youtube",1,0));assertNotNull(store.freshResult(command,checked+1))
        preferences.edit().putString("mobile_dns_mode","manual").putString("mobile_dns_manual","8.8.8.8").apply()
        assertNull(store.freshResult(command))
        preferences.edit().putString("mobile_dns_mode","auto").putString("mobile_dns_manual","1.1.1.1").apply()
        assertNotNull(store.freshResult(command))
        store.expireCache();assertNull(store.freshResult(command))
        store.accept(command,result("youtube",42));store.report(command)
        assertNull(store.freshResult(command))
        store.resetExclusions();assertNull(store.freshResult(command))
    }
    @Test fun exclusionsAreNotEvictedAfterTheOldEightEntryLimit() {
        val prefs=prefs();val store=MobileStrategyStore(prefs,"instagram","wifi")
        StrategyCatalog.commands.take(20).forEach { store.reject(it) }
        val restored=MobileStrategyStore(prefs,"instagram","wifi")
        assertEquals(20,restored.entries().size)
        StrategyCatalog.commands.take(20).forEach { assertTrue(restored.excluded(it)) }
    }
    @Test fun legacyPositiveCountersAloneDoNotProveFullChecksAndScheduleSurvivesRestart() {
        val prefs=prefs();val command=StrategyCatalog.commands[1]
        prefs.edit().putString("mobile_history.youtube.wifi",JSONArray().put(JSONObject().put("command",command).put("good",10).put("bad",0)).toString()).apply()
        assertTrue(MobileStrategyStore(prefs,"youtube","wifi").choices().isEmpty())
        assertFalse(MobileScanPolicy.due(true,false,1000,1001))
        assertFalse(MobileScanPolicy.due(true,false,1000,1000+MobileScanPolicy.INTERVAL-1))
        assertTrue(MobileScanPolicy.due(true,false,1000,1000+MobileScanPolicy.INTERVAL))
        assertTrue(MobileScanPolicy.due(false,true,1000,1001))
        assertFalse(MobileScanPolicy.due(false,false,0,1001))
        assertFalse(MobileScanPolicy.due(true,false,1000,999))
    }
    @Test(timeout=5000) fun parallelFirstFailureCancelsWaitingSiblingsWithoutAwaitingTheirTimeout() {
        val entered=CountDownLatch(3);val waiting=CountDownLatch(2);val cancelled=CountDownLatch(2);val abort=AtomicInteger()
        val started=System.nanoTime()
        val rows=ParallelProbe.round(3,{}, { it },{ abort.incrementAndGet() }) { i ->
            entered.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS))
            if(i==0) { assertTrue(waiting.await(2,TimeUnit.SECONDS));false } else {
                waiting.countDown()
                try { CountDownLatch(1).await(30,TimeUnit.SECONDS);true }
                catch(_: InterruptedException) { cancelled.countDown();false }
            }
        }
        assertEquals(1,rows.size);assertFalse(rows.single().value)
        assertTrue(cancelled.await(2,TimeUnit.SECONDS))
        assertEquals(1,abort.get());assertTrue((System.nanoTime()-started)/1000000<4000)
    }
    @Test fun completeRoundKeepsAllHostIdentitiesWithBoundedParallelism() {
        val active=AtomicInteger();val peak=AtomicInteger()
        val rows=ParallelProbe.round(10,{}, { it>=0 },{}) { index ->
            val count=active.incrementAndGet();peak.updateAndGet { maxOf(it,count) }
            Thread.yield();active.decrementAndGet();index
        }
        assertEquals((0..9).toList(),rows.map { it.index });assertTrue(peak.get()<=4)
    }
}
