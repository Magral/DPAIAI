package io.github.dovecoteescapee.byedpi.utility

object TuningScore {
    fun lackOfReplies(packets: Long,sent: Long,received: Long): Boolean = packets >= 0 && sent >= 0 && received >= 0 && (packets >= 20 || sent >= 16*1024) && received < 64*1024
    fun eligible(success: Int, total: Int): Boolean = total > 0 && success == total
}
