package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MobileSearchTest {
    @Test fun fastPassNeverPromotesTimeoutToNegativeEvidence() {
        assertEquals(5000,MobileSearchPolicy.timeout(60000,true))
        assertEquals(60000,MobileSearchPolicy.timeout(60000,false))
        val failure = JSONObject().put("rows",JSONArray().put(JSONObject().put("ok",false)))
        assertTrue(MobileSearchPolicy.conclusiveFailure(failure))
        for (flag in listOf("deferred","inconclusive","cancelled"))
            assertFalse(MobileSearchPolicy.conclusiveFailure(JSONObject(failure.toString()).put(flag,true)))
        failure.getJSONArray("rows").getJSONObject(0).put("timeout",true)
        assertFalse(MobileSearchPolicy.conclusiveFailure(failure))
        assertFalse(MobileSearchPolicy.conclusiveFailure(JSONObject().put("error","worker timeout")))
    }
    @Test fun generatorIsBoundedDistinctAndCannotIntroduceUnsupportedOperations() {
        val skip = StrategyCatalog.commands.toSet()
        val commands = StrategyGenerator.candidates(listOf("--split 1 --tlsrec 1+s","--disorder 1"),skip)
        assertTrue(commands.isNotEmpty());assertTrue(commands.size <= 120)
        assertEquals(commands.size,commands.distinct().size)
        assertTrue(commands.none { it in skip })
        assertTrue(commands.all { CatalogProtocol.validCommand(it) && MobilePlan.valid(it) })
        assertEquals(commands,StrategyGenerator.candidates(listOf("--split 1 --tlsrec 1+s","--disorder 1"),skip))
        assertTrue(StrategyGenerator.candidates(emptyList(),emptySet(),0).isEmpty())
        val unsafe=StrategyGenerator.candidates(listOf("--hosts /secret --split 2","--exec foo --split 3"))
        assertTrue(unsafe.all { CatalogProtocol.validCommand(it) })
        System.getenv("RUNNER_TEMP")?.let { directory ->
            Files.write(Paths.get(directory,"mobile-generator-fixtures.json"),JSONArray(commands).toString().toByteArray())
        }
    }
    @Test fun totalCounterTracksDeferredWorkWithoutCountingItTwice() {
        MobilePresentation.clear();MobilePresentation.beginScan("youtube",3)
        MobilePresentation.start("youtube","--split 1")
        MobilePresentation.checked("youtube",1);MobilePresentation.applied("youtube")
        assertTrue(MobilePresentation.summary().contains("Ищем более быструю"))
        MobilePresentation.checked("youtube",1);MobilePresentation.checked("youtube",1)
        MobilePresentation.secondPass("youtube",1)
        assertEquals(3,MobilePresentation.scan("youtube")!!.checked)
        assertTrue(MobilePresentation.summary().contains("Отложено: 1"))
        MobilePresentation.secondPass("youtube",0);MobilePresentation.endScan("youtube")
        assertEquals(3,MobilePresentation.scan("youtube")!!.checked)
        assertTrue(MobilePresentation.summary().contains("Уже работает"))
        assertFalse(MobilePresentation.summary().contains("Ищем более быструю"))
        MobilePresentation.clear()
    }
    @Test fun stoppingGeneratorInvalidatesPendingAndRunningRequests() {
        MobileGenerator.request("youtube",2)
        val request=MobileGenerator.take()!!;assertTrue(MobileGenerator.valid(request))
        MobileGenerator.update(request,2,10,1,1,"Проверка")
        MobileGenerator.stop();assertFalse(MobileGenerator.valid(request))
        MobileGenerator.update(request,10,10,9,0,"Старый результат")
        assertFalse(MobileGenerator.state.running);assertEquals(1,MobileGenerator.state.good)
        MobileGenerator.request("instagram",5);MobileGenerator.stop();assertNull(MobileGenerator.take())
    }
}
