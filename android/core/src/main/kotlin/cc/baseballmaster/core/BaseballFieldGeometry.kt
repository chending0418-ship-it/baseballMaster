package cc.baseballmaster.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FieldPoint(val x:Double,val y:Double)

/** iOS 3.1.1 BaseballFieldLayout, in logical points/dp. Stored observations keep
 * their original normalized coordinates; only rendering and new taps are mapped.
 */
class BaseballFieldGeometry(val width:Double,val height:Double) {
    init {require(width>0 && height>74)}
    val home=FieldPoint(width/2,height-48)
    val outfieldRadius=max(1.0,min((width/2-min(24.0,max(14.0,width*.04)))*sqrt(2.0)*1.30,(home.y-40)/.62))
    val baseHalfDiagonal=min(width*.18,min(max(132.0,home.y-max(34.0,height*.40))/2,max(1.0,(home.y-74)/2)))
    val first=FieldPoint(home.x+baseHalfDiagonal,home.y-baseHalfDiagonal)
    val second=FieldPoint(home.x,home.y-baseHalfDiagonal*2)
    val third=FieldPoint(home.x-baseHalfDiagonal,home.y-baseHalfDiagonal)
    val center=FieldPoint(home.x,home.y-baseHalfDiagonal)
    val leftFence=FieldPoint(home.x-outfieldRadius/sqrt(2.0),home.y-outfieldRadius/sqrt(2.0))
    val rightFence=FieldPoint(home.x+outfieldRadius/sqrt(2.0),home.y-outfieldRadius/sqrt(2.0))
    private val legacyX=listOf(0.0,.25,.5,.75,1.0)
    private val legacyY=listOf(0.0,.36,.63,.90,1.0)
    private val displayX=listOf(0.0,third.x,home.x,first.x,width)
    private val displayY=listOf(0.0,second.y,center.y,home.y,height)
    fun contactPoint(legacy:FieldPoint)=FieldPoint(interpolate(legacy.x,legacyX,displayX),interpolate(legacy.y,legacyY,displayY))
    fun contactCoordinate(display:FieldPoint)=FieldPoint(interpolate(display.x,displayX,legacyX),interpolate(display.y,displayY,legacyY))
    fun fielder(position:Int):FieldPoint=when(position) {
        1->center
        2->FieldPoint(home.x,min(height-18,home.y+30))
        3->FieldPoint(min(width-38,first.x+66),first.y+35)
        4->FieldPoint(center.x+max(66.0,baseHalfDiagonal*1.1),center.y-baseHalfDiagonal*.72)
        5->FieldPoint(max(38.0,third.x-66),third.y+35)
        6->FieldPoint(center.x-max(66.0,baseHalfDiagonal*1.1),center.y-baseHalfDiagonal*.72)
        7->FieldPoint(width*.19,max(51.0,home.y-outfieldRadius*.58))
        8->FieldPoint(width*.5,max(29.0,home.y-outfieldRadius*.65))
        9->FieldPoint(width*.81,max(51.0,home.y-outfieldRadius*.58))
        10->FieldPoint(width*.5,(max(29.0,home.y-outfieldRadius*.65)+second.y)/2)
        else->throw IllegalArgumentException("守备位置无效")
    }
    private fun interpolate(value:Double,source:List<Double>,target:List<Double>):Double {
        val bounded=value.coerceIn(source.first(),source.last())
        val index=(0 until source.lastIndex).firstOrNull {bounded<=source[it+1]} ?: source.lastIndex-1
        val fraction=(bounded-source[index])/max(.000001,source[index+1]-source[index])
        return target[index]+fraction*(target[index+1]-target[index])
    }
}
