package cc.baseballmaster.scorekeeper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.baseballmaster.core.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

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
    @Query("SELECT * FROM teams ORDER BY name") fun teams(): Flow<List<TeamEntity>>

    @Query("SELECT * FROM games ORDER BY updatedAt DESC") fun games(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id") suspend fun game(id: String): GameEntity?

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
        ],
    version = 3,
    exportSchema = true,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        fun open(context: Context, name: String = "baseballmaster.db") =
            Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

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
        mutate(gameID, baseRevision, operationID, action.name) {
            MatchEngine.apply(it, Command("pitch", pitch = action), operationID)
        }

    suspend fun command(
        gameID: String,
        command: Command,
        baseRevision: Long,
        operationID: String = UUID.randomUUID().toString(),
    ): Game =
        mutate(gameID, baseRevision, operationID, GameJson.encodeToString(command)) {
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
        val current = db.dao().game(draft.gameID) ?: error("比赛不存在，草稿未覆盖原资料")
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
        check(current.revision == baseRevision) { "比赛已更新，请核对后重试" }
        val next = reduce(MatchEngine.upgradeLegacy(decode(current)))
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
