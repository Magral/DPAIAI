package io.github.dovecoteescapee.byedpi.warp

import org.junit.Assert.*
import org.junit.Test

class WarpRecoveryPolicyTest {
    @Test fun retryBudgetDoesNotSpinOrResetSavedIdentity() {
        assertEquals(listOf(30000L,120000L,600000L,600000L), (0..3).map(WarpRecoveryPolicy::retryDelay))
        assertEquals(12000L,WarpRecoveryPolicy.HANDSHAKE_MS)
        assertEquals(120000L,WarpRecoveryPolicy.SWEEP_MS)
        assertFalse(WarpRecoveryPolicy.canAttempt(120000L))
        assertTrue(WarpRecoveryPolicy.canAttempt(119999L))
    }
    @Test fun diagnosticsAreValidOnlyForReadyCurrentTunnel() {
        val gate=WarpSession();val preparing=gate.restart()
        assertFalse(gate.valid(preparing));gate.connected(preparing);assertTrue(gate.valid(preparing))
        val next=gate.restart();assertFalse(gate.valid(preparing));assertFalse(gate.valid(next))
        gate.connected(preparing);assertFalse(gate.valid(next))
        gate.connected(next);assertTrue(gate.valid(next));gate.invalidate();assertFalse(gate.valid(next))
    }
    @Test fun diagnosticUidDoesNotTurnEmptyTargetListIntoGlobalOrOwnOnlyVpn() {
        assertEquals(setOf("com.google.android.youtube","io.github.magral.dpipilot"),WarpRecoveryPolicy.routedApps(setOf("com.google.android.youtube","com.game"),"io.github.magral.dpipilot"))
        try {WarpRecoveryPolicy.routedApps(setOf("com.game"),"io.github.magral.dpipilot");fail("No target app accepted")}catch(_: IllegalArgumentException){}
    }
}
