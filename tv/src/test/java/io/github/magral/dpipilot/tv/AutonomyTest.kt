package io.github.magral.dpipilot.tv

import org.junit.Assert.*
import org.junit.Test

class AutonomyTest {
    @Test fun previouslyWorkingBackupsPrecedeUntestedAndRejectedMethodsAreNotReused() {
        val order = TvPolicy.candidates(listOf(3,1,2),3,setOf(4))
        assertEquals(listOf(1,2),order.take(2))
        assertEquals(3,order.last())
        assertFalse(4 in order)
        assertEquals(order.size,order.distinct().size)
        assertEquals(TvPolicy.strategies.indices.toSet()-4,order.toSet())
        assertEquals(TvPolicy.adaptiveIndex,TvPolicy.candidates(emptyList(),null,emptySet()).first())
    }
    @Test fun onlyConfirmedSelectionEntersThreeItemBackupHistory() {
        val old = TvPolicy.history("2,2,invalid,-1,3,100,4,5")
        assertEquals(listOf(2,3,4),old)
        assertEquals(listOf(3,2,4),TvPolicy.remember(old,3))
        assertEquals(old,TvPolicy.history(old.joinToString(",")))
    }
    @Test fun idlePlaybackSmallTelemetryAndCounterResetDoNotLookLikeAStall() {
        val watcher = TrafficWatchdog()
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(1000,0,0,0)))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(46000,0,0,0)))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(91000,12,4000,1500)))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(136000,500,50000,2000000)))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(181000,1,100,100)))
    }
    @Test fun sustainedRequestsWithoutResponsesTriggerAutonomousRecovery() {
        val watcher = TrafficWatchdog()
        watcher.stalled(TrafficWatchdog.Sample(1000,0,0,0))
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(20000,50,20000,500)))
        assertTrue(watcher.stalled(TrafficWatchdog.Sample(46000,60,22000,1000)))
        watcher.reset()
        assertFalse(watcher.stalled(TrafficWatchdog.Sample(48000,60,22000,1000)))
    }
    @Test fun watchdogDoesNotKillAnIdleButAliveServiceOrLoopAfterClockReset() {
        assertFalse(RecoveryPolicy.hung(200000,190000))
        assertTrue(RecoveryPolicy.hung(400000,100000))
        assertFalse(RecoveryPolicy.hung(1000,500000))
        assertFalse(RecoveryPolicy.allowRestart(2000000,1900000))
        assertTrue(RecoveryPolicy.allowRestart(4000000,1900000))
        assertTrue(RecoveryPolicy.allowRestart(1000,500000))
    }
}
