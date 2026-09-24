package ua.blackout.app
import java.time.*

object SunTimes{
 private val coords=mapOf("kyiv" to Pair(50.4501,30.5234),"dnipro-dtek" to Pair(48.4647,35.0462),"dnipro-cek" to Pair(48.4647,35.0462))
 fun forRegion(region:String,date:LocalDate):Pair<String,String>?{
  val c=coords[region]?:return null
  return Pair(calc(date,c.first,c.second,true),calc(date,c.first,c.second,false))
 }
 private fun calc(date:LocalDate,lat:Double,lon:Double,rise:Boolean):String{
  val n=date.dayOfYear.toDouble();val lngHour=lon/15.0;val t=n+((if(rise)6.0 else 18.0)-lngHour)/24.0
  val m=0.9856*t-3.289
  var l=m+1.916*kotlin.math.sin(Math.toRadians(m))+0.020*kotlin.math.sin(Math.toRadians(2*m))+282.634;l=(l+360)%360
  var ra=Math.toDegrees(kotlin.math.atan(0.91764*kotlin.math.tan(Math.toRadians(l))));ra=(ra+360)%360
  ra+=kotlin.math.floor(l/90)*90-kotlin.math.floor(ra/90)*90;ra/=15
  val sinDec=0.39782*kotlin.math.sin(Math.toRadians(l));val cosDec=kotlin.math.cos(kotlin.math.asin(sinDec))
  val cosH=(kotlin.math.cos(Math.toRadians(90.833))-sinDec*kotlin.math.sin(Math.toRadians(lat)))/(cosDec*kotlin.math.cos(Math.toRadians(lat)))
  val h=(if(rise)360-Math.toDegrees(kotlin.math.acos(cosH)) else Math.toDegrees(kotlin.math.acos(cosH)))/15
  var utc=h+ra-0.06571*t-6.622-lngHour;utc=(utc+24)%24
  val instant=date.atStartOfDay(ZoneOffset.UTC).plusMinutes((utc*60).toLong())
  val local=instant.atZone(ZoneId.of("Europe/Kyiv"));return "%02d:%02d".format(local.hour,local.minute)
 }
}