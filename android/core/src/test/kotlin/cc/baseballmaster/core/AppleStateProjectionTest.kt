package cc.baseballmaster.core

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object NativeScoreSnapshot {
    fun from(g: Game): JsonObject {
        val raw=GameJson.encodeToJsonElement(g).jsonObject
        val fields=listOf("inning","isTop","balls","strikes","outs","homeBatterIndex","awayBatterIndex","appearancePitches","extraFoulUsed","bases","homeRuns","awayRuns","homeRunsByInning","awayRunsByInning","homeHits","awayHits","homeErrors","awayErrors","homeOrder","awayOrder","isFinal","pendingDecision","halfEndedAwaitingDecision","walkResponsiblePitcherID","strikeoutResponsibleBatterID")
        val active=g.bases.filterNotNull().toSet()
        return buildJsonObject {
            fields.forEach {put(it,raw.getValue(it))};put("pitcherID",g.pitcher.id)
            for(key in listOf("batting","pitching","fielding")) put(key,JsonObject(raw.getValue(key).jsonObject.filterValues {it.jsonObject.values.any {n ->n.jsonPrimitive.int!=0}}))
            put("runnerPitcherIDs",GameJson.encodeToJsonElement(g.runnerPitcherIDs.filterKeys {it in active}))
            put("unearnedRunnerIDs",GameJson.encodeToJsonElement(g.unearnedRunnerIDs.filter {it in active}.sorted()))
            put("automaticRunnerIDs",GameJson.encodeToJsonElement(g.automaticRunnerIDs.filter {it in active}.sorted()))
            put("clockRunning",g.clockRunningSince!=null);put("statisticsIncomplete",g.statisticsIncomplete);put("correctionCount",g.historyRevisions.size)
            put("pitcherInningUsage",GameJson.encodeToJsonElement(g.pitcherInnings.mapValues {it.value.sorted()}))
        }
    }
}

class AppleStateProjectionTest {
    private val root get()=File(System.getProperty("parity.fixtures"))
    private fun fixture()=GameJson.parseToJsonElement(root.resolve("scoring/pro-groundOut-scoring.json").readText()).jsonObject.getValue("iosInitialPayload").jsonObject
    @Test fun projectsAll109OriginalInitialStatesWithoutRewritingTheirWireData() {
        val files=root.walkTopDown().filter {it.isFile && it.name.endsWith("-scoring.json")}.toList();assertEquals(109,files.size)
        for(file in files) {
            val f=GameJson.parseToJsonElement(file.readText()).jsonObject;val stored=f.getValue("iosInitialPayload").jsonObject
            val original=stored.toString();val projection=AppleStateProjection.read(stored);val actual=NativeScoreSnapshot.from(projection.view())
            for((key,expected) in f.getValue("initial").jsonObject) assertEquals("${file.name}: $key",expected,actual[key])
            assertEquals(original,stored.toString());assertEquals(stored,projection.stored)
        }
    }
    @Test fun futureFieldsAndNestedHistoryStayOpaqueAndInspectionIsDetached() {
        val base=fixture();val state=base.getValue("state").jsonObject
        val future=buildJsonObject {put("id","preserve-whole-object");put("payload",buildJsonArray {add(1);add(true)})}
        val source=JsonObject(base+("state" to JsonObject(state+("futureGraph" to future))))
        val p=AppleStateProjection.read(source);assertTrue("futureGraph" in p.preservedOnlyFields)
        val view=p.view();view.home.players.first().name="不应覆盖来源";view.homeRuns=100
        assertEquals(future,p.state["futureGraph"]);assertNotEquals(view.home.players.first().name,p.view().home.players.first().name)
        assertEquals(source,p.stored)
    }
    @Test fun legacyWholeHalfTiebreakMarkerPreventsDuplicatePlacementAndOriginalMarkerIsRetained() {
        val base=fixture();val state=JsonObject(base.getValue("state").jsonObject+mapOf("tiebreakStartInning" to JsonPrimitive(1),"extraInningConfirmed" to JsonPrimitive(true),
            "tiebreakRunnerBases" to buildJsonArray {add(2);add(3)},"tiebreakPlacementKeys" to buildJsonArray {add("1-top")}))
        val p=AppleStateProjection.read(JsonObject(base+("state" to state)));val g=p.view()
        assertFalse(g.requiresTiebreakPlacement);assertTrue(g.tiebreakPlacementKeys.containsAll(setOf("1-top-base2","1-top-base3")))
        assertEquals(listOf(JsonPrimitive("1-top")),p.state.getValue("tiebreakPlacementKeys").jsonArray.toList())
    }
    @Test fun duplicateDictionaryKeysAreRejectedInsteadOfOverwritingAPlayersSavedLine() {
        val base=fixture();val state=base.getValue("state").jsonObject;val id=state.getValue("homeTeam").jsonObject.getValue("players").jsonArray.first().jsonObject.getValue("id")
        val lines=buildJsonArray {repeat(2) {add(id);add(GameJson.encodeToJsonElement(BattingLine()))}}
        val bad=JsonObject(base+("state" to JsonObject(state+("batting" to lines))))
        assertThrows(IllegalArgumentException::class.java) {AppleStateProjection.read(bad)}
    }
    @Test fun scoresBeyondNativeIntegerCapacityAreRejectedRatherThanWrappingToAPlausibleScore() {
        val base=fixture();val state=base.getValue("state").jsonObject
        val high=buildJsonArray {repeat(5000) {add(1_000_000)}};val zero=buildJsonArray {repeat(5000) {add(0)}}
        val bad=JsonObject(base+("state" to JsonObject(state+mapOf("homeRunsByInning" to high,"awayRunsByInning" to zero))))
        assertThrows(ArithmeticException::class.java) {AppleStateProjection.read(bad)}
        assertEquals(high,bad.getValue("state").jsonObject.getValue("homeRunsByInning"))
    }
}

