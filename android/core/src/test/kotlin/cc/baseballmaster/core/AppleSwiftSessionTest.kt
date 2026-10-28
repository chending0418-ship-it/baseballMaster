package cc.baseballmaster.core

import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit development check, never a substitute for iOS App/device testing.
 * The child runs the unchanged Swift action/correction/undo implementations
 * with the documented Linux memory adapters. No service is contacted.
 */
class AppleSwiftSessionTest {
    private fun uid(value: String)=value.uppercase()
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun date(at: Long)=JsonPrimitive(BigDecimal.valueOf(at).movePointLeft(3).subtract(BigDecimal("978307200")))
    private fun journal(stored: JsonObject)=stored.getValue("state").jsonObject["historyJournal"]?.takeUnless {it==JsonNull}?.jsonObject
    private fun operations(stored: JsonObject)=journal(stored)?.getValue("operations")?.jsonArray ?: JsonArray(emptyList())
    private fun revisions(stored: JsonObject)=stored.getValue("state").jsonObject["correctionRevisions"]?.takeUnless {it==JsonNull}?.jsonArray ?: JsonArray(emptyList())

    private class Session(binary: File,errors: File): AutoCloseable {
        private val child=ProcessBuilder(binary.absolutePath,"--session").redirectError(errors).start()
        private val input=child.outputStream.bufferedWriter()
        private val output=child.inputStream.bufferedReader()
        fun send(request: JsonObject): JsonObject {
            input.write(request.toString());input.newLine();input.flush()
            val line=output.readLine() ?: error("Original Swift session ended without a response")
            val response=GameJson.parseToJsonElement(line).jsonObject
            check(response.getValue("ok").jsonPrimitive.boolean) {"Original Swift: ${response["error"]}"}
            return response
        }
        override fun close() {
            input.close()
            if(!child.waitFor(5,TimeUnit.SECONDS)) {child.destroyForcibly();error("Swift session failed to exit")}
            check(child.exitValue()==0) {"Swift session exit ${child.exitValue()}"}
        }
    }

