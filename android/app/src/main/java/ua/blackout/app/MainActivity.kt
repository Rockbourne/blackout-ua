package ua.blackout.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.view.LayoutInflater
import android.view.View
import java.time.*
import java.time.format.DateTimeFormatter
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity:AppCompatActivity(){
 private val api by lazy { Retrofit.Builder().baseUrl("https://blackout-ua-api.onrender.com/").addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java) }
 private val cherkasyDirect by lazy { Retrofit.Builder().baseUrl("https://cabinet.cherkasyoblenergo.com/").addConverterFactory(GsonConverterFactory.create()).build().create(CherkasyDirectApi::class.java) }
 private val installId by lazy { Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID) }
 private val providers=listOf("yasno","cherkasyoblenergo")
 private val providerLabels=listOf("YASNO","Черкасиобленерго")
 private val yasnoRegions=listOf("kyiv","dnipro-dtek","dnipro-cek")
 private val yasnoRegionLabels=listOf("Київ","Дніпро · ДТЕК","Дніпро · ЦЕК")
 private val notifyValues=listOf(0,10,15,30,60)
 private val notifyLabels=listOf("Не попереджати","За 10 хв","За 15 хв","За 30 хв","За 60 хв")
 private var fcmToken:String?=null

 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
  val prefs=getSharedPreferences("blackout",MODE_PRIVATE)
  fun applySun(){
   val provider=providers[prefs.getInt("provider_index",0).coerceIn(0,providers.lastIndex)]
   val region=if(provider=="cherkasyoblenergo") "cherkasy" else yasnoRegions[prefs.getInt("region_index",0).coerceIn(0,yasnoRegions.lastIndex)]
   val on=prefs.getBoolean("show_sun",true);val zone=ZoneId.of("Europe/Kyiv");val date=LocalDate.now(zone)
   val today=SunTimes.forRegion(region,date);val tomorrow=SunTimes.forRegion(region,date.plusDays(1))
   findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSun(on,today?.first,today?.second)
   findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSun(on,tomorrow?.first,tomorrow?.second)
  }
  applySun()
  findViewById<Button>(R.id.settings).setOnClickListener{
   val v=LayoutInflater.from(this).inflate(R.layout.dialog_settings,null)
   val providerSpinner=v.findViewById<Spinner>(R.id.provider);val regionSpinner=v.findViewById<Spinner>(R.id.region);val citySpinner=v.findViewById<Spinner>(R.id.city)
   val streetView=v.findViewById<AutoCompleteTextView>(R.id.street);val houseView=v.findViewById<AutoCompleteTextView>(R.id.house);val sun=v.findViewById<Switch>(R.id.showSun);val notifySpinner=v.findViewById<Spinner>(R.id.notifyBefore)
   providerSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,providerLabels)
   providerSpinner.setSelection(prefs.getInt("provider_index",0).coerceIn(0,providers.lastIndex))
   notifySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,notifyLabels)
   notifySpinner.setSelection(notifyValues.indexOf(prefs.getInt("notify_before_minutes",30)).let{if(it>=0)it else 3})
   streetView.setText(prefs.getString("street",""),false);houseView.setText(prefs.getString("house",""),false);sun.isChecked=prefs.getBoolean("show_sun",true)

   var departments:List<CherkasyItem> = emptyList()
   var cities:List<CherkasyItem> = emptyList()
   var streets:List<CherkasyItem> = emptyList()
   var selectedStreetId:Int?=prefs.getInt("cherkasy_street_id",-1).takeIf{it>=0}

   fun setupYasno(){
    regionSpinner.onItemSelectedListener=null;citySpinner.onItemSelectedListener=null
    citySpinner.visibility=View.GONE;regionSpinner.visibility=View.VISIBLE
    regionSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,yasnoRegionLabels)
    regionSpinner.setSelection(prefs.getInt("region_index",0).coerceIn(0,yasnoRegions.lastIndex))
   }
   fun loadCherkasyCities(deptId:Int){
    cities=emptyList();streets=emptyList();selectedStreetId=null
    streetView.setText("",false);houseView.setText("",false)
    citySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Завантаження…"))
    cherkasyDirect.cities(deptId=deptId).enqueue(object:Callback<List<CherkasyItem>>{
     override fun onResponse(c:Call<List<CherkasyItem>>,r:Response<List<CherkasyItem>>){
      cities=r.body().orEmpty().filter{!it.ID.isNullOrBlank()&&!it.NAME.isNullOrBlank()}
      if(cities.isEmpty()){
       citySpinner.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Немає даних"))
       Toast.makeText(this@MainActivity,"Черкасиобленерго не повернуло населені пункти",Toast.LENGTH_LONG).show()
       return
      }
      citySpinner.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cities.map{it.NAME!!})
      val saved=prefs.getInt("cherkasy_city_index",0).coerceIn(0,cities.lastIndex)
      citySpinner.setSelection(saved,false)
     }
     override fun onFailure(c:Call<List<CherkasyItem>>,t:Throwable){
      citySpinner.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Помилка завантаження"))
      Toast.makeText(this@MainActivity,"Не вдалося завантажити населені пункти: ${t.javaClass.simpleName}",Toast.LENGTH_LONG).show()
     }
    })
   }
   fun setupCherkasy(){
    regionSpinner.visibility=View.VISIBLE;citySpinner.visibility=View.VISIBLE
    regionSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Завантаження філій…"))
    citySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,emptyList<String>())
    api.cherkasyDepartments().enqueue(object:Callback<CherkasyItems>{
     override fun onResponse(c:Call<CherkasyItems>,r:Response<CherkasyItems>){
      departments=r.body()?.items.orEmpty().filter{!it.ID.isNullOrBlank()&&!it.NAME.isNullOrBlank()}
      regionSpinner.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,departments.map{it.NAME!!})
      if(departments.isNotEmpty()){
       val saved=prefs.getInt("cherkasy_department_index",0).coerceIn(0,departments.lastIndex)
       regionSpinner.setSelection(saved, false)
       departments.getOrNull(saved)?.ID?.toIntOrNull()?.let{loadCherkasyCities(it)}
      }
     }
     override fun onFailure(c:Call<CherkasyItems>,t:Throwable){Toast.makeText(this@MainActivity,"Не вдалося завантажити список філій",Toast.LENGTH_LONG).show()}
    })
    regionSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
     override fun onNothingSelected(p:AdapterView<*>?){}
     override fun onItemSelected(p:AdapterView<*>?,view:View?,pos:Int,id:Long){
      val deptId=departments.getOrNull(pos)?.ID?.toIntOrNull()?:return
      streetView.setText("",false);houseView.setText("",false)
      loadCherkasyCities(deptId)
     }
    }
    citySpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
     override fun onNothingSelected(p:AdapterView<*>?){}
     override fun onItemSelected(p:AdapterView<*>?,view:View?,pos:Int,id:Long){
      streets=emptyList();selectedStreetId=null;streetView.setText("",false);houseView.setText("",false)
     }
    }
   }
   providerSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
    override fun onNothingSelected(p:AdapterView<*>?){}
    override fun onItemSelected(p:AdapterView<*>?,view:View?,pos:Int,id:Long){if(providers[pos]=="cherkasyoblenergo")setupCherkasy() else setupYasno()}
   }

   streetView.addTextChangedListener(object:TextWatcher{
    override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){}
    override fun afterTextChanged(s:Editable?){
     val q=s?.toString()?.trim().orEmpty();if(q.length<2)return
     if(providers[providerSpinner.selectedItemPosition]=="cherkasyoblenergo"){
      val cityId=cities.getOrNull(citySpinner.selectedItemPosition)?.ID?.toIntOrNull()?:return
      cherkasyDirect.streets(cityId=cityId,q=q).enqueue(object:Callback<List<CherkasyItem>>{
       override fun onResponse(c:Call<List<CherkasyItem>>,r:Response<List<CherkasyItem>>){
       streets=r.body().orEmpty()
       streetView.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,streets.map{it.NAME?:""}))
       streetView.setOnItemClickListener{_,_,pos,_->
        selectedStreetId=streets.getOrNull(pos)?.ID?.toIntOrNull()
        houseView.setText("Завантаження…",false);houseView.isEnabled=false
        selectedStreetId?.let{loadCherkasyHouses(it,houseView)}
       }
       if(streets.isNotEmpty())streetView.showDropDown()
      }
       override fun onFailure(c:Call<List<CherkasyItem>>,t:Throwable){}
      })
     }else{
      val region=yasnoRegions[regionSpinner.selectedItemPosition]
      api.streets(region,q).enqueue(object:Callback<AddressItems>{
       override fun onResponse(c:Call<AddressItems>,r:Response<AddressItems>){val items=r.body()?.items.orEmpty();streetView.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,items.mapNotNull{it.value}));streetView.setOnItemClickListener{_,_,pos,_->items.getOrNull(pos)?.id?.let{loadYasnoHouses(region,it,houseView)}};streetView.showDropDown()}
       override fun onFailure(c:Call<AddressItems>,t:Throwable){}
      })
     }
    }
   })

   AlertDialog.Builder(this).setTitle("Налаштування").setView(v).setNegativeButton("Скасувати",null).setPositiveButton("Зберегти"){_,_->
    val pi=providerSpinner.selectedItemPosition;val provider=providers[pi];val street=streetView.text.toString().trim();val house=houseView.text.toString().trim()
    val edit=prefs.edit().putInt("provider_index",pi).putString("street",street).putString("house",house).putBoolean("show_sun",sun.isChecked).putInt("notify_before_minutes",notifyValues[notifySpinner.selectedItemPosition])
    if(provider=="cherkasyoblenergo"){edit.putInt("cherkasy_department_index",regionSpinner.selectedItemPosition).putInt("cherkasy_city_index",citySpinner.selectedItemPosition);selectedStreetId?.let{edit.putInt("cherkasy_street_id",it)}} else edit.putInt("region_index",regionSpinner.selectedItemPosition)
    edit.apply();applySun()
    if(street.length>=2&&house.isNotEmpty()){if(provider=="cherkasyoblenergo"){selectedStreetId?.let{loadCherkasyAddress(it,street,house)}}else loadYasnoAddress(yasnoRegions[regionSpinner.selectedItemPosition],street,house)}
   }.show()
  }

  findViewById<Button>(R.id.testSchedule).setOnClickListener{
   val now=ZonedDateTime.now(ZoneId.of("Europe/Kyiv"));fun hm(minutes:Int):String{val m=(now.hour*60+now.minute+minutes).coerceIn(0,1439);return "%02d:%02d".format(m/60,m%60)}
   val date=now.toLocalDate().toString();val firstStart=hm(31);val firstEnd=hm(151);val secondStart=hm(300);val secondEnd=hm(420)
   val todayOutages=mutableListOf(Outage(date,firstStart,firstEnd));val todaySlots=mutableListOf(Slot("00:00",firstStart,"ON","NotPlanned"),Slot(firstStart,firstEnd,"OFF","Definite"))
   if(secondStart>firstEnd&&secondEnd>secondStart){todayOutages.add(Outage(date,secondStart,secondEnd));todaySlots.add(Slot(firstEnd,secondStart,"ON","NotPlanned"));todaySlots.add(Slot(secondStart,secondEnd,"OFF","Definite"));todaySlots.add(Slot(secondEnd,"24:00","ON","NotPlanned"))}else todaySlots.add(Slot(firstEnd,"24:00","ON","NotPlanned"))
   val today=DaySchedule(date,"SCHEDULED",todayOutages,todaySlots);val td=now.plusDays(1).toLocalDate().toString();val tomorrow=DaySchedule(td,"SCHEDULED",listOf(Outage(td,"08:00","11:00"),Outage(td,"18:00","21:00")),listOf(Slot("00:00","08:00","ON","NotPlanned"),Slot("08:00","11:00","OFF","Definite"),Slot("11:00","18:00","ON","NotPlanned"),Slot("18:00","21:00","OFF","Definite"),Slot("21:00","24:00","ON","NotPlanned")))
   findViewById<TextView>(R.id.status).text="ТЕСТ · Світло має бути";findViewById<TextView>(R.id.details).text="Тестові дані · група TEST";renderNext(CurrentState("ON",null,now.toString(),Outage(date,firstStart,firstEnd)));renderDays(today,tomorrow)
  }
  findViewById<Button>(R.id.testNotification).setOnClickListener{
   val manager=getSystemService(NotificationManager::class.java)
   val channel="outage_changes"
   manager.createNotificationChannel(NotificationChannel(channel,"Зміни графіка",NotificationManager.IMPORTANCE_HIGH))
   val notification=NotificationCompat.Builder(this,channel)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle("Blackout UA · тест")
    .setContentText("Тестове сповіщення: до відключення 30 хв")
    .setPriority(NotificationCompat.PRIORITY_HIGH)
    .setAutoCancel(true).build()
   manager.notify(4242,notification)
  }
  FirebaseMessaging.getInstance().token.addOnSuccessListener{token->fcmToken=token;api.register(DeviceRegister(installId,token)).enqueue(simpleCallback())}
 }

 private fun loadYasnoHouses(region:String,streetId:Int,view:AutoCompleteTextView){api.houses(region,streetId,view.text.toString().trim()).enqueue(object:Callback<AddressItems>{override fun onResponse(c:Call<AddressItems>,r:Response<AddressItems>){val values=r.body()?.items?.mapNotNull{it.value}.orEmpty();view.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,values));if(values.isNotEmpty())view.showDropDown()};override fun onFailure(c:Call<AddressItems>,t:Throwable){}})}
 private fun loadCherkasyHouses(streetId:Int,view:AutoCompleteTextView){
  cherkasyDirect.houses(streetId=streetId).enqueue(object:Callback<List<CherkasyItem>>{
   override fun onResponse(c:Call<List<CherkasyItem>>,r:Response<List<CherkasyItem>>){
    val values=r.body().orEmpty().mapNotNull{it.HOUSE}
    view.isEnabled=true;view.setText("",false)
    view.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,values))
    if(values.isNotEmpty())view.showDropDown()
   }
   override fun onFailure(c:Call<List<CherkasyItem>>,t:Throwable){view.isEnabled=true;view.setText("",false);Toast.makeText(this@MainActivity,"Не вдалося завантажити будинки",Toast.LENGTH_LONG).show()}
  })
 }

 private fun loadYasnoAddress(region:String,street:String,house:String){
  findViewById<TextView>(R.id.status).text="Шукаю…";api.addressOutages(region,street,house).enqueue(object:Callback<AddressOutages>{
   override fun onResponse(c:Call<AddressOutages>,r:Response<AddressOutages>){val body=r.body();if(!r.isSuccessful||body==null){findViewById<TextView>(R.id.status).text="Адресу не знайдено";return};val s=body.schedules.firstOrNull()?:return;findViewById<TextView>(R.id.status).text=when(s.current.status){"ON"->"Світло має бути";"OFF"->"Планове відключення";else->"Статус невідомий"};findViewById<TextView>(R.id.details).text="${body.address.street.value}, ${body.address.house.value} · група ${s.group}";renderNext(s.current);renderDays(s.today,s.tomorrow);if(fcmToken!=null)api.subscribe(Subscription(installId,provider="yasno",region=region,group=s.group,notify_before_minutes=getSharedPreferences("blackout",MODE_PRIVATE).getInt("notify_before_minutes",30))).enqueue(simpleCallback())}
   override fun onFailure(c:Call<AddressOutages>,t:Throwable){findViewById<TextView>(R.id.status).text="Немає зв’язку"}
  })
 }

 private fun loadCherkasyAddress(streetId:Int,street:String,house:String){
  findViewById<TextView>(R.id.status).text="Шукаю…"
  cherkasyDirect.accounts(streetId=streetId,house=house).enqueue(object:Callback<List<CherkasyAccount>>{
   override fun onResponse(c:Call<List<CherkasyAccount>>,r:Response<List<CherkasyAccount>>){
    val accounts=r.body().orEmpty();val ls=accounts.firstOrNull()?.LS
    if(ls.isNullOrBlank()){findViewById<TextView>(R.id.status).text="Адресу не знайдено";return}
    val zone=ZoneId.of("Europe/Kyiv");val date=LocalDate.now(zone);val fmt=DateTimeFormatter.ofPattern("dd.MM.yyyy")
    api.cherkasyDisconnections(ls,date.format(fmt),date.plusDays(1).format(fmt)).enqueue(object:Callback<CherkasyDisconnections>{
     override fun onResponse(c2:Call<CherkasyDisconnections>,r2:Response<CherkasyDisconnections>){
      val body=r2.body();if(!r2.isSuccessful||body==null){findViewById<TextView>(R.id.status).text="Не вдалося отримати дані";return}
      findViewById<TextView>(R.id.status).text=if(body.disconnections.isEmpty())"Немає зареєстрованих відключень" else "Є дані про відключення"
      findViewById<TextView>(R.id.countdown).text="";findViewById<TextView>(R.id.nextOutage).text=if(body.disconnections.isEmpty())"Черкасиобленерго не повертає відключень на сьогодні/завтра" else "Формат активних відключень ще не нормалізовано"
      findViewById<TextView>(R.id.details).text="$street, $house · Черкасиобленерго · оновлено ${body.provider_updated_at?:"—"}"
      val emptyToday=DaySchedule(date.toString(),"UNKNOWN",emptyList(),emptyList());val emptyTomorrow=DaySchedule(date.plusDays(1).toString(),"UNKNOWN",emptyList(),emptyList());renderDays(emptyToday,emptyTomorrow)
     }
     override fun onFailure(c2:Call<CherkasyDisconnections>,t:Throwable){findViewById<TextView>(R.id.status).text="Немає зв’язку"}
    })
   }
   override fun onFailure(c:Call<List<CherkasyAccount>>,t:Throwable){findViewById<TextView>(R.id.status).text="Немає зв’язку"}
  })
 }

 private fun renderDays(today:DaySchedule,tomorrow:DaySchedule){findViewById<ScheduleTimelineView>(R.id.todayTimeline).setSchedule(today,true);findViewById<ScheduleTimelineView>(R.id.tomorrowTimeline).setSchedule(tomorrow,false);findViewById<TextView>(R.id.today).text=formatDay("Сьогодні",today);findViewById<TextView>(R.id.tomorrow).text=formatDay("Завтра",tomorrow)}
 private fun renderNext(current:CurrentState){val countdown=findViewById<TextView>(R.id.countdown);val next=findViewById<TextView>(R.id.nextOutage);val outage=current.next_outage;if(outage==null){countdown.text="";next.text="Наступних відключень у графіку немає";return};next.text="Наступне відключення: ${outage.date} · ${outage.start}–${outage.end}";try{val zone=ZoneId.of("Europe/Kyiv");val start=LocalDateTime.parse("${outage.date}T${outage.start}:00").atZone(zone);val mins=Duration.between(ZonedDateTime.now(zone),start).toMinutes();countdown.text=if(mins>0)"До відключення: ${mins/60} год ${mins%60} хв" else ""}catch(_:Exception){countdown.text=""}}
 private fun formatDay(title:String,d:DaySchedule):String{
  val times=if(d.outages.isEmpty()){
   if(d.status=="UNKNOWN") "немає підтверджених даних" else "відключень не заплановано"
  }else d.outages.joinToString("\n"){"${it.start}–${it.end}"}
  return "$title · ${d.date ?: ""}\n$times"
 }
 private fun simpleCallback()=object:Callback<Map<String,Any>>{override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){};override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){}}
}
