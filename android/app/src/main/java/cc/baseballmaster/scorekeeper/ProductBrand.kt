package cc.baseballmaster.scorekeeper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ProductBrand(reverse: Boolean, modifier: Modifier = Modifier, tile: Dp = 36.dp) {
    // Brand 1.3 defines clear space as one quarter of the icon width.
    BoxWithConstraints(modifier.padding(tile / 4)) {
        val wide = maxWidth >= tile * (297.8f / 48f)
        val asset = when {
            wide && reverse -> R.drawable.product_brand_horizontal_reverse
            wide -> R.drawable.product_brand_horizontal
            reverse -> R.drawable.product_brand_compact_reverse
            else -> R.drawable.product_brand_compact
        }
        val painter = painterResource(asset)
        val ratio = painter.intrinsicSize.width / painter.intrinsicSize.height
        val width = minOf(maxWidth, tile * ratio)
        Image(
            painter, stringResource(R.string.app_name),
            Modifier.width(width).height(width / ratio).testTag("product-brand"),
        )
    }
}

internal fun Canvas.drawProductBrand(bitmap: Bitmap, left: Float, top: Float, tile: Float, paint: Paint) {
    val right = left + tile * bitmap.width / bitmap.height
    drawBitmap(bitmap, null, RectF(left, top, right, top + tile), paint)
}
