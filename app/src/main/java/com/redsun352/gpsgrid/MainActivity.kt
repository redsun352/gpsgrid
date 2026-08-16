package com.redsun352.gpsgrid

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
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
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.math.*

data class SurveyPoint(val id:Int,val latitude:Double,val longitude:Double,val altitude:Double,val accuracy:Float,val time:Long)

class MainActivity:ComponentActivity(){
 private val fused by lazy{LocationServices.getFusedLocationProviderClient(this)}
 private var sampleCallback:((Location)->Unit)?=null
 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)readLocation{sampleCallback?.invoke(it)}}
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{GpsGrid()}}}
 private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun readLocation(done:(Location)->Unit){if(!hasPermission())return;val token=CancellationTokenSource();fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token).addOnSuccessListener{if(it!=null)done(it)}}
 private suspend fun postJson(url:String,json:String):Boolean=withContext(Dispatchers.IO){try{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=5000;c.readTimeout=5000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(json.toByteArray())};c.responseCode in 200..299}catch(_:Exception){false}}
 private suspend fun getOk(url:String):Boolean=withContext(Dispatchers.IO){try{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="GET";c.connectTimeout=5000;c.readTimeout=5000;c.responseCode in 200..299}catch(_:Exception){false}}
 @Composable private fun GpsGrid(){
  var points by remember{mutableStateOf(listOf<SurveyPoint>())}
  var closed by remember{mutableStateOf(false)}
  var status by remember{mutableStateOf("GPS hazır")}
  var accuracy by remember{mutableStateOf<Float?>(null)}
  var collecting by remember{mutableStateOf(false)}
  var progress by remember{mutableStateOf(0)}
  var samples by remember{mutableStateOf(listOf<Location>())}
  var serverUrl by remember{mutableStateOf("http://192.168.1.100:8765")}
  var connected by remember{mutableStateOf(false)}
  var testing by remember{mutableStateOf(false)}

  fun startCapture(){
   if(closed||collecting)return
   if(!hasPermission()){permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return}
   samples=emptyList();progress=0;collecting=true;status="GPS örnekleri toplanıyor..."
  }

  LaunchedEffect(collecting){
   if(!collecting)return@LaunchedEffect
   sampleCallback={location->accuracy=location.accuracy;samples=samples+location}
   repeat(30){index->
    readLocation{location->sampleCallback?.invoke(location)}
    progress=index+1
    delay(1000)
   }
   val good=samples.filter{it.hasAccuracy()}
   if(good.isNotEmpty()){
    val point=SurveyPoint(
     points.size+1,
     good.map{it.latitude}.average(),
     good.map{it.longitude}.average(),
     good.map{if(it.hasAltitude())it.altitude else 0.0}.average(),
     good.map{it.accuracy.toDouble()}.average().toFloat(),
     System.currentTimeMillis()
    )
    points=points+point
    accuracy=point.accuracy
    status="P${point.id} kaydedildi — ±${"%.1f".format(point.accuracy)} m"
    val body=JSONObject().apply{
     put("id",point.id);put("latitude",point.latitude);put("longitude",point.longitude)
     put("altitude",point.altitude);put("accuracy",point.accuracy);put("time",point.time)
    }
    connected=postJson("${serverUrl.trimEnd('/')}/api/point",body.toString())
    if(!connected)status+=" — PC bağlantısı yok"
   }else status="Geçerli GPS örneği alınamadı"
   collecting=false
   sampleCallback=null
  }

  LaunchedEffect(testing){
   if(testing){
    connected=getOk("${serverUrl.trimEnd('/')}/api/state")
    status=if(connected)"PC bağlantısı başarılı" else "PC bağlantısı başarısız"
    testing=false
   }
  }

  Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("GPS Grid",style=MaterialTheme.typography.headlineMedium)
   Text(status)
   Text("Accuracy: ${accuracy?.let{"%.1f m".format(it)}?:"—"} • Nokta: ${points.size}")
   OutlinedTextField(value=serverUrl,onValueChange={serverUrl=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("PC Server adresi")})
   Button(onClick={if(!testing){testing=true;status="PC bağlantısı test ediliyor..."}},modifier=Modifier.fillMaxWidth(),enabled=!testing){Text(if(connected)"PC BAĞLI" else "PC BAĞLANTISINI TEST ET")}
   SurveyCanvas(points,closed,Modifier.fillMaxWidth().height(260.dp))
   if(collecting){LinearProgressIndicator(progress={progress/30f},Modifier.fillMaxWidth());Text("$progress / 30 ölçüm")}
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick={startCapture()},Modifier.weight(1f),enabled=!closed&&!collecting){Text("NOKTA EKLE")}
    Button(onClick={closed=true;status="Polygon kapatıldı"},Modifier.weight(1f),enabled=!closed&&points.size>=3&&!collecting){Text("POLİGONU KAPAT")}
   }
   if(closed)Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("POLYGON HAZIR");Text("Alan: ${formatArea(polygonArea(points))}");Text("Çevre: ${formatDistance(polygonPerimeter(points))}")}}
   Text("Saha noktaları")
   LazyColumn(Modifier.weight(1f)){items(points){point->Text("P${point.id}  %.7f, %.7f  ±%.1f m".format(point.latitude,point.longitude,point.accuracy))}}
   OutlinedButton(onClick={points=points.dropLast(1);status="Son nokta silindi"},Modifier.fillMaxWidth(),enabled=points.isNotEmpty()&&!closed&&!collecting){Text("SON NOKTAYI SİL")}
  }
 }

 @Composable private fun SurveyCanvas(points:List<SurveyPoint>,closed:Boolean,modifier:Modifier){
  val background=MaterialTheme.colorScheme.surfaceVariant
  val line=MaterialTheme.colorScheme.primary
  val dot=MaterialTheme.colorScheme.error
  Box(modifier.background(background),contentAlignment=Alignment.Center){
   if(points.isEmpty()){
    Text("Noktalar burada görünecek")
   }else{
    Canvas(Modifier.fillMaxSize().padding(20.dp)){
     val minLat=points.minOf{it.latitude};val maxLat=points.maxOf{it.latitude}
     val minLon=points.minOf{it.longitude};val maxLon=points.maxOf{it.longitude}
     val scale=min(size.width/max(1e-9,maxLon-minLon).toFloat(),size.height/max(1e-9,maxLat-minLat).toFloat())*.8f
     val centerX=size.width/2;val centerY=size.height/2
     val centerLon=(minLon+maxLon)/2;val centerLat=(minLat+maxLat)/2
     val path=Path()
     points.forEachIndexed{index,point->
      val x=centerX+((point.longitude-centerLon)*scale).toFloat()
      val y=centerY-((point.latitude-centerLat)*scale).toFloat()
      if(index==0)path.moveTo(x,y)else path.lineTo(x,y)
     }
     if(closed)path.close()
     drawPath(path,line,style=Stroke(5f))
     points.forEach{point->
      val x=centerX+((point.longitude-centerLon)*scale).toFloat()
      val y=centerY-((point.latitude-centerLat)*scale).toFloat()
      drawCircle(dot,9f,Offset(x,y))
     }
    }
   }
  }
 }

 private fun polygonArea(points:List<SurveyPoint>):Double{if(points.size<3)return 0.0;val r=6371008.8;val lat=Math.toRadians(points.map{it.latitude}.average());val xy=points.map{Pair(Math.toRadians(it.longitude)*r*cos(lat),Math.toRadians(it.latitude)*r)};var sum=0.0;xy.indices.forEach{i->val j=(i+1)%xy.size;sum+=xy[i].first*xy[j].second-xy[j].first*xy[i].second};return abs(sum)/2}
 private fun polygonPerimeter(points:List<SurveyPoint>):Double{if(points.size<2)return 0.0;var sum=0.0;for(i in 1 until points.size)sum+=distance(points[i-1],points[i]);sum+=distance(points.last(),points.first());return sum}
 private fun distance(a:SurveyPoint,b:SurveyPoint):Double{val r=6371008.8;val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(b.latitude);val dp=Math.toRadians(b.latitude-a.latitude);val dl=Math.toRadians(b.longitude-a.longitude);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 2*r*asin(sqrt(h))}
 private fun formatArea(value:Double)=if(value>=10000)"%.4f ha (%.1f m²)".format(value/10000,value)else"%.1f m²".format(value)
 private fun formatDistance(value:Double)=if(value>=1000)"%.3f km".format(value/1000)else"%.2f m".format(value)
}
