package cc.baseballmaster.scorekeeper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
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
        path(fill = SolidColor(Color.Black)) {
            // Three solid heads, with the central recorder slightly in front.
            moveTo(15f, 6f); curveTo(15f, 7.66f, 13.66f, 9f, 12f, 9f)
            curveTo(10.34f, 9f, 9f, 7.66f, 9f, 6f)
            curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f)
            curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f); close()
            moveTo(7f, 8f); curveTo(7f, 9.38f, 5.88f, 10.5f, 4.5f, 10.5f)
            curveTo(3.12f, 10.5f, 2f, 9.38f, 2f, 8f)
            curveTo(2f, 6.62f, 3.12f, 5.5f, 4.5f, 5.5f)
            curveTo(5.88f, 5.5f, 7f, 6.62f, 7f, 8f); close()
            moveTo(22f, 8f); curveTo(22f, 9.38f, 20.88f, 10.5f, 19.5f, 10.5f)
            curveTo(18.12f, 10.5f, 17f, 9.38f, 17f, 8f)
            curveTo(17f, 6.62f, 18.12f, 5.5f, 19.5f, 5.5f)
            curveTo(20.88f, 5.5f, 22f, 6.62f, 22f, 8f); close()
            moveTo(6.5f, 20f); lineTo(6.5f, 17f)
            curveTo(6.5f, 13.67f, 8.63f, 11f, 12f, 11f)
            curveTo(15.37f, 11f, 17.5f, 13.67f, 17.5f, 17f)
            lineTo(17.5f, 20f); close()
            moveTo(0.5f, 19f); lineTo(0.5f, 16.5f)
            curveTo(0.5f, 13.85f, 2f, 12f, 4.5f, 12f)
            curveTo(5.4f, 12f, 6.1f, 12.2f, 6.7f, 12.55f)
            curveTo(5.5f, 13.85f, 5f, 15.35f, 5f, 17f)
            lineTo(5f, 19f); close()
            moveTo(19f, 19f); lineTo(19f, 17f)
            curveTo(19f, 15.35f, 18.5f, 13.85f, 17.3f, 12.55f)
            curveTo(17.9f, 12.2f, 18.6f, 12f, 19.5f, 12f)
            curveTo(22f, 12f, 23.5f, 13.85f, 23.5f, 16.5f)
            lineTo(23.5f, 19f); close()
        }
    }.build()
    val lineupManage = ImageVector.Builder("管理阵容", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 6f); curveTo(11f, 7.66f, 9.66f, 9f, 8f, 9f)
            curveTo(6.34f, 9f, 5f, 7.66f, 5f, 6f)
            curveTo(5f, 4.34f, 6.34f, 3f, 8f, 3f)
            curveTo(9.66f, 3f, 11f, 4.34f, 11f, 6f); close()
            moveTo(19f, 7f); curveTo(19f, 8.38f, 17.88f, 9.5f, 16.5f, 9.5f)
            curveTo(15.12f, 9.5f, 14f, 8.38f, 14f, 7f)
            curveTo(14f, 5.62f, 15.12f, 4.5f, 16.5f, 4.5f)
            curveTo(17.88f, 4.5f, 19f, 5.62f, 19f, 7f); close()
            moveTo(2f, 20f); lineTo(2f, 17f)
            curveTo(2f, 13.5f, 4.3f, 11f, 8f, 11f)
            curveTo(10.1f, 11f, 11.8f, 11.8f, 13f, 13.3f)
            lineTo(13f, 20f); close()
            moveTo(13f, 11.7f); curveTo(16f, 10.3f, 20.3f, 11f, 21.7f, 13.2f)
            lineTo(14.5f, 13.2f); close()
        }
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Eight visible gear teeth and a clear axle, independent of background color.
            for (point in 0 until 32) {
                val angle = point * kotlin.math.PI / 16.0
                val radius = if (point % 4 == 1 || point % 4 == 2) 4.5 else 3.7
                val x = (18.5 + kotlin.math.cos(angle) * radius).toFloat()
                val y = (18.5 + kotlin.math.sin(angle) * radius).toFloat()
                if (point == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            moveTo(20.1f, 18.5f); curveTo(20.1f, 19.38f, 19.38f, 20.1f, 18.5f, 20.1f)
            curveTo(17.62f, 20.1f, 16.9f, 19.38f, 16.9f, 18.5f)
            curveTo(16.9f, 17.62f, 17.62f, 16.9f, 18.5f, 16.9f)
            curveTo(19.38f, 16.9f, 20.1f, 17.62f, 20.1f, 18.5f); close()
        }
    }.build()
    val profile = ImageVector.Builder("我的", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(22f, 12f); curveTo(22f, 17.52f, 17.52f, 22f, 12f, 22f)
            curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
            curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f)
            curveTo(17.52f, 2f, 22f, 6.48f, 22f, 12f); close()
            moveTo(15.2f, 8.6f); curveTo(15.2f, 10.37f, 13.77f, 11.8f, 12f, 11.8f)
            curveTo(10.23f, 11.8f, 8.8f, 10.37f, 8.8f, 8.6f)
            curveTo(8.8f, 6.83f, 10.23f, 5.4f, 12f, 5.4f)
            curveTo(13.77f, 5.4f, 15.2f, 6.83f, 15.2f, 8.6f); close()
            moveTo(6.3f, 18.1f); curveTo(6.7f, 15.15f, 9f, 13.5f, 12f, 13.5f)
            curveTo(15f, 13.5f, 17.3f, 15.15f, 17.7f, 18.1f)
            curveTo(14.3f, 20.5f, 9.7f, 20.5f, 6.3f, 18.1f); close()
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
