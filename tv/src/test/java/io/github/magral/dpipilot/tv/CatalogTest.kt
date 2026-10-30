package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test fun legacySavedStrategiesKeepTheirIdentityAndMissingIdsCannotSelectAnotherCommand() {
        assertEquals("",TvPolicy.strategies[0])
        assertEquals("--split 1",TvPolicy.strategies[1])
        assertEquals(7,TvPolicy.adaptiveIndex)
        assertEquals(listOf(3,1),TvPolicy.savedHistory("${StrategyCatalog.ids[3]},missing,${StrategyCatalog.ids[1]}","2"))
        assertTrue(TvPolicy.savedHistory("removed","2").isEmpty())
        assertEquals(listOf(2,1),TvPolicy.savedHistory(null,"2,1"))
        assertEquals(TvPolicy.strategies.size,StrategyCatalog.ids.toSet().size)
        assertTrue(TvPolicy.strategies.size > 60)
    }
    @Test fun boundedResumableWindowsEventuallyCoverTheEntireBankWithoutActiveOrBlockedEntries() {
        val active = 7
        val blocked = setOf(4,5)
        val seen = mutableSetOf<Int>()
        var cursor = 0
        repeat(TvPolicy.strategies.size) {
            val window = TvPolicy.scanWindow(listOf(1,2),active,blocked,cursor)
            assertTrue(window.size <= 12)
            assertEquals(window.size,window.distinct().size)
            assertFalse(active in window)
            assertFalse(window.any { it in blocked })
            assertEquals(listOf(1,2),window.take(2))
            seen.addAll(window)
            cursor = (window.last()+1)%TvPolicy.strategies.size
        }
        assertEquals(TvPolicy.strategies.indices.toSet()-active-blocked,seen)
    }
    @Test fun veryLongAutomaticProfilesHaveABoundedDeadline() {
        assertTrue(TvPolicy.strategies.indices.all { TvPolicy.probeReadTimeout(it) in 12000..60000 })
    }
}
