package com.ld.protean.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 用系统 LocationManager 取代百度的 LocationClient。
 *
 * 系统定位返回的就是 WGS84，和 osmdroid 的瓦片坐标系一致，因此不需要任何坐标偏移转换
 * （原实现要走百度 native 的 `Jni.coorEncrypt` 做 GCJ02 转换，现在整条依赖都不需要了）。
 *
 * 精度策略（对应「定位不准」的问题）：
 * 1. 先用最后已知位置把地图点亮。它可能是一小时前的网络定位（精度百米级），会被后续定位覆盖；
 * 2. 主动要一次新鲜定位（API 30+ 的 getCurrentLocation），比被动等回调快得多；
 * 3. 同时监听 fused / gps / network。fused 由系统的融合定位提供，通常比单独用 network 更准；
 * 4. 用精度 + 新鲜度做去抖，避免一个差的定位覆盖掉刚拿到的好定位。
 *
 * 房间内 GPS 常常一个 fix 都拿不到（`getLastKnownLocation(GPS)` 返回 null），
 * 这种情况只能靠 fused/network，精度就是百米级，属于系统能力上限而非代码问题。
 */
class SystemLocationTracker(
    context: Context,
    private val onLocation: (lat: Double, lon: Double, accuracy: Float) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var started = false

    /** 当前采用的最优定位，用于精度去抖 */
    private var bestLocation: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            consider(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = locationManager
        if (started || manager == null) return
        started = true

        runCatching {
            val providers = usableProviders(manager)

            // 1. 最后已知位置：取最新的一条，先让地图有内容
            providers
                .mapNotNull { lastKnown(manager, it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { consider(it, force = true) }

            // 2. 主动请求一次当前定位（API 30+），室内等被动回调可能永远等不到
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                providers.forEach { provider ->
                    runCatching {
                        manager.getCurrentLocation(provider, null, appContext.mainExecutor) { location ->
                            location?.let(::consider)
                        }
                    }.onFailure { Log.w(TAG, "getCurrentLocation($provider) failed", it) }
                }
            }

            // 3. 持续监听
            providers.forEach { provider ->
                manager.requestLocationUpdates(
                    provider, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
                )
            }
        }.onFailure {
            Log.e(TAG, "Failed to start location updates", it)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager?.removeUpdates(listener) }
            .onFailure { Log.e(TAG, "Failed to stop location updates", it) }
    }

    /** 精度最差也保留的定位；再差的基本是基站级别的粗定位，画面会明显偏移 */
    private fun consider(location: Location, force: Boolean = false) {
        val previous = bestLocation
        val previousAgeMs = previous?.let { ageMs(it) }

        val accepted = when {
            force || previous == null -> true
            // 精度更好（或相当）就用它
            location.accuracy <= previous.accuracy -> true
            // 上一个定位已经过去一段时间了，新的即使差一点也更有时效性
            previousAgeMs != null && previousAgeMs > MIN_REPLACE_AGE_MS -> true
            else -> false
        }

        if (!accepted) {
            Log.d(
                TAG,
                "skip provider=${location.provider} acc=${location.accuracy} " +
                    "(keep acc=${previous?.accuracy} ageMs=$previousAgeMs)"
            )
            return
        }

        if (location.accuracy > MAX_USABLE_ACCURACY_M) {
            Log.w(TAG, "coarse fix provider=${location.provider} acc=${location.accuracy}")
        }

        bestLocation = location
        Log.i(
            TAG,
            "fix provider=${location.provider} lat=${location.latitude} " +
                "lon=${location.longitude} acc=${location.accuracy} ageMs=${ageMs(location)}"
        )
        onLocation(location.latitude, location.longitude, location.accuracy)
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /** fused 优先（若系统支持），其次是 gps、network */
    @SuppressLint("MissingPermission")
    private fun usableProviders(manager: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { provider ->
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }.ifEmpty {
        listOf(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, provider: String): Location? =
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()

    companion object {
        private const val TAG = "SystemLocationTracker"

        private const val UPDATE_INTERVAL_MS = 1000L

        /** 上一个定位超过这个时长，就允许被精度更差的新定位覆盖 */
        private const val MIN_REPLACE_AGE_MS = 10_000L

        private const val MAX_USABLE_ACCURACY_M = 2000f
    }
}
