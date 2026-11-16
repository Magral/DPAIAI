package io.github.magral.dpipilot.tv

import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PlaybackDiagnosticsTest {
    @Test fun boundWarpReportNamesItsRouteWithoutRecommendingPaidVpn() {
        val diagnostic=PlaybackDiagnostics({ _,_,_ -> response("consent/login") },{ _,_ -> emptyList() },routeDescription="собственный WARP VPN")
        val report=diagnostic.run(null,ProbeSession())
        assertTrue(report.contains("Маршрут: собственный WARP VPN"))
        assertFalse(report.contains("может отличаться от YouTube"))
        assertFalse(report.contains("платный VPN"))
    }
    @Test fun routeChangeInvalidatesDiscoveryBeforeAnyMediaRequest() {
        var changed=false;var media=0
        val diagnostic=PlaybackDiagnostics({ req,_,_ ->
            if(req.host=="www.youtube.com")changed=true
            if(req.path.startsWith("/videoplayback"))media++
            response("ytInitialPlayerResponse = {\"streamingData\":{\"formats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r1.googlevideo.com/videoplayback?sig=old-route\"}]}};")
        },{ _,_ -> emptyList() },{ if(changed)throw CancellationException("route changed") })
        try {diagnostic.run(null,ProbeSession());fail("Mixed-route report completed")}catch(_: CancellationException) { }
        assertEquals(0,media)
    }

    private fun response(body: String,status: Int=200,type: String="text/html")=HttpCodec.Response(status,mapOf("content-type" to type),body.toByteArray())
    @Test fun unavailablePlayerNeverProducesMediaSuccessOrChangesOtherEvidence() {
        val diagnostic=PlaybackDiagnostics({ _,_,_ -> response("consent/login") },{ _,_ -> listOf("203.0.113.1") })
        val report=diagnostic.run(null,ProbeSession())
        assertTrue(report.contains("МЕДИА: НЕ ПРОВЕРЕНО"));assertFalse(report.contains("МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН"))
    }
    @Test fun discoveredMediaReallyDownloadsAndSignedQueryIsNotInReport() {
        var media=0
        val diagnostic=PlaybackDiagnostics({ req,_,_ ->
            if(req.host.endsWith(".googlevideo.com") && req.path.startsWith("/videoplayback")) {
                media++;val bytes=ByteArray(65536);"ftyp".toByteArray().copyInto(bytes,4)
                HttpCodec.Response(206,mapOf("content-type" to "video/mp4"),bytes)
            } else response("ytInitialPlayerResponse = {\"streamingData\":{\"formats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r1.googlevideo.com/videoplayback?sig=PRIVATE\"}]}};")
        },{ _,_ -> emptyList() })
        val report=diagnostic.run(19080,ProbeSession())
        assertTrue(media>0);assertTrue(report.contains("65536"));assertFalse(report.contains("PRIVATE"));assertFalse(report.contains("?sig="))
    }
    @Test fun cancellationIsNotReportedAsANetworkFailure() {
        val session=ProbeSession();session.cancel()
        val diagnostic=PlaybackDiagnostics({ _,_,_ -> fail("Cancelled session made a request");response("") },{ _,_ -> emptyList() })
        try {diagnostic.run(null,session);fail("Cancellation swallowed")}catch(_: CancellationException) { }
    }
}
