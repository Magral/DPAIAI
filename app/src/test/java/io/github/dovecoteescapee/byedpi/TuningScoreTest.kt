package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.TuningScore
import org.junit.Assert.*
import org.junit.Test

class TuningScoreTest {
    @Test fun onlyCompleteRepeatedChecksCanWin() {
        assertFalse(TuningScore.eligible(4,6))
        assertTrue(TuningScore.eligible(6,6))
        assertFalse(TuningScore.eligible(0,0))
    }
}
