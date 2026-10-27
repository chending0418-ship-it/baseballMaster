package cc.baseballmaster.scorekeeper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.baseballmaster.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import java.util.UUID

@Entity(tableName = "teams")
data class TeamEntity(@PrimaryKey val id: String, val name: String, val payload: String, val isOpponent: Boolean)
@Entity(tableName = "games")
data class GameEntity(@PrimaryKey val id: String, val payload: String, val revision: Long, val updatedAt: Long, @ColumnInfo(defaultValue = "1") val payloadVersion: Int = 1)
@Entity(tableName = "operations", indices = [Index("gameID")], foreignKeys = [ForeignKey(
    entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameID"], onDelete = ForeignKey.RESTRICT)])
data class OperationEntity(@PrimaryKey val id: String, val gameID: String, val action: String, val baseRevision: Long, val createdAt: Long)

@Dao interface LocalDao {
    @Query("SELECT * FROM teams WHERE isOpponent = 0 ORDER BY name") fun teams(): Flow<List<TeamEntity>>
    @Query("SELECT * FROM games ORDER BY updatedAt DESC") fun games(): Flow<List<GameEntity>>
    @Query("SELECT * FROM games WHERE id = :id") suspend fun game(id: String): GameEntity?
    @Query("SELECT * FROM operations WHERE id = :id") suspend fun operation(id: String): OperationEntity?
    @Query("SELECT COUNT(*) FROM operations WHERE gameID = :id") suspend fun operationCount(id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTeam(team: TeamEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGame(game: GameEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOperation(operation: OperationEntity)
    @Update suspend fun updateGame(game: GameEntity)
}
@Database(entities = [TeamEntity::class, GameEntity::class, OperationEntity::class], version = 2, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        fun open(context: Context, name: String = "baseballmaster.db") =
            Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN payloadVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}

/** No destructive fallback: unreadable/future stores remain untouched. */
class GameRepository(private val db: LocalDatabase, private val beforeCommit: () -> Unit = {}) {
    val teams get() = db.dao().teams()
    val games get() = db.dao().games()
    suspend fun createTeam(name: String, roster: String): Team {
        require(name.trim().isNotEmpty()) { "请填写球队名称" }
        val players = parseRoster(roster)
        val team = Team(UUID.randomUUID().toString(), name.trim(), players)
        db.dao().insertTeam(TeamEntity(team.id, team.name, GameJson.encodeToString(team), false))
        return team
    }
    suspend fun createGame(own: Team, opponentName: String, roster: String, mode: GameMode, ownIsHome: Boolean, rules: Rules = Rules(mode), scheduledAt: Long? = null): Game {
        require(opponentName.trim().isNotEmpty()) { "请填写对手名称" }
        require(rules.mode == mode)
        val required = if (mode == GameMode.slowPitch) 10 else 9
        val opponent = Team(UUID.randomUUID().toString(), opponentName.trim(), parseRoster(roster), true)
        require(own.players.size >= required && opponent.players.size >= required) { "双方至少需要 $required 名球员" }
        val game = MatchFactory.create(UUID.randomUUID().toString(), if(ownIsHome)own else opponent, if(ownIsHome)opponent else own,rules,own.id,scheduledAt)

        db.withTransaction {
            db.dao().insertTeam(TeamEntity(opponent.id, opponent.name, GameJson.encodeToString(opponent), true))
            db.dao().insertGame(GameEntity(game.id, GameJson.encodeToString(game), game.revision, System.currentTimeMillis(), 2))
            beforeCommit()
        }
        return game
    }
    suspend fun pitch(gameID: String, action: PitchAction, baseRevision: Long, operationID: String = UUID.randomUUID().toString()): Game =
        mutate(gameID, baseRevision, operationID, action.name) { MatchEngine.apply(it, Command("pitch", pitch = action), operationID) }

    suspend fun command(gameID: String, command: Command, baseRevision: Long, operationID: String = UUID.randomUUID().toString()): Game =
        mutate(gameID, baseRevision, operationID, GameJson.encodeToString(command)) { MatchEngine.apply(it, command, operationID) }

    suspend fun undo(gameID: String, baseRevision: Long, operationID: String = UUID.randomUUID().toString()): Game =
        mutate(gameID, baseRevision, operationID, "undo") { MatchEngine.undo(it) }

    suspend fun redo(gameID: String, baseRevision: Long, operationID: String = UUID.randomUUID().toString()): Game =
        mutate(gameID, baseRevision, operationID, "redo") { MatchEngine.redo(it) }

    suspend fun correct(gameID: String, index: Int, replacement: Command?, reason: String, baseRevision: Long,
                        acceptContext: Boolean = false, operationID: String = UUID.randomUUID().toString()): Game =
        mutate(gameID, baseRevision, operationID, GameJson.encodeToString(CorrectionRequest(index, replacement, reason, acceptContext))) {
            MatchEngine.correct(it, index, replacement, reason, acceptContext)
        }

    private suspend fun mutate(gameID: String, baseRevision: Long, operationID: String, signature: String,
                               reduce: (Game) -> Game): Game = db.withTransaction {
        require(operationID.isNotBlank())
        val current = db.dao().game(gameID) ?: error("比赛不存在，原资料未修改")
        val prior = db.dao().operation(operationID)
        if (prior != null) {
            require(prior.gameID == gameID && prior.action == signature && prior.baseRevision == baseRevision) { "同一操作编号不能修改内容" }
            return@withTransaction decode(current)
        }
        check(current.revision == baseRevision) { "比赛已更新，请核对后重试" }
        val next = reduce(MatchEngine.upgradeLegacy(decode(current)))
        check(next.revision == current.revision + 1) { "记录版本无效，原资料未修改" }
        db.dao().insertOperation(OperationEntity(operationID, gameID, signature, baseRevision, System.currentTimeMillis()))
        db.dao().updateGame(GameEntity(gameID, GameJson.encodeToString(next), next.revision, System.currentTimeMillis(), 2))
        beforeCommit()
        next
    }
    @kotlinx.serialization.Serializable
    private data class CorrectionRequest(val index: Int, val replacement: Command?, val reason: String, val acceptContext: Boolean)
    companion object {
        fun decode(entity: GameEntity): Game {
            check(entity.payloadVersion in 1..2) { "比赛格式较新，原资料已保留，请使用兼容版本" }
            return GameJson.decodeFromString<Game>(entity.payload).also { check(it.revision == entity.revision); it.validate() }
        }
        fun parseRoster(text: String): List<Player> {
            val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(rows.isNotEmpty() && rows.size <= 200) { "请填写 1–200 名球员，每行一位" }
            return rows.map { row ->
                val parts = row.split(',', '，').map { it.trim() }
                require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].matches(Regex("[0-9]{1,2}"))) { "名单格式：姓名,背号（0–99，支持 00）" }
                Player(UUID.randomUUID().toString(), parts[0], parts[1])
            }
        }
    }
}
