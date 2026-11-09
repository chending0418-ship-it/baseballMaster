package cc.baseballmaster.scorekeeper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.baseballmaster.core.*
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey val id: String,
    val name: String,
    val payload: String,
    val isOpponent: Boolean,
)

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val id: String,
    val payload: String,
    val revision: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val payloadVersion: Int = 1,
)

@Entity(
    tableName = "operations",
    indices = [Index("gameID")],
    foreignKeys =
        [
            ForeignKey(
                entity = GameEntity::class,
                parentColumns = ["id"],
                childColumns = ["gameID"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class OperationEntity(
    @PrimaryKey val id: String,
    val gameID: String,
    val action: String,
    val baseRevision: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "correction_drafts",
    foreignKeys =
        [
            ForeignKey(
                entity = GameEntity::class,
                parentColumns = ["id"],
                childColumns = ["gameID"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class CorrectionDraftEntity(
    @PrimaryKey val gameID: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(tableName = "team_archives")
data class TeamArchiveEntity(@PrimaryKey val id: String, val original: ByteArray)

@Dao
interface LocalDao {
    @Query("SELECT * FROM team_archives ORDER BY id") suspend fun allTeamArchives(): List<TeamArchiveEntity>
    @Query("SELECT * FROM team_archives WHERE id = :id") suspend fun teamArchive(id: String): TeamArchiveEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTeamArchive(row: TeamArchiveEntity)

    @Query("SELECT * FROM apple_original_operations WHERE id = :id") suspend fun appleOriginalOperation(id: String): AppleOriginalOperationEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAppleOriginalOperation(row: AppleOriginalOperationEntity)

    @Query("SELECT * FROM apple_backups") suspend fun allAppleBackups(): List<AppleBackupEntity>
    @Query("SELECT id, name, gameCount FROM apple_backups ORDER BY name") suspend fun appleBackupSummaries(): List<AppleBackupSummary>
    @Query("SELECT * FROM apple_backups WHERE id = :id") suspend fun appleBackup(id: String): AppleBackupEntity?
    @Query("SELECT * FROM apple_game_sources") suspend fun allAppleGameSources(): List<AppleGameSourceEntity>
    @Query("SELECT * FROM apple_game_sources WHERE gameID = :id") suspend fun appleGameSource(id: String): AppleGameSourceEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAppleBackup(source: AppleBackupEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAppleGameSource(source: AppleGameSourceEntity)
    @Update suspend fun updateAppleBackup(source: AppleBackupEntity)

    @Query("SELECT * FROM teams ORDER BY name") fun teams(): Flow<List<TeamEntity>>

    @Query("SELECT * FROM teams") suspend fun allTeams(): List<TeamEntity>

    @Query("SELECT * FROM games ORDER BY updatedAt DESC") fun games(): Flow<List<GameEntity>>

    @Query("DELETE FROM operations WHERE gameID = :id") suspend fun deleteGameOperations(id: String)
    @Query("DELETE FROM apple_original_operations WHERE gameID = :id") suspend fun deleteGameOriginalOperations(id: String)
    @Query("DELETE FROM apple_game_sources WHERE gameID = :id") suspend fun deleteGameSource(id: String)
    @Query("DELETE FROM games WHERE id = :id") suspend fun deleteGameRow(id: String)

    @Query("SELECT * FROM games WHERE id = :id") suspend fun game(id: String): GameEntity?

    @Query("SELECT * FROM operations") suspend fun allOperations(): List<OperationEntity>

    @Query("SELECT * FROM correction_drafts") suspend fun allCorrectionDrafts(): List<CorrectionDraftEntity>

    @Query("SELECT * FROM operations WHERE id = :id")
    suspend fun operation(id: String): OperationEntity?

    @Query("SELECT COUNT(*) FROM operations WHERE gameID = :id")
    suspend fun operationCount(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTeam(team: TeamEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGame(game: GameEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: OperationEntity)

    @Update suspend fun updateGame(game: GameEntity)

    @Query("SELECT * FROM teams WHERE id = :id") suspend fun team(id: String): TeamEntity?

    @Query("SELECT * FROM games") suspend fun allGames(): List<GameEntity>

    @Update suspend fun updateTeam(team: TeamEntity)

    @Query("SELECT * FROM correction_drafts WHERE gameID = :gameID")
    suspend fun correctionDraft(gameID: String): CorrectionDraftEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCorrectionDraft(draft: CorrectionDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCorrectionDraft(draft: CorrectionDraftEntity)

    @Query("DELETE FROM correction_drafts WHERE gameID = :gameID")
    suspend fun deleteCorrectionDraft(gameID: String)
}

@Database(
    entities =
        [
            TeamEntity::class,
            GameEntity::class,
            OperationEntity::class,
            CorrectionDraftEntity::class,
            AppleBackupEntity::class,
            AppleGameSourceEntity::class,
            AppleOriginalOperationEntity::class,
            TeamArchiveEntity::class,
        ],
    version = 5,
    exportSchema = true,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        fun open(context: Context, name: String = "baseballmaster.db") =
            Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS team_archives (id TEXT NOT NULL, original BLOB NOT NULL, PRIMARY KEY(id))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS apple_backups (id TEXT NOT NULL, name TEXT NOT NULL, gameCount INTEGER NOT NULL, original BLOB NOT NULL, current BLOB NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS apple_game_sources (gameID TEXT NOT NULL, backupID TEXT NOT NULL, PRIMARY KEY(gameID), FOREIGN KEY(gameID) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(backupID) REFERENCES apple_backups(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_apple_game_sources_backupID ON apple_game_sources (backupID)")
                db.execSQL("CREATE TABLE IF NOT EXISTS apple_original_operations (id TEXT NOT NULL, gameID TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(gameID) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_apple_original_operations_gameID ON apple_original_operations (gameID)")
            }
        }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS correction_drafts (gameID TEXT NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(gameID), FOREIGN KEY(gameID) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                    )
                }
            }
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE games ADD COLUMN payloadVersion INTEGER NOT NULL DEFAULT 1"
                    )
                }
            }
    }
}

internal fun matchesAppleSourceProjection(expected: Game, storedPayload: String): Boolean {
    val saved = GameJson.parseToJsonElement(storedPayload).jsonObject
    val canonical = GameJson.encodeToJsonElement(expected).jsonObject
    if (saved == canonical) return true
    if ("pitcherInningUsageCurrent" in saved) return false

    // Older Android versions saved a projection of the same source with different pitcher caches.
    val derivedFields = setOf("pitcherInnings", "pitcherInningUsageCurrent", "pitcherInningHistoryIncomplete")
    fun checkpoint(value: JsonElement): JsonElement =
        if (value is JsonPrimitive && value.isString) {
            JsonObject(GameJson.parseToJsonElement(value.content).jsonObject - derivedFields)
        } else value

    fun withoutPitcherCaches(game: JsonObject): JsonObject =
        JsonObject((game - derivedFields).mapValues { (key, value) ->
            when (key) {
                "checkpoint" -> checkpoint(value)
                "historyRevisions" -> JsonArray(value.jsonArray.map { revision ->
                    JsonObject(revision.jsonObject.mapValues { (field, part) ->
                        if (field == "previousCheckpoint") checkpoint(part) else part
                    })
                })
                else -> value
            }
        })
    return withoutPitcherCaches(saved) == withoutPitcherCaches(canonical)
}

/** No destructive fallback: unreadable/future stores remain untouched. */
class GameRepository(private val db: LocalDatabase, private val beforeCommit: () -> Unit = {}) {
    val teams
        get() = db.dao().teams()

    suspend fun importAppleBackup(input: ByteArray): AppleImportReceipt {
        val plan = AppleSourceBackup.prepare(input)
        val bytes = plan.export()
        val games = plan.games()
        val teams = plan.teams()
        return db.withTransaction {
            val existing = db.dao().appleBackup(plan.digest)
            if (existing != null) {
                require(existing.original.contentEquals(bytes)) { "同编号备份内容冲突，原资料未修改" }
                verifyAppleSource(existing).games(existing.id).forEach { expected ->
                    require(db.dao().appleGameSource(expected.id)?.backupID == existing.id) { "来源映射缺失，未覆盖现有资料" }
                    val saved = db.dao().game(expected.id) ?: error("来源比赛缺失，未覆盖现有资料")
                    require(matchesAppleSourceProjection(expected, saved.payload)) { "来源与比赛不一致，未覆盖现有资料" }
                }
                return@withTransaction AppleImportReceipt(existing.id, existing.gameCount, true, plan.currentTeamID)
            }
            val savedGames = db.dao().allGames()
            val oldGameIDs = savedGames.map { it.id.lowercase(java.util.Locale.ROOT) }.toSet()
            require(games.none { it.id in oldGameIDs }) { "本机已存在同编号比赛，整批未导入" }
            val oldOperations = (savedGames.flatMap { originalRecords(decode(it)) }.map { it.id } + db.dao().allOperations().map { it.id }).map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            require(games.flatMap { originalRecords(it) }.none { it.id.lowercase(java.util.Locale.ROOT) in oldOperations }) { "本机已存在同编号比赛操作，整批未导入" }
            insertRosters(teams, allowIdentical = true)
            db.dao().insertAppleBackup(AppleBackupEntity(plan.digest, plan.name, games.size, bytes, bytes))
            games.forEach { game ->
                db.dao().insertGame(GameEntity(game.id, GameJson.encodeToString(game), game.revision, game.updatedAt, 4))
                db.dao().insertAppleGameSource(AppleGameSourceEntity(game.id, plan.digest))
                originalRecords(game).map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().forEach { db.dao().insertAppleOriginalOperation(AppleOriginalOperationEntity(it, game.id)) }
            }
            beforeCommit()
            AppleImportReceipt(plan.digest, games.size, false, plan.currentTeamID)
        }
    }

    suspend fun appleBackupSummaries(): List<AppleBackupSummary> = db.dao().appleBackupSummaries()
    suspend fun exportAppleBackup(id: String, original: Boolean = false): ByteArray = db.withTransaction {
        val source = db.dao().appleBackup(id) ?: error("来源备份不存在")
        if (original) {
            val plan = AppleSourceBackup.prepare(source.original)
            require(plan.digest == source.id) { "原始来源校验失败" }
            plan.export()
        } else verifyAppleSource(source).export()
    }

    private fun originalRecords(game: Game): List<RecordedCommand> = game.journal + game.historyRevisions.flatMap { it.previousRecords }

    private fun verifyAppleSource(source: AppleBackupEntity): AppleSourceBackup {
        val original = AppleSourceBackup.prepare(source.original)
        require(original.digest == source.id && original.name == source.name && original.games().size == source.gameCount) { "原始来源或摘要校验失败，已停止写入" }
        return AppleSourceBackup.validateLineage(source.original, source.current)
    }

    /** Import explicitly selected rosters only. Conflicting IDs abort the entire transaction. */
    suspend fun importRosters(rosters: List<Team>) = db.withTransaction {
        insertRosters(rosters)
        beforeCommit()
    }

    private suspend fun insertRosters(rosters: List<Team>, allowIdentical: Boolean = false) {
        require(rosters.isNotEmpty()) { "备份中没有球队名单" }
        require(rosters.map { it.id }.distinct().size == rosters.size) { "球队编号冲突" }
        val playerIDs = rosters.flatMap { it.players }.map { it.id }
        require(playerIDs.distinct().size == playerIDs.size) { "球员编号冲突" }
        val stored = db.dao().allTeams()
        val existing = stored.associateBy { it.id.lowercase(java.util.Locale.ROOT) }
        val newTeams = mutableListOf<Team>()
        for (team in rosters) {
            require(team.source == "local" && team.name.isNotBlank())
            team.players.forEach(Roster::validate)
            val old = existing[team.id.lowercase(java.util.Locale.ROOT)]
            if (old == null) newTeams += team
            else require(allowIdentical && old.id == team.id && old.name == team.name && old.isOpponent == team.isOpponent && GameJson.parseToJsonElement(old.payload) == GameJson.encodeToJsonElement(team)) { "同编号球队或名单存在差异，整批未导入且没有覆盖原资料" }
        }
        val existingPlayers = stored.flatMap { GameJson.decodeFromString<Team>(it.payload).players }.map { it.id.lowercase(java.util.Locale.ROOT) }.toSet()
        require(newTeams.flatMap { it.players }.none { it.id.lowercase(java.util.Locale.ROOT) in existingPlayers }) { "本机已存在同编号球员，未导入任何名单" }
        for (team in newTeams) db.dao().insertTeam(TeamEntity(team.id, team.name, GameJson.encodeToString(team), team.isOpponent))
    }

    internal suspend fun teamArchiveRows(): List<TeamArchiveEntity> = db.dao().allTeamArchives()
    internal suspend fun teamArchiveRow(id: String): TeamArchiveEntity? = db.dao().teamArchive(id)
    internal suspend fun importTeamArchives(rows: List<TeamArchiveEntity>) = db.withTransaction {
        require(rows.map { it.id }.distinct().size == rows.size) { "档案编号重复" }
        require((db.dao().allTeamArchives().map { it.id } + rows.map { it.id }).distinct().size <= 100) { "档案库最多保存 100 份档案" }
        val existing = db.dao().allTeamArchives().associateBy { it.id }
        require((existing.values + rows).distinctBy { it.id }.sumOf { it.original.size.toLong() } <= TeamArchiveBytes.libraryLimit) { "档案库超过 50 MB，无法加入本机备份" }
        rows.forEach { row ->
            TeamArchiveBytes.validate(row.id, row.original)
            val old = existing[row.id]
            if (old == null) db.dao().insertTeamArchive(row)
            else require(old.original.contentEquals(row.original)) { "同编号档案内容不同，原资料未修改" }
        }
        beforeCommit()
    }

    /** Export a consistent database snapshot including the operation ledger and correction drafts. */
    suspend fun exportDeviceBackup(currentTeamID: String?): ByteArray = db.withTransaction {
        AndroidDeviceBackup.encode(DeviceSnapshot(
            currentTeamID = currentTeamID,
            teams = db.dao().allTeams().map { DeviceTeam(it.id, it.name, it.payload, it.isOpponent) },
            games = db.dao().allGames().map { DeviceGame(it.id, it.payload, it.revision, it.updatedAt, it.payloadVersion) },
            operations = db.dao().allOperations().map { DeviceOperation(it.id, it.gameID, it.action, it.baseRevision, it.createdAt) },
            drafts = db.dao().allCorrectionDrafts().map { DeviceDraft(it.gameID, it.payload, it.updatedAt) },
            appleBackups = db.dao().allAppleBackups().map { DeviceAppleBackup(it.id, it.name, it.gameCount, Base64.getEncoder().encodeToString(it.original), Base64.getEncoder().encodeToString(it.current)) },
            appleSources = db.dao().allAppleGameSources().map { DeviceAppleSource(it.gameID, it.backupID) },
            teamArchives = db.dao().allTeamArchives().map { DeviceTeamArchive(it.id, Base64.getEncoder().encodeToString(it.original)) },
        ))
    }

    /** Restore only into an empty database; no existing match or draft is ever overwritten. */
    suspend fun restoreDeviceBackup(bytes: ByteArray): String? {
        val snapshot = AndroidDeviceBackup.decode(bytes)
        return db.withTransaction {
            require(db.dao().allTeams().isEmpty() && db.dao().allGames().isEmpty() && db.dao().allOperations().isEmpty() && db.dao().allCorrectionDrafts().isEmpty() && db.dao().allAppleBackups().isEmpty() && db.dao().allAppleGameSources().isEmpty() && db.dao().allTeamArchives().isEmpty()) { "本机已有资料，未覆盖任何数据" }
            snapshot.teamArchives.forEach { db.dao().insertTeamArchive(TeamArchiveEntity(it.id, Base64.getDecoder().decode(it.original))) }
            snapshot.teams.forEach { db.dao().insertTeam(TeamEntity(it.id, it.name, it.payload, it.isOpponent)) }
            snapshot.games.forEach { db.dao().insertGame(GameEntity(it.id, it.payload, it.revision, it.updatedAt, it.payloadVersion)) }
            snapshot.operations.forEach { db.dao().insertOperation(OperationEntity(it.id, it.gameID, it.action, it.baseRevision, it.createdAt)) }
            snapshot.drafts.forEach { db.dao().insertCorrectionDraft(CorrectionDraftEntity(it.gameID, it.payload, it.updatedAt)) }
            snapshot.appleBackups.forEach { db.dao().insertAppleBackup(AppleBackupEntity(it.id, it.name, it.gameCount, Base64.getDecoder().decode(it.original), Base64.getDecoder().decode(it.current))) }
            snapshot.appleSources.forEach { db.dao().insertAppleGameSource(AppleGameSourceEntity(it.gameID, it.backupID)) }
            snapshot.appleBackups.forEach { source ->
                AppleSourceBackup.prepare(Base64.getDecoder().decode(source.original)).games(source.id).forEach { game ->
                    originalRecords(game).map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().forEach { db.dao().insertAppleOriginalOperation(AppleOriginalOperationEntity(it, game.id)) }
                }
            }
            beforeCommit()
            snapshot.currentTeamID
        }
    }

    val games
        get() = db.dao().games()

    /** Delete one local record atomically; retained imported backup files stay intact. */
    suspend fun deleteGame(id: String, revision: Long) = db.withTransaction {
        val row = requireNotNull(db.dao().game(id)) { "比赛已不存在" }
        check(row.revision == revision) { "比赛已更新，请重新核对后删除" }
        require(db.dao().appleGameSource(id) == null) { "这场比赛属于完整导入档案，暂不能单独删除；原档案和备份已保留。" }
        db.dao().deleteCorrectionDraft(id)
        db.dao().deleteGameOriginalOperations(id)
        db.dao().deleteGameSource(id)
        db.dao().deleteGameOperations(id)
        db.dao().deleteGameRow(id)
        beforeCommit()
    }

    suspend fun createTeam(name: String, roster: String, isOpponent: Boolean = false): Team {
        require(name.trim().isNotEmpty()) { "请填写球队名称" }
        val players = parseRoster(roster)
        val team = Team(UUID.randomUUID().toString(), name.trim(), players, isOpponent)
        db.dao().insertTeam(TeamEntity(team.id, team.name, GameJson.encodeToString(team), isOpponent))
        return team
    }

    suspend fun createGame(
        own: Team,
        opponentName: String,
        roster: String,
        mode: GameMode,
        ownIsHome: Boolean,
        rules: Rules = Rules(mode),
        scheduledAt: Long? = null,
    ): Game {
        require(own.source=="local"&&!own.archived){"共享球队请通过授权同步入口创建比赛"}
        require(opponentName.trim().isNotEmpty()) { "请填写对手名称" }
        require(rules.mode == mode)
        val required = if (mode == GameMode.slowPitch) 10 else 9
        val opponent =
            Team(UUID.randomUUID().toString(), opponentName.trim(), parseRoster(roster), true)
        require(own.players.size >= required && opponent.players.size >= required) {
            "双方至少需要 $required 名球员"
        }
        val activeOwn = own.copy(players = own.players.filter { !it.archived })
        require(!own.archived)
        val game =
            MatchFactory.create(
                UUID.randomUUID().toString(),
                if (ownIsHome) activeOwn else opponent,
                if (ownIsHome) opponent else activeOwn,
                rules,
                own.id,
                scheduledAt,
            )

        db.withTransaction {
            db.dao()
                .insertTeam(
                    TeamEntity(opponent.id, opponent.name, GameJson.encodeToString(opponent), true)
                )
            db.dao()
                .insertGame(
                    GameEntity(
                        game.id,
                        GameJson.encodeToString(game),
                        game.revision,
                        System.currentTimeMillis(),
                        4,
                    )
                )
            beforeCommit()
        }
        return game
    }

    suspend fun createPreparedGame(prepared: Game): Game = createPreparedGameTransaction(prepared, false)

    suspend fun createSharedRosterGame(prepared: Game): Game = createPreparedGameTransaction(prepared, true)

    suspend fun createObservationGame(prepared: Game): Game = createPreparedGameTransaction(prepared, false, observation = true)

    private suspend fun createPreparedGameTransaction(prepared: Game, sharedSnapshot: Boolean, observation: Boolean = false): Game = db.withTransaction {
        require(prepared.appleSourceID == null) { "带来源标记的比赛必须通过来源导入事务保存" }
        val g = GameJson.decodeFromString<Game>(GameJson.encodeToString(prepared))
        g.validate()
        require(
            g.totalPitches == 0 &&
                !g.openingPitchRecorded &&
                !g.clockHasStarted &&
                (g.ourTeamID != null || observation)
        )
        require(g.journal.all { it.command.kind == "lineup" } && g.entries.all { it.kind == "lineup" }) {
            "赛前创建只接受阵容设置，已有比赛记录不能作为新比赛清空"
        }
        val own = if (observation) {
            require(!sharedSnapshot && g.ourTeamID == null && g.sharedRosterOrigin == null && g.postgameSharingChoice == null) { "观赛记录不能绑定本队或共享上传目标" }
            listOf(g.home, g.away).forEach { snapshot ->
                val local = db.dao().team(snapshot.id)?.let { GameJson.decodeFromString<Team>(it.payload) }
                    ?: error("观赛球队已不存在，请重新选择")
                require(local.isOpponent && !local.archived && local.source == "local" && snapshot.isOpponent) { "观赛请选择两支有效对手球队" }
                require(local.players.filter { !it.archived }.map { it.id }.toSet() == snapshot.players.map { it.id }.toSet()) { "观赛名单已变化，请重新核对" }
            }
            null
        } else if (sharedSnapshot) {
            requireNotNull(g.sharedRosterOrigin) { "共享名单来源缺失" }.validate(g)
            require(db.dao().team(g.ourTeamID!!) == null) { "共享名单身份与已有本机球队冲突，请核对；原资料已保留" }
            listOf(g.home, g.away).single { it.id == g.ourTeamID }
        } else {
            require(g.sharedRosterOrigin == null) { "共享名单比赛须通过独立保存入口" }
            val local = db.dao().team(g.ourTeamID!!)?.let { GameJson.decodeFromString<Team>(it.payload) }
                ?: error("我方球队不存在")
            require(!local.archived && local.source == "local" && local.id in listOf(g.home.id, g.away.id))
            local
        }
        if (!g.scheduledSetupPending) {
            Participation.validate(g.rules, g.homeOrder!!, g.homePositions!!, g.homeDesignatedHitterID)
            Participation.validate(g.rules, g.awayOrder!!, g.awayPositions!!, g.awayDesignatedHitterID)
        }
        if (own != null) {
            val opponent = if (g.home.id == own.id) g.away else g.home
            if (db.dao().team(opponent.id) == null)
                db.dao().insertTeam(TeamEntity(opponent.id, opponent.name, GameJson.encodeToString(opponent), true))
        }
        // New-game drafts carry only explicit, same-ID profile corrections. Apply
        // them in the same transaction, then discard the pregame command journal.
        syncPreparedLineupProfiles(g, g)
        // These validated entries describe setup, not played events. Leaving
        // them without their draft journal would trigger the legacy fallback
        // and falsely mark the first real pitch as incomplete.
        g.entries = emptyList()
        g.revision = 0
        g.journal = emptyList()
        g.cursor = 0
        g.checkpoint = null
        db.dao()
            .insertGame(
                GameEntity(g.id, GameJson.encodeToString(g), 0, System.currentTimeMillis(), if(g.scheduledSetupPending) 5 else 4)
            )
        beforeCommit()
        g
    }

    suspend fun configureScheduledGame(candidate: Game, baseRevision: Long, operationID: String = UUID.randomUUID().toString()): Game {
        val payload = GameJson.encodeToString(candidate)
        val prepared = GameJson.decodeFromString<Game>(payload)
        return mutate(prepared.id, baseRevision, operationID, "schedule-setup:" + payload) { original ->
            require(original.scheduledSetupPending && original.isScheduled && !prepared.scheduledSetupPending)
            require(prepared.ourTeamID == original.ourTeamID && setOf(prepared.home.id, prepared.away.id) == setOf(original.home.id, original.away.id)) { "请保留预告的双方球队身份" }
            require(prepared.appleSourceID == null && !prepared.practice && !prepared.isFinal && prepared.totalPitches == 0 && !prepared.openingPitchRecorded && !prepared.clockHasStarted)
            require((prepared.sharedRosterOrigin == null) == (original.sharedRosterOrigin == null))
            if(original.ourTeamID == null) require(prepared.sharedRosterOrigin == null && prepared.postgameSharingChoice == null && prepared.home.isOpponent && prepared.away.isOpponent)
            listOf(prepared.home, prepared.away).forEach { snapshot ->
                if (snapshot.source == "sharedSnapshot") {
                    require(snapshot.id == prepared.ourTeamID && db.dao().team(snapshot.id) == null)
                    requireNotNull(prepared.sharedRosterOrigin).validate(prepared)
                } else {
                    val local = db.dao().team(snapshot.id)?.let { GameJson.decodeFromString<Team>(it.payload) } ?: error("球队已不存在")
                    require(!local.archived && local.source == "local" && snapshot.isOpponent == local.isOpponent)
                    require(local.players.filter { !it.archived }.map { it.id }.toSet() == snapshot.players.map { it.id }.toSet()) { "名单已变化，请重新核对赛前设置" }
                }
            }
            val next = MatchFactory.create(original.id, prepared.home, prepared.away, prepared.rules, original.ourTeamID).apply {
                homeOrder = prepared.homeOrder; awayOrder = prepared.awayOrder
                homePositions = prepared.homePositions; awayPositions = prepared.awayPositions
                homePitcherID = prepared.homePitcherID; awayPitcherID = prepared.awayPitcherID
                homeDesignatedHitterID = prepared.homeDesignatedHitterID; awayDesignatedHitterID = prepared.awayDesignatedHitterID
                sharedRosterOrigin = prepared.sharedRosterOrigin; postgameSharingChoice = prepared.postgameSharingChoice
                scheduledAt = original.scheduledAt; playedTimeZoneID = prepared.playedTimeZoneID
                createdAt = original.createdAt; revision = original.revision + 1; updatedAt = System.currentTimeMillis()
            }
            Participation.validate(next.rules, next.homeOrder!!, next.homePositions!!, next.homeDesignatedHitterID)
            Participation.validate(next.rules, next.awayOrder!!, next.awayPositions!!, next.awayDesignatedHitterID)
            next.validate()
            syncPreparedLineupProfiles(next, prepared)
            next
        }
    }

    suspend fun appendRoster(expected: Team, text: String) = db.withTransaction {
        val row = db.dao().team(expected.id) ?: error("球队已不存在")
        val team = GameJson.decodeFromString<Team>(row.payload)
        require(team == expected && !team.archived && team.source == "local") { "名单已变化，请重新预览" }
        val additions = Roster.previewAppend(text, team.players.flatMap { listOf(it.name, it.englishName) }).map { it.player }
        require(team.players.count { !it.archived } + additions.size <= 200) { "球队现役名单最多 200 人" }
        updateRoster(team.copy(players = team.players + additions))
    }

    suspend fun updateRoster(team: Team) = db.withTransaction {
        require(db.dao().allAppleBackups().none { source -> AppleSourceBackup.prepare(source.current).teams().any { it.id == team.id } }) { "iOS 导入球队的名单编辑尚未支持，原始备份及名单未修改" }
        val original = db.dao().team(team.id) ?: error("球队不存在")
        val prior = GameJson.decodeFromString<Team>(original.payload)
        require(prior.source == "local") { "共享球队请通过授权同步入口管理" }
        require(
            team.source == prior.source &&
                team.isOpponent == prior.isOpponent &&
                team.name.isNotBlank() &&
                team.players.map { it.id }.distinct().size == team.players.size
        )
        require(prior.players.all { old -> team.players.any { it.id == old.id } }) {
            "请归档球员，保留稳定身份"
        }
        team.players.forEach(Roster::validate)
        if (team.archived && !prior.archived)
            require(
                db.dao().allGames().map(::decode).none {
                    !it.isFinal && (it.home.id == team.id || it.away.id == team.id)
                }
            ) {
                "球队有未结束比赛或赛程，请先处理比赛再移除球队"
            }
        db.dao()
            .updateTeam(
                TeamEntity(team.id, team.name, GameJson.encodeToString(team), team.isOpponent)
            )
        beforeCommit()
    }

    suspend fun createRoster(
        name: String,
        shortName: String,
        city: String,
        isOpponent: Boolean,
    ): Team {
        require(name.isNotBlank())
        val t =
            Team(
                UUID.randomUUID().toString(),
                name.trim(),
                emptyList(),
                isOpponent,
                city.trim(),
                shortName.trim().ifBlank { name.trim() },
            )
        db.dao().insertTeam(TeamEntity(t.id, t.name, GameJson.encodeToString(t), isOpponent))
        return t
    }

    suspend fun pitch(
        gameID: String,
        action: PitchAction,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(gameID, baseRevision, operationID, action.name, appleCommand = Command("pitch", pitch = action)) {
            MatchEngine.apply(it, Command("pitch", pitch = action), operationID)
        }

    suspend fun command(
        gameID: String,
        command: Command,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(gameID, baseRevision, operationID, GameJson.encodeToString(command), appleCommand = command) {
            if(it.scheduledSetupPending) {
                require(command.kind == "schedule") { "请先完成比赛赛前设置" }
                val date = requireNotNull(command.dateMillis) { "请填写预告时间" }
                require(date > 0)
                java.time.ZoneId.of(command.timeZoneID)
                it.copy(scheduledAt = date, playedTimeZoneID = command.timeZoneID, revision = it.revision + 1, updatedAt = System.currentTimeMillis()).also { next -> next.validate() }
            } else MatchEngine.apply(it, command, operationID)
        }

    suspend fun undo(
        gameID: String,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game = mutate(gameID, baseRevision, operationID, "undo") { MatchEngine.undo(it) }

    suspend fun redo(
        gameID: String,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game = mutate(gameID, baseRevision, operationID, "redo") { MatchEngine.redo(it) }

    suspend fun correct(
        gameID: String,
        index: Int,
        replacement: Command?,
        reason: String,
        baseRevision: Long,
        acceptContext: Boolean = false,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(
            gameID,
            baseRevision,
            operationID,
            GameJson.encodeToString(CorrectionRequest(index, replacement, reason, acceptContext)),
        ) {
            MatchEngine.correct(it, index, replacement, reason, acceptContext)
        }

    suspend fun loadCorrectionDraft(gameID: String): CorrectionDraft? =
        db.dao().correctionDraft(gameID)?.let {
            GameJson.decodeFromString<CorrectionDraft>(it.payload).also { draft ->
                check(draft.gameID == gameID)
            }
        }

    suspend fun saveCorrectionDraft(draft: CorrectionDraft) = db.withTransaction {
        require(db.dao().appleGameSource(draft.gameID) == null) { "iOS 导入比赛的纠错转换尚未支持，草稿未覆盖原记录" }
        val current = db.dao().game(draft.gameID) ?: error("比赛不存在，草稿未覆盖原资料")
        require(decode(current).appleSourceID == null) { "比赛具有 iOS 来源标记，不能直接写入本机纠错草稿" }
        require(draft.baseRevision <= current.revision)
        db.dao()
            .saveCorrectionDraft(
                CorrectionDraftEntity(
                    draft.gameID,
                    GameJson.encodeToString(draft),
                    System.currentTimeMillis(),
                )
            )
    }

    suspend fun discardCorrectionDraft(gameID: String) = db.dao().deleteCorrectionDraft(gameID)

    suspend fun saveCorrection(
        draft: CorrectionDraft,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(
            draft.gameID,
            draft.baseRevision,
            operationID,
            GameJson.encodeToString(draft),
            clearDraft = true,
        ) {
            History.commit(it, draft)
        }

    private suspend fun mutate(
        gameID: String,
        baseRevision: Long,
        operationID: String,
        signature: String,
        clearDraft: Boolean = false,
        appleCommand: Command? = null,
        reduce: suspend (Game) -> Game,
    ): Game = db.withTransaction {
        require(operationID.isNotBlank())
        val current = db.dao().game(gameID) ?: error("比赛不存在，原资料未修改")
        val prior = db.dao().operation(operationID)
        if (prior != null) {
            require(
                prior.gameID == gameID &&
                    prior.action == signature &&
                    prior.baseRevision == baseRevision
            ) {
                "同一操作编号不能修改内容"
            }
            return@withTransaction decode(current)
        }
        require(db.dao().appleOriginalOperation(operationID.lowercase(java.util.Locale.ROOT)) == null) { "操作编号与已导入的原始历史重复，未写入" }
        check(current.revision == baseRevision) { "比赛已更新，请核对后重试" }
        val imported = db.dao().appleGameSource(gameID)
        val marker = decode(current).appleSourceID
        require((imported == null && marker == null) || imported != null && marker == imported.backupID) { "比赛来源映射缺失或不一致，已停止写入" }
        val next = if (imported == null) reduce(MatchEngine.upgradeLegacy(decode(current))) else {
            require(appleCommand != null) { "这场 iOS 导入比赛的此项历史操作尚不能无损转换，原记录未修改" }
            require(appleCommand.playerUpdates.isNullOrEmpty() && appleCommand.lineupReplacements.isNullOrEmpty()) { "这场 iOS 导入比赛的球员资料更正／阵容替补尚未支持无损转换，原记录与名单未修改" }
            val source = db.dao().appleBackup(imported.backupID) ?: error("比赛来源缺失，原资料未修改")
            val backup = verifyAppleSource(source)
            val priorGame = backup.games(source.id).single { it.id == gameID }
            require(matchesAppleSourceProjection(priorGame, current.payload)) { "比赛与来源备份不一致，已停止写入" }
            val updated = backup.appendCommand(gameID, appleCommand.copy(at = appleCommand.at.takeIf { it > 0 } ?: System.currentTimeMillis()), operationID)
            db.dao().updateAppleBackup(source.copy(current = updated.export()))
            updated.games(source.id).single { it.id == gameID }
        }
        check(next.revision == current.revision + 1) { "记录版本无效，原资料未修改" }
        db.dao()
            .insertOperation(
                OperationEntity(
                    operationID,
                    gameID,
                    signature,
                    baseRevision,
                    System.currentTimeMillis(),
                )
            )
        db.dao()
            .updateGame(
                GameEntity(
                    gameID,
                    GameJson.encodeToString(next),
                    next.revision,
                    System.currentTimeMillis(),
                    if(next.scheduledSetupPending) 5 else 4,
                )
            )
        if (appleCommand != null) syncLineupProfiles(next, appleCommand)
        if (clearDraft) db.dao().deleteCorrectionDraft(gameID)
        beforeCommit()
        next
    }

    private suspend fun syncPreparedLineupProfiles(game: Game, draft: Game) {
        draft.journal.take(draft.cursor).map { it.command }.filter { it.kind == "lineup" }
            .groupBy { it.homeSide }.forEach { (home, commands) ->
                val latest = commands.flatMap { it.playerUpdates.orEmpty() }.associateBy { it.id }.values.toList()
                if (latest.isNotEmpty()) syncLineupProfiles(game, Command("lineup", homeSide = home, playerUpdates = latest))
            }
    }

    /** Called only from a Room transaction. Profile patches never write shared
     * rosters, create people, replace roster membership, or touch older matches. */
    private suspend fun syncLineupProfiles(game: Game, command: Command) {
        val updates = command.playerUpdates.orEmpty()
        if (updates.isEmpty()) return
        require(command.kind == "lineup") { "球员资料更正必须随阵容保存" }
        val home = requireNotNull(command.homeSide)
        val snapshot = if (home) game.home else game.away
        require(updates.map { it.id }.distinct().size == updates.size && updates.all { p -> snapshot.players.any { it.id == p.id } }) { "更正球员身份不属于本队" }
        updates.forEach { patch ->
            Roster.validate(patch)
            val saved = snapshot.players.first { it.id == patch.id }
            require(saved.name == patch.name && saved.chineseName == patch.chineseName && saved.englishName == patch.englishName && saved.number == patch.number && saved.jerseyNumbers == patch.jerseyNumbers) { "球员资料与本场阵容不一致，未写入名单" }
        }
        if (snapshot.source != "local") return // Match snapshot only; no remote authority is implied.
        val row = db.dao().team(snapshot.id) ?: return
        val local = GameJson.decodeFromString<Team>(row.payload)
        if (local.source != "local") return
        require(db.dao().allAppleBackups().none { source -> AppleSourceBackup.prepare(source.current).teams().any { it.id == local.id } }) { "iOS 导入球队的名单更正尚未支持，比赛与名单未修改" }
        require(updates.all { p -> local.players.any { it.id == p.id } }) { "球队名单已变化，请重新核对球员身份" }
        val patched = local.copy(players = local.players.map { prior ->
            val patch = updates.firstOrNull { it.id == prior.id }
            if (patch == null) prior else prior.copy(name = patch.name, chineseName = patch.chineseName,
                englishName = patch.englishName, number = patch.number, jerseyNumbers = patch.jerseyNumbers)
        })
        db.dao().updateTeam(row.copy(payload = GameJson.encodeToString(patched)))
    }

    @kotlinx.serialization.Serializable
    private data class CorrectionRequest(
        val index: Int,
        val replacement: Command?,
        val reason: String,
        val acceptContext: Boolean,
    )

    companion object {
        fun decode(entity: GameEntity): Game {
            check(entity.payloadVersion in 1..5) { "比赛格式较新，原资料已保留，请使用兼容版本" }
            return GameJson.decodeFromString<Game>(entity.payload).also {
                check(it.scheduledSetupPending == (entity.payloadVersion == 5))
                check(it.revision == entity.revision)
                it.validate()
                it.restorePitcherInningUsage()
            }
        }

        fun parseRoster(text: String) = Roster.parse(text)
    }
}
