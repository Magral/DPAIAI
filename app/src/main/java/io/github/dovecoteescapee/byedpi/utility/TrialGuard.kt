package io.github.dovecoteescapee.byedpi.utility

object TrialGuard {
    enum class Decision { WAIT,REJECT,CONFIRM }
    fun decide(now: Long,deadline: Long,probe: Boolean,stalled: Boolean,engine: Boolean,sameNetwork: Boolean,sameRequest: Boolean): Decision =
        if (!probe || stalled || !engine || !sameNetwork || !sameRequest) Decision.REJECT
        else if (now < deadline) Decision.WAIT else Decision.CONFIRM
}
