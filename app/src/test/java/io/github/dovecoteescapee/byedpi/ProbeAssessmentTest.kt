package io.github.dovecoteescapee.byedpi

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ProbeAssessmentTest {
    private fun rows(target: String,extra: Boolean = true): JSONArray = JSONArray().apply {
        for (round in 1..2) for (endpoint in ServiceTargets.target(target).endpoints) put(JSONObject()
            .put("host",endpoint.host).put("kind",endpoint.kind).put("round",round).put("skipped",false)
            .put("ok",endpoint.required || extra).put("public",endpoint.kind in listOf("youtube","image","instagram")))
    }
    @Test fun successfulSiteAndJpegAreNotRejectedByAnUnresponsiveRedirector() {
        val result = JSONObject().put("rows",rows("youtube",false))
        val score = ProbeAssessment.evaluate("youtube",result)
        assertTrue(score.eligible); assertFalse(score.complete)
        assertEquals(4,score.requiredSuccess); assertEquals(4,score.requiredTotal)
        assertEquals(0,score.extraSuccess); assertEquals(4,score.extraTotal)
    }
    @Test fun missingDuplicateSkippedOrInvalidContentCannotPassRequiredChecks() {
        val good = rows("youtube")
        fun score(rows: JSONArray) = ProbeAssessment.evaluate("youtube",JSONObject().put("rows",rows))
        assertTrue(score(good).complete)
        val skipped = JSONArray(good.toString()); skipped.getJSONObject(0).put("skipped",true); assertFalse(score(skipped).eligible)
        val html = JSONArray(good.toString()); html.getJSONObject(1).put("public",false); assertFalse(score(html).eligible)
        val missing = JSONArray(good.toString()); missing.remove(0); assertFalse(score(missing).eligible)
        val duplicate = JSONArray(good.toString()); duplicate.put(JSONObject(duplicate.getJSONObject(0).toString())); assertFalse(score(duplicate).eligible)
        assertFalse(ProbeAssessment.evaluate("youtube",JSONObject().put("success",8).put("total",8).put("publicEndpointsPassed",true)).eligible)
        assertFalse(ProbeAssessment.evaluate("youtube",JSONObject().put("rows",good).put("error","worker stopped")).eligible)
    }
    @Test fun instagramStillNeedsEveryTransportAndCombinedGuardsStayIndependent() {
        val instagram = rows("instagram")
        instagram.getJSONObject(0).put("public",false) // Anonymous restriction does not invalidate TLS.
        assertTrue(ProbeAssessment.evaluate("instagram",JSONObject().put("rows",instagram)).eligible)
        instagram.getJSONObject(1).put("ok",false)
        val combined = JSONArray(instagram.toString())
        val youtube = rows("youtube",false)
        for (i in 0 until youtube.length()) combined.put(youtube.getJSONObject(i))
        val result = JSONObject().put("rows",combined)
        assertTrue(ProbeAssessment.evaluate("youtube",result).eligible)
        assertFalse(ProbeAssessment.evaluate("instagram",result).eligible)
        assertFalse(ProbeAssessment.evaluate(MobilePlan.BOTH,result).eligible)
    }
    @Test fun tvCommandsAreStableAndTriedBeforePlainControl() {
        assertEquals(listOf("665dfb8a8725932b","b55013dea302bbb1","af0c588ecde5769d"),ServiceTargets.youtubeTvPreferred.map { CatalogProtocol.id(it) })
        val target = ServiceTargets.target("youtube")
        val window = MobileStrategyPolicy.ordered(StrategyCatalog.ids,target.preferred,StrategyCatalog.commands,null,0)
        assertEquals(listOf(35,9,50),window.take(3))
        val ids = window.map { StrategyCatalog.ids[it] }
        assertTrue(ids.indexOf(CatalogProtocol.id("")) > ids.indexOf("665dfb8a8725932b"))
    }
    @Test fun startingBothKeepsInstagramAndReplacesOnlyAnUnverifiedYoutubeSeed() {
        val instagram = "--disorder 1 --tlsrec 1+s"
        val values = mutableMapOf("mobile_target" to "both","mobile_command" to MobilePlan.create(instagram,"--disorder 1"),"tuned_instagram" to instagram)
        val prefs = java.lang.reflect.Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
            when (method.name) { "getString" -> values[args!![0]] ?: args[1]; else -> error("Unexpected preference method: ${method.name}") }
        } as SharedPreferences
        val plan = MobilePlan.saved(prefs)
        assertEquals(instagram,MobilePlan.command(plan,"instagram"))
        assertEquals(ServiceTargets.youtubeStartup,MobilePlan.command(plan,"youtube"))
        values["tuned_youtube"] = "--split 2"
        assertEquals("--split 2",MobilePlan.command(MobilePlan.saved(prefs),"youtube"))
    }
    @Test fun automaticDnsUsesCurrentRouterAndManualModeKeepsItsExplicitValue() {
        assertEquals(listOf("192.168.0.1"),MobileNetwork.selectDns(listOf("192.168.0.1","192.168.0.1"),true,"1.1.1.1"))
        assertEquals(listOf("9.9.9.9"),MobileNetwork.selectDns(listOf("192.168.0.1"),false,"9.9.9.9"))
        assertEquals(listOf("1.1.1.1"),MobileNetwork.selectDns(emptyList(),true,""))
        assertTrue(MobileNetwork.selectDns(listOf("192.168.0.1"),false,"").isEmpty())
    }
}
