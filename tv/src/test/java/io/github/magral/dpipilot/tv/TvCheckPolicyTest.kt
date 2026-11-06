package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class TvCheckPolicyTest {
    @Test fun oneFailureConfirmationProtectsKnownAndPartialStrategiesWithoutRevivingDeadUnknowns() {
        assertTrue(TvCheckPolicy.confirmFailure(false,false,0,true))
        for (passed in 1..5) assertTrue(TvCheckPolicy.confirmFailure(false,false,passed,false))
        assertFalse(TvCheckPolicy.confirmFailure(false,false,0,false))
        assertFalse(TvCheckPolicy.confirmFailure(true,false,6,true))
        assertFalse(TvCheckPolicy.confirmFailure(false,true,3,true))
        // Repeated partial tests remain failures: their successes must never be summed.
        val first = TvProbe.Result(false,"first",3,ProbeQuality(false,3,1))
        val second = TvProbe.Result(false,"second",3,ProbeQuality(false,3,1))
        assertFalse(first.ok)
        assertFalse(second.ok)
        assertTrue(TvProbe.Result(true,"confirmed",6,ProbeQuality(true,6,200)).ok)
    }
    @Test fun samePersistedStartBlocksChecksAcrossRebootAndFailuresForThreeDays() {
        val started = 100000L
        assertFalse(TvCheckPolicy.shouldCheck(true,false,started,started+3600000))
        assertFalse(TvCheckPolicy.shouldCheck(true,false,started,started+TvCheckPolicy.INTERVAL-1))
        assertTrue(TvCheckPolicy.shouldCheck(true,false,started,started+TvCheckPolicy.INTERVAL))
        assertTrue(TvCheckPolicy.shouldCheck(true,true,started,started+1))
        assertTrue(TvCheckPolicy.shouldCheck(false,true,started,started+1))
        assertFalse(TvCheckPolicy.shouldCheck(false,false,started,started+TvCheckPolicy.INTERVAL))
        assertTrue(TvCheckPolicy.shouldCheck(true,false,0,started))
        assertFalse(TvCheckPolicy.shouldCheck(true,false,started,started-1))
        assertEquals(TvCheckPolicy.INTERVAL,TvCheckPolicy.remaining(started,started-1))
    }
    @Test fun completeResultIsMandatoryRegardlessOfBooleanOrLowLatency() {
        val good = PreviewFixtures.quality(true,6,200)
        for (passed in listOf(0,1,3,5,7)) {
            val partial = PreviewFixtures.quality(true,passed,1)
            assertFalse(partial.eligible)
            assertFalse(TvProbe.Result(true,"partial",passed,partial).ok)
            assertFalse(TvProbe.Result(true,"inconsistent",passed,good).ok)
            assertFalse(QualityPolicy.better(partial,good,true))
            assertTrue(QualityPolicy.regression(good,partial))
            assertEquals(1,TvCheckPolicy.fastest(1 to good,listOf(2 to partial)))
        }
        assertFalse(TvProbe.Result(false,"failed",6,good).ok)
        assertTrue(TvProbe.Result(true,"complete",6,good).ok)
    }
    @Test fun minimumUsesUnclampedMillisecondsNotOldRankAndNeverSelectsPartialOrUnknownLatency() {
        val current = 1 to PreviewFixtures.quality(true,6,40000)
        val candidates = listOf(2 to PreviewFixtures.quality(true,6,35000),3 to PreviewFixtures.quality(false,5,1),4 to PreviewFixtures.quality(true,6,0))
        assertEquals(2,TvCheckPolicy.fastest(current,candidates))
        assertEquals(1,TvCheckPolicy.fastest(current,listOf(2 to current.second)))
        assertNull(TvCheckPolicy.fastest(null,listOf(3 to PreviewFixtures.quality(false,5,1))))
        assertEquals(listOf(2,1,3,4),TvCheckPolicy.ordered(listOf(3,1,2,4,1)) { mapOf(1 to 100L,2 to 40L,3 to 0L,4 to -1L).getValue(it) })
    }
}
