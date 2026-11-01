package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.catalog.StrategyCatalog
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class MobilePlanTest {
    @Test fun replacingYoutubeKeepsInstagramAndScopesEveryAdaptiveGroup() {
        val instagram = "--split 1 --auto torst,ssl_err --tlsrec 1+s"
        val value = MobilePlan.create(instagram,"--disorder 1")
        val next = MobilePlan.replace(value,"youtube","--split 2 --auto torst --disorder 1")
        assertEquals(instagram,MobilePlan.command(next,"instagram"))
        val args = MobilePlan.arguments(next,1080).toList()
        assertEquals(4,args.count { it == "--hosts" })
        assertEquals(2,args.count { it == ":"+MobilePlan.domains.getValue("instagram").joinToString(" ") })
        assertEquals(listOf("--auto","none"),args.takeLast(2))
        assertEquals(value,MobilePlan.replace(value,"youtube","--disorder 1"))
    }
    @Test fun wholeBundledBankFitsTwoIndependentNativeProfiles() {
        val commands = StrategyCatalog.commands
        for (command in commands) {
            assertTrue(MobilePlan.valid(MobilePlan.create(command,commands.maxBy { it.length })))
            assertTrue(MobilePlan.valid(MobilePlan.create(commands.maxBy { it.count { c -> c == ' ' } },command)))
        }
        assertFalse(MobilePlan.valid("""{"schema":1,"instagram":"--ip 0.0.0.0","youtube":""}"""))
        assertFalse(MobilePlan.valid("""{"schema":1,"instagram":"","youtube":"","extra":"ignored"}"""))
        assertFalse(MobilePlan.valid("{broken"))
        val huge = (1..16).joinToString(" ") { "--auto torst --split 1" }
        assertTrue(MobilePlan.valid(MobilePlan.create(huge,huge)))
        assertFalse(runCatching { MobilePlan.arguments(MobilePlan.create(huge,huge),1080) }.isSuccess)
    }
    @Test fun exportExactProductionArgvForNativeRoutingIntegration() {
        val fixtures = JSONObject()
            .put("router_instagram",JSONArray(MobilePlan.arguments("--mod-http h",19410).toList()))
            .put("router_youtube",JSONArray(MobilePlan.arguments("--mod-http r",19420).toList()))
            .put("router_youtube_next",JSONArray(MobilePlan.arguments("--mod-http h",19421).toList()))
            .put("router_instagram_tls",JSONArray(MobilePlan.arguments("--tlsrec 1",19410).toList()))
            .put("router_youtube_tls",JSONArray(MobilePlan.arguments("",19420).toList()))
            .put("http",JSONArray(MobilePlan.arguments(MobilePlan.create("--mod-http h --auto torst,ssl_err --mod-http h","--mod-http r"),1080).toList()))
            .put("tv36",JSONArray(MobilePlan.arguments(MobilePlan.create("--tlsrec 1",ServiceTargets.youtubeStartup),1080).toList()))
            .put("tls",JSONArray(MobilePlan.arguments(MobilePlan.create("--tlsrec 1",""),1080).toList()))
        assertEquals(3,MobilePlan.arguments(MobilePlan.create("",""),1080).count { it == "--auto" })
        System.getenv("RUNNER_TEMP")?.let { File(it,"mobile-dual-fixtures.json").writeText(fixtures.toString()) }
    }
}
