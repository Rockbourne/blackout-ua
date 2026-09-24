package ua.blackout.app
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.time.ZonedDateTime

class ScheduleTimelineView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var slots:List<Slot> = emptyList(); private var showNow=false
 private var showSun=false; private var sunrise:Int?=null; private var sunset:Int?=null
 fun setSchedule(day:DaySchedule,isToday:Boolean){slots=day.slots;showNow=isToday;invalidate()}
 fun setSun(show:Boolean,rise:String?=null,set:String?=null){showSun=show;sunrise=rise?.let(::minute);sunset=set?.let(::minute);invalidate()}
 private fun minute(v:String):Int{val x=v.split(":");return (x.getOrNull(0)?.toIntOrNull()?:0)*60+(x.getOrNull(1)?.toIntOrNull()?:0)}
 private fun x(m:Int,w:Float)=w*m/1440f
 override fun onDraw(c:Canvas){
  super.onDraw(c);val w=width.toFloat();val top=50f;val bottom=64f;val radius=7f
  p.color=0xff8b8b8b.toInt();c.drawRoundRect(0f,top,w,bottom,radius,radius,p)
  c.save();val clip=Path().apply{addRoundRect(0f,top,w,bottom,radius,radius,Path.Direction.CW)};c.clipPath(clip)
  for(s in slots){p.color=when(s.status){"OFF"->0xffd94b45.toInt();"ON"->0xff3fae68.toInt();else->0xffd6a52d.toInt()};c.drawRect(x(minute(s.start),w),top,x(minute(s.end),w),bottom,p)}
  c.restore()
  p.textSize=30f;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
  val boundaries=(slots.flatMap{listOf(it.start,it.end)}).distinct().filter{it!="24:00"}
  for(t in boundaries){val m=minute(t);val xx=x(m,w);p.color=0xff666666.toInt();c.drawText(t,xx.coerceIn(32f,w-32f),42f,p);p.strokeWidth=2f;c.drawLine(xx,top-6,xx,top+6,p)}
  if(showSun){
   fun sun(m:Int?,icon:String){if(m==null)return;val xx=x(m,w);p.textSize=29f;p.color=0xffffa000.toInt();val label=icon+" "+("%02d:%02d".format(m/60,m%60));c.drawText(label,xx.coerceIn(45f,w-45f),91f,p)}
   sun(sunrise,"☀");sun(sunset,"☾")
  }
  if(showNow){val n=ZonedDateTime.now();val m=n.hour*60+n.minute;val xx=x(m,w);p.color=0xff202020.toInt();p.strokeWidth=4f;c.drawLine(xx,top-10,xx,bottom+5,p);p.textSize=32f;c.drawText("%02d:%02d".format(n.hour,n.minute),xx.coerceIn(44f,w-44f),24f,p)}
 }
}