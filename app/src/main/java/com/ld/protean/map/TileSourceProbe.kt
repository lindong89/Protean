package com.ld.protean.map

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 底图连通性自检。
 *
 * 底图加载失败在界面上只表现为「一片空白」，osmdroid 自己既不报错也不提示，
 * 用户没法判断是网络问题、瓦片源问题还是坐标问题。这里用应用自己的网络栈
 * （和真正下载瓦片时同一套 TLS/代理设置）取一张瓦片，把结果记到日志并回传给界面，
 * 失败时提示用户换源 —— 排查「地图显示不出来」时这一条日志就够了。
 */
object TileSourceProbe {
    private const val TAG = "MapTileProbe"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000

    /** 小于这个字节数基本不是有效瓦片 */
    private const val MIN_VALID_BYTES = 200

    data class Result(val ok: Boolean, val message: String) {
        val logLine: String get() = "${if (ok) "OK" else "FAIL"} $message"
    }

    /**
     * 取一张瓦片验证可用性。[lat]/[lon] 传底图坐标系下的经纬度（GCJ-02 底图要先偏移）。
     */
    fun probe(set: MapTiles.TileSet, lat: Double, lon: Double, zoom: Int): Result {
        val z = zoom.coerceIn(set.minZoom, set.maxZoom)
        val (x, y) = slippyTile(lat, lon, z)
        val subdomain = set.subdomains.firstOrNull() ?: ""
        val url = set.url(z, x, y, subdomain)

        val start = SystemClock.elapsedRealtime()
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
                Configuration.getInstance().userAgentValue?.let {
                    setRequestProperty("User-Agent", it)
                }
            }

            try {
                val code = connection.responseCode
                val bytes = if (code in 200..299) {
                    connection.inputStream.use { it.readBytes().size }
                } else {
                    -1
                }
                val cost = SystemClock.elapsedRealtime() - start
                val ok = code in 200..299 && bytes >= MIN_VALID_BYTES

                Result(ok, "z=$z/$x/$y HTTP $code ${bytes.coerceAtLeast(0)}B ${cost}ms")
            } finally {
                runCatching { connection.disconnect() }
            }
        } catch (t: Throwable) {
            val cost = SystemClock.elapsedRealtime() - start
            Result(false, "z=$z/$x/$y ${t.javaClass.simpleName}: ${t.message} (${cost}ms)")
        }
    }

    fun log(set: MapTiles.TileSet, result: Result) {
        Log.i(TAG, "${set.name} ${result.logLine}")
    }

    /** 标准 Web 墨卡托瓦片编号 */
    private fun slippyTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)

        return x to y
    }
}

/**
 * 在 Fragment 里跑一次底图连通性自检：结果写日志，失败时提示用户可以换源。
 *
 * [modelLat]/[modelLon] 传模型层 WGS-84 坐标（通常用地图当前中心或设备位置）。
 */
fun Fragment.probeTileSourceAsync(
    providerId: String?,
    style: Int,
    modelLat: Double,
    modelLon: Double,
    zoom: Int,
) {
    val set = MapTiles.tileSet(providerId, style)
    val appContext = context?.applicationContext ?: return

    viewLifecycleOwner.lifecycleScope.launch {
        val (lat, lon) = MapTiles.toDisplay(modelLat, modelLon)
        val result = withContext(Dispatchers.IO) { TileSourceProbe.probe(set, lat, lon, zoom) }

        TileSourceProbe.log(set, result)

        if (!result.ok) {
            Toast.makeText(
                appContext,
                "底图「${set.name}」加载失败：${result.message}\n可在 设置 → 地图源 更换底图",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
