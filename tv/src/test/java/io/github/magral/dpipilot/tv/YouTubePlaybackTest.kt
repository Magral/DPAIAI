package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackTest {
    @Test fun separateAudioIsNotLostBehindVideoFormats() {
        val player=org.json.JSONObject("""{"streamingData":{"formats":[{"mimeType":"video/mp4","url":"https://r1.googlevideo.com/videoplayback?v=1"}],"adaptiveFormats":[{"mimeType":"video/mp4","url":"https://r2.googlevideo.com/videoplayback?v=2"},{"mimeType":"video/webm","url":"https://r3.googlevideo.com/videoplayback?v=3"},{"mimeType":"audio/mp4","url":"https://r4.googlevideo.com/videoplayback?a=1"}]}}""")
        val tracks=YouTubePlayback.samples(player)
        assertEquals(listOf("video","audio"),tracks.map {it.kind})
        assertTrue(tracks.last().url.contains("r4.googlevideo.com"))
    }

    @Test fun tlsOnlyIsNeverMediaConfirmation() {
        assertEquals(YouTubePlayback.State.UNAVAILABLE,YouTubePlayback.overall(emptyList()))
    }
    @Test fun htmlAndTinyBodiesCannotBecomeVideoProof() {
        assertEquals(YouTubePlayback.State.FAILED,YouTubePlayback.assess(200,"text/html",ByteArray(65536),1000).state)
        assertEquals(YouTubePlayback.State.FAILED,YouTubePlayback.assess(206,"video/mp4",byteArrayOf(0,0,0,24,102,116,121,112),1000).state)
    }
    @Test fun expiredOrRateLimitedSampleDoesNotCondemnStrategies() {
        for(status in listOf(403,404,410,429))assertEquals(YouTubePlayback.State.UNAVAILABLE,YouTubePlayback.assess(status,"text/html",ByteArray(0),1000).state)
    }
    @Test fun actualBytesAndTransferTimeAreRequiredAndReported() {
        val bytes=ByteArray(65536);"ftyp".toByteArray().copyInto(bytes,4)
        val fast=YouTubePlayback.assess(206,"video/mp4",bytes,250)
        assertEquals(YouTubePlayback.State.CONFIRMED,fast.state);assertEquals(2097L,fast.kbps)
        val slow=YouTubePlayback.assess(206,"video/mp4",bytes,10000)
        assertEquals(YouTubePlayback.State.FAILED,slow.state)
        assertEquals(YouTubePlayback.State.FAILED,YouTubePlayback.overall(listOf(fast,slow)))
    }
    @Test fun signedUrlsStayOnOfficialMediaHosts() {
        assertTrue(YouTubePlayback.mediaUrl("https://rr1---sn-test.googlevideo.com/videoplayback?expire=123"))
        for(url in listOf("http://r1.googlevideo.com/videoplayback","https://googlevideo.com.evil.test/videoplayback","https://user@r1.googlevideo.com/videoplayback","https://r1.googlevideo.com:8443/videoplayback","https://r1.googlevideo.com/videoplayback#secret","https://r1.googlevideo.com/videoplayback?x=\r\nHost:evil"))assertFalse(url,YouTubePlayback.mediaUrl(url))
    }
    @Test fun publicPlayerParsingDoesNotRunJavascriptAndFindsAudioToo() {
        val html="ytInitialPlayerResponse = {\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r1.googlevideo.com/videoplayback?x=}\\\"\"},{\"mimeType\":\"audio/mp4\",\"url\":\"https://r2.googlevideo.com/videoplayback?sig=secret\"}]}};"
        val data=YouTubePlayback.player(html)
        assertNotNull(data);assertEquals(1,YouTubePlayback.urls(data).size)
        assertTrue(YouTubePlayback.urls(data).single().contains("r2.googlevideo.com"))
        assertNull(YouTubePlayback.player("consent/login"))
        assertTrue(YouTubePlayback.urls(null).isEmpty())
    }
}
