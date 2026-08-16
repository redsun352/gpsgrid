package com.redsun352.gpsgrid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.*

data class SurveyPoint(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val time: Long
)

class MainActivity : ComponentActivity() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var onLocationResult: ((android.location.Location) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            readLocation { location -> onLocationResult?.invoke(location) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { GpsGridScreen() } }
    }

    @Composable
    private fun GpsGridScreen() {
        var points by remember { mutableStateOf(listOf<SurveyPoint>()) }
        var currentLocation by remember { mutableStateOf<android.location.Location?>(null) }
        var status by remember { mutableStateOf("Konum bekleniyor") }
        var closed by remember { mutableStateOf(false) }
        var accuracy by remember { mutableStateOf<Float?>(null) }

        fun requestPoint() {
            if (closed) return
            onLocationResult = { location ->
                currentLocation = location
                accuracy = location.accuracy
                if (!location.hasAccuracy() || location.accuracy > 5f) {
                    status = "Konum doğruluğu yetersiz: %.1f m (≤ 5 m gerekli)".format(location.accuracy)
                    return@onLocationResult
                }
                val p = SurveyPoint(
                    id = points.size + 1,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else 0.0,
                    accuracy = location.accuracy,
                    time = System.currentTimeMillis()
                )
                points = points + p
                status = "P${p.id} kaydedildi — %.1f m".format(p.accuracy)
            }
            if (hasLocationPermission()) readLocation { location -> onLocationResult?.invoke(location) }
            else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        fun closePolygon() {
            if (points.size >= 3) {
                closed = true
                status = "Polygon kapatıldı"
            } else status = "Polygon için en az 3 nokta gerekli"
        }

        val area = if (closed) polygonAreaM2(points) else 0.0
        val perimeter = polygonPerimeterM(points, closed)

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("GPS Grid", style = MaterialTheme.typography.headlineMedium)
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Accuracy: ${accuracy?.let { "%.1f m".format(it) } ?: "—"}")
                Text("Nokta: ${points.size}")
            }

            SurveyCanvas(points, closed, Modifier.fillMaxWidth().height(260.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !closed, onClick = { requestPoint() }) {
                    Text("NOKTA EKLE")
                }
                Button(modifier = Modifier.weight(1f), enabled = !closed && points.size >= 3, onClick = { closePolygon() }) {
                    Text("POLİGONU KAPAT")
                }
            }

            if (closed) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("POLYGON HAZIR", style = MaterialTheme.typography.titleMedium)
                        Text("Alan: ${formatArea(area)}")
                        Text("Çevre: ${formatDistance(perimeter)}")
                    }
                }
            }

            Text("Saha noktaları", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(points) { _, p ->
                    Text("P${p.id}  ${"%.7f".format(p.latitude)}, ${"%.7f".format(p.longitude)}  ±${"%.1f m".format(p.accuracy)}")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = points.isNotEmpty() && !closed,
                onClick = { points = points.dropLast(1); status = "Son nokta silindi" }
            ) { Text("SON NOKTAYI SİL") }
        }
    }

    @Composable
    private fun SurveyCanvas(points: List<SurveyPoint>, closed: Boolean, modifier: Modifier) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (points.isEmpty()) {
                Text("Noktalar burada görünecek")
                return@Box
            }
            Canvas(Modifier.fillMaxSize().padding(20.dp)) {
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLon = points.minOf { it.longitude }
                val maxLon = points.maxOf { it.longitude }
                val latRange = max(1e-9, maxLat - minLat)
                val lonRange = max(1e-9, maxLon - minLon)
                val scale = min(size.width / lonRange.toFloat(), size.height / latRange.toFloat()) * 0.8f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centerLon = (minLon + maxLon) / 2.0
                val centerLat = (minLat + maxLat) / 2.0
                fun pos(p: SurveyPoint): Offset = Offset(
                    cx + ((p.longitude - centerLon) * scale).toFloat(),
                    cy - ((p.latitude - centerLat) * scale).toFloat()
                )
                val path = Path()
                points.forEachIndexed { i, p ->
                    val o = pos(p)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                if (closed) path.close()
                drawPath(path, color = MaterialTheme.colorScheme.primary, style = Stroke(width = 5f))
                points.forEach { p ->
                    val o = pos(p)
                    drawCircle(MaterialTheme.colorScheme.error, radius = 9f, center = o)
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun readLocation(callback: (android.location.Location) -> Unit) {
        if (!hasLocationPermission()) return
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) callback(location)
            }
    }

    private fun polygonAreaM2(points: List<SurveyPoint>): Double {
        if (points.size < 3) return 0.0
        val lat0 = Math.toRadians(points.map { it.latitude }.average())
        val r = 6371008.8
        val xy = points.map { p ->
            Pair(Math.toRadians(p.longitude) * r * cos(lat0), Math.toRadians(p.latitude) * r)
        }
        var sum = 0.0
        xy.indices.forEach { i ->
            val j = (i + 1) % xy.size
            sum += xy[i].first * xy[j].second - xy[j].first * xy[i].second
        }
        return abs(sum) / 2.0
    }

    private fun polygonPerimeterM(points: List<SurveyPoint>, closed: Boolean): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) total += distanceM(points[i - 1], points[i])
        if (closed) total += distanceM(points.last(), points.first())
        return total
    }

    private fun distanceM(a: SurveyPoint, b: SurveyPoint): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = Math.toRadians(b.latitude - a.latitude)
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private fun formatArea(m2: Double): String = if (m2 >= 10000) "%.4f ha (%.1f m²)".format(m2 / 10000, m2) else "%.1f m²".format(m2)
    private fun formatDistance(m: Double): String = if (m >= 1000) "%.3f km".format(m / 1000) else "%.2f m".format(m)
}
