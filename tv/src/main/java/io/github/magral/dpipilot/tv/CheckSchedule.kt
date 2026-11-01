package io.github.magral.dpipilot.tv

/** Quiet is the product default: a verified strategy has no scheduled network deadline. */
object CheckSchedule {
    const val QUIET_WAIT = Long.MAX_VALUE
    fun delay(periodic: Boolean,healthy: Boolean): Long = if (!periodic) QUIET_WAIT else if (healthy) 3600000 else 600000
    fun candidates(periodic: Boolean,manual: Boolean) = if (periodic || manual) 12 else 3
    fun onResume(periodic: Boolean,engineReady: Boolean,explicit: Boolean) = periodic || !engineReady || explicit
}
