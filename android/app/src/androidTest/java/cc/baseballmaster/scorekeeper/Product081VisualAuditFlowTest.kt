package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native photographs for a page-by-page review. Run once in each already configured window. */
@RunWith(AndroidJUnit4::class)
class Product081VisualAuditFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val settings = context.getSharedPreferences("baseballmaster-settings", 0)
    private lateinit var view: String
    private lateinit var directory: File
    private lateinit var index: JSONObject
    private val screens = JSONArray()
    private val checks = mutableListOf<String>()
    private var ordinal = 0

    private data class Fixture(val own: Team, val opponent: Team, val finished: List<Game>, val basic: Game, val pro: Game) {
        val games get() = finished + listOf(basic, pro)
    }

    private fun <T> db(block: suspend (LocalDatabase, GameRepository) -> T): T = runBlocking {
        val database = LocalDatabase.open(context)
        try { block(database, GameRepository(database)) } finally { database.close() }
    }
    private fun payload(id: String) = db { database, _ -> requireNotNull(database.dao().game(id)).payload }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun viewport(): JSONObject {
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val root = device.findObject(By.res("portrait-app"))?.visibleBounds
        return JSONObject().put("displayWidthPx", device.displayWidth).put("displayHeightPx", device.displayHeight)
            .put("screenWidthDp", config.screenWidthDp).put("screenHeightDp", config.screenHeightDp)
            .put("densityDpi", metrics.densityDpi).put("orientation", config.orientation)
            .put("appBoundsPx", root?.toShortString() ?: "not exposed by this window")
    }
    private fun verifyViewport() {
        val width = context.resources.configuration.screenWidthDp
        if (view == "phone") {
            assertTrue("phone photographs require the actual compact window, got $width dp", width < 600)
            assertTrue("phone photographs require the actual portrait viewport", device.displayHeight > device.displayWidth)
        } else assertTrue("expanded photographs require the actual expanded window, got $width dp", width >= 600)
    }
    private fun node(tag: String): UiObject2 = NativeUi.find(device, By.res(tag))
    private fun ready(tag: String) { node(tag); checks += "visible control: $tag" }
    private fun click(tag: String) { node(tag).click(); device.waitForIdle(500); UiTreeCache.refresh() }
    private fun back() { device.pressBack(); device.waitForIdle(500); UiTreeCache.refresh() }
    private fun text(value: String) { NativeUi.find(device, By.text(value)); checks += "visible text: $value" }
    private fun verifyInitialReview(summary: String) {
        UiTreeCache.refresh()
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        listOf("尚未提交" to 12, summary to 18).forEach { (label, minimumHeightDp) ->
            val bounds = requireNotNull(device.findObject(By.text(label))).visibleBounds
            assertTrue("The initial review summary must be fully visible before any scrolling: $label",
                bounds.height() >= minimumHeightDp * context.resources.displayMetrics.density - 2 &&
                    bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        checks += "unscrolled initial review: pending status and full result summary within content viewport"
    }
    private fun verifyInitialProField() {
        UiTreeCache.refresh()
        assertNotNull("The contact field must appear without a scrolling helper",
            device.wait(Until.findObject(By.res("contact-field")), 10_000))
        UiTreeCache.refresh()
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val field = requireNotNull(device.findObject(By.res("contact-field"))).visibleBounds
        val density = context.resources.displayMetrics.density
        assertTrue("The entire 260dp input field must fit the initial $view task viewport before any scrolling",
            field.height() >= 260 * density - 2 && field.width() > 200 * density &&
                field.left >= viewport.left && field.right <= viewport.right &&
                field.top >= viewport.top && field.bottom <= viewport.bottom)
        checks += "initial unscrolled complete 260dp contact field bounds=$field within $viewport (direct findObject; no scroll helper)"
    }
    private fun selected(tag: String) {
        val choice = node(tag)
        assertTrue("Selected state must be exposed for $tag", choice.isChecked || choice.isSelected)
        checks += "checked or selected: $tag"
    }
    private fun showContactMap() {
        ready("player-contact-field")
        val heightDp = if (view == "expanded") 300 else 250
        val expected = (heightDp * context.resources.displayMetrics.density).toInt()
        repeat(8) {
            UiTreeCache.refresh()
            val field = requireNotNull(device.findObject(By.res("player-contact-field"))).visibleBounds
            val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
            if (field.height() >= expected - 4 && field.top >= viewport.top && field.bottom <= viewport.bottom) {
                checks += "complete ${heightDp}dp contact canvas within content viewport after intentional scrolling"; return
            }
            val topOverflow = (viewport.top - field.top).coerceAtLeast(0)
            val bottomOverflow = (field.bottom - viewport.bottom).coerceAtLeast(0)
            val distance = (maxOf(expected - field.height(), topOverflow, bottomOverflow) + 20).coerceIn(40, viewport.height() / 3)
            val center = viewport.centerY()
            val down = if (bottomOverflow > 0) true else if (topOverflow > 0) false else field.top > viewport.top + 4
            device.swipe(viewport.centerX(), center + if (down) distance / 2 else -distance / 2,
                viewport.centerX(), center + if (down) -distance / 2 else distance / 2, 25)
            device.waitForIdle(500)
        }
        error("The real ${heightDp}dp contact canvas must fit the content viewport for its scrolled photograph")
    }
    private fun assertPersonalOpeningRatesVisible() {
        UiTreeCache.refresh()
        assertNotNull(device.wait(Until.findObject(By.res("stats-player-batting-打率 AVG")), 10_000))
        UiTreeCache.refresh()
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val density = context.resources.displayMetrics.density
        listOf("打率 AVG", "上垒率 OBP", "长打率 SLG", "攻击指数 OPS").forEach { label ->
            val bounds = requireNotNull(device.findObject(By.res("stats-player-batting-$label"))).visibleBounds
            assertTrue("$label must be fully readable in the unscrolled opening viewport", bounds.height() >= 60 * density - 2 &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.width() > 40 * density)
            checks += "initial unscrolled $label bounds=$bounds within $viewport (direct findObject; no scroll helper)"
        }
    }
    private fun showPersonalCountTable() {
        val viewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val distance = (180 * context.resources.displayMetrics.density).toInt().coerceAtMost(viewport.height() / 3)
        device.swipe(viewport.centerX(), viewport.centerY() + distance / 2,
            viewport.centerX(), viewport.centerY() - distance / 2, 25)
        device.waitForIdle(500)
        ready("stats-player-batting-垒打数 TB")
        UiTreeCache.refresh()
        val currentViewport = requireNotNull(device.findObject(By.res("product-page-scroll"))).visibleBounds
        val density = context.resources.displayMetrics.density
        listOf("打席 PA", "垒打数 TB").forEach { label ->
            val bounds = requireNotNull(device.findObject(By.res("stats-player-batting-$label"))).visibleBounds
            assertTrue("The full counting-statistics table must fit its scrolled photograph", bounds.height() >= 40 * density - 2 &&
                bounds.top >= currentViewport.top && bounds.bottom <= currentViewport.bottom)
        }
        checks += "intentionally scrolled from identity overview; counting grid from PA through TB is visible"
    }
    private fun launch(tab: Int = 0) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("tab-$tab")), 30_000))
        click("tab-$tab")
        verifyViewport()
    }
    private fun open(game: Game) { launch(); click("game-${game.id}"); ready("game-results") }
    private fun input(tag: String, value: String) {
        node(tag).apply { click(); text = value }
        inst.runOnMainSync {
            val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        device.waitForIdle(500)
    }
    private fun chooseRoster(tag: String, team: Team) {
        click(tag); input("roster-picker-search", team.name); click("choose-roster-${team.id}")
    }
    private fun writeIndex() { File(directory, "screen-index.json").writeText(index.toString(2) + "\n") }
    private fun shot(name: String, category: String, route: String, visualChecks: String) {
        device.waitForIdle(500); inst.waitForIdleSync()
        val frame = CountDownLatch(1)
        inst.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        assertTrue("A composed frame is required for each photograph", frame.await(3, TimeUnit.SECONDS))
        UiTreeCache.refresh(); verifyViewport()
        val fileName = "%02d-%s".format(++ordinal, name)
        val png = File(directory, "$fileName.png")
        val xml = File(directory, "$fileName.xml")
        assertTrue("Native screenshot failed", device.takeScreenshot(png))
        device.dumpWindowHierarchy(xml)
        assertTrue("The photograph must have a real hierarchy", xml.length() > 100)
        screens.put(JSONObject().put("ordinal", ordinal).put("name", name).put("category", category).put("route", route)
            .put("capturedAtEpochMs", System.currentTimeMillis()).put("auditView", view).put("viewport", viewport())
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("apkSha256", index.getString("apkSha256"))
            .put("png", png.name).put("pngSha256", digest(png)).put("xml", xml.name).put("xmlSha256", digest(xml))
            .put("automatedChecks", JSONArray(checks.toList()))
            .put("manualVisualReview", visualChecks).put("reviewStatus", "captured; visual acceptance requires review"))
        checks.clear(); writeIndex()
    }
    private fun unchanged(game: Game, before: String, reason: String) {
        assertEquals(reason, before, payload(game.id)); checks += reason
    }

    private fun seed(): Fixture = db { _, repo ->
        val own = repo.createTeam("海浪少年队", "陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9\n沈奕航,10\n陆明轩,11\n叶知远,12")
        val opponent = repo.createTeam("飞鹰棒球队", "赵景行,1\n孙浩宇,2\n李沐阳,3\n周子谦,4\n吴承泽,5\n郑明轩,6\n王梓睿,7\n冯逸辰,8\n陈奕帆,9\n褚云舟,10\n卫星野,11\n蒋书航,12", true)
        suspend fun match(method: String): Game = repo.createPreparedGame(MatchFactory.create(
            UUID.randomUUID().toString(), opponent, own, Rules(recordingMethod = method), own.id))
        suspend fun play(game: Game, outcome: Outcome): Game {
            val fielding = if (outcome == Outcome.homeRun) Fielding(status = "noFielding") else Fielding(route = listOf(6, 3))
            return repo.command(game.id, Command("play", outcome = outcome,
                recordingMethod = game.rules.recordingMethod,
                fielding = if (game.rules.recordingMethod == "traditional" && outcome == Outcome.groundOut) fielding else null,
                observation = if (game.rules.recordingMethod == "situation") Observation(
                    type = if (outcome == Outcome.homeRun) "fly" else if (outcome == Outcome.groundOut) "ground" else "line",
                    precision = "point", x = .38, y = .42, fielding = fielding) else null), game.revision)
        }
        val finished = (0..1).map { index ->
            var game = match(if (index == 0) "traditional" else "situation")
            repeat(index + 1) { game = play(game, Outcome.homeRun) }
            repeat(3) { game = play(game, Outcome.groundOut) }
            assertFalse("The seed must include our team's defensive half", game.isTop)
            repeat(3) { game = play(game, Outcome.groundOut) }
            game = repo.command(game.id, Command("finish", text = "weather"), game.revision)
            assertTrue(game.isFinal)
            assertTrue(game.batting.values.sumOf { it.hits } > 0)
            assertTrue(game.pitching.values.sumOf { it.pitches } > 0)
            assertTrue(game.fielding.values.sumOf { it.assists + it.putouts } > 0)
            game
        }
        suspend fun live(method: String): Game {
            var game = play(match(method), Outcome.triple)
            game = repo.command(game.id, Command("pitch", pitch = PitchAction.ball), game.revision)
            assertNotNull(game.bases[2]); assertTrue(game.openingPitchRecorded)
            return game
        }
        Fixture(own, opponent, finished, live("traditional"), live("situation"))
    }

    @Test fun captureActualProductPagesWithoutCommittingAnyEditingDraft() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        view = InstrumentationRegistry.getArguments().getString("audit_view") ?: error("Pass audit_view=phone or expanded for the actual configured window")
        require(view in setOf("phone", "expanded"))
        directory = File(context.getExternalFilesDir(null), "081-visual-audit/$view-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val previousTeam = settings.getString("current_team_id", null)
        val hadTeam = settings.contains("current_team_id")
        val previousTheme = settings.getString("appAppearance", null)
        val hadTheme = settings.contains("appAppearance")
        val existing = db { database, _ ->
            database.dao().allGames().associate { it.id to it.payload } to database.dao().allTeams().associate { it.id to it.payload }
        }
        index = JSONObject().put("schema", 1).put("auditView", view).put("status", "running")
            .put("captureKind", "Native UiDevice photographs and UIAutomator XML; no generated or resized UI")
            .put("apkSha256", digest(File(context.applicationInfo.sourceDir)))
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("packageName", context.packageName).put("startedAtEpochMs", System.currentTimeMillis())
            .put("existingGamesBefore", existing.first.size).put("existingTeamsBefore", existing.second.size).put("screens", screens)
        writeIndex()
        try {
            verifyViewport()
            val fixture = seed()
            index.put("syntheticOnly", true).put("ownTeamID", fixture.own.id).put("opponentTeamID", fixture.opponent.id)
                .put("seededGameIDs", JSONArray(fixture.games.map { it.id }))
            val baselines = fixture.games.associate { it.id to payload(it.id) }
            settings.edit().putString("current_team_id", fixture.own.id).putString("appAppearance", "light").commit()
            launch(); ready("home-team-record")
            assertEquals(fixture.own.name, node("home-team-name").text)
            shot("tab-games", "四个Tab", "比赛", "当前球队、战绩、进行中与最近比赛的层级；留白、字号、底部Tab及竖屏/展开布局。")
            click("tab-1"); ready("roster-${fixture.own.id}")
            shot("tab-teams", "四个Tab", "球队", "共享入口、名单分类、球队卡及操作入口；没有逐行堆叠编辑按钮。")
            click("roster-${fixture.own.id}"); ready("roster-player-${fixture.own.players.first().id}")
            shot("team-roster", "球队与球员", "球队 → 海浪少年队", "12人真实名单的姓名、背号、行间距、进入个人统计与右侧菜单。")
            click("roster-player-menu-${fixture.own.players.first().id}"); ready("edit-player-${fixture.own.players.first().id}")
            shot("roster-player-menu", "球队与球员", "球队 → 球员操作菜单", "菜单层级、编辑与归档的可发现性及手指点击区域。")
            click("edit-player-${fixture.own.players.first().id}"); ready("player-chinese")
            shot("roster-player-editor", "球队与球员", "球队 → 编辑球员（未保存）", "姓名/英文名/多背号字段与保存、取消的语义和键盘外布局。")
            back(); click("roster-player-${fixture.own.players.first().id}")
            assertPersonalOpeningRatesVisible()
            ready("stats-player-game-scope")
            shot("player-statistics-overview", "个人统计", "球队名单 → 球员统计", "个人身份、年度、场次范围和投打守分类；此入口与统计Tab使用同一真实投影。")
            showPersonalCountTable()
            shot("player-statistics-batting", "个人统计", "球员统计 → 向下滚动至完整计数表", "与默认首屏区别的滚动视图；从打席PA至垒打数TB的完整真实计数，不以首屏或自动滚动冒充默认可见。")
            click("stats-player-open-game-filter"); ready("stats-player-filter-save"); ready("stats-player-filter-cancel")
            shot("player-game-filter", "个人统计", "球员统计 → 筛选比赛（未应用）", "独立比赛筛选页、全选/清除、实际比赛日期与比分；截图后取消，保留原个人范围。")
            click("stats-player-filter-cancel")
            val personalGames = PlayerStatisticsProjection.calculate(fixture.own, fixture.own.players.first(), fixture.games,
                java.time.Year.now().value, null).selectedGames
            val contacts = PlayerContactProjection.calculate(fixture.own.players.first().id, personalGames)
            assertEquals("The real fixtures include one recorded point", 1, contacts.contacts.count { it.point != null })
            assertEquals("The traditional fixture stays explicitly unknown", 1, contacts.unknownCount)
            checks += "selected completed fixtures contain one recorded point and one unknown location"
            showContactMap()
            shot("player-contact-map", "个人统计", "球员统计 → 向下滚动至击球落点", "滚动后的真实Canvas图，不是默认首屏；一条精确落点与一条位置未知记录，未知不绘点、不补轨迹。")
            click("player-contact-record-0")
            val contact = contacts.contacts.first()
            val contactGame = personalGames.first { it.id == contact.gameID }
            text("完成"); text(MatchDates.label(contactGame))
            text("${contactGame.away.shortName} ${contactGame.awayRuns} : ${contactGame.homeRuns} ${contactGame.home.shortName}")
            if (contact.originalText.isNotBlank()) text(contact.originalText)
            shot("player-contact-detail", "个人统计", "击球落点 → 第一条真实记录详情", "只读详情展示对应比赛日期、双方与比分、局次、记录位置/未知说明及原始文字；用实际完成按钮关闭。")
            NativeUi.find(device, By.text("完成")).click(); device.waitForIdle(500); UiTreeCache.refresh()
            click("stats-player-close")
            launch(2); ready("stats-team-record")
            assertEquals("2 场比赛 · 2胜0负0平", node("stats-team-record").text)
            shot("tab-statistics", "四个Tab", "统计", "球队/年度选择、2场已结束比赛范围、投打守分类与指标卡布局。")
            click("choice-统计分类-PITCHING"); selected("choice-统计分类-PITCHING"); ready("stats-summary-PITCHING")
            shot("statistics-pitching", "球队统计", "统计 → 投手", "真实投球数据及字段对齐，不能以按钮存在替代可读性检查。")
            click("choice-统计分类-FIELDING"); selected("choice-统计分类-FIELDING"); ready("stats-summary-FIELDING")
            shot("statistics-fielding", "球队统计", "统计 → 守备", "刺杀、助杀与失误统计的可读性。")
            launch(3); ready("open-app-settings")
            text("本机记录员"); text("当前球队 · ${fixture.own.name}"); text("本机保存")
            shot("tab-profile", "四个Tab", "我的", "本机记录员身份、当前球队与本机保存说明、学习帮助、本机数据和设置分组及底部Tab。")
            click("open-app-settings"); selected("choice-App 外观-light")
            shot("settings-light", "设置", "我的 → App设置 → 浅色", "设置分组、选择态、开关与浅色语义色。")
            click("choice-App 外观-dark"); selected("choice-App 外观-dark")
            assertEquals("dark", settings.getString("appAppearance", null))
            shot("settings-dark", "设置", "我的 → App设置 → 深色", "深色背景、卡片、文字、选择态、开关和系统栏的对比度。")
            click("choice-App 外观-light"); back(); click("open-local-data"); ready("manage-roster-from-profile")
            shot("local-data", "设置", "我的 → 本地数据", "本地范围说明、真实数据数量、管理入口和备份恢复分组。")
            ready("device-backup-save")
            shot("local-data-backup", "设置", "本地数据 → 备份与恢复", "原生备份/恢复/iOS来源与只读档案入口，不触发写入或系统文件选择。")
            back(); click("open-app-about"); text("Android ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            shot("about", "设置", "我的 → 关于", "实际安装版本、品牌、隐私和支持文案排版。")

            launch(); click("new-game"); ready("setup-own-team")
            shot("setup-first-screen", "比赛设置", "比赛 → 开始一场比赛", "记录方式独立分组、参赛球队、主客菜单、时间分组与固定下一步。")
            chooseRoster("setup-own-team", fixture.own); chooseRoster("setup-opponent", fixture.opponent)
            click("setup-game-mode"); ready("choice-模式-standard")
            shot("setup-mode-menu", "比赛设置", "新比赛 → 比赛模式", "模式菜单完整性、当前选项及遮罩关系。")
            click("choice-模式-standard"); ready("setup-postgame-choice")
            shot("setup-rules-sharing", "比赛设置", "新比赛 → 规则与赛后共享", "完整保留规则、预告/补录和共享设置；首屏与后续区块的节奏。")
            click("start-game"); ready("lineup-row-away-0")
            shot("pregame-first-screen", "赛前阵容", "下一步 → 双方阵容", "说明卡、客队先攻、中文12人名单、守备9/9、替换/守位/排序及固定确认。")
            click("lineup-position-away-0"); ready("lineup-position-option-9")
            shot("pregame-position-menu", "赛前阵容", "双方阵容 → 第一棒守位菜单", "9个守位原生菜单、中文名称、锚点和遮挡；与iOS实际效果逐页比较。")
            back(); ready("lineup-row-home-0")
            shot("pregame-home-team", "赛前阵容", "双方阵容 → 主队", "主客双方均可编辑；展开时两队并列、手机时顺序呈现的实际效果。")
            back(); back()
            assertEquals("Cancelling setup must not create a fifth match", 4, db { database, _ -> database.dao().allGames().count { GameRepository.decode(it).ourTeamID == fixture.own.id } })

            open(fixture.basic); ready("record-play")
            ready(if (view == "expanded") "scorekeeping-wide-layout" else "scorekeeping-compact-layout")
            shot("live-basic", "现场记录", "比赛 → 基础版进行中", "R/H/E、球数、场上九人、当前投打、主次操作及提示密度。")
            click("open-substitutions"); ready("edit-lineup-away")
            shot("live-lineup-tasks", "赛中阵容", "记录 → 阵容", "双方阵容与换投/代打/代跑/守备/双重换人/守位的任务层级。")
            click("edit-lineup-away"); ready("lineup-row-away-0")
            shot("live-lineup-editor", "赛中阵容", "阵容 → 客队阵容", "当前打者、下一位、已打、守位和球数说明，区别于赛前阵容。")
            back(); click("substitution-pitcher"); ready("substitution-player-${fixture.opponent.players[1].id}")
            shot("pitcher-selection", "赛中阵容", "阵容 → 换投手", "当前投手、原投手责任说明、场上与替补候选及确认边界。")
            click("substitution-player-${fixture.opponent.players[1].id}"); assertTrue(node("save-substitution").isEnabled)
            shot("pitcher-selected-unsaved", "赛中阵容", "换投手 → 已选候选（未确认）", "候选选择态与确认操作；截图后取消，不产生换投记录。")
            back(); back(); unchanged(fixture.basic, baselines.getValue(fixture.basic.id), "Lineup and pitcher selection cancellation preserves the complete Room payload")
            click("record-play"); ready("arrival-1")
            shot("basic-entry", "基础版击球", "击球进入场内 → 基础结果", "结果卡的图标、色彩、简述、可点击区及无需逐球选择记录方式。")
            click("arrival-1"); text("已记录：到一垒")
            shot("basic-cause", "基础版击球", "到一垒 → 原因", "已选结果摘要、安打/失误等原因卡与返回关系。")
            click("cause-single"); ready("save-play"); verifyInitialReview("一垒安打")
            shot("basic-runner-review", "基础版击球", "原因 → 核对跑者（尚未提交）", "按人折叠卡、建议进垒、进垒原因、保存前提示和唯一提交边界。")
            click("cancel-play"); unchanged(fixture.basic, baselines.getValue(fixture.basic.id), "Cancelling the basic review does not save a ball, revision or clock change")

            open(fixture.pro); ready("record-play")
            ready(if (view == "expanded") "scorekeeping-wide-layout" else "scorekeeping-compact-layout")
            shot("live-pro", "现场记录", "比赛 → Pro版进行中", "与基础版相同现场骨架，记录方式属于本场而非每球重复切换。")
            click("record-play"); ready("pro-outcome-groundOut")
            shot("pro-entry", "Pro版击球", "击球进入场内 → Pro结果", "紧凑结果网格、全部击球、按过程和结果待确认入口。")
            click("all-batted-outcomes"); ready("outcome-catalog-search")
            shot("pro-all-outcomes", "Pro版击球", "Pro → 全部击球", "真实完整目录、分组、搜索与返回；没有为截图删掉结果。")
            back(); click("pro-outcome-groundOut"); verifyInitialProField()
            shot("pro-field-initial", "Pro版击球", "滚地出局 → 球场", "球型网格、落点/守备传球Tab、初始直接可见球场及固定下一步。")
            click("choice-球型-ground")
            val field = node("contact-field").visibleBounds
            assertTrue("A real tappable field is required", field.width() > 200 && field.height() > 200)
            device.click(field.left + field.width() / 3, field.top + field.height() / 3)
            device.waitForIdle(500); ready("fielding-6"); selected("choice-球场操作-route")
            shot("pro-landing-chosen", "Pro版击球", "球场 → 点击落点", "真实落点标记及自动切入同场地守备/传球状态。")
            click("fielding-6"); click("fielding-3")
            val route = node("situation-route").text
            val shortstop = fixture.pro.fieldingTeam.players.first { it.id == fixture.pro.fieldingPositions[6] }.name
            val firstBaseman = fixture.pro.fieldingTeam.players.first { it.id == fixture.pro.fieldingPositions[3] }.name
            assertEquals("The route must identify shortstop then first baseman, with no extra touch", "$shortstop → $firstBaseman", route)
            checks += "real field route is exactly shortstop identity → first-baseman identity"
            shot("pro-route-6-3", "Pro版击球", "守备传球 → 游击6 → 一垒3", "6→3选中状态、球员身份和路线文字；球场、落点与守备共用草稿。")
            click("play-next"); ready("save-play"); verifyInitialReview("滚地出局 · 地滚")
            shot("pro-runner-review", "Pro版击球", "球场 → 核对跑者（尚未提交）", "结果、球型、守备路线、跑者去向、原因及保存前提示。")
            UiTreeCache.refresh()
            if (!device.hasObject(By.res("choice-最终位置-3-4"))) click("runner-card-3")
            click("choice-最终位置-3-4"); ready("runner-cause-3")
            shot("pro-runner-expanded", "Pro版击球", "核对跑者 → 展开三垒跑者", "逐人去向与原因选择，保留同一球草稿；没有额外保存。")
            click("runner-cause-3"); ready("runner-cause-3-hit")
            shot("pro-runner-cause-menu", "Pro版击球", "核对跑者 → 进垒原因", "跑者原因菜单的中文语义、选项范围和保存前边界；仍未提交本球。")
            back()
            click("cancel-play"); unchanged(fixture.pro, baselines.getValue(fixture.pro.id), "Cancelling Pro landing, 6-to-3 route and runner review preserves the entire Room payload")

            open(fixture.finished.first()); click("game-results"); ready("result-final-score")
            shot("results-overview", "比赛结果", "已结束比赛 → 比赛结果", "终场比分、R/H/E记分表、队内共享与后续数据入口。")
            ready("choice-数据-batting"); selected("choice-数据-batting"); ready("game-result-batting-${fixture.own.players.first().id}")
            shot("results-batting", "比赛结果", "比赛结果 → 打击", "真实终场打击表、姓名背号与数据列；检查手机横向可读性和展开利用率。")
            click("choice-数据-pitching"); selected("choice-数据-pitching"); ready("game-result-pitching-${fixture.own.players.first().id}")
            shot("results-pitching", "比赛结果", "比赛结果 → 投手", "真实投球局数、球数、三振、失分等表格，不依赖导出才能读。")
            click("choice-数据-fielding"); selected("choice-数据-fielding"); ready("game-result-fielding-${fixture.own.players.first().id}")
            shot("results-fielding", "比赛结果", "比赛结果 → 守备", "真实6→3造成的刺杀/助杀数据、行列对齐与横向滚动。")
            click("choice-数据-records"); selected("choice-数据-records"); text("中文比赛记录 · 按打席 · ${AppearanceProjection.build(fixture.finished.first()).size} 条")
            assertTrue("The final fixture must contain a genuinely unfinished appearance for the final-state copy check",
                AppearanceProjection.build(fixture.finished.first()).any { !it.complete && it.events.none { event -> event.incomplete } })
            text("本打席未完成（比赛已结束）")
            UiTreeCache.refresh()
            assertFalse("A finished match must not label its unfinished appearance as currently in progress",
                device.hasObject(By.text("当前打席进行中")))
            checks += "final unfinished appearance states that the match has ended; no current-in-progress label"
            shot("results-records", "比赛结果", "比赛结果 → 记录", "真实中文逐打席记录、局数与球员身份；记录与统计源于同一场比赛。")
            click("close-game-results"); ready("back-home"); click("back-home"); ready("tab-0")
            fixture.games.forEach { unchanged(it, baselines.getValue(it.id), "All visual-only navigation leaves seeded game ${it.id} unchanged") }
            db { database, _ ->
                existing.first.forEach { (id, original) -> assertEquals("Existing game must never be changed", original, requireNotNull(database.dao().game(id)).payload) }
                existing.second.forEach { (id, original) -> assertEquals("Existing roster must never be changed", original, requireNotNull(database.dao().team(id)).payload) }
            }
            index.put("existingDataUnchanged", true).put("editingDraftsCommitted", 0).put("seededGamesUnchangedAfterCapture", true)
                .put("status", "complete").put("finishedAtEpochMs", System.currentTimeMillis())
        } catch (failure: Throwable) {
            index.put("status", "failed").put("failure", failure.toString())
            runCatching { shot("failure", "失败证据", "最后实际页面", "保留真实失败现场；不能当成已通过的页面。") }
            throw failure
        } finally {
            val restored = settings.edit().apply {
                if (hadTeam) putString("current_team_id", previousTeam) else remove("current_team_id")
                if (hadTheme) putString("appAppearance", previousTheme) else remove("appAppearance")
            }.commit()
            index.put("currentTeamPreferenceRestored", settings.contains("current_team_id") == hadTeam && settings.getString("current_team_id", null) == previousTeam)
                .put("themePreferenceRestored", settings.contains("appAppearance") == hadTheme && settings.getString("appAppearance", null) == previousTheme)
            writeIndex()
            assertTrue("The original team and theme preferences must be restored", restored &&
                index.getBoolean("currentTeamPreferenceRestored") && index.getBoolean("themePreferenceRestored"))
            // A fresh Activity also reads the restored theme/team into its ViewModel.
            runCatching { launch() }
        }
    }
}
