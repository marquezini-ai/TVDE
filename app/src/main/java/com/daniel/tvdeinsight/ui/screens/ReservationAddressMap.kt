package com.daniel.tvdeinsight.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Mapa nativo: evita o WebView e mostra uma imagem real do OpenStreetMap. */
@Composable
internal fun ReservationAddressMap(address: String, radiusKm: Int, modifier: Modifier = Modifier) {
    var location by remember(address) { mutableStateOf<MapLocation?>(null) }
    var status by remember(address) { mutableStateOf(if (address.trim().length < 5) "Insira uma morada para a localizar no mapa." else "A localizar morada…") }
    // A imagem não depende do valor exato do raio: só precisa de ser renovada quando o zoom muda.
    // Assim, ao arrastar a barra não se apaga o mapa que já estava visível.
    var graphic by remember(address) { mutableStateOf<MapGraphic?>(null) }
    var mapLoading by remember(address) { mutableStateOf(false) }
    var mapError by remember(address) { mutableStateOf<String?>(null) }
    val safeRadiusKm = radiusKm.coerceIn(0, 10)

    LaunchedEffect(address) {
        if (address.trim().length < 5) {
            location = LISBON
            status = "Insira uma morada para a localizar no mapa."
        } else {
            val found = withContext(Dispatchers.IO) { geocode(address) }
            location = found ?: LISBON
            status = if (found == null) "Morada não encontrada — a mostrar mapa de Lisboa." else address
        }
    }
    val zoom = location?.let { zoomForRadius(it.latitude, safeRadiusKm) } ?: 10
    LaunchedEffect(location, zoom) {
        val current = location ?: return@LaunchedEffect
        mapLoading = true
        mapError = null
        val loaded = withContext(Dispatchers.IO) { loadMap(current, zoom) }
        if (loaded != null) graphic = loaded
        else if (graphic == null) mapError = "Não foi possível carregar o mapa. Verifique a ligação à internet."
        else mapError = "Não foi possível atualizar o mapa. A mostrar a última vista disponível."
        mapLoading = false
    }

    Box(
        modifier = modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(AndroidColor.TRANSPARENT) } },
            update = { image -> image.setImageBitmap(graphic?.bitmap) }
        )
        val currentGraphic = graphic
        val currentLocation = location
        if (currentGraphic != null && currentLocation != null) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * currentGraphic.markerX, size.height * currentGraphic.markerY)
                val metersPerImagePixel = metersPerPixel(currentLocation.latitude, currentGraphic.zoom)
                val radiusMeters = safeRadiusKm * 1_000f
                if (radiusMeters > 0f) {
                    val radiusX = radiusMeters / metersPerImagePixel / currentGraphic.sourceWidth * size.width
                    val radiusY = radiusMeters / metersPerImagePixel / currentGraphic.sourceHeight * size.height
                    drawOval(color = Color(0x1A42A5F5), topLeft = Offset(center.x - radiusX, center.y - radiusY), size = Size(radiusX * 2f, radiusY * 2f))
                    drawOval(color = Color(0xFF1565C0), topLeft = Offset(center.x - radiusX, center.y - radiusY), size = Size(radiusX * 2f, radiusY * 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                }
                drawCircle(Color(0xFFC62828), radius = 8.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), shape = RoundedCornerShape(7.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)
        ) {
            Text(
                mapError ?: if (mapLoading) "A atualizar mapa…" else status,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp
            )
        }
    }
}

private data class MapLocation(val latitude: Double, val longitude: Double)
private data class MapGraphic(val bitmap: Bitmap, val markerX: Float, val markerY: Float, val sourceWidth: Float, val sourceHeight: Float, val zoom: Int)
private val LISBON = MapLocation(38.7223, -9.1393)

private fun geocode(address: String): MapLocation? {
    val query = java.net.URLEncoder.encode(address.trim(), "UTF-8").replace("+", "%20")
    return geocodeNominatim(query, portugalOnly = true)
        ?: geocodeNominatim(query, portugalOnly = false)
        ?: geocodeArcGis(query)
}

private fun geocodeNominatim(query: String, portugalOnly: Boolean): MapLocation? = runCatching {
    val scope = if (portugalOnly) "&countrycodes=pt" else ""
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=3&accept-language=pt-PT$scope&q=$query"
    openConnection(url).inputStream.bufferedReader().use { reader ->
        val rows = JSONArray(reader.readText())
        rows.optJSONObject(0)?.let { MapLocation(it.optDouble("lat"), it.optDouble("lon")) }
    }
}.getOrNull()

