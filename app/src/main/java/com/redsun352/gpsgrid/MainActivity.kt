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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var sampleCallback: ((Location) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            readLocation { location -> sampleCallback?.invoke(location) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { GpsGrid() } }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun readLocation(done: (Location) -> Unit) {
        if (!hasPermission()) return
        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null) done(location)
            }
    }

    @Composable
    private fun GpsGrid() {
        var points by remember { mutableStateOf(listOf<SurveyPoint>()) }
        var closed by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("GPS hazır") }
        var accuracy by remember { mutableStateOf<Float?>(null) }
        var collecting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0) }
        var samples by remember { mutableStateOf(listOf<Location>()) }

        fun startCapture() {
            if (closed || collecting) return
            if (!hasPermission()) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return
            }
            samples = emptyList()
            progress = 0
            collecting = true
            status = "GPS örnekleri toplanıyor..."
        }

        LaunchedEffect(collecting) {
            if (!collecting) return@LaunchedEffect

            sampleCallback = { location ->
                accuracy = location.accuracy
                samples = samples + location
            }

            repeat(30) { index ->
                readLocation { location -> sampleCallback?.invoke(location) }
                progress = index + 1
                delay(1000)
            }

            val good = samples.filter { it.hasAccuracy() }
            if (good.isNotEmpty()) {
                val latitude = good.map { it.latitude }.average()
                val longitude = good.map { it.longitude }.average()
                val altitude = good.map {
                    if (it.hasAltitude()) it.altitude else 0.0
                }.average()
                val averageAccuracy = good.map { it.accuracy.toDouble() }
                    .average().toFloat()

                val newPoint = SurveyPoint(
                    id = points.size + 1,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracy = averageAccuracy,
                    time = System.currentTimeMillis()
                )
                points = points + newPoint
                accuracy = averageAccuracy
                status = "P${newPoint.id} kaydedildi — ±${"%.1f".format(averageAccuracy)} m"
            } else {
                status = "Geçerli GPS örneği alınamadı"
            }

            collecting = false
            sampleCallback = null
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("GPS Grid", style = MaterialTheme.typography.headlineMedium)
            Text(status)
            Text(
                "Accuracy: ${accuracy?.let { "%.1f m".format(it) } ?: "—"} • Nokta: ${points.size}"
            )

            SurveyCanvas(
                points = points,
                closed = closed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )

            if (collecting) {
                LinearProgressIndicator(
                    progress = { progress / 30f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$progress / 30 ölçüm")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { startCapture() },
                    modifier = Modifier.weight(1f),
                    enabled = !closed && !collecting
                ) {
                    Text(if (collecting) "ÖLÇÜLÜYOR..." else "NOKTA EKLE")
                }

                Button(
                    onClick = {
                        closed = true
                        status = "Polygon kapatıldı"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !closed && points.size >= 3 && !collecting
                ) {
                    Text("POLİGONU KAPAT")
                }
            }

            if (closed) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("POLYGON HAZIR")
                        Text("Alan: ${formatArea(polygonArea(points))}")
                        Text("Çevre: ${formatDistance(polygonPerimeter(points))}")
                    }
                }
            }

            Text("Saha noktaları")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(points) { point ->
                    Text(
                        "P${point.id}  %.7f, %.7f  ±%.1f m".format(
                            point.latitude,
                            point.longitude,
                            point.accuracy
                        )
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    points = points.dropLast(1)
                    status = "Son nokta silindi"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = points.isNotEmpty() && !closed && !collecting
            ) {
                Text("SON NOKTAYI SİL")
            }
        }
    }

    @Composable
    private fun SurveyCanvas(
        points: List<SurveyPoint>,
        closed: Boolean,
        modifier: Modifier
    ) {
        val background = MaterialTheme.colorScheme.surfaceVariant
        val lineColor = MaterialTheme.colorScheme.primary
        val dotColor = MaterialTheme.colorScheme.error

        Box(
            modifier = modifier.background(background),
            contentAlignment = Alignment.Center
        ) {
            if (points.isEmpty()) {
                Text("Noktalar burada görünecek")
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    val minLat = points.minOf { it.latitude }
                    val maxLat = points.maxOf { it.latitude }
                    val minLon = points.minOf { it.longitude }
                    val maxLon = points.maxOf { it.longitude }

                    val lonRange = max(1e-9, maxLon - minLon)
                    val latRange = max(1e-9, maxLat - minLat)
                    val scaleX = size.width / lonRange.toFloat()
                    val scaleY = size.height / latRange.toFloat()
                    val scale = min(scaleX, scaleY) * 0.8f

                    val centerLon = (minLon + maxLon) / 2.0
                    val centerLat = (minLat + maxLat) / 2.0
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f

                    fun position(point: SurveyPoint): Offset {
                        return Offset(
                            centerX + ((point.longitude - centerLon) * scale).toFloat(),
                            centerY - ((point.latitude - centerLat) * scale).toFloat()
                        )
                    }

                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val position = position(point)
                        if (index == 0) {
                            path.moveTo(position.x, position.y)
                        } else {
                            path.lineTo(position.x, position.y)
                        }
                    }

                    if (closed) path.close()
                    drawPath(path, lineColor, style = Stroke(width = 5f))

                    points.forEach { point ->
                        drawCircle(dotColor, radius = 9f, center = position(point))
                    }
                }
            }
        }
    }

    private fun polygonArea(points: List<SurveyPoint>): Double {
        if (points.size < 3) return 0.0
        val radius = 6371008.8
        val latitude = Math.toRadians(points.map { it.latitude }.average())
        val coordinates = points.map {
            Pair(
                Math.toRadians(it.longitude) * radius * cos(latitude),
                Math.toRadians(it.latitude) * radius
            )
        }

        var sum = 0.0
        coordinates.indices.forEach { index ->
            val next = (index + 1) % coordinates.size
            sum += coordinates[index].first * coordinates[next].second -
                coordinates[next].first * coordinates[index].second
        }
        return abs(sum) / 2.0
    }

    private fun polygonPerimeter(points: List<SurveyPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += distance(points[index - 1], points[index])
        }
        total += distance(points.last(), points.first())
        return total
    }

    private fun distance(a: SurveyPoint, b: SurveyPoint): Double {
        val radius = 6371008.8
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = Math.toRadians(b.latitude - a.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2 * radius * asin(sqrt(h))
    }

    private fun formatArea(value: Double): String {
        return if (value >= 10000) {
            "%.4f ha (%.1f m²)".format(value / 10000, value)
        } else {
            "%.1f m²".format(value)
        }
    }

    private fun formatDistance(value: Double): String {
        return if (value >= 1000) {
            "%.3f km".format(value / 1000)
        } else {
            "%.2f m".format(value)
        }
    }
}
