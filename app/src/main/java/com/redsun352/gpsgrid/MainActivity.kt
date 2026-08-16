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
import kotlinx.coroutines.delay
import kotlin.math.*

data class SurveyPoint(val id:Int,val latitude:Double,val longitude:Double,val altitude:Double,val accuracy:Float,val time:Long)

class MainActivity:ComponentActivity(){
 private val fused by lazy{LocationServices.getFusedLocationProviderClient(this)}
 private var sampleCallback:((Location)->Unit)?=null
 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)readLocation{sampleCallback?.invoke(it)}}
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{GpsGrid()}}}
 private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun readLocation(done:(Location)->Unit){if(!hasPermission())return;val token=CancellationTokenSource();fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token).addOnSuccessListener{if(it!=null)done(it)}}
 @Composable private fun GpsGrid(){
  var points by remember{mutableStateOf(listOf<SurveyPoint>())};var closed by remember{mutableStateOf(false)};var status by remember{mutableStateOf("GPS hazır")};var accuracy by remember{mutableStateOf<Float?>(null)};var collecting by remember{mutableStateOf(false)};var progress by remember{mutableStateOf(0)};var samples by remember{mutableStateOf(listOf<Location>())}
  fun startCapture(){if(closed||collecting)return;if(!hasPermission()){permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return};samples=emptyList();progress=0;collecting=true;status="GPS örnekleri toplanıyor…"}
  LaunchedEffect(collecting){if(collecting){sampleCallback={loc->accuracy=loc.accuracy;samples=samples+loc};repeat(30){readLocation{sampleCallback?.invoke(it)};progress=it+1;delay(1000)};val good=samples.filter{it.hasAccuracy()};if(good.isNotEmpty()){val lat=good.map{it.latitude}.average();val lon=good.map{it.longitude}.average();val alt=good.map{if(it.hasAltitude())it.altitude else 0.0}.average();val acc=good.map{it.accuracy.toDouble()}.average().toFloat();points=points+SurveyPoint(points.size+1,lat,lon,alt,acc,System.currentTimeMillis());accuracy=acc;status="P${points.size} kaydedildi — ${"%.1f".format(acc)} m"}else status="GPS örneği alınamadı";collecting=false;sampleCallback=null}}
  Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("GPS Grid",style=MaterialTheme.typography.headlineMedium);Text(status);Text("Accuracy: ${accuracy?.let{"%.1f m".format(it)}?:"—"} • Nokta: ${points.size}")
   SurveyCanvas(points,closed,Modifier.fillMaxWidth().height(260.dp))
   if(collecting){LinearProgressIndicator(progress={progress/30f},Modifier.fillMaxWidth());Text("$progress / 30 ölçüm")}
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={startCapture()},modifier=Modifier.weight(1f),enabled=!closed&&!collecting){Text(if(collecting)"ÖLÇÜLÜYOR…" else "NOKTA EKLE")};Button(onClick={closed=true;status="Polygon kapatıldı"},modifier=Modifier.weight(1f),enabled=!closed&&points.size>=3&&!collecting){Text("POLİGONU KAPAT")}}
   if(closed)Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("POLYGON HAZIR");Text("Alan: ${formatArea(polygonArea(points))}");Text("Çevre: ${formatDistance(polygonPerimeter(points))}")}}
   Text("Saha noktaları");LazyColumn(Modifier.weight(1f)){items(points){p->Text("P${p.id}  %.7f, %.7f  ±%.1f m".format(p.latitude,p.longitude,p.accuracy))}}
   OutlinedButton(onClick={points=points.dropLast(1);status="Son nokta silindi"},modifier=Modifier.fillMaxWidth(),enabled=points.isNotEmpty()&&!closed&&!collecting){Text("SON NOKTAYI SİL")}
  }
 }
 @Composable private fun SurveyCanvas(ps:List<SurveyPoint>,closed:Boolean,modifier:Modifier){val bg=MaterialTheme.colorScheme.surfaceVariant;val line=MaterialTheme.colorScheme.primary;val dot=MaterialTheme.colorScheme.error;Box(modifier.background(bg),contentAlignment=Alignment.Center){if(ps.isEmpty())Text("Noktalar burada görünecek")else Canvas(Modifier.fillMaxSize().padding(20.dp)){val minLat=ps.minOf{it.latitude};val maxLat=ps.maxOf{it.latitude};val minLon=ps.minOf{it.longitude};val maxLon=ps.maxOf{it.longitude};val scale=min(size.width/max(1e-9,(maxLon-minLon)).toFloat(),size.height/max(1e-9,(maxLat-minLat)).toFloat())*.8f;val cx=size.width/2;val cy=size.height/2;val cl=(minLon+maxLon)/2;val ca=(minLat+maxLat)/2;fun pos(p:SurveyPoint)=Offset(cx+((p.longitude-cl)*scale).toFloat(),cy-((p.latitude-ca)*scale).toFloat());val path=Path();ps.forEachIndexed{i,p->val o=pos(p);if(i==0)path.moveTo(o.x,o.y)else path.lineTo(o.x,o.y)};if(closed)path.close();drawPath(path,line,style=Stroke(5f));ps.forEach{drawCircle(dot,9f,pos(it))}}}}
 private fun polygonArea(ps:List<SurveyPoint>):Double{if(ps.size<3)return 0.0;val r=6371008.8;val lat=Math.toRadians(ps.map{it.latitude}.average());val xy=ps.map{Pair(Math.toRadians(it.longitude)*r*cos(lat),Math.toRadians(it.latitude)*r)};var s=0.0;xy.indices.forEach{i->val j=(i+1)%xy.size;s+=xy[i].first*xy[j].second-xy[j].first*xy[i].second};return abs(s)/2}
 private fun polygonPerimeter(ps:List<SurveyPoint>):Double{if(ps.size<2)return 0.0;var s=0.0;for(i in 1 until ps.size)s+=distance(ps[i-1],ps[i]);s+=distance(ps.last(),ps.first());return s}
 private fun distance(a:SurveyPoint,b:SurveyPoint):Double{val r=6371008.8;val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(b.latitude);val dp=Math.toRadians(b.latitude-a.latitude);val dl=Math.toRadians(b.longitude-a.longitude);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 2*r*asin(sqrt(h))}
 private fun formatArea(v:Double)=if(v>=10000)"%.4f ha (%.1f m²)".format(v/10000,v)else"%.1f m²".format(v);private fun formatDistance(v:Double)=if(v>=1000)"%.3f km".format(v/1000)else"%.2f m".format(v)
}