private fun geocodeArcGis(query: String): MapLocation? = runCatching {
    val url = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates?f=json&maxLocations=1&singleLine=$query"
    openConnection(url).inputStream.bufferedReader().use { reader ->
        val candidate = org.json.JSONObject(reader.readText()).optJSONArray("candidates")?.optJSONObject(0)
        candidate?.optJSONObject("location")?.let { point -> MapLocation(point.optDouble("y"), point.optDouble("x")) }
    }
}.getOrNull()

private fun loadMap(location: MapLocation, zoom: Int): MapGraphic? = loadTileFallback(location, zoom)

private fun loadTileFallback(location: MapLocation, zoom: Int): MapGraphic? = runCatching {
    val scale = 2.0.pow(zoom)
    val x = (location.longitude + 180.0) / 360.0 * scale
    val latRadians = location.latitude * PI / 180.0
    val y = (1.0 - ln(kotlin.math.tan(latRadians) + 1.0 / cos(latRadians)) / PI) / 2.0 * scale
    val globalPixelX = x * TILE_SIZE
    val globalPixelY = y * TILE_SIZE
    val cropLeft = floor(globalPixelX - MAP_WIDTH / 2.0).toInt()
    val cropTop = floor(globalPixelY - MAP_HEIGHT / 2.0).toInt()
    val firstTileX = floor(cropLeft / TILE_SIZE.toDouble()).toInt()
    val firstTileY = floor(cropTop / TILE_SIZE.toDouble()).toInt()
    val lastTileX = floor((cropLeft + MAP_WIDTH - 1) / TILE_SIZE.toDouble()).toInt()
    val lastTileY = floor((cropTop + MAP_HEIGHT - 1) / TILE_SIZE.toDouble()).toInt()
    val map = Bitmap.createBitmap(MAP_WIDTH, MAP_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(map)
    for (tileY in firstTileY..lastTileY) for (tileX in firstTileX..lastTileX) {
        val tile = loadOsmTile(zoom, wrapTile(tileX, scale.toInt()), tileY.coerceIn(0, scale.toInt() - 1)) ?: return null
        canvas.drawBitmap(tile, (tileX * TILE_SIZE - cropLeft).toFloat(), (tileY * TILE_SIZE - cropTop).toFloat(), null)
    }
    MapGraphic(map, .5f, .5f, MAP_WIDTH.toFloat(), MAP_HEIGHT.toFloat(), zoom)
}.getOrNull()

private fun loadOsmTile(zoom: Int, x: Int, y: Int): Bitmap? {
    val key = "$zoom/$x/$y"
    synchronized(tileCache) { tileCache[key]?.let { return it } }
    var bitmap: Bitmap? = null
    repeat(3) { attempt ->
        if (bitmap == null) {
            bitmap = decodeBitmap("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            if (bitmap == null && attempt < 2) Thread.sleep(250L)
        }
    }
    val loadedBitmap = bitmap ?: return null
    synchronized(tileCache) {
        tileCache[key] = loadedBitmap
        while (tileCache.size > 48) tileCache.remove(tileCache.entries.first().key)
    }
    return loadedBitmap
}

private fun decodeBitmap(url: String): Bitmap? = runCatching { openConnection(url).inputStream.use(BitmapFactory::decodeStream) }.getOrNull()

private fun openConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 10_000; readTimeout = 12_000
    setRequestProperty("User-Agent", "TVDEInsight/0.5.10 (Android)")
    setRequestProperty("Accept-Language", "pt-PT,pt;q=0.9")
}

private fun zoomForRadius(latitude: Double, radiusKm: Int): Int {
    // A circunferência ocupa ~72% da altura e deixa margem visível em volta.
    val desiredMetersPerPixel = radiusKm.coerceAtLeast(1) * 1_000.0 * 2.78 / MAP_HEIGHT
    val raw = ln(156543.03392 * cos(latitude * PI / 180.0) / desiredMetersPerPixel) / ln(2.0)
    return floor(raw).toInt().coerceIn(5, 16)
}

private fun metersPerPixel(latitude: Double, zoom: Int): Float =
    (156543.03392 * cos(latitude * PI / 180.0) / 2.0.pow(zoom)).toFloat()

private fun wrapTile(value: Int, maximum: Int): Int = ((value % maximum) + maximum) % maximum
private const val TILE_SIZE = 256
private const val MAP_WIDTH = 640
private const val MAP_HEIGHT = 400
private val tileCache = LinkedHashMap<String, Bitmap>()
