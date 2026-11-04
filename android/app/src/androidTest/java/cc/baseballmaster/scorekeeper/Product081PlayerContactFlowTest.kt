package cc.baseballmaster.scorekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import cc.baseballmaster.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real committed matches, actual Canvas taps and a read-only personal filtering workflow. */
@RunWith(AndroidJUnit4::class)
class Product081PlayerContactFlowTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    @org.junit.Rule @JvmField val captureFailure=object:org.junit.rules.TestWatcher() {
        override fun failed(error:Throwable,description:org.junit.runner.Description) {shot("failure")}
    }
    private fun <T> db(block:suspend(LocalDatabase,GameRepository)->T):T=runBlocking {
        val database=LocalDatabase.open(context)
        try {block(database,GameRepository(database))} finally {database.close()}
    }
    private fun node(tag:String)=NativeUi.find(device,By.res(tag))
    private fun click(tag:String) {node(tag).click();device.waitForIdle(500);UiTreeCache.refresh()}
    private fun expect(text:String) {NativeUi.find(device,By.text(text))}
    private fun shot(name:String) {
        device.waitForIdle(500);UiTreeCache.refresh()
        val directory=requireNotNull(context.getExternalFilesDir(null))
        assertTrue(device.takeScreenshot(File(directory,"081-player-contact-$name.png")))
        device.dumpWindowHierarchy(File(directory,"081-player-contact-$name.xml"))
    }
    private fun fullField():UiObject2 {
        val density=context.resources.displayMetrics.density
        repeat(8) {
            val field=node("player-contact-field")
            val bounds=field.visibleBounds
            if(bounds.height()>=250*density-3) return field
            val scroll=node("product-page-scroll").visibleBounds
            val distance=minOf((120*density).toInt(),scroll.height()/3)
            device.swipe(scroll.centerX(),scroll.bottom-24,scroll.centerX(),scroll.bottom-24-distance,35)
            device.waitForIdle(500);UiTreeCache.refresh()
        }
        error("Contact Canvas must be fully visible before mapping its real tap coordinates")
    }
    private fun closeContact() {
        // AlertDialog is a separate native accessibility window. Its real,
        // uniquely labelled confirmation action remains accessible by text.
        NativeUi.find(device, By.text("完成")).click()
        device.waitForIdle(500); UiTreeCache.refresh()
    }
    private fun openRecord(result:String) {
        NativeUi.find(device,By.text(java.util.regex.Pattern.compile("[0-9]+ · $result"))).click()
        device.waitForIdle(500);UiTreeCache.refresh()
    }

    @Test fun actualPointTapRegionAndUnknownDetailsAndPersonalScopeNeverWriteSavedData() {
        Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(3000)
        val settings=context.getSharedPreferences("baseballmaster-settings",0)
        val hadTeam=settings.contains("current_team_id")
        val priorTeam=settings.getString("current_team_id",null)
        try {
            val suffix=UUID.randomUUID().toString().take(5)
            val roster="陈靖森,13\n朱睿荃,64\n袁乐扬,21\n袁允司,68\n白兰田,74\n钱芳林,95\n林浩然,6\n许嘉辰,8\n周予安,9"
            val (team,games)=db {_,repo ->
                val own=repo.createTeam("落点海风$suffix",roster)
                suspend fun finish(outcome:Outcome,observation:Observation?):Game {
                    var game=repo.createGame(own,"落点青禾$suffix",roster,GameMode.standard,false)
                    game=repo.command(game.id,Command("pitch",pitch=PitchAction.ball),game.revision)
                    game=repo.command(game.id,Command("play",outcome=outcome,observation=observation),game.revision)
                    return repo.command(game.id,Command("finish",text="weather"),game.revision)
                }
                own to listOf(finish(Outcome.single,Observation(type="line",precision="point",x=.42,y=.27)),
                    finish(Outcome.flyOut,Observation(type="fly",precision="region",region="left")),
                    finish(Outcome.homeRun,Observation(type="fly")))
            }
            assertTrue(games.all {it.isFinal && it.totalPitches==2 && it.away.players.first().id==team.players.first().id})
            val before=db {database,_->Triple(database.dao().allGames().associate {it.id to it.payload},
                database.dao().allTeams().associate {it.id to it.payload},database.dao().allOperations().associateBy {it.id})}
            fun noWrites(stage:String)=db {database,_->
                assertEquals("$stage: complete saved match payloads",before.first,database.dao().allGames().associate {it.id to it.payload})
                assertEquals("$stage: complete roster payloads",before.second,database.dao().allTeams().associate {it.id to it.payload})
                assertEquals("$stage: complete operation rows",before.third,database.dao().allOperations().associateBy {it.id})
            }
            assertTrue(settings.edit().putString("current_team_id",team.id).commit())
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("tab-2")),30_000))
            click("tab-2");click("stats-player-${team.players.first().id}")
            expect("当前筛选 3 场");expect("● 安打 2");expect("● 出局 1");expect("● 其他 0")
            NativeUi.find(device,By.textContains("未记录位置 1 次，不绘制落点"))
            val field=fullField();val bounds=field.visibleBounds
            val density=context.resources.displayMetrics.density.toDouble()
            val geometry=BaseballFieldGeometry(bounds.width()/density,bounds.height()/density)
            val point=geometry.contactPoint(FieldPoint(.42,.27))
            shot("three-games-real-map")
            assertTrue(device.click(bounds.left+(point.x*density).toInt(),bounds.top+(point.y*density).toInt()))
            device.waitForIdle(500);UiTreeCache.refresh()
            expect("一垒安打");expect("第1局上");expect("位置：记录点位")
            expect(MatchDates.label(games.first()))
            expect("${games.first().away.shortName} 0 : 0 ${games.first().home.shortName}")
            expect(games.first().entries.first {it.kind=="play"}.text)
            shot("point-tapped-real-single-detail");closeContact()
            openRecord("飞球出局")
            expect("位置：左外野（区域范围，图上为近似示意）");shot("region-is-approximate");closeContact()
            openRecord("本垒打")
            expect("位置：未记录，不推测落点");shot("unknown-never-invents-point");closeContact()
            noWrites("Plot taps and all three real contact dialogs")
            click("stats-player-open-game-filter")
            click("stats-player-filter-clear")
            StatisticsUi.setChecked(device,"stats-player-filter-game-${games.first().id}",true)
            click("stats-player-filter-save")
            expect("当前筛选 1 场");expect("● 安打 1");expect("● 出局 0");expect("● 其他 0")
            NativeUi.find(device,By.textContains("未记录位置 0 次，不绘制落点"))
            openRecord("一垒安打");expect("位置：记录点位");closeContact()
            shot("point-only-personal-scope")
            noWrites("Filtering the personal map to only the actual point match")
            assertEquals(team.id,settings.getString("current_team_id",null))
        } finally {
            assertTrue(settings.edit().apply {
                if(hadTeam) putString("current_team_id",priorTeam) else remove("current_team_id")
            }.commit())
            assertEquals(hadTeam,settings.contains("current_team_id"))
            assertEquals(priorTeam,settings.getString("current_team_id",null))
        }
    }
}
