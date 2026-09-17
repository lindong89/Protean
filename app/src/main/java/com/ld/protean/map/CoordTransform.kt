package com.ld.protean.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 <-> GCJ-02（火星坐标）互转。
 *
 * 国内地图（高德/腾讯/百度）的瓦片是 GCJ-02 偏移坐标，而系统定位、模拟定位、Photon
 * 逆地理编码用的都是 WGS-84。两者直接混用会让标点和底图错开五六百米（实测厦门 583 米），
 * 所以显示前必须换算。
 *
 * 用的是公开的标准实现（eviltransform 等库同款），已用参考向量校验：
 * wgs84ToGcj02(31.1774276, 121.5272106) = (31.17530398364597, 121.531541859215)
 */
object CoordTransform {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val PI_DEG = PI

    /**
     * 中国大陆粗略范围以外不做偏移（境外地图本来就没有 GCJ-02 偏移）。
     *
     * 这是一个「大矩形」启发式，与 eviltransform 等参考实现保持一致，代价是它把部分
     * 东南亚（例如新加坡）也算作境内。这对本应用没有实际影响：偏移只在用国内底图时启用，
     * 而国内底图在这些区域的瓦片本身就是偏移坐标系。
     */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon

        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI_DEG
        val magic = 1 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)

        val mgLat = lat + (dLat * 180.0) / (A * (1 - EE) / (magic * sqrtMagic) * PI_DEG)
        val mgLon = lon + (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI_DEG)
        return mgLat to mgLon
    }

    /**
     * 反解没有解析式，用迭代逼近：几次就能收敛到 1e-9 度（约 0.1 毫米）以内。
     */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon

        var wgsLat = lat
        var wgsLon = lon
        repeat(ITERATIONS) {
            val (gcjLat, gcjLon) = wgs84ToGcj02(wgsLat, wgsLon)
            wgsLat += lat - gcjLat
            wgsLon += lon - gcjLon
        }
        return wgsLat to wgsLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI_DEG) + 20.0 * sin(2.0 * x * PI_DEG)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI_DEG) + 40.0 * sin(y / 3.0 * PI_DEG)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI_DEG) + 320.0 * sin(y * PI_DEG / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI_DEG) + 20.0 * sin(2.0 * x * PI_DEG)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI_DEG) + 40.0 * sin(x / 3.0 * PI_DEG)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI_DEG) + 300.0 * sin(x / 30.0 * PI_DEG)) * 2.0 / 3.0
        return ret
    }

    private const val ITERATIONS = 5
}
