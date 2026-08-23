package com.redsun352.gpsgrid

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

data class SurveyPoint(val id:Int,val latitude:Double,val longitude:Double,val altitude:Double,val accuracy:Float,val time:Long)
data class GnssSummary(val gps:Int=0,val galileo:Int=0,val glonass:Int=0,val beidou:Int=0,val qzss:Int=0,val other:Int=0,val used:Int=0,val dual:Boolean=false){val total:Int get()=gps+galileo+glonass+beidou+qzss+other}

class MainActivity:ComponentActivity(){
 private val fused by lazy{LocationServices.getFusedLocationProviderClient(this)}
 private val lm by lazy{getSystemService(LOCATION_SERVICE) as LocationManager}
 private val clientId by lazy{"android-${android.os.Build.MODEL}-${android.os.Build.ID}"}
 private val deviceName by lazy{"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"}
 private val gnss=mutableStateOf(GnssSummary())
 private var sampleCallback:((Location)->Unit)?=null
 private val cb=object: GnssStatus.Callback(){override fun onSatelliteStatusChanged(s:GnssStatus){
  var gps=0;var gal=0;var glo=0;var bds=0;var qz=0;var other=0;var used=0;val freqs=mutableSetOf<Int>()
  for(i in 0 until s.satelliteCount){if(s.getCn0DbHz(i)<=0f)continue;when(s.getConstellationType(i)){GnssStatus.CONSTELLATION_GPS->gps++;GnssStatus.CONSTELLATION_GALILEO->gal++;GnssStatus.CONSTELLATION_GLONASS->glo++;GnssStatus.CONSTELLATION_BEIDOU->bds++;GnssStatus.CONSTELLATION_QZSS->qz++;else->other++};if(s.usedInFix(i))used++;val f=s.getCarrierFrequencyHz(i);if(f>0f)freqs.add(f.roundToInt())}
  gnss.value=GnssSummary(gps,gal,glo,bds,qz,other,used,freqs.size>=2)
 }}
 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)startGnss()}
 override fun onCreate(b:Bundle?){super.onCreate(b);startGnss();setContent{MaterialTheme{GpsGrid()}}}
 override fun onDestroy(){runCatching{lm.unregisterGnssStatusCallback(cb)};super.onDestroy()}
 private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun startGnss(){if(hasPermission())runCatching{lm.registerGnssStatusCallback(mainExecutor,cb)}}
 private fun current(done:(Location)->Unit){if(!hasPermission())return;val t=CancellationTokenSource();fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,t.token).addOnSuccessListener{if(it!=null&&it.hasAccuracy())done(it)}}
 private fun headers(c:HttpURLConnection){c.setRequestProperty("X-GPSGrid-Client",clientId);c.setRequestProperty("X-GPSGrid-Device",deviceName);c.setRequestProperty("X-Tailscale-Client",deviceName)}
 private suspend fun post(url:String,json:String)=withContext(Dispatchers.IO){runCatching{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=8000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");headers(c);c.outputStream.use{it.write(json.toByteArray())};c.responseCode in 200..299}.getOrDefault(false)}
 private suspend fun get(url:String)=withContext(Dispatchers.IO){runCatching{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=8000;c.readTimeout=8000;headers(c);if(c.responseCode in 200..299)c.inputStream.bufferedReader().use{it.readText()}else null}.getOrNull()}
 private fun pointJson(p:SurveyPoint,s:GnssSummary)=JSONObject().apply{put("id",p.id);put("latitude",p.latitude);put("longitude",p.longitude);put("altitude",p.altitude);put("accuracy",p.accuracy);put("time",p.time);put("device_id",clientId);put("device_name",deviceName);put("satellites_visible",s.total);put("satellites_used",s.used);put("gps",s.gps);put("galileo",s.galileo);put("glonass",s.glonass);put("beidou",s.beidou);put("qzss",s.qzss);put("dual_frequency",s.dual)}.toString()
 private fun parse(text:String):Pair<List<SurveyPoint>,Boolean>{val o=JSONObject(text);val a=o.optJSONArray("points")?:JSONArray();val p=mutableListOf<SurveyPoint>();for(i in 0 until a.length()){val x=a.getJSONObject(i);p+=SurveyPoint(x.optInt("id",i+1),x.optDouble("latitude"),x.optDouble("longitude"),x.optDouble("altitude"),x.optDouble("accuracy").toFloat(),x.optLong("time"))};return p to o.optBoolean("closed")}
 @Composable private fun GpsGrid(){
  var points by remember{mutableStateOf(emptyList<SurveyPoint>())};var closed by remember{mutableStateOf(false)};var acc by remember{mutableStateOf<Float?>(null)};var connected by remember{mutableStateOf(false)};var url by remember{mutableStateOf("http://100.95.116.23:8765")};var settings by remember{mutableStateOf(false)};var collecting by remember{mutableStateOf(false)};var progress by remember{mutableStateOf(0)};var samples by remember{mutableStateOf(emptyList<Location>())};var rectangle by remember{mutableStateOf(false)};var first by remember{mutableStateOf<SurveyPoint?>(null)};var status by remember{mutableStateOf("GNSS başlatılıyor...")}
  val g=gnss.value;val ready=acc!=null&&acc!!<=5f&&g.used>=4;val quality=when{acc==null->"BEKLENİYOR";acc!!<=3f&&g.used>=6->"ÇOK İYİ";ready->"İYİ";else->"ZAYIF"}
  LaunchedEffect(Unit){while(true){if(!collecting&&hasPermission())current{acc=it.accuracy};connected=get("${url.trimEnd('/')}/api/state")!=null;get("${url.trimEnd('/')}/api/state")?.let{runCatching{parse(it).let{z->if(points.isEmpty()){points=z.first;closed=z.second}}}};delay(3000)}}
  fun begin(){if(closed||collecting)return;if(!hasPermission()){permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return};if(!ready){status="Nokta için Accuracy ≤5 m ve Fix'te ≥4 uydu gerekli";return};samples=emptyList();progress=0;collecting=true;status=if(rectangle)"${if(first==null)"1." else "2."} köşe ölçülüyor..."}
  LaunchedEffect(collecting){if(!collecting)return@LaunchedEffect;sampleCallback={l->acc=l.accuracy;samples=samples+l};repeat(10){i->current{sampleCallback?.invoke(it)};progress=i+1;delay(1000)};val good=samples.filter{it.hasAccuracy()}.sortedBy{it.accuracy};if(good.isNotEmpty()){val best=good.take(5);val p=SurveyPoint(0,best.map{it.latitude}.average(),best.map{it.longitude}.average(),best.map{if(it.hasAltitude())it.altitude else 0.0}.average(),best.map{it.accuracy.toDouble()}.average().toFloat(),System.currentTimeMillis());if(rectangle){if(first==null){first=p;status="1. köşe kaydedildi. Karşı köşeye geç."}else{val a=first!!;val b=p;val la1=min(a.latitude,b.latitude);val la2=max(a.latitude,b.latitude);val lo1=min(a.longitude,b.longitude);val lo2=max(a.longitude,b.longitude);points=listOf(SurveyPoint(1,la1,lo1,a.altitude,a.accuracy,a.time),SurveyPoint(2,la1,lo2,b.altitude,b.accuracy,b.time),SurveyPoint(3,la2,lo2,b.altitude,b.accuracy,b.time),SurveyPoint(4,la2,lo1,a.altitude,a.accuracy,a.time));points.forEach{runCatching{post("${url.trimEnd('/')}/api/point",pointJson(it,g))}};first=null;status="Dikdörtgen 4 köşeyle oluşturuldu"}}else{val q=p.copy(id=points.size+1);points=points+q;val ok=post("${url.trimEnd('/')}/api/point",pointJson(q,g));status=if(ok)"P${q.id} kaydedildi — ±${"%.1f".format(q.accuracy)} m" else "P${q.id} kaydedildi — PC bağlantısı yok"}}else status="Geçerli GNSS örneği alınamadı";collecting=false;sampleCallback=null}
  LaunchedEffect(closed){if(closed)post("${url.trimEnd('/')}/api/polygon/close","{}")}
  if(settings){Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Ayarlar",style=MaterialTheme.typography.headlineMedium);OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Tailscale PC Server")});Button({settings=false},Modifier.fillMaxWidth()){Text("ANA SAYFAYA DÖN")}};return}
  Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("GPS Grid",style=MaterialTheme.typography.headlineMedium);TextButton({settings=true}){Text("AYARLAR")}}
   Text("PC: ${if(connected)"🟢 BAĞLI" else "🔴 BAĞLI DEĞİL"}")
   Text("GNSS: ${g.total}  • Fix: ${g.used}  • Accuracy: ${acc?.let{"%.1f m".format(it)}?:"—"}  • Kalite: $quality")
   Text("GPS ${g.gps}   Galileo ${g.galileo}   GLONASS ${g.glonass}   BeiDou ${g.beidou}   QZSS ${g.qzss}${if(g.dual)"   • Çift frekans algılandı" else ""}")
   if(!ready&&!collecting)Text("Nokta ekleme kilitli: Accuracy ≤5 m ve en az 4 kullanılan uydu gerekli",color=MaterialTheme.colorScheme.error)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(!rectangle,{rectangle=false;first=null},{Text("SERBEST")});FilterChip(rectangle,{rectangle=true;first=null},{Text("DİKDÖRTGEN")})}
   Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(if(points.isEmpty())"Nokta yok" else points.joinToString(" → "){ "P${it.id}" });if(closed){Text("POLYGON KAPALI");Text("Alan: ${formatArea(area(points))}")}}}
   if(collecting){LinearProgressIndicator(progress={progress/10f},Modifier.fillMaxWidth());Text("GNSS örneği: $progress / 10")}
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){Button({begin()},Modifier.weight(1f),enabled=!closed&&!collecting&&ready){Text(if(rectangle)"KÖŞE EKLE" else "NOKTA EKLE")};Button({closed=true},Modifier.weight(1f),enabled=!closed&&!collecting&&points.size>=3){Text("POLİGONU KAPAT")}}
   if(closed)Button({closed=false},Modifier.fillMaxWidth()){Text("POLİGONU DÜZENLE")}
   Text(status)
   LazyColumn(Modifier.weight(1f)){items(points){p->Text("P${p.id}  ${"%.7f".format(p.latitude)}, ${"%.7f".format(p.longitude)}  ±${"%.1f".format(p.accuracy)} m")}}
   OutlinedButton({if(points.isNotEmpty()&&!closed&&!rectangle)points=points.dropLast(1)},Modifier.fillMaxWidth(),enabled=points.isNotEmpty()&&!closed&&!collecting&&!rectangle){Text("SON NOKTAYI SİL")}
  }
 }
 private fun area(p:List<SurveyPoint>):Double{if(p.size<3)return 0.0;val r=6371008.8;val lat=Math.toRadians(p.map{it.latitude}.average());val q=p.map{Math.toRadians(it.longitude)*r*cos(lat) to Math.toRadians(it.latitude)*r};var s=0.0;q.indices.forEach{i->val j=(i+1)%q.size;s+=q[i].first*q[j].second-q[j].first*q[i].second};return abs(s)/2}
 private fun formatArea(v:Double)=if(v>=10000)"%.4f ha".format(v/10000)else"%.1f m²".format(v)
}
