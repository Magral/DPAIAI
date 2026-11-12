package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.catalog.*
import io.github.dovecoteescapee.byedpi.utility.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileTvParityTest {
    private fun result(id: String,passed: Int,latency: Long = 100): JSONObject {
        val rows = JSONArray()
        for (round in 1..2) for (endpoint in ServiceTargets.target(id).endpoints) rows.put(JSONObject()
            .put("host",endpoint.host).put("kind",endpoint.kind).put("round",round)
            .put("ok",rows.length() < passed).put("public",true).put("elapsedMs",latency))
        return JSONObject().put("rows",rows)
    }
    private fun preview(ms: Long = 100) = PreviewQuality(PreviewQuality.sources.flatMap { source ->
        (1..2).map { round -> PreviewSample(source,round,16000,ms,20,true) }
    })
    @Test fun retryOnlyPartialOrPreviouslyWorkingFailureAndNeverMergeAttempts() {
        for (id in listOf("youtube","instagram")) {
            val total = ServiceTargets.target(id).endpoints.size*2
            assertFalse(MobileCheckPolicy.confirmFailure(id,result(id,0),false))
            assertTrue(MobileCheckPolicy.confirmFailure(id,result(id,0),true))
            assertTrue(MobileCheckPolicy.confirmFailure(id,result(id,1),false))
            assertFalse(MobileCheckPolicy.confirmFailure(id,result(id,total),true))
            assertFalse(MobileCheckPolicy.confirmFailure(id,result(id,1).put("inconclusive",true),true))
            assertFalse(MobileCheckPolicy.confirmFailure(id,result(id,1).put("cancelled",true),true))
            assertFalse(MobileCheckPolicy.confirmFailure(id,result(id,1).put("error","worker failed"),true))
            assertFalse(ProbeAssessment.evaluate(id,result(id,total-1).put("retried",true)).eligible)
        }
    }
    @Test fun fullMobileThresholdRemainsIndependentFromOptionalTvPreview() {
        for (quality in listOf(PreviewQuality(),preview(),preview(12000),preview().copy(samples=preview().samples.drop(1)))) {
            assertTrue(ProbeAssessment.evaluate("youtube",result("youtube",26).put("preview",quality.encode())).eligible)
            assertFalse(ProbeAssessment.evaluate("youtube",result("youtube",25).put("preview",quality.encode())).eligible)
        }
    }
    @Test fun latencyRanksFullResultsAndNeverPartialNodes() {
        val a = StrategyCatalog.commands[1];val b = StrategyCatalog.commands[2]
        val stable = result("youtube",26,800).put("preview",preview(100).encode())
        val slow = result("youtube",26,1).put("preview",preview(2200).encode())
        assertEquals(b,MobileScanPolicy.fastest(listOf(b to slow,a to stable),"youtube")!!.first)
        val partial = result("youtube",25,1).put("preview",preview(1).encode())
        assertEquals(a,MobileScanPolicy.fastest(listOf(b to partial,a to stable),"youtube")!!.first)
        assertEquals(a,MobileScanPolicy.fastest(listOf(a to stable,b to stable),"youtube")!!.first)
    }
    @Test fun progressAndFinishedLabelsContainOnlyUserInformation() {
        val command = "--split 1 --tlsrec 1+s"
        for (phase in listOf("checking","retry","preview","good","failed","saved","paused")) {
            val text = MobilePresentation.text(MobilePresentation.Status(command,2,8,phase))
            assertFalse(text.contains("--"));assertFalse(text.contains("youtube.com"));assertFalse(text.contains("TLS"))
            if (phase !in listOf("saved","paused")) assertTrue(text.contains("2 из 8"))
        }
        MobilePresentation.clear();MobilePresentation.start("youtube",command)
        for (passed in 0..8) {
            val progress = ProbeProgress(passed,8)
            assertEquals(progress,ProbeProgress.decode(progress.encode()))
            MobilePresentation.progress("youtube",command,progress)
            assertTrue(MobilePresentation.summary().contains("$passed из 8"))
        }
        MobilePresentation.finish("youtube",command,result("youtube",25))
        assertTrue(MobilePresentation.summary().contains("Не прошла"))
        MobilePresentation.finish("youtube",command,result("youtube",26))
        assertTrue(MobilePresentation.summary().contains("Годна"))
        MobilePresentation.start("youtube",command);MobilePresentation.pause()
        assertTrue(MobilePresentation.summary().contains("Подбор остановлен"))
    }
    @Test fun fullPreviewBodyRejectsPrefixPartialFramingAndTruncation() {
        val bytes = ByteArray(2048)
        "RIFF".toByteArray().copyInto(bytes);"WEBP".toByteArray().copyInto(bytes,8)
        val length=bytes.size-8
        for (i in 0..3) bytes[4+i]=(length ushr (8*i)).toByte()
        val full = HttpCodec.Response(200,mapOf("content-type" to "image/webp"),bytes)
        fun accept(r: HttpCodec.Response) = PreviewBody.accepts(r) { PreviewBody.Size(480,360) }
        assertTrue(accept(full));assertFalse(accept(full.copy(capped=true)))
        assertFalse(accept(full.copy(status=206)));assertFalse(accept(full.copy(body=bytes.copyOf(1024))))
        assertFalse(PreviewBody.accepts(full) { PreviewBody.Size(120,90) })
        assertFalse(accept(full.copy(headers=full.headers+("content-range" to "bytes 0-2047/4096"))))
    }
}
