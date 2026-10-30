package io.github.dovecoteescapee.byedpi.utility

object TuningScore {
    fun eligible(success: Int, total: Int): Boolean = total > 0 && success == total
}
