package cc.baseballmaster.scorekeeper

import androidx.room.*

/** Original bytes are immutable; current bytes change only in the same transaction as the game. */
@Entity(tableName = "apple_backups")
data class AppleBackupEntity(@PrimaryKey val id: String, val name: String, val gameCount: Int, val original: ByteArray, val current: ByteArray)

@Entity(tableName = "apple_game_sources", indices = [Index("backupID")], foreignKeys = [
    ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameID"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = AppleBackupEntity::class, parentColumns = ["id"], childColumns = ["backupID"], onDelete = ForeignKey.RESTRICT),
])
data class AppleGameSourceEntity(@PrimaryKey val gameID: String, val backupID: String)

@Entity(tableName = "apple_original_operations", indices = [Index("gameID")], foreignKeys = [
    ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameID"], onDelete = ForeignKey.RESTRICT),
])
data class AppleOriginalOperationEntity(@PrimaryKey val id: String, val gameID: String)

data class AppleBackupSummary(val id: String, val name: String, val gameCount: Int)
data class AppleImportReceipt(val id: String, val gameCount: Int, val alreadyPresent: Boolean, val currentTeamID: String?)
