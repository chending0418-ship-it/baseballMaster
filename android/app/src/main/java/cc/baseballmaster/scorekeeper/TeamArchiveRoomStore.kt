package cc.baseballmaster.scorekeeper

import android.content.Context
import cc.baseballmaster.core.AppleTeamArchiveIntegrity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal object TeamArchiveBytes {
    // Base64 plus the device envelope's Base64 must still fit the 100 MB recovery limit.
    const val libraryLimit = 50_000_000L
    fun validate(id: String, bytes: ByteArray) {
        require(Regex("[a-f0-9]{64}").matches(id) && bytes.size <= 100_000_000) { "档案编号或大小无效" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(id == digest) { "球队档案校验失败，原有资料未修改" }
        bytes.inputStream().use(AppleTeamArchiveIntegrity::inspect)
    }
}

/** The original bytes live beside games in Room, so recovery commits all data together. */
internal class TeamArchiveRoomStore(context: Context) : AutoCloseable {
    private val db = LocalDatabase.open(context.applicationContext)
    private val repository = GameRepository(db)
    private val legacy = legacyStore(context)
    var migrationWarning: String? = null
        private set

    fun stage(input: java.io.InputStream, canceled: () -> Unit = {}) = legacy.stage(input, canceled)
    fun discard(pending: TeamArchiveStore.Pending) = legacy.discard(pending)

    private suspend fun migrate() {
        migrationWarning = null
        try { repository.migrateLegacyTeamArchives(legacy) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { migrationWarning = "旧档案尚未纳入本机备份：${e.message}。原文件已保留，仍可查看或另存。" }
    }

    suspend fun list(): TeamArchiveStore.Listing {
        migrate()
        val old = legacy.list()
        var unreadable = old.unreadableCount
        val items = old.items.associateBy { it.id }.toMutableMap()
        repository.teamArchiveRows().forEach { row ->
            try {
                TeamArchiveBytes.validate(row.id, row.original)
                items[row.id] = TeamArchiveStore.Item(row.id, row.original.inputStream().use(AppleTeamArchiveIntegrity::inspect))
            } catch (_: Exception) { unreadable++ }
        }
        return TeamArchiveStore.Listing(items.values.sortedBy { it.id }, unreadable)
    }

    suspend fun save(pending: TeamArchiveStore.Pending): TeamArchiveStore.Saved {
        // Re-read the staging file and validate its digest, rather than trust an old preview.
        val bytes = pending.file.inputStream().use { readBounded(it) }
        TeamArchiveBytes.validate(pending.digest, bytes)
        val existing = repository.teamArchiveRow(pending.digest)
        repository.importTeamArchives(listOf(TeamArchiveEntity(pending.digest, bytes)))
        return TeamArchiveStore.Saved(TeamArchiveStore.Item(pending.digest, bytes.inputStream().use(AppleTeamArchiveIntegrity::inspect)), existing != null)
    }

    suspend fun read(id: String): cc.baseballmaster.core.AppleTeamArchive {
        val row = repository.teamArchiveRow(id) ?: return legacy.read(id)
        TeamArchiveBytes.validate(id, row.original)
        return row.original.inputStream().use(AppleTeamArchiveIntegrity::read)
    }

    suspend fun export(id: String, output: OutputStream) {
        val row = repository.teamArchiveRow(id)
        if (row == null) legacy.export(id, output)
        else { TeamArchiveBytes.validate(id, row.original); output.write(row.original); output.flush() }
    }

    override fun close() = db.close()
    companion object {
        private fun legacyStore(context: Context) = TeamArchiveStore(File(context.filesDir, "team-archives"), File(context.cacheDir, "team-archive-staging"))
        private fun readBounded(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return output.toByteArray()
                require(output.size().toLong() + n <= 100_000_000) { "档案超过 100 MB" }
                output.write(buffer, 0, n)
            }
        }
        suspend fun migrateLegacy(repository: GameRepository, context: Context) = repository.migrateLegacyTeamArchives(legacyStore(context))
    }
}

/** A failed/corrupt legacy migration must stop backup, never silently omit a file. */
internal suspend fun GameRepository.migrateLegacyTeamArchives(context: Context) = TeamArchiveRoomStore.migrateLegacy(this, context)
private suspend fun GameRepository.migrateLegacyTeamArchives(legacy: TeamArchiveStore) {
    val listing = legacy.list()
    require(listing.unreadableCount == 0) { "旧档案有 ${listing.unreadableCount} 份未通过校验，请先另存原文件" }
    require(listing.items.size <= 100) { "旧档案超过 100 份，请先另存原文件" }
    var total = 0L
    val rows = listing.items.map { item ->
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) { require(++total <= TeamArchiveBytes.libraryLimit) { "旧档案库超过 50 MB，请先另存原文件" }; output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                total += count
                require(total <= TeamArchiveBytes.libraryLimit) { "旧档案库超过 50 MB，请先另存原文件" }
                output.write(bytes, offset, count)
            }
        }
        legacy.export(item.id, bounded)
        TeamArchiveEntity(item.id, output.toByteArray())
    }
    importTeamArchives(rows)
}
