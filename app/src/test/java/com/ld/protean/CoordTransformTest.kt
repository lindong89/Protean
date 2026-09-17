package com.ld.protean

import com.ld.protean.map.CoordTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * GCJ-02 偏移换算的回归测试。
 *
 * 这层换算错了不会崩，只会让标点悄悄偏几百米，所以必须用公开的标准参考向量钉住。
 */
class CoordTransformTest {

    /** googollee/eviltransform 文档中的参考向量（上海市中心附近） */
    @Test
    fun wgs84ToGcj02MatchesReferenceVector() {
        val (lat, lon) = CoordTransform.wgs84ToGcj02(31.1774276, 121.5272106)

        assertEquals(31.17530398364597, lat, 1e-9)
        assertEquals(121.531541859215, lon, 1e-9)
    }

    @Test
    fun roundTripRecoversOriginal() {
        val samples = listOf(
            24.6440992 to 118.0185591, // 厦门
            39.90869 to 116.39750,     // 天安门
            22.5431 to 114.0579,       // 深圳
        )

        samples.forEach { (lat, lon) ->
            val (gcjLat, gcjLon) = CoordTransform.wgs84ToGcj02(lat, lon)
            val (wgsLat, wgsLon) = CoordTransform.gcj02ToWgs84(gcjLat, gcjLon)

            assertEquals(lat, wgsLat, 1e-9)
            assertEquals(lon, wgsLon, 1e-9)
        }
    }

    @Test
    fun outsideChinaIsNotShifted() {
        // 注意：境外判定用的是「大矩形」启发式（与 eviltransform 等参考实现一致），
        // 矩形覆盖了部分东南亚，比如新加坡也会被判定为境内而被偏移。
        // 这里只取明确在矩形外的点。
        listOf(40.7128 to -74.0060, 35.6895 to 139.6917, 51.5074 to -0.1278).forEach { (lat, lon) ->
            val (outLat, outLon) = CoordTransform.wgs84ToGcj02(lat, lon)

            assertEquals(lat, outLat, 0.0)
            assertEquals(lon, outLon, 0.0)
        }
    }

    @Test
    fun chinaOffsetIsHundredsOfMeters() {
        val (lat, lon) = CoordTransform.wgs84ToGcj02(24.6440992, 118.0185591)
        val meters = hypot(
            (lat - 24.6440992) * 111_320.0,
            (lon - 118.0185591) * 111_320.0 * cos(Math.toRadians(24.6440992))
        )

        assertTrue("偏移量 $meters 米不在预期范围内", meters in 300.0..800.0)
    }
}
