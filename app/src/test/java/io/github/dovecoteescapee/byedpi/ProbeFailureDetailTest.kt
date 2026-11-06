package io.github.dovecoteescapee.byedpi
import io.github.dovecoteescapee.byedpi.utility.probeFailureDetail
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeFailureDetailTest {
    @Test fun failedEndpointIsVisibleEvenAfterManySuccessfulRows() {
        val result = JSONObject("""{"rows":[{"host":"good","ok":true},{"host":"cdn","ok":false,"detail":"Read timed out"}]}""")
        val detail = probeFailureDetail(result)
        assertTrue(detail.contains("cdn: Read timed out"))
        assertFalse(detail.contains("good"))
        assertFalse(probeFailureDetail(JSONObject()).isBlank())
        assertTrue(probeFailureDetail(JSONObject().put("error","x".repeat(2000))).length <= 1200)
    }
}
