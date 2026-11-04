package cc.baseballmaster.scorekeeper

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import cc.baseballmaster.core.BaseballFieldGeometry
import cc.baseballmaster.core.FieldPoint

internal val FieldGreen=Color(0xFF2E765A)
internal val FieldDirt=Color(0xFFD7B785)

/** Shared iOS 3.1.1 field for scoring, contact recording and performance review. */
internal fun DrawScope.drawBaseballField(
    geometry:BaseballFieldGeometry, field:Color=FieldGreen, dirt:Color=FieldDirt,
    occupied:List<Boolean> = listOf(false,false,false),
) {
    val scale=(size.width/geometry.width).toFloat()
    fun point(value:FieldPoint)=Offset(value.x.toFloat()*scale,value.y.toFloat()*scale)
    val home=point(geometry.home);val center=point(geometry.center)
    val radius=geometry.outfieldRadius.toFloat()*scale
    fun diamond(at:Offset,r:Float)=Path().apply {
        moveTo(at.x,at.y+r);lineTo(at.x+r,at.y);lineTo(at.x,at.y-r);lineTo(at.x-r,at.y);close()
    }
    drawRect(field.copy(alpha=.86f))
    val sector=Path().apply {
        moveTo(home.x,home.y)
        arcTo(Rect(home.x-radius,home.y-radius,home.x+radius,home.y+radius),225f,90f,false)
        close()
    }
    drawPath(sector,field)
    drawPath(sector,dirt.copy(alpha=.9f),style=Stroke(8*scale))
    for(fence in listOf(geometry.leftFence,geometry.rightFence))
        drawLine(Color.White.copy(alpha=.82f),home,point(fence),1.5f*scale,
            pathEffect=PathEffect.dashPathEffect(floatArrayOf(4*scale,3*scale)))
    val d=geometry.baseHalfDiagonal.toFloat()*scale
    drawPath(diamond(center,d*1.25f),dirt)
    drawPath(diamond(center,d*.58f),field.copy(alpha=.92f))
    drawPath(diamond(center,d),Color.White.copy(alpha=.72f),style=Stroke(2*scale))
    drawOval(dirt,center-Offset(17*scale,10*scale),androidx.compose.ui.geometry.Size(34*scale,20*scale))
    drawRoundRect(Color.White.copy(alpha=.9f),center-Offset(7*scale,1.5f*scale),androidx.compose.ui.geometry.Size(14*scale,3*scale),CornerRadius(1.5f*scale))
    listOf(geometry.first,geometry.second,geometry.third).forEachIndexed {index,base->
        val active=occupied.getOrElse(index) {false}
        drawPath(diamond(point(base),(if(active)13f else 8f)*scale),if(active) Color(0xFFFABD29) else Color.White)
    }
    val plate=Path().apply {
        moveTo(home.x-9*scale,home.y-9*scale);lineTo(home.x+9*scale,home.y-9*scale)
        lineTo(home.x+9*scale,home.y);lineTo(home.x,home.y+9*scale);lineTo(home.x-9*scale,home.y);close()
    }
    drawPath(plate,Color.White)
}
