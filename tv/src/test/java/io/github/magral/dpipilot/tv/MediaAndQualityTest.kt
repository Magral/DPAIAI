package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

class MediaAndQualityTest {
    private fun response(raw: String,limit: Int = 1024) = HttpCodec.read(ByteArrayInputStream(raw.toByteArray()),limit)
    private fun quality(kbps: Long,latency: Long = 100,consistency: Int = 90) = ProbeQuality(true,6,latency,MediaResult(MediaStatus.PASSED,kbps,latency,consistency))
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
    @Test fun htmlSmallRangesAndWrongOffsetAreNotVideoProof() {
        val good=HttpCodec.Response(206,mapOf("content-type" to "video/mp4","content-range" to "bytes 0-3/100","content-length" to "4"),byteArrayOf(1,2,3,4))
        assertTrue(MediaLinks.validRange(good,0,4))
        assertFalse(MediaLinks.validRange(good.copy(headers=good.headers+mapOf("content-type" to "text/html")),0,4))
        assertFalse(MediaLinks.validRange(good.copy(body=byteArrayOf(1)),0,4))
        assertFalse(MediaLinks.validRange(good,4,4))
        assertFalse(MediaLinks.validRange(good.copy(headers=good.headers+mapOf("content-length" to "8")),0,4))
    }
    @Test fun directPlayerParsingDoesNotExecuteCipherOrAcceptAnotherHost() {
        val json="""{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"mimeType":"video/mp4","height":360,"url":"https://rr1.googlevideo.com/videoplayback?expire=9&sig=secret"}]},"note":"brace }; inside string"}"""
        val html="var ytInitialPlayerResponse = $json; more();"
        assertEquals(json,MediaLinks.playerJson(html))
        assertEquals("rr1.googlevideo.com",MediaLinks.direct(html)!!.host)
        assertNull(MediaLinks.direct(html.replace("rr1.googlevideo.com","rr1.googlevideo.com.evil.test")))
        assertNull(MediaLinks.direct(html.replace("\"url\"","\"signatureCipher\"")))
        assertNull(MediaLinks.direct(html.replace("\"OK\"","\"LOGIN_REQUIRED\"")))
        assertFalse(MediaLinks.allowedRequest(URI("https://user:pass@rr1.googlevideo.com/videoplayback")))
        assertFalse(MediaLinks.allowedRequest(URI("http://rr1.googlevideo.com/videoplayback")))
        assertFalse(MediaLinks.allowedRequest(URI("https://rr1.googlevideo.com:8443/videoplayback")))
    }
    @Test fun rankingUsesRepeatableImprovementAndDoesNotRewardUnavailableMetadata() {
        val baseline=quality(4000)
        assertFalse(QualityPolicy.better(quality(4500),baseline,false))
        assertTrue(QualityPolicy.better(quality(6000),baseline,false))
        assertFalse(QualityPolicy.better(quality(6000,100,10),baseline,false))
        val unknown=ProbeQuality(true,6,1,MediaResult(MediaStatus.UNAVAILABLE))
        assertFalse(QualityPolicy.better(unknown,baseline,false))
        assertFalse(QualityPolicy.better(ProbeQuality(false,5,1,baseline.media),baseline,true))
        assertTrue(QualityPolicy.regression(baseline,quality(1500)))
    }
    @Test fun rollbackNeedsEvidenceAndNeverReturnsToAKnownStalledPredecessor() {
        val trial=Trial(1,2,1000,true,quality(4000))
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
    @Test fun mediaBudgetSurvivesClockRollbackAndResetsOnlyAfterTheWindow() {
        val b=ProbeBudget(1000,32*1024*1024L)
        assertFalse(b.prepare(500))
        assertFalse(b.prepare(1000))
        assertTrue(b.prepare(86402000))
        assertEquals(0L,b.spent)
        b.charge(100)
        assertEquals(100L,b.spent)
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
