package ua.blackout.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import android.app.AlertDialog
import android.view.LayoutInflater
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Duration
import java.time.ZonedDateTime
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity:AppCompatActivity(){
 private val api by lazy { Retrofit.Builder().baseUrl("https://blackout-ua-api.onrender.com/").addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java) }
 private val installId by lazy { Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID) }
 private val providers=listOf("yasno")
 private val notifyValues=listOf(0,10,15,30,60)
 private val notifyLabels=listOf("Не попереджати","За 10 хв","За 15 хв","За 30 хв","За 60 хв")
 private val regions=listOf("kyiv","dnipro-dtek","dnipro-cek")
 private var fcmToken:String?=null
 private var selectedStreetId:Int?=null
 private var streetRequest:Call<AddressItems>?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
  val prefs=getSharedPreferences("blackout",MODE_PRIVATE)
  fun applySun(){
   val region=regions[prefs.getInt("region_index",0).coerceIn(0,regions.lastIndex)]
   val on=prefs.getBoolean("show_sun",true)
   val today=SunTimes.forRegion(region,LocalDate.now());val tomorrow=SunTimes.forRegion(region,LocalDate.now().plusDays(1))
   findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSun(on,today?.first,today?.second)
   findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSun(on,tomorrow?.first,tomorrow?.second)
  }
  applySun()
  findViewById<Button>(R.id.settings).setOnClickListener{
   val v=LayoutInflater.from(this).inflate(R.layout.dialog_settings,null)
   val providerSpinner=v.findViewById<Spinner>(R.id.provider);val spinner=v.findViewById<Spinner>(R.id.region);val streetView=v.findViewById<AutoCompleteTextView>(R.id.street);val houseView=v.findViewById<AutoCompleteTextView>(R.id.house);val sun=v.findViewById<Switch>(R.id.showSun);val notifySpinner=v.findViewById<Spinner>(R.id.notifyBefore)
   providerSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("YASNO"));providerSpinner.setSelection(prefs.getInt("provider_index",0));notifySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,notifyLabels);notifySpinner.setSelection(notifyValues.indexOf(prefs.getInt("notify_before_minutes",30)).let{if(it>=0)it else 3})
   spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Київ","Дніпро · ДТЕК","Дніпро · ЦЕК"));spinner.setSelection(prefs.getInt("region_index",0))
   streetView.setText(prefs.getString("street",""),false);houseView.setText(prefs.getString("house",""),false);sun.isChecked=prefs.getBoolean("show_sun",true)
   streetView.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){};override fun afterTextChanged(s:Editable?){val q=s?.toString()?.trim().orEmpty();if(q.length<2)return;api.streets(regions[spinner.selectedItemPosition],q).enqueue(object:Callback<AddressItems>{override fun onResponse(call:Call<AddressItems>,r:Response<AddressItems>){val items=r.body()?.items.orEmpty();streetView.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,items.mapNotNull{it.value}));streetView.setOnItemClickListener{_,_,pos,_->items.getOrNull(pos)?.id?.let{loadHouses(regions[spinner.selectedItemPosition],it,houseView)}};streetView.showDropDown()};override fun onFailure(call:Call<AddressItems>,t:Throwable){}})}})
   AlertDialog.Builder(this).setTitle("Налаштування").setView(v).setNegativeButton("Скасувати",null).setPositiveButton("Зберегти"){_,_->val ri=spinner.selectedItemPosition;val street=streetView.text.toString().trim();val house=houseView.text.toString().trim();prefs.edit().putInt("provider_index",providerSpinner.selectedItemPosition).putInt("region_index",ri).putString("street",street).putString("house",house).putBoolean("show_sun",sun.isChecked).putInt("notify_before_minutes",notifyValues[notifySpinner.selectedItemPosition]).apply();applySun();if(street.length>=2&&house.isNotEmpty())loadAddress(regions[ri],street,house)}.show()
  }
  findViewById<Button>(R.id.testSchedule).setOnClickListener{
   val now=ZonedDateTime.now();fun hm(minutes:Int):String{val m=((now.hour*60+now.minute+minutes)%1440+1440)%1440;return "%02d:%02d".format(m/60,m%60)}
   val date=now.toLocalDate().toString();val today=DaySchedule(date,"SCHEDULED",listOf(Outage(date,hm(30),hm(150)),Outage(date,hm(300),hm(420))),listOf(Slot("00:00",hm(30),"ON","NotPlanned"),Slot(hm(30),hm(150),"OFF","Definite"),Slot(hm(150),hm(300),"ON","NotPlanned"),Slot(hm(300),hm(420),"OFF","Definite"),Slot(hm(420),"24:00","ON","NotPlanned")))
   val td=now.plusDays(1).toLocalDate().toString();val tomorrow=DaySchedule(td,"SCHEDULED",listOf(Outage(td,"08:00","11:00"),Outage(td,"18:00","21:00")),listOf(Slot("00:00","08:00","ON","NotPlanned"),Slot("08:00","11:00","OFF","Definite"),Slot("11:00","18:00","ON","NotPlanned"),Slot("18:00","21:00","OFF","Definite"),Slot("21:00","24:00","ON","NotPlanned")))
   findViewById<TextView>(R.id.status).text="ТЕСТ · Світло має бути";findViewById<TextView>(R.id.details).text="Тестові дані · група TEST";renderNext(CurrentState("ON",null,now.toString(),Outage(date,hm(30),hm(150))));findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSchedule(today,true);findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSchedule(tomorrow,false);findViewById<TextView>(R.id.today).text=formatDay("Сьогодні",today);findViewById<TextView>(R.id.tomorrow).text=formatDay("Завтра",tomorrow)
  }
  FirebaseMessaging.getInstance().token.addOnSuccessListener{token->fcmToken=token;api.register(DeviceRegister(installId,token)).enqueue(simpleCallback())}

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
    if(fcmToken!=null)api.subscribe(Subscription(installId,region=region,group=s.group,notify_before_minutes=getSharedPreferences("blackout",MODE_PRIVATE).getInt("notify_before_minutes",30))).enqueue(simpleCallback())
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
   val zone=ZoneId.of("Europe/Kyiv")
   val start=LocalDateTime.parse("${outage.date}T${outage.start}:00").atZone(zone)
   val mins=Duration.between(ZonedDateTime.now(zone),start).toMinutes()
   countdown.text=if(mins>0)"До відключення: ${mins/60} год ${mins%60} хв" else ""
  }catch(_:Exception){countdown.text=""}
 }
 private fun formatDay(title:String,d:DaySchedule):String{
  val times=if(d.outages.isEmpty())"відключень не заплановано" else d.outages.joinToString("\n"){"${it.start}–${it.end}"}
  return "$title · ${d.date ?: ""}\n$times"
 }
 private fun simpleCallback()=object:Callback<Map<String,Any>>{override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){};override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){}}
}