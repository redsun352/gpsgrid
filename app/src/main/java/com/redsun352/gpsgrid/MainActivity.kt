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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
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

class MainActivity:ComponentActivity(){
 private val fused by lazy{LocationServices.getFusedLocationProviderClient(this)}
 private val locationManager by lazy{getSystemService(LOCATION_SERVICE) as LocationManager}
 private val clientId by lazy{"android-${android.os.Build.MODEL}-${android.os.Build.ID}"}
 private val deviceName by lazy{"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"}
 private val satellites=mutableStateOf(0)
 private val usedSatellites=mutableStateOf(0)
 private val gnssFix=mutableStateOf(false)
 private var sampleCallback:((Location)->Unit)?=null
 private val gnssCallback=object: GnssStatus.Callback(){
  override fun onSatelliteStatusChanged(s:GnssStatus){
   var visible=0;var used=0
   for(i in 0 until s.satelliteCount){
    if(s.getCn0DbHz(i)>0f) visible++
    if(s.usedInFix(i)) used++
   }
   satellites.value=visible
   usedSatellites.value=used
   gnssFix.value=used>0
  }
 }
 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true){startGnss();readLocation{}}}
 override fun onCreate(b:Bundle?){super.onCreate(b);startGnss();setContent{MaterialTheme{GpsGrid()}}}
 override fun onDestroy(){try{locationManager.unregisterGnssStatusCallback(gnssCallback)}catch(_:Exception){};super.onDestroy()}
 private fun startGnss(){if(hasPermission())try{locationManager.registerGnssStatusCallback(mainExecutor,gnssCallback)}catch(_:Exception){}}
 private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun readLocation(done:(Location)->Unit){if(!hasPermission())return;val token=CancellationTokenSource();fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token).addOnSuccessListener{if(it!=null&&it.hasAccuracy())done(it)}}
 private fun applyHeaders(c:HttpURLConnection){c.setRequestProperty("X-GPSGrid-Client",clientId);c.setRequestProperty("X-GPSGrid-Device",deviceName);c.setRequestProperty("X-Tailscale-Client",deviceName)}
 private suspend fun postJson(url:String,json:String):Boolean=withContext(Dispatchers.IO){try{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=8000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");applyHeaders(c);c.outputStream.use{it.write(json.toByteArray())};c.responseCode in 200..299}catch(_:Exception){false}}
 private suspend fun getText(url:String):String?=withContext(Dispatchers.IO){try{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="GET";c.connectTimeout=8000;c.readTimeout=8000;applyHeaders(c);if(c.responseCode in 200..299)c.inputStream.bufferedReader().use{it.readText()}else null}catch(_:Exception){null}}
 private suspend fun heartbeat(url:String)=postJson("${url.trimEnd('/')}/api/heartbeat",JSONObject().apply{put("device_id",clientId);put("device_name",deviceName);put("platform","Android");put("transport","Tailscale")}.toString())
 private fun parseState(text:String):Pair<List<SurveyPoint>,Boolean>{val o=JSONObject(text);val a=o.optJSONArray("points")?:JSONArray();val p=mutableListOf<SurveyPoint>();for(i in 0 until a.length()){val x=a.getJSONObject(i);p.add(SurveyPoint(x.optInt("id",i+1),x.optDouble("latitude"),x.optDouble("longitude"),x.optDouble("altitude"),x.optDouble("accuracy").toFloat(),x.optLong("time")))};return Pair(p,o.optBoolean("closed"))}
 @Composable private fun GpsGrid(){
  var points by remember{mutableStateOf(listOf<SurveyPoint>())};var closed by remember{mutableStateOf(false)};var status by remember{mutableStateOf("GPS başlatılıyor...")};var accuracy by remember{mutableStateOf<Float?>(null)};var collecting by remember{mutableStateOf(false)};var progress by remember{mutableStateOf(0)};var samples by remember{mutableStateOf(listOf<Location>())};var serverUrl by remember{mutableStateOf("http://100.95.116.23:8765")};var connected by remember{mutableStateOf(false)};var testing by remember{mutableStateOf(false)};var showSettings by remember{mutableStateOf(false)};var rectangleMode by remember{mutableStateOf(false)};var rectangleFirst by remember{mutableStateOf<SurveyPoint?>(null)}
  val sat=satellites.value;val used=usedSatellites.value;val qualityReady=(accuracy!=null&&accuracy!!<=5f&&used>=4);val qualityText=when{accuracy==null->"GPS bekleniyor";accuracy!!<=3f&&used>=6->"ÇOK İYİ";accuracy!!<=5f&&used>=4->"İYİ";else->"BEKLE"}
  LaunchedEffect(Unit){while(true){if(!collecting&&hasPermission())readLocation{accuracy=it.accuracy};connected=heartbeat(serverUrl);val state=getText("${serverUrl.trimEnd('/')}/api/state");if(state!=null)try{val z=parseState(state);if(points.isEmpty()){points=z.first;closed=z.second}}catch(_:Exception){};delay(3000)}}
  fun startCapture(){if(closed||collecting)return;if(!hasPermission()){permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return};if(!qualityReady){status="Nokta eklemek için Accuracy ≤5 m ve Fix'te en az 4 uydu gerekli";return};samples=emptyList();progress=0;collecting=true;status="${if(rectangleMode&&rectangleFirst==null)"1. köşe" else if(rectangleMode)"2. köşe" else "Nokta"} ölçülüyor..."}
  LaunchedEffect(collecting){if(!collecting)return@LaunchedEffect;sampleCallback={l->accuracy=l.accuracy;samples=samples+l};repeat(10){i->readLocation{sampleCallback?.invoke(it)};progress=i+1;delay(1000)};val good=samples.filter{it.hasAccuracy()}.sortedBy{it.accuracy};if(good.isNotEmpty()){val best=good.take(min(5,good.size));val p=SurveyPoint(1,best.map{it.latitude}.average(),best.map{it.longitude}.average(),best.map{if(it.hasAltitude())it.altitude else 0.0}.average(),best.map{it.accuracy.toDouble()}.average().toFloat(),System.currentTimeMillis());if(rectangleMode){if(rectangleFirst==null){rectangleFirst=p;status="1. köşe kaydedildi. Karşı köşeye geçin."}else{val a=rectangleFirst!!;val b=p;val la1=min(a.latitude,b.latitude);val la2=max(a.latitude,b.latitude);val lo1=min(a.longitude,b.longitude);val lo2=max(a.longitude,b.longitude);points=listOf(SurveyPoint(1,la1,lo1,a.altitude,a.accuracy,a.time),SurveyPoint(2,la1,lo2,b.altitude,b.accuracy,b.time),SurveyPoint(3,la2,lo2,b.altitude,b.accuracy,b.time),SurveyPoint(4,la2,lo1,a.altitude,a.accuracy,a.time));points.forEach{q->postJson("${serverUrl.trimEnd('/')}/api/point",JSONObject().apply{put("id",q.id);put("latitude",q.latitude);put("longitude",q.longitude);put("altitude",q.altitude);put("accuracy",q.accuracy);put("time",q.time);put("device_id",clientId);put("device_name",deviceName);put("satellites_visible",sat);put("satellites_used",used)}.toString())};status="4 köşeli dikdörtgen hazır — polygonu kapatabilirsiniz";rectangleFirst=null}}else{val q=p.copy(id=points.size+1);points=points+q;val ok=postJson("${serverUrl.trimEnd('/')}/api/point",JSONObject().apply{put("id",q.id);put("latitude",q.latitude);put("longitude",q.longitude);put("altitude",q.altitude);put("accuracy",q.accuracy);put("time",q.time);put("device_id",clientId);put("device_name",deviceName);put("satellites_visible",sat);put("satellites_used",used)}.toString());status=if(ok)"P${q.id} kaydedildi — ±${"%.1f".format(q.accuracy)} m" else "P${q.id} kaydedildi — PC bağlantısı yok"}}else status="Geçerli GPS örneği alınamadı";collecting=false;sampleCallback=null}
  LaunchedEffect(testing){if(testing){connected=getText("${serverUrl.trimEnd('/')}/api/state")!=null;status=if(connected)"PC bağlantısı başarılı — Tailscale" else "PC bağlantısı başarısız";testing=false}}
  LaunchedEffect(closed){if(closed){val ok=postJson("${serverUrl.trimEnd('/')}/api/polygon/close","{}");status=if(ok)"Polygon kapatıldı" else "Polygon kapatılamadı"}}
  if(showSettings){Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Ayarlar",style=MaterialTheme.typography.headlineMedium);OutlinedTextField(value=serverUrl,onValueChange={serverUrl=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Tailscale PC Server")});Button(onClick={testing=true},modifier=Modifier.fillMaxWidth()){Text(if(connected)"PC BAĞLI — TEST ET" else "PC BAĞLANTISINI TEST ET")};Button(onClick={showSettings=false},modifier=Modifier.fillMaxWidth()){Text("ANA SAYFAYA DÖN")}};return}
  Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("GPS Grid",style=MaterialTheme.typography.headlineMedium);TextButton(onClick={showSettings=true}){Text("AYARLAR")}}
   Text("PC: ${if(connected)"🟢 BAĞLI" else "🔴 BAĞLANIYOR..."}");Text("Uydu: $sat • Fix'te: $used • Accuracy: ${accuracy?.let{"%.1f m".format(it)}?:"—"} • GPS kalite: $qualityText")
   if(!qualityReady&&!collecting)Text("Nokta eklemek için kaliteli GPS bekleniyor (Accuracy ≤5 m / Fix'te ≥4 uydu)",color=MaterialTheme.colorScheme.error)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(selected=!rectangleMode,onClick={rectangleMode=false;rectangleFirst=null},label={Text("SERBEST")});FilterChip(selected=rectangleMode,onClick={rectangleMode=true;rectangleFirst=null},label={Text("DİKDÖRTGEN")})}
   SurveyCanvas(points,closed,Modifier.fillMaxWidth().height(220.dp));if(collecting){LinearProgressIndicator(progress={progress/10f},Modifier.fillMaxWidth());Text("$progress / 10 örnek")}
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){Button(onClick={startCapture()},Modifier.weight(1f),enabled=!closed&&!collecting&&qualityReady){Text(if(rectangleMode)"KÖŞE EKLE" else "NOKTA EKLE")};Button(onClick={closed=true},Modifier.weight(1f),enabled=!closed&&points.size>=3&&!collecting){Text("POLİGONU KAPAT")}}
   if(closed){Button(onClick={closed=false;status="Polygon düzenleme açık"},Modifier.fillMaxWidth()){Text("POLİGONU DÜZENLE")};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text("POLYGON HAZIR");Text("Alan: ${formatArea(polygonArea(points))}");Text("Çevre: ${formatDistance(polygonPerimeter(points))}")}}}
   Text("Saha noktaları");LazyColumn(Modifier.weight(1f)){items(points){p->Text("P${p.id}  ${"%.7f".format(p.latitude)}, ${"%.7f".format(p.longitude)}  ±${"%.1f".format(p.accuracy)} m")}}
   OutlinedButton(onClick={if(points.isNotEmpty()){points=points.dropLast(1);status="Son nokta silindi"}},Modifier.fillMaxWidth(),enabled=points.isNotEmpty()&&!closed&&!collecting&&!rectangleMode){Text("SON NOKTAYI SİL")}
  }
 }
 @Composable private fun SurveyCanvas(points:List<SurveyPoint>,closed:Boolean,modifier:Modifier){val bg=MaterialTheme.colorScheme.surfaceVariant;val primary=MaterialTheme.colorScheme.primary;val error=MaterialTheme.colorScheme.error;Box(modifier.background(bg),contentAlignment=Alignment.Center){if(points.isEmpty())Text("Noktalar burada görünecek") else Canvas(Modifier.fillMaxSize().padding(20.dp)){val minLat=points.minOf{it.latitude};val maxLat=points.maxOf{it.latitude};val minLon=points.minOf{it.longitude};val maxLon=points.maxOf{it.longitude};val scale=min(size.width/max(1e-9,maxLon-minLon).toFloat(),size.height/max(1e-9,maxLat-minLat).toFloat())*.8f;val cx=size.width/2;val cy=size.height/2;val cl=(minLon+maxLon)/2;val ca=(minLat+maxLat)/2;val path=Path();points.forEachIndexed{i,p->{val x=cx+((p.longitude-cl)*scale).toFloat();val y=cy-((p.latitude-ca)*scale).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}};if(closed)path.close();drawPath(path,primary,style=Stroke(5f));points.forEach{p->val x=cx+((p.longitude-cl)*scale).toFloat();val y=cy-((p.latitude-ca)*scale).toFloat();drawCircle(error,9f,Offset(x,y))}}}}
 private fun polygonArea(p:List<SurveyPoint>):Double{if(p.size<3)return 0.0;val r=6371008.8;val lat=Math.toRadians(p.map{it.latitude}.average());val q=p.map{Pair(Math.toRadians(it.longitude)*r*cos(lat),Math.toRadians(it.latitude)*r)};var s=0.0;q.indices.forEach{i->val j=(i+1)%q.size;s+=q[i].first*q[j].second-q[j].first*q[i].second};return abs(s)/2}
 private fun polygonPerimeter(p:List<SurveyPoint>):Double{if(p.size<2)return 0.0;var s=0.0;for(i in 1 until p.size)s+=distance(p[i-1],p[i]);s+=distance(p.last(),p.first());return s}
 private fun distance(a:SurveyPoint,b:SurveyPoint):Double{val r=6371008.8;val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(b.latitude);val dp=Math.toRadians(b.latitude-a.latitude);val dl=Math.toRadians(b.longitude-a.longitude);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 2*r*asin(sqrt(h))}
 private fun formatArea(v:Double)=if(v>=10000)"%.4f ha (%.1f m²)".format(v/10000,v)else"%.1f m²".format(v)
 private fun formatDistance(v:Double)=if(v>=1000)"%.3f km".format(v/1000)else"%.2f m".format(v)
}
