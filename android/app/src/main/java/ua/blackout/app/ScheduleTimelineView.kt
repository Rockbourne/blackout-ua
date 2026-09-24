package ua.blackout.app
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.ZonedDateTime

class ScheduleTimelineView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs){
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private var slots:List<Slot> = emptyList()
 private var showNow=false
 fun setSchedule(day:DaySchedule,isToday:Boolean){slots=day.slots;showNow=isToday;invalidate()}
 private fun minute(v:String):Int{val p=v.split(":");return (p.getOrNull(0)?.toIntOrNull()?:0)*60+(p.getOrNull(1)?.toIntOrNull()?:0)}
 override fun onDraw(c:Canvas){
  super.onDraw(c)
  val h=height.toFloat(); val w=width.toFloat()
  paint.color=0xff9e9e9e.toInt();c.drawRect(0f,0f,w,h,paint)
  for(s in slots){
   paint.color=when(s.status){"OFF"->0xffd32f2f.toInt();"ON"->0xff43a047.toInt();else->0xfff9a825.toInt()}
   val l=w*minute(s.start)/1440f;val r=w*minute(s.end)/1440f;c.drawRect(l,0f,r,h,paint)
  }
  if(showNow){val n=ZonedDateTime.now();val x=w*(n.hour*60+n.minute)/1440f;paint.color=0xffffffff.toInt();paint.strokeWidth=4f;c.drawLine(x,0f,x,h,paint)}
 }
}