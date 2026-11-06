package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class PreviewQualityTest {
    // Android's compile API omits java.desktop. Reflection keeps the fixture codec on the host JVM only.
    private val codec get() = Class.forName("javax.imageio.ImageIO")
    private fun jpeg(width: Int = 480,height: Int = 360): ByteArray {
        val type = Class.forName("java.awt.image.BufferedImage")
        val n = java.lang.Integer.TYPE
        val image = type.getConstructor(n,n,n).newInstance(width,height,1)
        val pixel = type.getMethod("setRGB",n,n,n)
        for(y in 0 until height) for(x in 0 until width) pixel.invoke(image,x,y,(x*37+y*13)*123457)
        val out = ByteArrayOutputStream()
        codec.getMethod("write",Class.forName("java.awt.image.RenderedImage"),String::class.java,java.io.OutputStream::class.java).invoke(null,image,"jpeg",out)
        return out.toByteArray()
    }
    private fun response(body: ByteArray,status: Int = 200,capped: Boolean = false) =
        HttpCodec.Response(status,mapOf("content-type" to "image/jpeg"),body,capped)
    private fun decode(bytes: ByteArray): PreviewBody.Size? {
        val image = codec.getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(bytes)) ?: return null
        return PreviewBody.Size(image.javaClass.getMethod("getWidth").invoke(image) as Int,image.javaClass.getMethod("getHeight").invoke(image) as Int)
    }
    @Test fun completeDecodableImagesPassButTheOldOneKilobytePrefixAndPlaceholdersDoNot() {
        val full = jpeg()
        assertTrue(full.size > 1024)
        assertTrue(PreviewBody.accepts(response(full),::decode))
        assertFalse(PreviewBody.accepts(response(full.copyOf(1024)),::decode))
        assertFalse(PreviewBody.accepts(response(full,capped=true),::decode))
        assertFalse(PreviewBody.accepts(response(full,206),::decode))
        assertFalse(PreviewBody.accepts(response(jpeg(120,90)),::decode))
        assertFalse(PreviewBody.accepts(response(full.copyOf(full.size-20)),::decode))
        assertFalse(PreviewBody.accepts(response(full)) { null })
    }
    @Test fun optionalPreviewQualityNeverVetoesSixAccessibleNodes() {
        val good = PreviewFixtures.transfers()
        val variants = listOf(PreviewQuality(),good.copy(samples=good.samples.dropLast(1)),
            good.copy(samples=List(6) { good.samples.first() }),PreviewFixtures.transfers(8100),
            PreviewFixtures.transfers(100,3001),
            good.copy(samples=good.samples.mapIndexed { i,s -> if(i==2) s.copy(valid=false) else s }))
        for (preview in variants) {
            assertTrue(ProbeQuality(true,6,1,preview).eligible)
            assertTrue(TvProbe.Result(true,"nodes pass",6,ProbeQuality(true,6,1,preview)).ok)
        }
        assertFalse(variants[1].usable)
        assertFalse(variants[2].usable)
        assertFalse(variants[3].responsive)
        assertFalse(variants[4].responsive)
        assertFalse(variants[5].usable)
        assertTrue(variants[3].score > good.score)
        assertFalse(ProbeQuality(true,3,1,good).eligible)
        assertFalse(ProbeQuality(false,6,1,good).eligible)
    }
    @Test fun stablePreviewTransfersRankFirstButTimingAloneNeverBansWorkingAccess() {
        val stable = ProbeQuality(true,6,800,PreviewFixtures.transfers(100))
        val slow = ProbeQuality(true,6,1,PreviewFixtures.transfers(2200))
        assertTrue(slow.eligible)
        assertFalse(QualityPolicy.better(slow,stable,false))
        assertFalse(TvProbe.Result(true,"fixture unavailable",6,stable,inconclusive=true).ok)
        assertEquals(1,TvCheckPolicy.fastest(1 to stable,listOf(2 to slow)))
        assertFalse(QualityPolicy.regression(stable,slow))
        assertTrue(QualityPolicy.regression(stable,slow.copy(passed=3)))
        assertFalse(QualityPolicy.regression(stable,stable.copy(preview=PreviewFixtures.transfers(110))))
        val saved = PreviewQuality.decode(stable.preview.encode())
        assertEquals(stable.preview,saved)
        assertTrue(saved.usable)
    }
    @Test fun webpLengthAndHttpFramingMustBeCompleteEvenBeforeDecoding() {
        val bytes = ByteArray(2048)
        "RIFF".toByteArray().copyInto(bytes,0);"WEBP".toByteArray().copyInto(bytes,8)
        val length=bytes.size-8
        for(i in 0..3) bytes[4+i]=(length ushr (8*i)).toByte()
        val ok = HttpCodec.Response(200,mapOf("content-type" to "image/webp"),bytes)
        assertTrue(PreviewBody.accepts(ok) { PreviewBody.Size(480,360) })
        assertFalse(PreviewBody.accepts(ok.copy(body=bytes.copyOf(1024))) { error("Truncation must be rejected before decode") })
        assertFalse(PreviewBody.accepts(ok.copy(headers=ok.headers+("content-range" to "bytes 0-2047/4096"))) { error("Partial entities cannot pass") })
    }
}
