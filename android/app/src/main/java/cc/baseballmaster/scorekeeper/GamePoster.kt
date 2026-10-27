package cc.baseballmaster.scorekeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.baseballmaster.core.Game
import cc.baseballmaster.core.GameMode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Mirrors the existing iOS match announcement poster and uses its saved match date. */
internal object GamePoster {
    fun render(game: Game, headline: String, venue: String, note: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = Color.rgb(26, 28, 26); val red = Color.rgb(199, 41, 31); val paper = Color.rgb(245, 242, 232)
        canvas.drawColor(paper)
        fun block(x: Float, y: Float, w: Float, h: Float, color: Int) { paint.color = color; canvas.drawRect(x, y, x + w, y + h, paint) }
        fun text(value: String, x: Int, y: Int, w: Int, h: Int, size: Float, color: Int, bold: Boolean = false, center: Boolean = false) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT }
            var current = size
            fun layout(): StaticLayout {
                p.textSize = current
                return StaticLayout.Builder.obtain(value, 0, value.length, p, w).setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            }
            var l = layout()
            while (current > 12 && l.height > h) { current -= 1; l = layout() }
            canvas.save(); canvas.clipRect(x, y, x + w, y + h); canvas.translate(x.toFloat(), y.toFloat()); l.draw(canvas); canvas.restore()
        }
        block(0f, 0f, 18f, 1440f, red)
        text("BASEBALL MASTER  /  MATCH DAY",72,54,730,40,24f,ink,true)
        block(830f,48f,178f,62f,red); text("赛事预告",838,61,162,44,29f,paper,true,true)
        text(headline,72,143,936,80,35f,ink)
        block(72f,247f,936f,5f,ink)
        text("AWAY  ／  客队",76,278,920,42,26f,red,true)
        text(game.away.name,64,322,952,198,174f,ink,true)
        block(72f,552f,936f,2f,ink); block(460f,523f,160f,62f,red); text("VS",460,531,160,54,40f,paper,true,true)
        text("HOME  ／  主队",76,599,920,42,26f,red,true)
        text(game.home.name,64,643,952,198,174f,ink,true)
        block(72f,869f,936f,5f,ink)
        val whenMillis = if (game.isScheduled) game.scheduledAt ?: game.createdAt.takeIf { it > 0 } else game.playedAt ?: game.startedAt ?: game.scheduledAt ?: game.createdAt.takeIf { it > 0 }
        val date = whenMillis?.let { runCatching { Instant.ofEpochMilli(it).atZone(ZoneId.of(game.playedTimeZoneID)) }.getOrNull() }
        fun fmt(pattern: String) = date?.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA)) ?: "待定"
        text(fmt("yyyy / MM.dd"),72,907,592,85,67f,ink,true)
        text(fmt("EEEE"),74,997,520,42,28f,ink)
        block(687f,907f,2f,123f,ink)
        text(fmt("HH:mm"),722,907,286,85,67f,red,true)
        text("开赛 · 当地时间",725,997,283,42,25f,ink)
        block(72f,1059f,936f,2f,ink)
        text("球场  /  VENUE",74,1085,926,36,22f,red,true)
        text(venue.ifBlank { "场地待通知" },72,1127,936,82,39f,ink,true)
        text(note,74,1231,926,98,29f,ink)
        block(72f,1350f,936f,2f,ink)
        val rules = if (game.rules.mode == GameMode.slowPitch) {
            "成人慢垒 / " + (if (game.rules.timed) "时间赛 ${game.rules.timeLimitMinutes ?: "待定"} 分钟" else "${game.rules.innings} 局") + " / 10 人守备"
        } else "${game.rules.innings} 局制  /  ${game.rules.fielders} 人守备" + (game.rules.timeLimitMinutes?.let { "  /  限时 $it 分钟" } ?: "")
        text(rules,74,1373,492,40,21f,ink)
        text("棒球大师 · 以组织方最新通知为准",576,1373,432,40,21f,ink)
        return bitmap
    }
    fun write(context: Context, game: Game, headline: String, venue: String, note: String): File {
        val dir = File(context.cacheDir, "reports"); check(dir.isDirectory || dir.mkdirs())
        val file = File(dir, "BaseballMaster-poster-${UUID.randomUUID()}.png")
        val bitmap = render(game, headline, venue, note)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        return file
    }
}

@Composable
internal fun GamePosterScreen(game: Game, dismiss: () -> Unit) {
    var headline by remember { mutableStateOf("一起到场，为球队加油") }
    var venue by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("欢迎队员、家长和朋友们到场观赛") }
    val preview = remember(game.id, headline, venue, note) { GamePoster.render(game, headline, venue, note) }
    FormPage("比赛宣传海报", false, true, "poster-close", "完成", dismiss, dismiss) {
        Image(preview.asImageBitmap(), contentDescription = "比赛海报，${game.away.name}对${game.home.name}", modifier = Modifier.fillMaxWidth().testTag("game-poster-preview"))
        Text("海报信息", fontWeight = FontWeight.Bold)
        OutlinedTextField(headline, { headline = it.take(40) }, Modifier.fillMaxWidth().testTag("poster-headline"), label = { Text("宣传标题") })
        OutlinedTextField(venue, { venue = it.take(80) }, Modifier.fillMaxWidth().testTag("poster-venue"), label = { Text("比赛场地（选填）") })
        OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth().testTag("poster-note"), label = { Text("通知备注") })
        Text("仅用于这张海报；比赛时间和双方取自已保存的比赛。")
        ReportShareButton("分享比赛宣传图片", "share-game-poster", mime = "image/png") { context -> GamePoster.write(context, game, headline, venue, note) }
    }
}
