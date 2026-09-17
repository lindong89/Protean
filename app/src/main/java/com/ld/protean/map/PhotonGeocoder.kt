package com.ld.protean.map

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 基于 Photon（komoot 提供的开源地理编码服务）的关键字搜索与逆地理编码，
 * 用来替代百度的 SuggestionSearch / GeoCoder，不需要任何 API Key。
 *
 * 说明：
 * - 返回的坐标是 WGS84，与 osmdroid / 系统定位一致，不需要坐标转换；
 * - Photon 的 lang 参数只支持 default/de/en/fr，不支持 zh，因此不传；
 * - 该服务属公益服务，请勿高频调用（调用方已做输入防抖）。
 */
object PhotonGeocoder {
    private const val BASE_URL = "https://photon.komoot.io"
    private const val USER_AGENT = "Protean/1.0 (Android)"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 关键字搜索。
     * @param near 当前位置（可选），用于让结果偏向附近
     */
    suspend fun search(
        query: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 8,
    ): List<Poi> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(BASE_URL).append("/api/?q=")
            append(URLEncoder.encode(query, "UTF-8"))
            append("&limit=").append(limit)
            if (near != null) {
                append("&lat=").append(near.first).append("&lon=").append(near.second)
            }
        }
        requestFeatures(url).mapNotNull { feature ->
            runCatching { feature.toPoi(near) }.getOrNull()
        }
    }

    /**
     * 逆地理编码：坐标 -> 文字地址。
     */
    suspend fun reverse(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/reverse?lat=$lat&lon=$lon&limit=1"
        requestFeatures(url).firstOrNull()?.let { feature ->
            runCatching {
                describe(feature.getJSONObject("properties") ?: JSONObject())
            }.getOrNull()
        }
    }

    private fun requestFeatures(url: String): List<JSONObject> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val features = JSON.parseObject(body)?.getJSONArray("features") ?: return@use emptyList()
                (0 until features.size).mapNotNull { features.getJSONObject(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toPoi(near: Pair<Double, Double>?): Poi {
        val coordinates = getJSONObject("geometry")?.getJSONArray("coordinates")
            ?: throw IllegalArgumentException("no coordinates")
        val lon = coordinates.getDoubleValue(0)
        val lat = coordinates.getDoubleValue(1)

        val props = getJSONObject("properties") ?: JSONObject()
        val name = props.getString("name")
            ?: props.getString("street")
            ?: props.getString("city")
            ?: "未知地点"
        val address = describe(props)
        val tag = props.getString("osm_value") ?: props.getString("type") ?: ""

        val poi = Poi(name, address, lon, lat, tag)
        if (near != null) {
            val distance = poi.distanceTo(near.first, near.second)
            poi.address = if (distance < 1000) {
                "${distance.toInt()}m $address"
            } else {
                "${(distance / 1000.0).toString().take(4)}km $address"
            }
        }
        return poi
    }

    private fun describe(props: JSONObject): String {
        val parts = listOf("country", "state", "city", "district", "locality", "street", "housenumber", "postcode")
            .mapNotNull { props.getString(it)?.takeIf { value -> value.isNotBlank() } }
            .distinct()
        return parts.joinToString(" ").ifBlank { "未知地址" }
    }
}
