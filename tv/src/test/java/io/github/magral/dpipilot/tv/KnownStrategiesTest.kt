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
        for (index in 1..6) store.approved(index,PreviewFixtures.quality(true,6,100+index.toLong()),1000)
        store.failed(7)
        val restored = QualityStore(prefs).apply { network("wifi-a") }
        assertEquals((1..6).toSet(),restored.choices(emptyList()).toSet())
        assertFalse(7 in restored.choices(emptyList()))
        assertTrue(restored.describe(1).contains("качество превью подтверждено"))
        restored.network("wifi-b")
        assertTrue(restored.choices(emptyList()).isEmpty())
        restored.network("wifi-a")
        assertEquals(6,restored.choices(emptyList()).size)
    }
    @Test fun failuresStayExcludedAcrossRestartAndSuccessCannotSilentlyReviveThem() {
        val prefs = preferences()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        store.approved(1,PreviewFixtures.quality(true,6,100),1000)
        store.approved(2,PreviewFixtures.quality(true,6,40),1000)
        store.approved(3,PreviewFixtures.quality(true,6,200),1000)
        assertEquals(listOf(2,1,3),store.choices(emptyList()))
        store.failed(2)
        store.approved(2,PreviewFixtures.quality(true,6,1),2000)
        val restored = QualityStore(prefs).apply { network("wifi-a") }
        assertEquals(setOf(2),restored.excluded())
        assertEquals(listOf(1,3),restored.choices(listOf(2)))
        restored.network("wifi-b")
        assertTrue(restored.excluded().isEmpty())
        restored.network("wifi-a")
        assertEquals(setOf(2),restored.excluded())
        restored.resetExclusions()
        assertEquals(listOf(1,3),restored.choices(emptyList()))
        restored.approved(2,PreviewFixtures.quality(true,6,40),3000)
        assertEquals(listOf(2,1,3),restored.choices(emptyList()))
        assertTrue(QualityStore(prefs).apply { network("wifi-a") }.excluded().isEmpty())
    }
    @Test fun excludedMissingCatalogIdSurvivesUnrelatedHistoryWrites() {
        val prefs = preferences()
        prefs.edit().putString("quality.wifi-a", "{\"removed-command\":{\"excluded\":true,\"fail\":1}}").apply()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        store.approved(1,PreviewFixtures.quality(true,6,100),1000)
        val saved = org.json.JSONObject(prefs.getString("quality.wifi-a","{}").orEmpty())
        assertTrue(saved.getJSONObject("removed-command").getBoolean("excluded"))
        assertFalse(store.summary().contains("№0"))
    }
    @Test fun oldCountersAndLegacyHistoryNeverQualifyWithoutAnExplicitCompleteResult() {
        val prefs = preferences()
        val id = RuntimeCatalog.ids[1]
        prefs.edit().putString("quality.wifi-a",org.json.JSONObject().put(id,
            org.json.JSONObject().put("ok",3).put("fail",3).put("latency",1).put("excluded",false)).toString()).apply()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        assertTrue(store.choices(listOf(1,2)).isEmpty())
        store.approved(1,PreviewFixtures.quality(true,6,100),1000)
        assertEquals(listOf(1),store.choices(listOf(2)))
        assertTrue(store.describe(1).contains("Последняя проверка: узлы 6/6"))
        assertTrue(store.describe(1).contains("успешных 4, отказов 3"))
    }
    @Test fun partialFastCandidateCannotEnterMenuOrReturnAfterResetAndRestart() {
        val prefs = preferences()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        store.approved(1,PreviewFixtures.quality(true,6,200),1000)
        store.approved(2,PreviewFixtures.quality(true,3,1),1000)
        assertEquals(listOf(1),store.choices(listOf(2)))
        assertTrue(store.describe(2).contains("узлы 3/6"))
        store.failed(1,quality=PreviewFixtures.quality(false,3,1))
        store.resetExclusions()
        assertTrue(store.choices(listOf(1,2)).isEmpty())
        assertTrue(QualityStore(prefs).apply { network("wifi-a") }.choices(listOf(1,2)).isEmpty())
        store.approved(2,PreviewFixtures.quality(true,6,50),2000)
        assertEquals(listOf(2),store.choices(emptyList()))
    }
    @Test fun oldSixOfSixWithoutDeliveryProofIsHiddenAndAUserComplaintOverridesSyntheticSuccess() {
        val prefs = preferences();val id = RuntimeCatalog.ids[1]
        prefs.edit().putString("quality.wifi-a",org.json.JSONObject().put(id,org.json.JSONObject().put("ok",2).put("last_complete",true).put("last_passed",6)).toString()).apply()
        val store = QualityStore(prefs).apply { network("wifi-a") }
        assertTrue(store.choices(listOf(1)).isEmpty())
        store.approved(1,PreviewFixtures.quality(true,6,100),1000)
        assertEquals(listOf(1),store.choices(emptyList()))
        store.failed(1,user=true)
        assertTrue(store.choices(listOf(1)).isEmpty())
        store.resetExclusions()
        assertTrue(store.choices(listOf(1)).isEmpty())
        assertTrue(store.describe(1).contains("жалоб 1"))
    }
    @Test fun missingStableIdsCannotSelectAnotherCommandAndLegacySuccessIsIncludedOnce() {
        val ids = RuntimeCatalog.ids
        assertEquals(listOf(5,1,2),TvPolicy.selectable(listOf(ids[5],"removed",ids[1],ids[5]),listOf(ids[2],ids[1])))
        assertTrue(TvPolicy.selectable(listOf("removed"),emptyList()).isEmpty())
    }
}
