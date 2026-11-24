package cc.baseballmaster.scorekeeper

import androidx.room.*
import cc.baseballmaster.core.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Entity(tableName="schedule_writers", foreignKeys=[ForeignKey(entity=GameEntity::class, parentColumns=["id"],
    childColumns=["gameID"], onDelete=ForeignKey.CASCADE)])
data class ScheduleWriterEntity(@PrimaryKey val gameID: String, val binding: String, val original: String?, val outbox: String?) {
    internal fun bindingValue() = GameJson.decodeFromString<WriterBinding>(binding)
}

@Serializable
internal data class WriterBinding(val notice: TeamScheduleNotice, val member: String, val device: String,
    val recovery: String, val access: Long?, val epoch: Long, val version: Long, val opening: Boolean,
    val split: Boolean, val allowed: Boolean = true, val acceptedCheckpoint: Boolean = false) {
    fun permits(state: TeamDirectoryState): Boolean {
        val current = state.directory?.teams?.singleOrNull { it.id.equals(notice.teamID,true) }
        return allowed && !state.credentialExpired && !state.requiresRecoveryReview && state.deviceID == device &&
            state.directory?.recoveryEpoch == recovery && current?.memberID == member && current.accessEpoch == access && current.role != TeamRole.member
    }
}

@Serializable private data class WriterSubmission(val id: String, val version: Long, val body: JsonObject)

internal class ScheduleLineupReview(val proposalID: String, val changes: String) : IllegalStateException("上一球已经记下，请核对换人时点：$changes")

internal class ScheduleWriter(private val db: LocalDatabase, private val session: TeamDirectorySession) {
    private val lock = Mutex()
    private val dao get() = db.dao()
    private fun endpointID(notice: TeamScheduleNotice) = notice.id.lowercase()
    suspend fun cached(id: String) = dao.writer(id)?.bindingValue()?.takeIf { it.permits(session.snapshot()) }
    private suspend fun restrict(id: String) = db.withTransaction {
        dao.writer(id)?.let { row -> dao.saveWriter(row.copy(binding=GameJson.encodeToString(row.bindingValue().copy(allowed=false)))) }
    }
    suspend fun status(notice: TeamScheduleNotice, checkCapability: Boolean = false): ScheduleScoringDetail {
        val id = endpointID(notice)
        val detail = try { session.scoringDetail(notice,checkCapability) } catch(e: TeamServiceFault) {
            if (e.status in listOf(401,403,404,426)) restrict(id)
            throw e
        }
        if (detail.access.closed || detail.access.transfer != null || detail.access.role != "scorer" || !detail.access.holdsDevice ||
            dao.writer(id)?.bindingValue()?.epoch?.let { it != detail.access.epoch } == true) restrict(id)
        return detail
    }
    suspend fun enter(notice: TeamScheduleNotice): Game = lock.withLock {
        val id = endpointID(notice)
        var detail = status(notice)
        val frame = requireNotNull(detail.frame) { "等待发布人提供双方先发" }
        check(detail.access.role == "scorer" && !detail.access.closed && detail.access.transfer == null) { "当前无法领取局面岗位" }
        if (!detail.access.hasCheckpoint && !frame.pregame && dao.writer(id) == null) error("需要原设备交接完整逐球档案")
        session.writerAction(notice, detail, "start")
        detail = status(notice)
        check(detail.access.holdsDevice && detail.access.role == "scorer" && !detail.access.closed && detail.access.transfer == null)
        val currentField = requireNotNull(detail.frame)
        val source = if (detail.access.hasCheckpoint) session.scoringCheckpoint(notice) else null
        val game = db.withTransaction {
            val prior = dao.gamesMatchingScheduleID(id).singleOrNull()
            val local = prior?.let(GameRepository::decode)
            val row = dao.writer(id)
            val previous = row?.bindingValue()
            check(local == null || local.publishedScheduleTeamID.equals(notice.teamID,true) ||
                local.isScheduled && session.snapshot().schedulePublications[id]?.teamID.equals(notice.teamID,true)) { "本机有同编号的其他比赛" }
            val received = previous?.acceptedCheckpoint == true && previous.epoch == detail.access.epoch
            val sameDevice = previous != null && previous.member == detail.memberID && previous.device == detail.deviceID &&
                previous.recovery == detail.recoveryEpoch && previous.access == detail.accessEpoch
            val game = when {
                received && local != null -> local
                source != null -> ScheduleCheckpoint.receive(source,notice,currentField).also { incoming ->
                    if (local != null && !local.isScheduled) require(incoming.journal.take(local.cursor) == local.journal.take(local.cursor)) { "本机有未核对的逐球历史" }
                }
                sameDevice && local != null && (previous?.epoch == detail.access.epoch || row?.outbox == null && previous?.version == detail.version) -> local
                else -> {
                    require(currentField.pregame && (local == null || local.isScheduled)) { "需要原设备交接完整逐球档案" }
                    currentField.editingView(id).copy(createdAt=local?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        scheduledAt=notice.startsAt, scheduleVenue=notice.venue, publishedScheduleTeamID=notice.teamID)
                }
            }
            require(game.id == id)
            val binding = WriterBinding(notice,detail.memberID,requireNotNull(detail.deviceID),requireNotNull(detail.recoveryEpoch),
                detail.accessEpoch,detail.access.epoch,if (previous?.epoch == detail.access.epoch) previous.version else detail.version,
                detail.access.openingConfirmed,detail.access.lineup != null,acceptedCheckpoint=received || source != null)
            val stored = GameEntity(id,GameJson.encodeToString(game),game.revision,System.currentTimeMillis(),4)
            if (prior == null) dao.insertGame(stored) else dao.updateGame(stored)
            dao.saveWriter(ScheduleWriterEntity(id,GameJson.encodeToString(binding), source?.toString() ?: row?.original,
                row?.outbox?.takeIf { previous?.epoch == binding.epoch }))
            game
        }
        if (source != null) session.writerAction(notice,detail,"received")
        game
    }

