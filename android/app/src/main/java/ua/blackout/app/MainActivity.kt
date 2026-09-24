package ua.blackout.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import java.time.Duration
import java.time.ZonedDateTime
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity:AppCompatActivity(){
 private val api by lazy { Retrofit.Builder().baseUrl("https://blackout-ua-api.onrender.com/").addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java) }
 private val installId by lazy { Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID) }
 private val regions=listOf("kyiv","dnipro-dtek","dnipro-cek")
 private var fcmToken:String?=null
 private var selectedStreetId:Int?=null
 private var streetRequest:Call<AddressItems>?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
  val spinner=findViewById<Spinner>(R.id.region)
  val streetView=findViewById<AutoCompleteTextView>(R.id.street)
  val houseView=findViewById<AutoCompleteTextView>(R.id.house)
  spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Київ","Дніпро · ДТЕК","Дніпро · ЦЕК"))
  val prefs=getSharedPreferences("blackout",MODE_PRIVATE)
  val sunSwitch=findViewById<Switch>(R.id.showSun)

  spinner.setSelection(prefs.getInt("region_index",0))
  sunSwitch.isChecked=prefs.getBoolean("show_sun",true)
  fun applySun(){val on=sunSwitch.isChecked;findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSun(on,"06:45","18:52");findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSun(on,"06:47","18:50")}
  applySun()
  sunSwitch.setOnCheckedChangeListener{_,on->prefs.edit().putBoolean("show_sun",on).apply();applySun()}
  streetView.setText(prefs.getString("street",""),false)
  houseView.setText(prefs.getString("house",""),false)
  streetView.addTextChangedListener(object:TextWatcher{
   override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
   override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
   override fun afterTextChanged(s:Editable?){
    selectedStreetId=null
    val q=s?.toString()?.trim().orEmpty()
    if(q.length<2)return
    streetRequest?.cancel()
    streetRequest=api.streets(regions[spinner.selectedItemPosition],q)
    streetRequest?.enqueue(object:Callback<AddressItems>{
     override fun onResponse(call:Call<AddressItems>,response:Response<AddressItems>){
      if(call.isCanceled)return
      val items=response.body()?.items.orEmpty()
      val labels=items.mapNotNull{it.value}
      streetView.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,labels))
      streetView.setOnItemClickListener{_,_,position,_-> 
       val chosen=items.getOrNull(position)?:return@setOnItemClickListener
       selectedStreetId=chosen.id
       streetView.setText(chosen.value.orEmpty(),false)
       houseView.setText("",false)
       chosen.id?.let{loadHouses(regions[spinner.selectedItemPosition],it,houseView)}
      }
      if(labels.isNotEmpty()&&streetView.hasFocus())streetView.showDropDown()
     }
     override fun onFailure(call:Call<AddressItems>,t:Throwable){}
    })
   }
  })
  houseView.setOnClickListener{selectedStreetId?.let{loadHouses(regions[spinner.selectedItemPosition],it,houseView)}}
  findViewById<Button>(R.id.testSchedule).setOnClickListener{
   val now=ZonedDateTime.now()
   fun hm(minutes:Int):String{val m=((now.hour*60+now.minute+minutes)%1440+1440)%1440;return "%02d:%02d".format(m/60,m%60)}
   val date=now.toLocalDate().toString()
   val today=DaySchedule(date,"SCHEDULED",listOf(Outage(date,hm(30),hm(150)),Outage(date,hm(300),hm(420))),listOf(
    Slot("00:00",hm(30),"ON","NotPlanned"),Slot(hm(30),hm(150),"OFF","Definite"),Slot(hm(150),hm(300),"ON","NotPlanned"),Slot(hm(300),hm(420),"OFF","Definite"),Slot(hm(420),"24:00","ON","NotPlanned")
   ))
   val tomorrowDate=now.plusDays(1).toLocalDate().toString()
   val tomorrow=DaySchedule(tomorrowDate,"SCHEDULED",listOf(Outage(tomorrowDate,"08:00","11:00"),Outage(tomorrowDate,"18:00","21:00")),listOf(
    Slot("00:00","08:00","ON","NotPlanned"),Slot("08:00","11:00","OFF","Definite"),Slot("11:00","18:00","ON","NotPlanned"),Slot("18:00","21:00","OFF","Definite"),Slot("21:00","24:00","ON","NotPlanned")
   ))
   val current=CurrentState("ON",null,now.toString(),Outage(date,hm(30),hm(150)))
   findViewById<TextView>(R.id.status).text="ТЕСТ · Світло має бути"
   findViewById<TextView>(R.id.details).text="Тестові дані · група TEST"
   renderNext(current)
   findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSchedule(today,true)
   findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSchedule(tomorrow,false)
   findViewById<TextView>(R.id.today).text=formatDay("Сьогодні",today)
   findViewById<TextView>(R.id.tomorrow).text=formatDay("Завтра",tomorrow)
  }
  FirebaseMessaging.getInstance().token.addOnSuccessListener{token->fcmToken=token;api.register(DeviceRegister(installId,token)).enqueue(simpleCallback())}
  findViewById<Button>(R.id.search).setOnClickListener{
   val street=streetView.text.toString().trim()
   val house=houseView.text.toString().trim()
   if(street.length<2||house.isEmpty()){Toast.makeText(this,"Вкажіть вулицю та будинок",Toast.LENGTH_SHORT).show();return@setOnClickListener}
   prefs.edit().putString("street",street).putString("house",house).putInt("region_index",spinner.selectedItemPosition).apply()
   loadAddress(regions[spinner.selectedItemPosition],street,house)
  }
 }
 private fun loadHouses(region:String,streetId:Int,view:AutoCompleteTextView){
  api.houses(region,streetId,view.text.toString().trim()).enqueue(object:Callback<AddressItems>{
   override fun onResponse(call:Call<AddressItems>,response:Response<AddressItems>){
    val values=response.body()?.items?.mapNotNull{it.value}.orEmpty()
    view.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,values))
    if(values.isNotEmpty())view.showDropDown()
   }
   override fun onFailure(call:Call<AddressItems>,t:Throwable){}
  })
 }
 private fun loadAddress(region:String,street:String,house:String){
  findViewById<TextView>(R.id.status).text="Шукаю…"
  api.addressOutages(region,street,house).enqueue(object:Callback<AddressOutages>{
   override fun onResponse(c:Call<AddressOutages>,r:Response<AddressOutages>){
    val body=r.body();if(!r.isSuccessful||body==null){findViewById<TextView>(R.id.status).text="Адресу не знайдено";return}
    val s=body.schedules.firstOrNull()?:return
    findViewById<TextView>(R.id.status).text=when(s.current.status){"ON"->"Світло має бути";"OFF"->"Планове відключення";else->"Статус невідомий"}
    findViewById<TextView>(R.id.details).text="${body.address.street.value}, ${body.address.house.value} · група ${s.group}"
    renderNext(s.current)
    findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSchedule(s.today,true)
    findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSchedule(s.tomorrow,false)
    findViewById<TextView>(R.id.today).text=formatDay("Сьогодні",s.today)
    findViewById<TextView>(R.id.tomorrow).text=formatDay("Завтра",s.tomorrow)
    if(fcmToken!=null)api.subscribe(Subscription(installId,region=region,group=s.group)).enqueue(simpleCallback())
   }
   override fun onFailure(c:Call<AddressOutages>,t:Throwable){findViewById<TextView>(R.id.status).text="Немає зв’язку"}
  })
 }
 private fun renderNext(current:CurrentState){
  val countdown=findViewById<TextView>(R.id.countdown)
  val next=findViewById<TextView>(R.id.nextOutage)
  val outage=current.next_outage
  if(outage==null){countdown.text="";next.text="Наступних відключень у графіку немає";return}
  next.text="Наступне відключення: ${outage.date} · ${outage.start}–${outage.end}"
  try{
   val start=ZonedDateTime.parse("${outage.date}T${outage.start}:00+03:00")
   val mins=Duration.between(ZonedDateTime.now(),start).toMinutes()
   countdown.text=if(mins>0)"До відключення: ${mins/60} год ${mins%60} хв" else ""
  }catch(_:Exception){countdown.text=""}
 }
 private fun formatDay(title:String,d:DaySchedule):String{
  val times=if(d.outages.isEmpty())"відключень не заплановано" else d.outages.joinToString("\n"){"${it.start}–${it.end}"}
  return "$title · ${d.date ?: ""}\n$times"
 }
 private fun simpleCallback()=object:Callback<Map<String,Any>>{override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){};override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){}}
}