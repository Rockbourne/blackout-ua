package ua.blackout.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity:AppCompatActivity(){
 private val api by lazy { Retrofit.Builder().baseUrl("https://blackout-ua-api.onrender.com/").addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java) }
 private val installId by lazy { Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID) }
 private var fcmToken:String?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
  val spinner=findViewById<Spinner>(R.id.region)
  spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Київ","Дніпро · ДТЕК","Дніпро · ЦЕК"))
  val prefs=getSharedPreferences("blackout",MODE_PRIVATE)
  findViewById<EditText>(R.id.street).setText(prefs.getString("street",""))
  findViewById<EditText>(R.id.house).setText(prefs.getString("house",""))
  spinner.setSelection(prefs.getInt("region_index",0))
  FirebaseMessaging.getInstance().token.addOnSuccessListener{token->fcmToken=token;api.register(DeviceRegister(installId,token)).enqueue(simpleCallback())}
  findViewById<Button>(R.id.search).setOnClickListener{
   val regions=listOf("kyiv","dnipro-dtek","dnipro-cek")
   val street=findViewById<EditText>(R.id.street).text.toString().trim()
   val house=findViewById<EditText>(R.id.house).text.toString().trim()
   if(street.length<2||house.isEmpty()){Toast.makeText(this,"Вкажіть вулицю та будинок",Toast.LENGTH_SHORT).show();return@setOnClickListener}
   prefs.edit().putString("street",street).putString("house",house).putInt("region_index",spinner.selectedItemPosition).apply()
   loadAddress(regions[spinner.selectedItemPosition],street,house)
  }
 }
 private fun loadAddress(region:String,street:String,house:String){
  findViewById<TextView>(R.id.status).text="Шукаю…"
  api.addressOutages(region,street,house).enqueue(object:Callback<AddressOutages>{
   override fun onResponse(c:Call<AddressOutages>,r:Response<AddressOutages>){
    val body=r.body();if(!r.isSuccessful||body==null){findViewById<TextView>(R.id.status).text="Адресу не знайдено";return}
    val s=body.schedules.firstOrNull()?:return
    findViewById<TextView>(R.id.status).text=when(s.current.status){"ON"->"Світло має бути";"OFF"->"Планове відключення";else->"Статус невідомий"}
    findViewById<TextView>(R.id.details).text="${body.address.street.value}, ${body.address.house.value} · група ${s.group}"
    findViewById<TextView>(R.id.today).text=formatDay("Сьогодні",s.today)
    findViewById<TextView>(R.id.tomorrow).text=formatDay("Завтра",s.tomorrow)
    if(fcmToken!=null)api.subscribe(Subscription(installId,region=region,group=s.group)).enqueue(simpleCallback())
   }
   override fun onFailure(c:Call<AddressOutages>,t:Throwable){findViewById<TextView>(R.id.status).text="Немає зв’язку"}
  })
 }
 private fun formatDay(title:String,d:DaySchedule):String{
  val times=if(d.outages.isEmpty())"відключень не заплановано" else d.outages.joinToString("\n"){"${it.start}–${it.end}"}
  return "$title · ${d.date ?: ""}\n$times"
 }
 private fun simpleCallback()=object:Callback<Map<String,Any>>{override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){};override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){}}
}