    @Test(timeout=180_000) fun allFrozenScenariosExecuteWithActualSwiftIdentitiesAndRetainedCorrectionHistory() {
        val binary=File(System.getProperty("swift.core.binary").also {require(it.isNotBlank())})
        require(binary.isFile && binary.canExecute()) {"Compile the original Swift reference first"}
        val compiled=GameJson.parseToJsonElement(binary.parentFile.resolve("binary.json").readText()).jsonObject
        assertEquals(compiled.getValue("sha256").jsonPrimitive.content,sha(binary.readBytes()))
        val root=File(System.getProperty("parity.fixtures"))
        val repository=root.parentFile.parentFile
        val sources=GameJson.parseToJsonElement(binary.parentFile.resolve("sources.json").readText()).jsonArray
        for(raw in sources) {
            val source=raw.jsonObject
            val path=source.getValue("path").jsonPrimitive.content
            val original=repository.resolve(path)
            require(original.canonicalFile.toPath().startsWith(repository.canonicalFile.toPath()))
            assertEquals("Recompile the changed Swift reference: $path",source.getValue("sha256").jsonPrimitive.content,sha(original.readBytes()))
        }
        val output=root.parentFile.parentFile.resolve("output/validation/android/an04-interactive/sessions").apply {mkdirs()}
        val files=root.walkTopDown().filter {it.isFile && it.name.endsWith("-scoring.json")}.sortedBy {it.relativeTo(root).path}.toList()
        assertEquals(109,files.size)
        var frames=0;var actions=0;var historyActions=0;var reviews=0;var undos=0;var replayChecks=0
        val sourceReplayDifferences=mutableListOf<JsonObject>()
        val failures=mutableListOf<String>();val results=mutableListOf<JsonObject>()
        for(file in files) {
            val name=file.relativeTo(root).path
            val rows=mutableListOf<JsonObject>()
            try {
                val fixture=GameJson.parseToJsonElement(file.readText()).jsonObject
                var game=GameJson.decodeFromJsonElement<Game>(fixture.getValue("initialGame"))
                val initial=fixture.getValue("iosInitialPayload").jsonObject
                val created=BigDecimal(initial.getValue("createdAt").jsonPrimitive.content).add(BigDecimal("978307200")).movePointRight(3).toLong()
                val wireIDs=mutableMapOf<String,String>()
                fun checkFrame(label: String,response: JsonObject,expected: JsonObject) {
                    val native=NativeScoreSnapshot.from(game)
                    val source=response.getValue("actual").jsonObject
                    for((key,value) in expected) {
                        assertEquals("$name $label Kotlin $key",value,native[key])
                        assertEquals("$name $label Swift $key",value,source[key])
                    }
                    response["replayed"]?.jsonObject?.let {replay ->
                        val different=expected.keys.filter {it!="correctionCount" && expected[it]!=replay[it]}.toSet()
                        // Original source rewrites the lineup context after a
                        // missed pitcher correction. Its second replay loses
                        // that replacement. Preserve evidence; do not change
                        // iOS/global behavior during the Android migration.
                        val known=name=="history/history-lineup-after-missed-pitcher-scoring.json" && label=="3 historyInsert"
                        if(known) {
                            assertEquals(setOf("homeOrder","pitcherID","pitching"),different)
                            sourceReplayDifferences+=buildJsonObject {
                                put("fixture",name);put("frame",label);put("fields",buildJsonArray {different.sorted().forEach {add(it)}})
                                put("expected",JsonObject(expected.filterKeys {it in different}));put("replayed",JsonObject(replay.filterKeys {it in different}))
                            }
                        } else assertTrue("$name $label replay differences: $different",different.isEmpty())
                        replayChecks++
                    }
                    // The source audit graph is opaque to the read-only view.
                    val projection=AppleStateProjection.read(response.getValue("storedGame").jsonObject)
                    val projected=NativeScoreSnapshot.from(projection.view())
                    for((key,value) in source) when(key) {
                        "correctionCount"->Unit
                        "pitcherInningHistoryIncomplete"->assertEquals("$name $label incomplete",value.jsonPrimitive.boolean,"pitcherInningHistoryIncomplete" in projection.warnings)
                        else->assertEquals("$name $label projection $key",value,projected[key])
                    }
                    frames++
                }
                Session(binary,output.resolve(file.parentFile.name+"-"+file.name+".stderr")).use {session ->
                    var response=session.send(buildJsonObject {put("action","init");put("stored",initial)})
                    checkFrame("initial",response,fixture.getValue("initial").jsonObject)
                    rows+=response
                    fixture.getValue("steps").jsonArray.forEachIndexed {index,raw ->
                        val command=raw.jsonObject.getValue("command").jsonObject
                        val kind=command.getValue("kind").jsonPrimitive.content
                        val previous=response.getValue("storedGame").jsonObject
                        val oldOps=operations(previous)
                        val at=created+(index+1)*1000L
                        var newID: String?=null
                        val request=buildJsonObject {
                            put("timestamp",date(at))
                            when(kind) {
                                "undo","redo"->{game=if(kind=="undo") MatchEngine.undo(game) else MatchEngine.redo(game);put("action",kind);undos++}
                                "historyRevert"->{game=History.commit(game,History.revertingDraft(game));put("action","revert");historyActions++}
                                "historyInsert","historyInsertMany"->{
                                    var draft=History.draft(game)
                                    val insertions=if(kind=="historyInsert") listOf(command) else command.getValue("insertions").jsonArray.map {it.jsonObject}
                                    put("action","insert");put("reason","补记")
                                    put("insertions",buildJsonArray {
                                        for(item in insertions) {
                                            val position=item.getValue("index").jsonPrimitive.int
                                            val before=History.stateBefore(game,draft,position)
                                            val c=GameJson.decodeFromJsonElement<Command>(item.getValue("insert")).copy(at=at)
                                            draft=History.insert(game,draft,position,c)
                                            val record=draft.records[position]
                                            // These replacement actions have
                                            // no runner-move payload. A re-entry
                                            // is valid only once the whole
                                            // correction draft is assembled.
                                            require(c.kind in setOf("pitcher","position","pinchRunner"))
                                            wireIDs[record.id]=uid(record.id)
                                            add(buildJsonObject {
                                                put("index",position);put("id",uid(record.id));put("timestamp",date(at))
                                                put("command",AppleHistoryCommandCodec.encodeAction(c,before,before,emptyList()))
                                            })
                                        }
                                    })
                                    game=History.commit(game,draft.copy(reason="补记"));historyActions++
                                }
                                "historyAmend"->{
                                    val draft=History.draft(game);val position=command.getValue("index").jsonPrimitive.int
                                    val records=draft.records.toMutableList();val old=records[position]
                                    val errors=command["errors"]?.let {GameJson.decodeFromJsonElement<List<ErrorCredit>>(it)}
                                    val ruling=command["ruling"]?.let {GameJson.decodeFromJsonElement<StatisticsRuling>(it)}
                                    val sourceID=wireIDs.getValue(old.id)
                                    val sourceIndex=oldOps.indexOfFirst {it.jsonObject.getValue("id").jsonPrimitive.content==sourceID}
                                    require(sourceIndex>=0)
                                    put("action","amend");put("index",sourceIndex);put("reason","人工复核")
                                    errors?.let {items ->put("errors",buildJsonArray {items.forEachIndexed {i,e ->add(buildJsonObject {
                                        put("id",uid(UUID.nameUUIDFromBytes((name+"/$index/$i").toByteArray()).toString()))
                                        put("playerID",uid(e.playerID));put("count",e.count);put("note",e.note)
                                    })}})}
                                    ruling?.let {r ->put("statisticsRuling",buildJsonObject {
                                        put("runsBattedIn",r.runsBattedIn);put("earnedRuns",buildJsonArray {r.earnedRuns.forEach {(id,n)->add(uid(id));add(n)}})
                                        put("unearnedRunnerIDs",buildJsonArray {r.unearnedRunnerIDs.forEach {add(uid(it))}})
                                    })}
                                    records[position]=old.copy(edited=true,additionalErrors=errors ?: old.additionalErrors,statisticsRuling=ruling ?: old.statisticsRuling)
                                    game=History.commit(game,draft.copy(records=records,reason="人工复核"));historyActions++
                                }
                                else->{
                                    val before=game
                                    var c=GameJson.decodeFromJsonElement<Command>(command).copy(at=at)
                                    var reviewEvent: JsonObject?=null
                                    if(kind=="review") {
                                        val owner=before.entries[requireNotNull(c.position)].id
                                        c=c.copy(eventID=owner)
                                        val sourceID=wireIDs.getValue(owner)
                                        val op=oldOps.single {it.jsonObject.getValue("id").jsonPrimitive.content==sourceID}.jsonObject
                                        val eventIDs=op.getValue("eventIDs").jsonArray.map {it.jsonPrimitive.content}.toSet()
                                        reviewEvent=previous.getValue("state").jsonObject.getValue("scoringEvents").jsonArray.map {it.jsonObject}.single {
                                            it.getValue("id").jsonPrimitive.content in eventIDs && it["needsReview"]?.jsonPrimitive?.boolean==true
                                        }
                                        reviews++
                                    }
                                    val id=UUID.nameUUIDFromBytes((name+"/$index").toByteArray()).toString();newID=id
                                    game=MatchEngine.apply(before,c,id)
                                    val entry=game.entries.last {it.id==id}
                                    put("action","command");put("command",AppleHistoryCommandCodec.encodeAction(c,before,game,entry.decisions,reviewEvent))
                                    AppleHistoryCommandCodec.observation(entry)?.let {put("observation",it)}
                                }
                            }
                        }
                        response=session.send(request)
                        val stored=response.getValue("storedGame").jsonObject
                        newID?.let {nativeID ->
                            val oldIDs=oldOps.map {it.jsonObject.getValue("id").jsonPrimitive.content}.toSet()
                            val added=operations(stored).filter {it.jsonObject.getValue("id").jsonPrimitive.content !in oldIDs}
                            if(added.isNotEmpty()) {assertEquals(1,added.size);wireIDs[nativeID]=added.single().jsonObject.getValue("id").jsonPrimitive.content}
                            else assertEquals("Only pregame lineup resets capture", "lineup",kind)
                        }
                        if(kind.startsWith("history")) {
                            val audits=revisions(stored);val priorAudits=revisions(previous)
                            assertEquals(priorAudits.size+1,audits.size)
                            assertEquals(priorAudits.map {it.jsonObject.getValue("id")},audits.dropLast(1).map {it.jsonObject.getValue("id")})
                            val archived=audits.last().jsonObject.getValue("previousJournal").jsonObject
                            assertEquals("Checkpoint bytes retained",journal(previous)!!.getValue("checkpoint"),archived.getValue("checkpoint"))
                            val archivedOps=archived.getValue("operations").jsonArray
                            assertEquals(oldOps.size,archivedOps.size)
                            for(i in oldOps.indices) for(key in listOf("id","eventIDs","logIDs","nextAppearanceID"))
                                assertEquals("$name audit operation $i $key",oldOps[i].jsonObject[key],archivedOps[i].jsonObject[key])
                        }
                        rows+=response
                        checkFrame("$index $kind",response,raw.jsonObject.getValue("expected").jsonObject);actions++
                    }
                }
                results+=buildJsonObject {put("fixture",name);put("sha256",sha(file.readBytes()));put("passed",true);put("frames",rows.size)}
            } catch(failure: Throwable) {
                failures+="$name: ${failure.message}"
                results+=buildJsonObject {put("fixture",name);put("passed",false);put("error",failure.message ?: failure.javaClass.name)}
            } finally {
                output.resolve(file.parentFile.name+"-"+file.name).writeText(JsonArray(rows).toString())
            }
        }
        output.resolve("results.json").writeText(buildJsonObject {
            put("results",JsonArray(results));put("frames",frames);put("actions",actions);put("historyActions",historyActions);put("reviews",reviews);put("undoRedo",undos);put("replayChecks",replayChecks)
            put("swiftBinarySHA256",compiled.getValue("sha256"));put("scope","Original Swift core with Linux memory adapters; no App/CoreData/UI/device execution")
            put("sources",sources)
            put("knownSourceReplayDifferences",JsonArray(sourceReplayDifferences))
        }.toString())
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
        assertEquals("Known source discrepancy must stay visible",1,sourceReplayDifferences.size)
        assertEquals(files.sumOf {GameJson.parseToJsonElement(it.readText()).jsonObject.getValue("steps").jsonArray.size},actions)
    }
}
