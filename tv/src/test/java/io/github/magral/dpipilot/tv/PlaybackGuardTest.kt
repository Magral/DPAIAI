package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackGuardTest {
    @Test fun telemetryIsNotPlaybackAndUsefulFlowExpires() {
        val guard = PlaybackGuard()
        guard.observe(1000,0)
        guard.observe(6000,1024)
        assertFalse(guard.recent(6000))
        guard.observe(11000,300000)
        assertTrue(guard.recent(11000))
        assertFalse(guard.recent(101000))
    }
    @Test fun trickleClockRollbackAndNewTunnelCannotReuseOldProgress() {
        val guard = PlaybackGuard()
        guard.observe(1000,0)
        guard.observe(32000,100000)
        guard.observe(63000,200000)
        assertFalse(guard.recent(63000))
        guard.observe(68000,500000)
        assertTrue(guard.recent(68000))
        guard.observe(69000,0)
        assertFalse(guard.recent(69000))
        guard.observe(70000,300000)
        guard.observe(500,600000)
        assertFalse(guard.recent(500))
        guard.reset()
        assertFalse(guard.recent(70000))
    }
    @Test fun failedDiagnosticsNeverPromoteAnUntestedCandidateDuringRoutineChecks() {
        assertNull(SelectionPolicy.replacement(7,null,2,false,false))
        assertNull(SelectionPolicy.replacement(7,2,2,false,true))
        assertEquals(2,SelectionPolicy.replacement(7,2,null,false,false))
        assertEquals(2,SelectionPolicy.replacement(7,null,2,true,false))
        assertNull(SelectionPolicy.replacement(7,7,7,true,false))
        assertEquals(2,SelectionPolicy.replacement(7,2,null,true,true))
    }
    @Test fun sleepGapDoesNotTriggerAnImmediateTrafficReset() {
        val watcher = TrafficWatchdog()
        watcher.stalled(TrafficWatchdog.Sample(1000,0,0,0))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(1000000,25,20000,0)))
        assertTrue(watcher.stalled(TrafficWatchdog.Sample(1045000,55,40000,0)))
    }
    @Test fun stoppingDiagnosticsClosesTheInFlightSocketAndRejectsLateRegistration() {
        val session = TvProbe.Session()
        val current = java.net.Socket()
        session.register(current)
        session.cancel()
        assertTrue(current.isClosed)
        val late = java.net.Socket()
        try { session.register(late); fail("Cancelled diagnostic accepted a socket") }
        catch (_: java.util.concurrent.CancellationException) { assertTrue(late.isClosed) }
    }
    @Test fun diagnosticsAllowTimeForAllNativeFallbackStages() {
        val timeout = Regex("--timeout ([0-9]+)").find(TvPolicy.strategies[TvPolicy.adaptiveIndex])!!.groupValues[1].toInt()
        val fallbackCount = TvPolicy.strategies[TvPolicy.adaptiveIndex].split("--auto ").size-1
        assertTrue(TvPolicy.probeReadTimeout(TvPolicy.adaptiveIndex) > timeout*fallbackCount*1000)
        assertTrue(TvPolicy.probeReadTimeout(1) >= 10000)
    }
}
