package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

class DnsAndVideoTest {
    private fun reply(q: ByteArray,flags: Int=0x8180): ByteArray {
        val out=ByteArrayOutputStream();val d=DataOutputStream(out)
        d.write(q,0,2);d.writeShort(flags);d.writeShort(1);d.writeShort(1);d.writeInt(0)
        d.write(q,12,q.size-12);d.writeShort(0xc00c);d.writeShort(1);d.writeShort(1);d.writeInt(120);d.writeShort(4);d.write(byteArrayOf(8,8,8,8))
        return out.toByteArray()
    }
    @Test fun dnsRequiresMatchingTransactionQuestionAndPublicCompleteAddress() {
        val q=DnsResolver.query("youtube.com",1234)
        assertEquals("8.8.8.8" to 120L,DnsResolver.answer(reply(q),q))
        for(bad in listOf(reply(q).apply { this[0]=0 },reply(q,0x8183),reply(q).copyOf(15),reply(q).apply { this[13]='x'.code.toByte() },reply(q).apply { this[size-4]=127 })) {
            assertTrue(runCatching { DnsResolver.answer(bad,q) }.isFailure)
        }
        assertTrue(DnsResolver.providers.all { DnsResolver.valid(it.address) })
        assertFalse(DnsResolver.valid("999.1.1.1"));assertFalse(DnsResolver.valid("http://dns.google/dns-query"))
    }
    @Test fun playerExtractionHandlesNestedObjectsAndQuotedBracesWithoutExecutingScripts() {
        val page="prefix ytInitialPlayerResponse = {\"streamingData\":{\"formats\":[{\"url\":\"https://r1.googlevideo.com/videoplayback?x=}\\\"\"}]}};suffix"
        assertNotNull(MobileVideoProbe.player(page)?.optJSONObject("streamingData"))
        assertNull(MobileVideoProbe.player("consent/login"))
        assertTrue(MobileVideoProbe.mediaUrl("https://rr1---sn-test.googlevideo.com/videoplayback?expire=123"))
        for(url in listOf("http://r1.googlevideo.com/videoplayback","https://googlevideo.com.evil.invalid/videoplayback","https://user@r1.googlevideo.com/videoplayback","https://r1.googlevideo.com:8443/videoplayback"))assertFalse(MobileVideoProbe.mediaUrl(url))
    }
}