/** Explicit return check after original Swift scenarios ran. It is excluded
 * from ordinary test: absent or mismatched artifacts must fail, never skip.
 */
class AppleCommandStateReturnTest {
    @Test fun readsEveryActualSwiftCurrentStateAndNonemptyCheckpointAgainstOriginalCanonicalValues() {
        val directory=File(System.getProperty("swift.command.directory").also {require(it.isNotBlank())})
        val receipts=AppleWireJson.parse(directory.resolve("results.json").readBytes()).jsonArray
        assertEquals(99,receipts.size);var currentStates=0;var checkpoints=0
        fun compare(label: String,p: AppleStateProjection,expected: JsonObject) {
            val actual=NativeScoreSnapshot.from(p.view())
            for((key,value) in expected) {
                if(key=="pitcherInningHistoryIncomplete") assertEquals(label,value.jsonPrimitive.boolean,"pitcherInningHistoryIncomplete" in p.warnings)
                else assertEquals("$label: $key",value,actual[key])
            }
        }
        for(receipt in receipts) {
            val r=receipt.jsonObject;assertTrue(r.getValue("passed").jsonPrimitive.boolean)
            val source=File(r.getValue("input").jsonPrimitive.content).readBytes()
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(source).joinToString("") {"%02x".format(it)}
            assertEquals("Changed source commands",r.getValue("sourceSha256").jsonPrimitive.content,sha)
            val report=AppleWireJson.parse(directory.resolve(r.getValue("report").jsonPrimitive.content).readBytes()).jsonObject
            for(raw in report.getValue("steps").jsonArray) {
                val step=raw.jsonObject;val stored=step.getValue("storedGame").jsonObject;val label="${report.getValue("scenario").jsonPrimitive.content} ${step.getValue("step")}"
                compare(label,AppleStateProjection.read(stored),step.getValue("actual").jsonObject);currentStates++
                step["checkpointCanonical"]?.jsonObject?.let {expected ->
                    val checkpoint=Base64.getDecoder().decode(stored.getValue("state").jsonObject.getValue("historyJournal").jsonObject.getValue("checkpoint").jsonPrimitive.content)
                    compare("$label checkpoint",AppleStateProjection.read(stored,AppleWireJson.parse(checkpoint).jsonObject),expected);checkpoints++
                }
            }
        }
        assertEquals(420,currentStates);assertEquals(419,checkpoints)
    }
}
