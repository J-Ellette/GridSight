package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

enum class MapTileLayer(val label: String, val shortCode: String) {
    SATELLITE("Satellite", "SAT")
}

object SlippyMapMath {
    const val TILE_SIZE_PX = 256.0

    fun lonToTileX(lon: Double, zoom: Int): Double {
        val n = 2.0.pow(zoom)
        return ((lon + 180.0) / 360.0) * n
    }

    fun latToTileY(lat: Double, zoom: Int): Double {
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clampedLat)
        val n = 2.0.pow(zoom)
        return (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
    }

    fun tileXToLon(x: Double, zoom: Int): Double {
        val n = 2.0.pow(zoom)
        return x / n * 360.0 - 180.0
    }

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = 2.0.pow(zoom)
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))
        return Math.toDegrees(latRad)
    }

    fun zoomScaleToTileZoom(zoomScale: Float): Int {
        // Base zoom 15 (~street block scale); scale 0.5..4.0 maps to z=14..17
        val delta = floor(ln(zoomScale.toDouble().coerceAtLeast(0.25)) / ln(2.0)).toInt()
        return (15 + delta).coerceIn(12, 18)
    }
}

class OfflineTileCache(context: Context) {
    private val cacheRoot = File(context.cacheDir, "map_tiles").apply { mkdirs() }
    private val memoryCache = LruCache<String, ImageBitmap>(120)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun getMemoryCached(layer: MapTileLayer, z: Int, x: Int, y: Int): ImageBitmap? {
        return memoryCache.get("${layer.name}_${z}_${x}_${y}")
    }

    suspend fun loadTile(layer: MapTileLayer, z: Int, x: Int, y: Int): ImageBitmap? {
        val maxIndex = (1 shl z)
        val wrappedX = ((x % maxIndex) + maxIndex) % maxIndex
        if (y < 0 || y >= maxIndex) return null

        val key = "${layer.name}_${z}_${wrappedX}_${y}"
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val diskFile = File(cacheRoot, "$key.png")
            if (diskFile.exists() && diskFile.length() > 0L) {
                val bmp = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
                if (bmp != null) {
                    val img = bmp.asImageBitmap()
                    memoryCache.put(key, img)
                    return@withContext img
                }
            }

            val url = buildTileUrl(layer, z, wrappedX, y)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GridSight-Android-HUD/1.0 (offline-first tactical map)")
                .build()

            val bytes = runCatching {
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            }.getOrNull() ?: return@withContext null

            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                ?: return@withContext null
            runCatching { diskFile.writeBytes(bytes) }
            val img = bmp.asImageBitmap()
            memoryCache.put(key, img)
            img
        }
    }

    private fun buildTileUrl(layer: MapTileLayer, z: Int, x: Int, y: Int): String {
        return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }
}
