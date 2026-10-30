package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class TvPolicyTest {
    @Test fun rechecksSavedStrategyBeforeAlternativesAndCanForceReplacement() {
        val normal = TvPolicy.order(3,false)
        assertEquals(3,normal.first())
        assertEquals(TvPolicy.strategies.indices.toSet(),normal.toSet())
        assertEquals(normal.size,normal.distinct().size)
        val replacement = TvPolicy.order(3,true)
        assertNotEquals(3,replacement.first())
        assertEquals(3,replacement.last())
        assertEquals(TvPolicy.strategies.indices.toList(),TvPolicy.order(Int.MAX_VALUE,false))
    }
    @Test fun rejectedOrBlockedResponsesNeverCountAsWorkingContent() {
        assertTrue(TvPolicy.accepts("youtube",204,"",0))
        assertFalse(TvPolicy.accepts("youtube",200,"text/html",100))
        assertFalse(TvPolicy.accepts("youtube",429,"text/html",100))
        assertTrue(TvPolicy.accepts("image",206,"image/jpeg",1024))
        assertFalse(TvPolicy.accepts("image",200,"text/html",195))
        assertFalse(TvPolicy.accepts("image",200,"image/jpeg",0))
        assertFalse(TvPolicy.accepts("image",403,"image/jpeg",100))
    }
    @Test fun outagesBackOffWithoutAnUnboundedRetryStorm() {
        assertEquals(60000L,TvPolicy.backoffMillis(1))
        assertTrue(TvPolicy.backoffMillis(2) > TvPolicy.backoffMillis(1))
        assertEquals(600000L,TvPolicy.backoffMillis(50))
    }
}
