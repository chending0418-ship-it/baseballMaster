package cc.baseballmaster.scorekeeper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Product navigation symbols: diamond, roster and statistics, shared at all sizes. */
internal object NavigationSymbols {
    val diamond = ImageVector.Builder("比赛", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2f); lineTo(22f, 12f); lineTo(12f, 22f); lineTo(2f, 12f); close()
            moveTo(12f, 7f); lineTo(17f, 12f); lineTo(12f, 17f); lineTo(7f, 12f); close()
        }
    }.build()
    val roster = ImageVector.Builder("球队", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(15f, 7f); curveTo(15f, 11f, 9f, 11f, 9f, 7f); curveTo(9f, 3f, 15f, 3f, 15f, 7f)
            moveTo(6f, 21f); lineTo(6f, 18f); curveTo(6f, 12f, 18f, 12f, 18f, 18f); lineTo(18f, 21f); close()
            moveTo(5f, 5f); curveTo(1f, 5f, 1f, 11f, 5f, 11f)
            moveTo(19f, 5f); curveTo(23f, 5f, 23f, 11f, 19f, 11f)
            moveTo(3f, 15f); lineTo(1f, 17f); lineTo(1f, 21f)
            moveTo(21f, 15f); lineTo(23f, 17f); lineTo(23f, 21f)
        }
    }.build()
    val statistics = ImageVector.Builder("统计", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 14f); lineTo(7f, 14f); lineTo(7f, 22f); lineTo(3f, 22f); close()
            moveTo(10f, 8f); lineTo(14f, 8f); lineTo(14f, 22f); lineTo(10f, 22f); close()
            moveTo(17f, 2f); lineTo(21f, 2f); lineTo(21f, 22f); lineTo(17f, 22f); close()
        }
    }.build()
}
