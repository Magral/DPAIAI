package io.github.magral.dpipilot.tv

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class KnownStrategiesTest {
    /** In-memory Android preference contract; exercises real JSON persistence without a device. */
    private fun preferences(): SharedPreferences {
        val data = mutableMapOf<String,Any?>()
        val staged = mutableMapOf<String,Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _,method,args ->
            when (method.name) {
                "putString" -> { staged[args!![0] as String] = args[1]; editor }
                "remove" -> { staged[args!![0] as String] = null; editor }
                "apply", "commit" -> {
                    staged.forEach { (k,v) -> if (v == null) data.remove(k) else data[k] = v }
                    staged.clear(); if (method.name == "commit") true else null
                }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _,method,args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> data[args!![0] as String] ?: args[1]
                else -> error("Unexpected prefs method ${method.name}")
            }
        } as SharedPreferences
    }
    @Test fun successfulScoutsSurviveRestartWithoutApplyingThemAndStayScopedToNetwork() {
        val prefs = preferences()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        for (index in 1..6) store.approved(index,ProbeQuality(true,6,100+index.toLong()),1000)
        store.failed(7)
        val restored = QualityStore(prefs).apply { network("wifi-a") }
        assertEquals((1..6).toSet(),restored.choices(emptyList()).toSet())
        assertFalse(7 in restored.choices(emptyList()))
        assertTrue(restored.describe(1).contains("1/1"))
        restored.network("wifi-b")
        assertTrue(restored.choices(emptyList()).isEmpty())
        restored.network("wifi-a")
        assertEquals(6,restored.choices(emptyList()).size)
    }
    @Test fun missingStableIdsCannotSelectAnotherCommandAndLegacySuccessIsIncludedOnce() {
        val ids = RuntimeCatalog.ids
        assertEquals(listOf(5,1,2),TvPolicy.selectable(listOf(ids[5],"removed",ids[1],ids[5]),listOf(ids[2],ids[1])))
        assertTrue(TvPolicy.selectable(listOf("removed"),emptyList()).isEmpty())
    }
}