    suspend fun confirm(notice: TeamScheduleNotice) = lock.withLock {
        val detail = status(notice)
        session.writerAction(notice,detail,"confirmOpening",fields=buildJsonObject { put("version",detail.version) })
        val id = endpointID(notice)
        db.withTransaction { val row = requireNotNull(dao.writer(id)); dao.saveWriter(row.copy(binding=GameJson.encodeToString(row.bindingValue().copy(opening=true)))) }
    }

    suspend fun sync(notice: TeamScheduleNotice, reject: String? = null, accept: String? = null) = lock.withLock {
        val id = endpointID(notice)
        val detail = status(notice)
        val initial = requireNotNull(dao.writer(id))
        val binding = initial.bindingValue()
        if (detail.access.closed && initial.outbox != null) {
            val pending = GameJson.decodeFromString<WriterSubmission>(initial.outbox)
            if (detail.lastFrameID == pending.id) {
                dao.saveWriter(initial.copy(outbox=null,binding=GameJson.encodeToString(binding.copy(allowed=false,version=detail.version))))
                return@withLock
            }
        }
        check(binding.permits(session.snapshot()) && binding.epoch == detail.access.epoch) { "局面岗位已变化，请返回赛程核对" }
        val prior = initial.outbox?.let { GameJson.decodeFromString<WriterSubmission>(it) }
        val frame = requireNotNull(detail.frame)
        val proposal = detail.proposal
        fun verifyTiming(game: Game, pending: JsonObject) {
            val baseline = pending.getValue("baseline").jsonObject
            val lineup = pending.getValue("lineup").jsonObject
            val participation = listOf("battingIDs","fieldingIDs","anchorID","pitcherID","dhID","runners","automaticIDs").any { baseline[it] != lineup[it] }
            val last = pending["lastPlay"]?.jsonPrimitive?.contentOrNull
            if (participation && accept != detail.pending && !fieldFor(initial,frame,game).lastPlay.equals(last,true)) {
                val changes = lineup.getValue("changes").jsonArray.joinToString("、") { it.jsonPrimitive.content }
                throw ScheduleLineupReview(requireNotNull(detail.pending),changes)
            }
        }
        if (prior == null && proposal != null && reject != detail.pending && detail.reserved != detail.pending) {
            val current = GameRepository.decode(requireNotNull(dao.game(id)))
            verifyTiming(current,proposal)
            frame.applyProposal(current,proposal)
            session.writerAction(notice,detail,"reserve",fields=buildJsonObject { put("appliedID",detail.pending) })
        }
        val submission = prior ?: db.withTransaction {
            val row = requireNotNull(dao.game(id))
            val game = GameRepository.decode(row)
            check(binding.version == detail.version) { "服务端局面版本已变化，请核对原设备的记录" }
            val accepted = proposal != null && reject != detail.pending
            val next = if (accepted) {
                verifyTiming(game,requireNotNull(proposal))
                frame.applyProposal(game,proposal)
            } else game
            val field = fieldFor(initial,frame,next,if (accepted) proposal?.get("lineup")?.jsonObject else null)
            if (field == frame && proposal == null) return@withTransaction null
            val pending = WriterSubmission(UUID.randomUUID().toString().uppercase(),detail.version,buildJsonObject {
                put("version",detail.version); put("frame",GameJson.encodeToJsonElement(field)); put("finished",next.isFinal)
                if (proposal != null) { put("appliedID",detail.pending); put("rejected",!accepted) }
            })
            if (accepted) dao.updateGame(row.copy(payload=GameJson.encodeToString(next),revision=next.revision,updatedAt=System.currentTimeMillis()))
            dao.saveWriter(initial.copy(outbox=GameJson.encodeToString(pending)))
            pending
        }
        if (submission != null) {
            if (detail.lastFrameID != submission.id) session.writerAction(notice,detail,"frame",submission.id,submission.body)
            db.withTransaction {
                val row = requireNotNull(dao.writer(id))
                dao.saveWriter(row.copy(binding=GameJson.encodeToString(row.bindingValue().copy(version=submission.version+1)),outbox=null))
            }
        }
    }

    private fun fieldFor(row: ScheduleWriterEntity, template: ScheduleField, game: Game, lineup: JsonObject? = null): ScheduleField {
        val last = game.entries.lastOrNull()?.id
        val source = row.original?.let { GameJson.parseToJsonElement(it).jsonObject.getValue("state").jsonObject }
        val originalID = source?.get("historyJournal")?.jsonObject?.get("operations")?.jsonArray?.lastOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content.equals(last,true)
        }?.jsonObject?.get("logIDs")?.jsonArray?.lastOrNull()?.jsonPrimitive?.content
        return template.withGame(game,lineup,originalID)
    }

    suspend fun release(notice: TeamScheduleNotice) = lock.withLock {
        val id = endpointID(notice)
        val detail = status(notice)
        val transfer = requireNotNull(detail.access.transfer)
        check(detail.access.holdsDevice && detail.access.role == "scorer")
        val row = requireNotNull(dao.writer(id))
        val game = GameRepository.decode(requireNotNull(dao.game(id)))
        val field = fieldFor(row,requireNotNull(detail.frame),game)
        val original = row.original?.let { GameJson.parseToJsonElement(it).jsonObject }
        val document = ScheduleCheckpoint.document(game,original,field)
        session.writerAction(notice,detail,"release",fields=buildJsonObject {
            put("version",detail.version); put("transferID",transfer.id); put("document",document); put("frame",GameJson.encodeToJsonElement(field))
        })
    }
}
