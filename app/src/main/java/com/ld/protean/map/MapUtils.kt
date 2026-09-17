package com.ld.protean.map

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.ld.protean.R
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/** 路线折线颜色，与原实现保持一致 */
private val ROUTE_LINE_COLOR = Color.argb(178, 0, 78, 255)
private const val ROUTE_LINE_WIDTH = 10f

/**
 * 模型层坐标（WGS-84）转成底图坐标。
 *
 * 底图若是 GCJ-02（高德等国内地图），这里会做偏移换算；底图是 WGS-84 时原样返回。
 * 所有「把经纬度画到地图上」的地方都必须走这里，否则标点会偏几百米。
 */
fun Pair<Double, Double>.toGeoPoint(): GeoPoint {
    val (lat, lon) = MapTiles.toDisplay(first, second)
    return GeoPoint(lat, lon)
}

fun GeoPoint.toLatLon(): Pair<Double, Double> = latitude to longitude

/** 地图上的点换回模型层 WGS-84（用户点选底图时用） */
fun GeoPoint.toModelLatLon(): Pair<Double, Double> = MapTiles.toModel(latitude, longitude)

fun Context.selectedLocationIcon(): Drawable? =
    ContextCompat.getDrawable(this, R.drawable.icon_selected_location_16)

fun Context.myLocationIcon(): Drawable? =
    ContextCompat.getDrawable(this, R.drawable.icon_my_location)

/**
 * 清除覆盖物，但保留 [keep] 中传入的覆盖物。
 *
 * 注意：osmdroid 的点击/长按监听是挂在 MapEventsOverlay 上的，它本身也是一个覆盖物，
 * 所以不能像原来的 `baiduMap.clear()` 那样无脑清空，否则监听会一起消失。
 */
fun MapView.resetOverlays(vararg keep: Overlay?) {
    overlays.clear()
    keep.filterNotNull().forEach { overlays.add(it) }
    invalidate()
}

fun MapView.addSelectionMarker(point: GeoPoint, icon: Drawable?): Marker {
    val marker = Marker(this).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        if (icon != null) this.icon = icon
    }
    overlays.add(marker)
    invalidate()
    return marker
}

fun MapView.addRouteLine(points: List<Pair<Double, Double>>): Polyline? {
    if (points.size < 2) return null
    val line = Polyline(this).apply {
        outlinePaint.color = ROUTE_LINE_COLOR
        outlinePaint.strokeWidth = ROUTE_LINE_WIDTH
        setPoints(points.map { it.toGeoPoint() })
    }
    overlays.add(line)
    invalidate()
    return line
}
