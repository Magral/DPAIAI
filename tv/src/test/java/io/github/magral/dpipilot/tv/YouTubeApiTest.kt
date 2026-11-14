package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class YouTubeApiTest {
    private val response="""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Playback requires a valid client. https://example.test/?token=PRIVATE"}}""".toByteArray()
    @Test fun publicPlayerJsonProvesApiTransportWithoutPretendingVideoWorks() {
        val answer=YouTubeApi.assess(200,"application/json; charset=UTF-8",response)
        assertTrue(answer.ok);assertFalse(answer.unavailable)
        assertTrue(answer.detail.contains("UNPLAYABLE"));assertTrue(answer.detail.contains("valid client"));assertFalse(answer.detail.contains("PRIVATE"))
    }
    @Test fun tlsHtmlAndUnrelatedJsonDoNotQualifyAsApi() {
        for((type,body) in listOf("text/html" to "<html>ok</html>","application/json" to "{}"))assertFalse(YouTubeApi.assess(200,type,body.toByteArray()).ok)
    }
    @Test fun unavailableSourceCannotCondemnAllStrategies() {
        for(status in listOf(400,401,403,404,410,429,503)) {
            val answer=YouTubeApi.assess(status,"text/html",byteArrayOf());assertFalse(answer.ok);assertTrue(answer.unavailable)
        }
    }
    @Test fun actualCheckerMakesTwoIndependentRequestsAndCannotHideOneFailure() {
        var requests=0
        val session=io.github.dovecoteescapee.byedpi.utility.ProbeSession()
        val check=YouTubeApi.check(19080,session,5000,2) { req,_,_ ->
            assertEquals(5000,req.timeoutMs);assertEquals(19080,req.proxyPort);assertNotNull(req.body)
            requests++
            if(requests==2)throw java.net.SocketTimeoutException("test timeout")
            HttpCodec.Response(200,mapOf("content-type" to "application/json"),response)
        }
        assertEquals(2,requests);assertFalse(check.ok);assertTrue(check.timedOut);assertFalse(check.unavailable)
        session.cancel()
        try {YouTubeApi.check(19080,session,5000,2) {_,_,_ -> fail("Cancelled check made a request");error("unexpected") };fail("Cancellation swallowed")}
        catch(_: java.util.concurrent.CancellationException) { }
    }
    @Test fun successRequiresEachFreshObservationNotJustOneGoodAttempt() {
        val good=YouTubeApi.assess(200,"application/json",response)
        val bad=YouTubeApi.assess(503,"text/html",byteArrayOf())
        assertFalse(YouTubeApi.confirmed(listOf(good),2));assertFalse(YouTubeApi.confirmed(listOf(good,bad),2))
        assertTrue(YouTubeApi.confirmed(listOf(good,good),2));assertFalse(YouTubeApi.confirmed(listOf(good,good,good),2))
    }
}
