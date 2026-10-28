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
import kotlinx.serialization.json.encodeToJsonElement

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

@Dao
interface LocalDao {
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
        ],
    version = 4,
    exportSchema = true,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        fun open(context: Context, name: String = "baseballmaster.db") =
            Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

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
                    require(GameJson.encodeToJsonElement(expected) == GameJson.parseToJsonElement(saved.payload)) { "来源与比赛不一致，未覆盖现有资料" }
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
        ))
    }

    /** Restore only into an empty database; no existing match or draft is ever overwritten. */
    suspend fun restoreDeviceBackup(bytes: ByteArray): String? {
        val snapshot = AndroidDeviceBackup.decode(bytes)
        return db.withTransaction {
            require(db.dao().allTeams().isEmpty() && db.dao().allGames().isEmpty() && db.dao().allOperations().isEmpty() && db.dao().allCorrectionDrafts().isEmpty() && db.dao().allAppleBackups().isEmpty() && db.dao().allAppleGameSources().isEmpty()) { "本机已有资料，未覆盖任何数据" }
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

    suspend fun createTeam(name: String, roster: String): Team {
        require(name.trim().isNotEmpty()) { "请填写球队名称" }
        val players = parseRoster(roster)
        val team = Team(UUID.randomUUID().toString(), name.trim(), players)
        db.dao().insertTeam(TeamEntity(team.id, team.name, GameJson.encodeToString(team), false))
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

    suspend fun createPreparedGame(prepared: Game): Game = db.withTransaction {
        require(prepared.appleSourceID == null) { "带来源标记的比赛必须通过来源导入事务保存" }
        val g = GameJson.decodeFromString<Game>(GameJson.encodeToString(prepared))
        g.validate()
        require(
            g.totalPitches == 0 &&
                !g.openingPitchRecorded &&
                !g.clockHasStarted &&
                g.ourTeamID != null
        )
        val own =
            db.dao().team(g.ourTeamID!!)?.let { GameJson.decodeFromString<Team>(it.payload) }
                ?: error("我方球队不存在")
        require(!own.archived && own.source == "local" && own.id in listOf(g.home.id, g.away.id))
        Participation.validate(g.rules, g.homeOrder!!, g.homePositions!!, g.homeDesignatedHitterID)
        Participation.validate(g.rules, g.awayOrder!!, g.awayPositions!!, g.awayDesignatedHitterID)
        val opponent = if (g.home.id == own.id) g.away else g.home
        if (db.dao().team(opponent.id) == null)
            db.dao()
                .insertTeam(
                    TeamEntity(opponent.id, opponent.name, GameJson.encodeToString(opponent), true)
                )
        g.revision = 0
        g.journal = emptyList()
        g.cursor = 0
        g.checkpoint = null
        db.dao()
            .insertGame(
                GameEntity(g.id, GameJson.encodeToString(g), 0, System.currentTimeMillis(), 4)
            )
        beforeCommit()
        g
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
        mutate(gameID, baseRevision, operationID, action.name, applePitch = action) {
            MatchEngine.apply(it, Command("pitch", pitch = action), operationID)
        }

    suspend fun command(
        gameID: String,
        command: Command,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(gameID, baseRevision, operationID, GameJson.encodeToString(command), applePitch = if (command.kind == "pitch" && command == Command("pitch", pitch = command.pitch, at = command.at)) command.pitch else null, atMillis = command.at.takeIf { it > 0 }) {
            MatchEngine.apply(it, command, operationID)
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
        applePitch: PitchAction? = null,
        atMillis: Long? = null,
        reduce: (Game) -> Game,
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
            require(applePitch != null) { "这场 iOS 导入比赛目前仅支持受限投球续记；此操作尚不能无损转换，原记录未修改" }
            val source = db.dao().appleBackup(imported.backupID) ?: error("比赛来源缺失，原资料未修改")
            val backup = verifyAppleSource(source)
            val priorGame = backup.games(source.id).single { it.id == gameID }
            require(GameJson.encodeToJsonElement(priorGame) == GameJson.parseToJsonElement(current.payload)) { "比赛与来源备份不一致，已停止写入" }
            val updated = backup.appendPitch(gameID, applePitch, operationID, atMillis ?: System.currentTimeMillis())
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
                    4,
                )
            )
        if (clearDraft) db.dao().deleteCorrectionDraft(gameID)
        beforeCommit()
        next
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
            check(entity.payloadVersion in 1..4) { "比赛格式较新，原资料已保留，请使用兼容版本" }
            return GameJson.decodeFromString<Game>(entity.payload).also {
                check(it.revision == entity.revision)
                it.validate()
            }
        }

        fun parseRoster(text: String) = Roster.parse(text)
    }
}
