package io.github.dovecoteescapee.byedpi.utility

object TuningScore {
    fun eligible(target: String, success: Int, total: Int, nativeSuccess: Int, nativeTotal: Int): Boolean =
        if (target == "telegram") nativeTotal > 0 && nativeSuccess == nativeTotal
        else total > 0 && success == total
}
