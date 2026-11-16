package io.github.dovecoteescapee.byedpi.warp

import org.junit.Assert.*
import org.junit.Test

class WarpRecoveryPolicyTest {
    @Test fun retriesEventuallyReachPortsAfterSilentEarlyTimeouts() {
        val candidates=(listOf(2408,500,4500,1701,443,988)).flatMap {port->listOf("plain","junk","reserved").map {"$port|$it"}}
        val queue=WarpAttempts(candidates)
        val visited=mutableListOf<String>()
        repeat(3) {repeat(10){visited.add(queue.next())}}
        assertTrue(visited.containsAll(candidates))
        assertEquals(candidates,visited.take(candidates.size))
    }
    @Test fun completedReportBecomesStaleWhenTunnelStopsOrChanges() {
        val gate=WarpSession();val ticket=gate.restart();gate.connected(ticket)
        val report=WarpDiagnosticReport(ticket,"МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН")
        assertEquals("МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН",report.display(gate))
        gate.invalidate();assertTrue(report.display(gate).startsWith("РЕЗУЛЬТАТ УСТАРЕЛ"))
        val other=gate.restart();gate.connected(other)
        assertTrue(report.display(gate).startsWith("РЕЗУЛЬТАТ УСТАРЕЛ"))
    }
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
