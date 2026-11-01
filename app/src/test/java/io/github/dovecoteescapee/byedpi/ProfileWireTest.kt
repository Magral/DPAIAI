package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.catalog.*
import org.junit.Test
import org.junit.Assert.*

class ProfileWireTest {
    @Test fun singleTvProfileAndPhonePairRoundTripWithoutCredentialsOrForeignFiles() {
        val tv = ProfileWire.encode(mapOf("youtube" to "--split 1"))
        assertEquals(mapOf("youtube" to "--split 1"),ProfileWire.decode(tv))
        val pair = mapOf("youtube" to "--disorder 1","instagram" to "--tlsrec 1+s")
        assertEquals(pair,ProfileWire.decode(ProfileWire.encode(pair)))
        assertTrue(runCatching { ProfileWire.encode(mapOf("youtube" to "--fake-data /tmp/file")) }.isFailure)
        assertTrue(runCatching { ProfileWire.encode(mapOf("other" to "--split 1")) }.isFailure)
        assertTrue(runCatching { ProfileWire.decode(tv.replace(CatalogProtocol.engine,"other")) }.isFailure)
        assertTrue(runCatching { ProfileWire.decode(tv+" ".repeat(17000)) }.isFailure)
    }
}
