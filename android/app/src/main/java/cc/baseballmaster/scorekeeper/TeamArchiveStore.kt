package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.AppleTeamArchive
import cc.baseballmaster.core.AppleTeamArchiveIntegrity
import cc.baseballmaster.core.AppleTeamArchiveIntegrityResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Private, read-only copies of the original .bmteam bytes. No Room or authority dependency. */
internal class TeamArchiveStore(
    private val directory: File,
    private val stagingDirectory: File,
    private val beforePublish: () -> Unit = {},
    private val byteLimit: Long = 100_000_000,
) {
    data class Pending internal constructor(internal val file: File, val digest: String, val summary: AppleTeamArchiveIntegrityResult)
    data class Item(val id: String, val summary: AppleTeamArchiveIntegrityResult)
    data class Listing(val items: List<Item>, val unreadableCount: Int)
    data class Saved(val item: Item, val alreadyPresent: Boolean)

    companion object {
        private val lock = Any()
        private val idPattern = Regex("[a-f0-9]{64}")
    }

    fun stage(input: InputStream, checkCanceled: () -> Unit = {}): Pending {
        ensureDirectory(stagingDirectory)
        val file = File.createTempFile("archive-", ".pending", stagingDirectory)
        try {
            FileOutputStream(file).use { output -> copyBounded(input, output, checkCanceled); output.fd.sync() }
            checkCanceled()
            val summary = file.inputStream().use(AppleTeamArchiveIntegrity::inspect)
            return Pending(file, digest(file), summary)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun discard(pending: Pending) {
        // Only this store's generated staging files are removable through this operation.
        require(pending.file.canonicalFile.parentFile == stagingDirectory.canonicalFile)
        pending.file.delete()
    }

    fun save(pending: Pending): Saved = synchronized(lock) {
        ensureDirectory(directory)
        require(pending.file.canonicalFile.parentFile == stagingDirectory.canonicalFile) { "档案暂存位置无效" }
        require(digest(pending.file) == pending.digest) { "暂存档案已改变，请重新选择" }
        val summary = pending.file.inputStream().use(AppleTeamArchiveIntegrity::inspect)
        val destination = file(pending.digest)
        if (destination.exists()) {
            verify(destination, pending.digest)
            return@synchronized Saved(Item(pending.digest, summary), true)
        }
        val temporary = File.createTempFile("archive-", ".pending", directory)
        var published = false
        try {
            pending.file.inputStream().use { input ->
                FileOutputStream(temporary).use { output -> copyBounded(input, output); output.fd.sync() }
            }
            verify(temporary, pending.digest)
            beforePublish()
            // Atomic no-clobber publication: even a competing writer cannot replace an existing file.
            // Fail closed on filesystems without hard links; never fall back to an overwrite.
            Files.createLink(destination.toPath(), temporary.toPath())
            published = true
            verify(destination, pending.digest)
            Saved(Item(pending.digest, summary), false)
        } catch (failure: Throwable) {
            if (published) destination.delete()
            throw failure
        } finally {
            temporary.delete()
        }
    }

    fun list(): Listing = synchronized(lock) {
        ensureDirectory(directory)
        val items = mutableListOf<Item>()
        var unreadable = 0
        val files = directory.listFiles() ?: error("无法读取本机档案目录")
        files.filter { it.isFile && it.extension == "bmteam" }.sortedBy { it.name }.forEach { candidate ->
            try {
                val id = candidate.nameWithoutExtension
                require(idPattern.matches(id))
                verify(candidate, id)
                items += Item(id, candidate.inputStream().use(AppleTeamArchiveIntegrity::inspect))
            } catch (_: Exception) { unreadable++ }
        }
        Listing(items, unreadable)
    }

    fun read(id: String): AppleTeamArchive = synchronized(lock) {
        val source = file(id)
        verify(source, id)
        source.inputStream().use(AppleTeamArchiveIntegrity::read)
    }

    fun export(id: String, output: OutputStream) = synchronized(lock) {
        val source = file(id)
        verify(source, id)
        source.inputStream().use { input -> copyBounded(input, output) }
        output.flush()
    }

    private fun file(id: String): File {
        require(idPattern.matches(id)) { "档案编号无效" }
        return File(directory, "$id.bmteam")
    }

    private fun verify(file: File, expectedDigest: String) {
        require(file.isFile && file.length() <= byteLimit) { "档案不存在或超过 100 MB" }
        require(digest(file) == expectedDigest) { "本机档案校验失败，原有文件未修改" }
        file.inputStream().use(AppleTeamArchiveIntegrity::inspect)
    }

    private fun ensureDirectory(file: File) {
        check(file.isDirectory || file.mkdirs()) { "无法创建本机档案目录" }
    }

    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= byteLimit) { "档案超过 100 MB" }
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, checkCanceled: () -> Unit = {}) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            checkCanceled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= byteLimit) { "档案超过 100 MB，请选择较小的档案" }
            output.write(buffer, 0, count)
        }
    }
}
