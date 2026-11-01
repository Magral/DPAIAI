package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.catalog.CheckSchedule
import io.github.dovecoteescapee.byedpi.utility.TrialGuard
import org.junit.Test
import org.junit.Assert.*

class CheckScheduleTest {
    @Test fun trialCannotConfirmEarlyOrAcrossNetworkAndRejectsAnyObservedFailure() {
        fun decision(now: Long,probe: Boolean = true,stalled: Boolean = false,network: Boolean = true,request: Boolean = true) = TrialGuard.decide(now,120000,probe,stalled,true,network,request)
        assertEquals(TrialGuard.Decision.WAIT,decision(119999))
        assertEquals(TrialGuard.Decision.CONFIRM,decision(120000))
        assertEquals(TrialGuard.Decision.REJECT,decision(120001,probe=false))
        assertEquals(TrialGuard.Decision.REJECT,decision(120001,stalled=true))
        assertEquals(TrialGuard.Decision.REJECT,decision(120001,network=false))
        assertEquals(TrialGuard.Decision.REJECT,decision(120001,request=false))
    }
    @Test fun verifiedQuietProfileHasNoPeriodicProbeAndResumeDoesNotTriggerIt() {
        assertEquals(Long.MAX_VALUE,CheckSchedule.delay(false,true))
        assertEquals(Long.MAX_VALUE,CheckSchedule.delay(false,false))
        assertFalse(CheckSchedule.onResume(false,true,false))
        assertTrue(CheckSchedule.onResume(false,false,false))
        assertTrue(CheckSchedule.onResume(false,true,true))
        assertEquals(3,CheckSchedule.candidates(false,false))
        assertEquals(12,CheckSchedule.candidates(false,true))
        assertEquals(3600000L,CheckSchedule.delay(true,true))
    }
}
