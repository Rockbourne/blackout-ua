package ua.blackout.app
import retrofit2.Call
import retrofit2.http.*

data class DeviceRegister(val installation_id:String,val fcm_token:String?,val platform:String="android")
data class Subscription(val installation_id:String,val provider:String="yasno",val region:String,val group:String,val notify_changes:Boolean=true,val notify_before_minutes:Int=30)
data class AddressItem(val id:Int?,val value:String?)
data class AddressItems(val items:List<AddressItem>)
data class CherkasyItem(val ID:String?,val NAME:String?,val HOUSE:String?)
data class CherkasyItems(val items:List<CherkasyItem>)
data class CherkasyAccount(val LS:String?,val NAME:String?,val ADDRESS:String?)
data class CherkasyAccounts(val items:List<CherkasyAccount>)
data class CherkasyDisconnections(val provider:String,val region:String,val provider_updated_at:String?,val disconnections:List<Map<String,Any>>,val normalized:Boolean)
data class Outage(val date:String?,val start:String,val end:String)
data class CurrentState(val status:String,val until:String?,val checked_at:String?,val next_outage:Outage?)
data class Slot(val start:String,val end:String,val status:String,val type:String?)
data class DaySchedule(val date:String?,val status:String,val outages:List<Outage>,val slots:List<Slot> = emptyList())
data class Schedule(val group:String,val current:CurrentState,val today:DaySchedule,val tomorrow:DaySchedule)
data class AddressResult(val street:AddressItem,val house:AddressItem)
data class AddressOutages(val region:String,val address:AddressResult,val groups:List<String>,val schedules:List<Schedule>)

interface Api {
 @POST("api/v1/devices/register") fun register(@Body body:DeviceRegister):Call<Map<String,Any>>
 @POST("api/v1/subscriptions") fun subscribe(@Body body:Subscription):Call<Map<String,Any>>
 @GET("api/v1/address/streets") fun streets(@Query("region") region:String,@Query("q") q:String):Call<AddressItems>
 @GET("api/v1/address/houses") fun houses(@Query("region") region:String,@Query("street_id") streetId:Int,@Query("q") q:String=""):Call<AddressItems>
 @GET("api/v1/address-outages") fun addressOutages(@Query("region") region:String,@Query("street") street:String,@Query("house") house:String):Call<AddressOutages>

 @GET("api/v1/cherkasy/departments") fun cherkasyDepartments():Call<CherkasyItems>\n @GET("api/v1/cherkasy/departments/{deptId}/cities") fun cherkasyCities(@Path("deptId") deptId:Int=1):Call<CherkasyItems>
 @GET("api/v1/cherkasy/cities/{cityId}/streets") fun cherkasyStreets(@Path("cityId") cityId:Int,@Query("q") q:String=""):Call<CherkasyItems>
 @GET("api/v1/cherkasy/streets/{streetId}/houses") fun cherkasyHouses(@Path("streetId") streetId:Int,@Query("q") q:String=""):Call<CherkasyItems>
 @GET("api/v1/cherkasy/address/accounts") fun cherkasyAccounts(@Query("street_id") streetId:Int,@Query("house") house:String):Call<CherkasyAccounts>
 @GET("api/v1/cherkasy/disconnections") fun cherkasyDisconnections(@Query("abon_ls") ls:String,@Query("start_date") start:String,@Query("end_date") end:String):Call<CherkasyDisconnections>
}
