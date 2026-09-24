package ua.blackout.app
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BlackoutMessagingService:FirebaseMessagingService(){
 override fun onNewToken(token:String){super.onNewToken(token)}
 override fun onMessageReceived(message:RemoteMessage){
  super.onMessageReceived(message)
  val manager=getSystemService(NotificationManager::class.java)
  val channel="outage_changes"
  manager.createNotificationChannel(NotificationChannel(channel,"Зміни графіка",NotificationManager.IMPORTANCE_HIGH))
  val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
  val pending=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val title=message.notification?.title ?: "Blackout UA"
  val body=message.notification?.body ?: "Графік відключень змінився"
  val notification=NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pending).build()
  manager.notify((System.currentTimeMillis()%100000).toInt(),notification)
 }
}