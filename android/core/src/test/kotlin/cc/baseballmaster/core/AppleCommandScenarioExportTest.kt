package cc.baseballmaster.core

import java.io.File
import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Produces command inputs only. Original Swift execution is a separate,
 * sequential tool step, never implied merely by this producer passing.
 */
class AppleCommandScenarioExportTest {
    @Test fun preparesDirectCommandsFromTheFrozenIOSScenariosForOriginalSwiftExecution() {
        val root=File(System.getProperty("parity.fixtures"))
        val output=root.parentFile.parentFile.resolve("output/validation/android/an04-command-wire/kotlin")
        val inputs=output.resolve("scenarios").apply {mkdirs()}
        val all=root.walkTopDown().filter {it.isFile && it.name.endsWith("-scoring.json")}.sortedBy {it.relativeTo(root).path}.toList()
        assertEquals(109,all.size)
        val excluded=setOf("undo","redo","review","historyInsert","historyInsertMany","historyRevert","historyAmend")
        val prepared=mutableListOf<String>();val skipped=mutableListOf<JsonObject>()
        for(file in all) {
            val fixture=GameJson.parseToJsonElement(file.readText()).jsonObject
            val name=file.relativeTo(root).path
            val kinds=fixture.getValue("steps").jsonArray.map {it.jsonObject.getValue("command").jsonObject.getValue("kind").jsonPrimitive.content}.filter {it in excluded}.distinct()
            val target=inputs.resolve(file.parentFile.name+"-"+file.nameWithoutExtension+".scenario.json")
            if(kinds.isNotEmpty()) {
                target.delete()
                skipped+=buildJsonObject {put("input",name);put("reason",kinds.joinToString(","))}
                continue
            }
            val stored=fixture.getValue("iosInitialPayload").jsonObject
            val sourceDate=BigDecimal(stored.getValue("createdAt").jsonPrimitive.content).add(BigDecimal("978307200")).movePointRight(3).toLong()
            var before=GameJson.decodeFromJsonElement<Game>(fixture.getValue("initialGame"))
            val steps=buildJsonArray {
                fixture.getValue("steps").jsonArray.forEachIndexed {index,raw ->
                    val step=raw.jsonObject
                    val c=GameJson.decodeFromJsonElement<Command>(step.getValue("command")).copy(at=sourceDate+(index+1)*1000)
                    val id=UUID.nameUUIDFromBytes((name+"/$index").toByteArray()).toString()
                    val after=MatchEngine.apply(before,c,id)
                    val event=after.entries.last {it.id==id}
                    val wire=AppleHistoryCommandCodec.encodeAction(c,before,after,event.decisions)
                    add(buildJsonObject {
                        put("id",id);put("timestamp",JsonPrimitive(BigDecimal.valueOf(c.at).movePointLeft(3).subtract(BigDecimal("978307200"))))
                        put("command",wire);AppleHistoryCommandCodec.observation(event)?.let {put("observation",it)}
                        put("expected",step.getValue("expected"))
                    })
                    before=after
                }
            }
            val sourceHash=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
            target.writeText(buildJsonObject {put("scenario",name);put("sourceFixture",name);put("sourceFixtureSHA256",sourceHash);put("stored",stored);put("steps",steps)}.toString())
            prepared+=target.name
        }
        assertTrue(prepared.isNotEmpty())
        output.resolve("manifest.json").writeText(buildJsonObject {
            put("prepared",GameJson.encodeToJsonElement(prepared));put("skipped",JsonArray(skipped))
            put("scope","Direct command encoding only; original Swift execution is separate, full export/history corrections are not complete")
        }.toString())
    }
}
