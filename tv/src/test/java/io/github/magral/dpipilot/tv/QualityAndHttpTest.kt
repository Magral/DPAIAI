package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class QualityAndHttpTest {
    private fun response(raw: String,limit: Int = 1024) = HttpCodec.read(ByteArrayInputStream(raw.toByteArray()),limit)
    @Test fun truncatedBodyChunkFramingAndDuplicateLengthCannotPass() {
        for (raw in listOf(
            "HTTP/1.1 206 OK\r\nContent-Length: 10\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\na")) {
            try { response(raw); fail("Accepted malformed/truncated HTTP") } catch (_: IOException) { }
        }
        assertEquals("abcd",response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n").body.toString(Charsets.UTF_8))
        assertEquals(200,response("HTTP/1.1 103 Early Hints\r\nLink: x\r\n\r\nHTTP/1.1 200 OK\r\nSet-Cookie: a\r\nSet-Cookie: b\r\nContent-Length: 1\r\n\r\nx").status)
        assertEquals(4,response("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nabcdefgh",4).body.size)
    }
    @Test fun invalidContentCannotBecomeASuccessfulPreview() {
        val jpeg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())+ByteArray(100)
        assertTrue(TvPolicy.acceptsPreview(206,"image/jpeg",jpeg))
        assertFalse(TvPolicy.acceptsPreview(403,"image/jpeg",jpeg))
        assertFalse(TvPolicy.acceptsPreview(200,"text/html",jpeg))
        assertFalse(TvPolicy.acceptsPreview(200,"image/jpeg","<html>blocked</html>".toByteArray()))
        assertFalse(TvPolicy.acceptsPreview(200,"image/jpeg",jpeg.take(2).toByteArray()))
    }
    @Test fun validReachabilityNeedsNoPublicVideoLinkAndLatencyNoiseCannotTriggerReplacement() {
        val baseline = ProbeQuality(true,6,2000)
        assertTrue(baseline.eligible)
        assertFalse(QualityPolicy.better(ProbeQuality(true,6,1800),baseline,false))
        assertTrue(QualityPolicy.better(ProbeQuality(true,6,300),baseline,false))
        assertFalse(QualityPolicy.better(ProbeQuality(false,5,1),baseline,true))
        assertTrue(QualityPolicy.regression(baseline,ProbeQuality(false,4,100)))
        assertFalse(QualityPolicy.regression(baseline,ProbeQuality(true,6,6000)))
    }
    @Test fun rollbackNeedsEvidenceAndNeverReturnsToAKnownStalledPredecessor() {
        val trial=Trial(1,2,1000,true,ProbeQuality(true,6,100))
        assertFalse(trial.rollback(2000,false,true))
        assertTrue(trial.rollback(46000,false,true))
        assertTrue(trial.rollback(2000,true,false))
        assertFalse(trial.copy(restorable=false).rollback(46000,true,true))
        assertFalse(trial.rollback(0,true,true))
        assertTrue(trial.expired(121000))
    }
    @Test fun idleTimeCannotOutrankSustainedUsefulFlowAndFailuresReduceReputation() {
        val idle=QualityRecord(successes=5,activeSeconds=36000)
        val useful=QualityRecord(successes=5,usefulSeconds=1200,activeSeconds=1200)
        assertTrue(useful.rank()>idle.rank())
        assertTrue(useful.copy(stalls=3,rollbacks=1).rank()<useful.rank())
    }
    @Test fun repeatedDemandWithTinyResponsesTriggersButPauseDoesNot() {
        val watch=TrafficWatchdog()
        watch.stalled(TrafficWatchdog.Sample(1000,0,0,0))
        assertFalse(watch.stalled(TrafficWatchdog.Sample(46000,50,20000,20000)))
        assertTrue(watch.stalled(TrafficWatchdog.Sample(91000,100,40000,40000)))
        watch.reset()
        watch.stalled(TrafficWatchdog.Sample(1000,0,0,0))
        assertFalse(watch.stalled(TrafficWatchdog.Sample(46000,2,1000,0)))
        assertFalse(watch.stalled(TrafficWatchdog.Sample(91000,4,2000,0)))
    }
}
