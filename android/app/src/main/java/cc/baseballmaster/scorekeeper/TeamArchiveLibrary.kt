package cc.baseballmaster.scorekeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.AppleArchiveHistory
import cc.baseballmaster.core.AppleTeamArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

@Composable
internal fun TeamArchiveControls() {
    var showing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showing = true }, modifier = Modifier.testTag("open-team-archives")) { Text("球队完整档案（只读）") }
    if (showing) TeamArchiveLibrary { showing = false }
}

@Composable
private fun TeamArchiveLibrary(dismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) {
        TeamArchiveStore(File(context.filesDir, "team-archives"), File(context.cacheDir, "team-archive-staging"))
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    val blocked = busy || picker != null
    var pending by remember { mutableStateOf<TeamArchiveStore.Pending?>(null) }
    var listing by remember { mutableStateOf(TeamArchiveStore.Listing(emptyList(), 0)) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    var exportID by rememberSaveable { mutableStateOf<String?>(null) }
    var archive by remember { mutableStateOf<AppleTeamArchive?>(null) }

    val stagedForCleanup = rememberUpdatedState(pending)
    DisposableEffect(store) { onDispose { active = false; stagedForCleanup.value?.let(store::discard) } }
    LaunchedEffect(store) {
        try { listing = withContext(Dispatchers.IO) { store.list() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "无法读取档案目录：${e.message}" }
    }
    LaunchedEffect(selectedID) {
        archive = null
        val id = selectedID ?: return@LaunchedEffect
        busy = true
        try { archive = withContext(Dispatchers.IO) { store.read(id) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { selectedID = null; message = "无法读取档案：${e.message}。原有文件未修改。" }
        finally { busy = false }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (!active || picker != "import") return@rememberLauncherForActivityResult
        picker = null
        if (uri != null && !busy) scope.launch {
            busy = true
            var staged: TeamArchiveStore.Pending? = null
            try {
                val cancellation = currentCoroutineContext()
                val ready = withContext(Dispatchers.IO) {
                    (context.contentResolver.openInputStream(uri) ?: error("无法打开文件")).use { input ->
                        store.stage(input) { cancellation.ensureActive() }.also { staged = it }
                    }
                }
                pending?.let(store::discard)
                pending = ready
                staged = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "档案未导入：${e.message ?: "文件不受支持"}。原有资料未修改。" }
            finally { staged?.let(store::discard); busy = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (!active || picker != "export") return@rememberLauncherForActivityResult
        picker = null
        val id = exportID
        exportID = null
        if (uri != null && id != null && !busy) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件")).use { store.export(id, it) }
                }
                message = "已保存原始 .bmteam 文件，内容与导入文件完全相同。文件未加密，请妥善保管。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "导出失败：${e.message ?: "请检查存储空间"}。本机原档案未修改；目标文件可能不完整，请重新导出。" }
            finally { busy = false }
        }
    }

    FormPage("球队完整档案", blocked, true, "", "", dismiss, {}) {
        Text("保存共享球队导出的原始档案，离线查看名单与比分。不会替换当前球队，也不会恢复管理、记录或观看权限。")
        Text("当前为只读快照，不可续记；完整比赛互导仍在开发。档案另存于本机，不包含在 Android 本机备份中，请另行导出保管。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { picker = "import"; open.launch(arrayOf("application/octet-stream", "application/json", "*/*")) }, enabled = !blocked && pending == null, modifier = Modifier.testTag("import-team-archive")) {
            Text(if (busy) "正在处理档案…" else "选择 .bmteam 档案（不超过 100 MB）")
        }
        if (listing.unreadableCount > 0) Text("${listing.unreadableCount} 份本机文件未通过校验，已保留原文件且不会打开。", color = MaterialTheme.colorScheme.error)
        if (listing.items.isEmpty() && !busy) Text("尚无本机球队档案")
        listing.items.forEach { item ->
            CardBlock {
                TextButton(onClick = { selectedID = item.id }, enabled = !blocked, modifier = Modifier.testTag("team-archive-${item.id}")) { Text(item.summary.teamName) }
                Text("${item.summary.activeGameCount} 场有效比赛 / 共 ${item.summary.gameCount} 条记录 · 本机只读")
            }
        }
    }
    pending?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) { store.discard(candidate); pending = null } }, title = { Text("保存只读球队档案？") },
            text = { Text("${candidate.summary.teamName} · ${candidate.summary.activeGameCount} 场有效比赛。将保存原文件、完整历史及删除标记，不修改当前球队或恢复共享权限。") },
            confirmButton = { TextButton(onClick = {
                if (!busy) scope.launch {
                    busy = true
                    try {
                        val saved = withContext(Dispatchers.IO) { store.save(candidate) }
                        listing = withContext(Dispatchers.IO) { store.list() }
                        message = if (saved.alreadyPresent) "相同档案已在本机，无需重复导入。" else "已保存为本机只读档案；当前球队与共享权限未改变。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "保存失败：${e.message}。原有资料未修改。" }
                    finally { store.discard(candidate); pending = null; busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("confirm-team-archive")) { Text("保存只读档案") } },
            dismissButton = { TextButton(onClick = { store.discard(candidate); pending = null }, enabled = !busy) { Text("取消") } })
    }
    archive?.let { loaded ->
        TeamArchiveReader(loaded, blocked, dismiss = { selectedID = null }, export = {
            exportID = selectedID
            picker = "export"
            save.launch("BaseballMaster-球队完整档案.bmteam")
        })
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("球队档案") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("完成") } }) }
}

@Composable
private fun TeamArchiveReader(archive: AppleTeamArchive, busy: Boolean, dismiss: () -> Unit, export: () -> Unit) {
    var playerPage by remember(archive) { mutableIntStateOf(0) }
    var gamePage by remember(archive) { mutableIntStateOf(0) }
    var selectedGame by remember(archive) { mutableStateOf<JsonObject?>(null) }
    val profile = archive.manifest.getValue("profile").jsonObject
    val players = profile.getValue("players").jsonArray
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    FormPage("本机球队档案", busy, true, "", "", dismiss, {}) {
        Text(archive.summary.teamName, fontWeight = FontWeight.Bold)
        Text("导出时的只读快照；共享球队后续修改不会更新这份档案。可查看原始逐球、打席与更正审计；仅供阅读，不可编辑或撤回更正。")
        TextButton(onClick = export, enabled = !busy, modifier = Modifier.testTag("export-team-archive")) { Text("保存原始 .bmteam 文件") }
        Text("球队名单（${players.size} 人）", fontWeight = FontWeight.Bold)
        players.drop(playerPage * 20).take(20).forEach { raw ->
            val player = raw.jsonObject
            val name = player.text("chineseName").ifBlank { player.text("englishName").ifBlank { "未填写姓名" } }
            val numbers = player.getValue("numbers").jsonArray.joinToString(" / ") { "#${it.jsonPrimitive.content}" }.ifBlank { "暂无背号" }
            val archived = player["archived"]?.jsonPrimitive?.booleanOrNull == true
            Text("$numbers $name${if (archived) " · 已停用" else ""}")
        }
        ArchivePages(playerPage, players.size, enabled = !busy) { playerPage = it }
        Text("比赛记录（${archive.records.size} 条）", fontWeight = FontWeight.Bold)
        archive.records.drop(gamePage * 20).take(20).forEach { record ->
            CardBlock {
                val purged = record.getValue("purged").jsonPrimitive.boolean
                val deleted = record.getValue("deleted").jsonPrimitive.boolean
                if (purged) Text("已清除的比赛 · 仅保留删除标记")
                else {
                    val stored = record.getValue("payload").jsonObject
                    val state = stored.getValue("state").jsonObject
                    val away = state.getValue("awayTeam").jsonObject.text("name")
                    val home = state.getValue("homeTeam").jsonObject.text("name")
                    fun score(key: String) = state.getValue(key).jsonArray.sumOf { it.jsonPrimitive.long }
                    Text("$away  ${score("awayRunsByInning")} : ${score("homeRunsByInning")}  $home", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { selectedGame = stored }, enabled = !busy, modifier = Modifier.testTag("archive-game-history")) { Text("查看逐球与更正历史") }
                    Text(if (deleted) "导出时已在回收站" else when (stored.getValue("status").jsonPrimitive.int) { 1 -> "已结束"; 2 -> "预告比赛"; else -> "进行中" })
                }
            }
        }
        ArchivePages(gamePage, archive.records.size, enabled = !busy) { gamePage = it }
    }
    selectedGame?.let { stored -> ArchiveGameHistory(stored) { selectedGame = null } }
}

@Composable
private fun ArchivePages(page: Int, count: Int, enabled: Boolean, select: (Int) -> Unit) {
    if (count > 20) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { select(page - 1) }, enabled = enabled && page > 0) { Text("上一页") }
        Text("${page + 1} / ${(count + 19) / 20}")
        TextButton(onClick = { select(page + 1) }, enabled = enabled && (page + 1) * 20 < count) { Text("下一页") }
    }
}

@Composable
internal fun ArchiveGameHistory(stored: JsonObject, secure: Boolean = false, dismiss: () -> Unit) {
    var loaded by remember(stored) { mutableStateOf<AppleArchiveHistory?>(null) }
    var failure by remember(stored) { mutableStateOf<String?>(null) }
    LaunchedEffect(stored) {
        try { loaded = withContext(Dispatchers.Default) { AppleArchiveHistory.read(stored) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "历史内容无法读取" }
    }
    val history = loaded
    if (history == null) {
        FormPage("档案比赛历史", false, true, "", "", dismiss, {},secure=secure) {
            Text(failure?.let { "无法读取历史：$it。原始档案已保留。" } ?: "正在读取历史…")
        }
        return
    }
    var section by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    val sections = listOf("逐球日志", "记分事件", "打席", "历史操作", "更正审计")
    val rows = listOf(history.logs, history.events, history.appearances, history.operations, history.corrections)[section]
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    FormPage("档案比赛历史", false, true, "", "", dismiss, {},secure=secure) {
        Text("原始只读历史", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("archive-history-title"))
        Text("保留原编号及顺序，不从当前局面推算历史；此页不能续记或撤回更正。")
        if (!history.hasJournal) Text("此记录没有操作日志，无法据此完整重放或纠错。")
        sections.forEachIndexed { index, title ->
            TextButton(onClick = { section = index; page = 0 }, modifier = Modifier.testTag("archive-history-section-$index")) {
                Text("${if (section == index) "● " else ""}$title")
            }
        }
        Text("${sections[section]} · ${rows.size} 条", modifier = Modifier.testTag("archive-history-count"))
        if (rows.isEmpty()) Text("原文件未包含此类记录")
        rows.drop(page * 20).take(20).forEachIndexed { index, row ->
            CardBlock {
                Text("${page * 20 + index + 1}. ${row.text("title").ifBlank { row.text("text").ifBlank { row.text("reason") } }}", fontWeight = FontWeight.Bold)
                Text("ID: ${row.text("id")}")
                when (section) {
                    0 -> Text("第 ${row.text("inning")} 局${if (row.text("isTop") == "true") "上" else "下"}")
                    1 -> {
                        Text("${row.text("category")} · ${row.text("notation")}")
                        Text("关联日志：${row.text("logEntryID")}\n关联打席：${row.text("plateAppearanceID")}")
                    }
                    2 -> {
                        Text("第 ${row.text("inning")} 局${if (row.text("isTop") == "true") "上" else "下"} · ${if (row.text("completed") == "true") "已完成" else "未完成"}${if (row.text("interrupted") == "true") " · 中断" else ""}")
                        Text("打者：${row["batterIDs"]?.jsonArray?.joinToString { it.jsonPrimitive.content }.orEmpty()}")
                    }
                    3 -> {
                        Text("${row["command"]?.jsonObject?.keys?.joinToString().orEmpty()}${if (row.text("edited") == "true") " · 已编辑" else ""}${if (row.text("inserted") == "true") " · 补记" else ""}")
                        Text("事件 ${row["eventIDs"]?.jsonArray?.size ?: 0} 条 · 日志 ${row["logIDs"]?.jsonArray?.size ?: 0} 条")
                    }
                    4 -> {
                        row["summary"]?.jsonArray?.forEach { Text(it.jsonPrimitive.content) }
                        val previous = row["previousJournal"]?.jsonObject
                        Text("保留更正前操作 ${previous?.get("operations")?.jsonArray?.size ?: 0} 条及原始检查点")
                    }
                }

            }
        }
        ArchivePages(page, rows.size, enabled = true) { page = it }
    }
}
