package ua.blackout.app
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
class MainActivity:AppCompatActivity(){
 private val api by lazy { Retrofit.Builder().baseUrl("https://blackout-ua-api.onrender.com/").addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java) }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  val status=findViewById<TextView>(R.id.status)
  FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
   val id=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)
   api.register(DeviceRegister(id,token)).enqueue(object:Callback<Map<String,Any>>{
    override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){
     if(r.isSuccessful) api.subscribe(Subscription(id,region="kyiv",group="32.1")).enqueue(object:Callback<Map<String,Any>>{
      override fun onResponse(c:Call<Map<String,Any>>,r:Response<Map<String,Any>>){status.text=if(r.isSuccessful)"Сповіщення активні" else "Помилка підписки"}
      override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){status.text="Немає зв’язку"}
     }) else status.text="Помилка реєстрації"
    }
    override fun onFailure(c:Call<Map<String,Any>>,t:Throwable){status.text="Немає зв’язку"}
   })
  }
 }
}
