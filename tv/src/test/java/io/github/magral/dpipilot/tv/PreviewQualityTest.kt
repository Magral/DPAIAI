package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class PreviewQualityTest {
    private fun jpeg(width: Int = 480,height: Int = 360): ByteArray {
        val image = BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        for(y in 0 until height) for(x in 0 until width) image.setRGB(x,y,(x*37+y*13)*123457)
        return ByteArrayOutputStream().also { ImageIO.write(image,"jpeg",it) }.toByteArray()
    }
    private fun response(body: ByteArray,status: Int = 200,capped: Boolean = false) =
        HttpCodec.Response(status,mapOf("content-type" to "image/jpeg"),body,capped)
    private fun decode(bytes: ByteArray): PreviewBody.Size? = ImageIO.read(ByteArrayInputStream(bytes))?.let { PreviewBody.Size(it.width,it.height) }
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
    @Test fun sixSuccessfulNodesCannotQualifyMissingDuplicatedSlowOrStallingPreviews() {
        val good = PreviewFixtures.transfers()
        assertTrue(ProbeQuality(true,6,100,good).eligible)
        assertFalse(ProbeQuality(true,6,1).eligible)
        assertFalse(ProbeQuality(true,6,1,good.copy(samples=good.samples.dropLast(1))).eligible)
        assertFalse(ProbeQuality(true,6,1,good.copy(samples=List(6) { good.samples.first() })).eligible)
        assertFalse(ProbeQuality(true,6,1,PreviewFixtures.transfers(8100)).eligible)
        assertFalse(ProbeQuality(true,6,1,PreviewFixtures.transfers(100,3001)).eligible)
        val partial = good.copy(samples=good.samples.mapIndexed { i,s -> if(i==2) s.copy(valid=false) else s })
        assertFalse(TvProbe.Result(true,"nodes pass",6,ProbeQuality(true,6,1,partial)).ok)
    }
    @Test fun stablePreviewTransfersOutrankLowerPingAndRepeatedPerformanceRegressionRollsBack() {
        val stable = ProbeQuality(true,6,800,PreviewFixtures.transfers(100))
        val slow = ProbeQuality(true,6,1,PreviewFixtures.transfers(2200))
        assertTrue(slow.eligible)
        assertFalse(QualityPolicy.better(slow,stable,false))
        assertFalse(TvProbe.Result(true,"fixture unavailable",6,stable,inconclusive=true).ok)
        assertEquals(1,TvCheckPolicy.fastest(1 to stable,listOf(2 to slow)))
        assertTrue(QualityPolicy.regression(stable,slow))
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
