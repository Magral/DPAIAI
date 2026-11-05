package io.github.magral.dpipilot.tv

object PreviewFixtures {
    fun transfers(ms: Long = 100,gap: Long = 20): PreviewQuality = PreviewQuality(PreviewQuality.sources.flatMap { source ->
        (1..2).map { round -> PreviewSample(source,round,16000,ms,gap,true) }
    })
    fun quality(controlOk: Boolean,passed: Int,latencyMs: Long) = ProbeQuality(controlOk,passed,latencyMs,transfers())
}
