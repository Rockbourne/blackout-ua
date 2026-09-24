package ua.blackout.app
import retrofit2.Call
import retrofit2.http.*
data class DeviceRegister(val installation_id:String,val fcm_token:String?,val platform:String="android")
data class Subscription(val installation_id:String,val provider:String="yasno",val region:String,val group:String,val notify_changes:Boolean=true,val notify_before_minutes:Int=30)
interface Api {
 @POST("api/v1/devices/register") fun register(@Body body:DeviceRegister):Call<Map<String,Any>>
 @POST("api/v1/subscriptions") fun subscribe(@Body body:Subscription):Call<Map<String,Any>>
}
