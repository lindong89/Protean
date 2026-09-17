package com.ld.protean.ui.viewmodel

import android.app.Notification
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.ld.protean.map.PhotonGeocoder
import org.osmdroid.views.MapView

/**
 * 原 BaiduMapViewModel：去掉百度地图/定位 SDK 对象，只保留跨页面共享的状态。
 *
 * 坐标约定：内部一律用 WGS84（first=latitude, second=longitude），
 * 与 osmdroid 的瓦片坐标系、以及系统定位输出保持一致，因此不再需要 GCJ02 转换。
 */
class MapViewModel : ViewModel() {
    var isExists = false
    lateinit var mapView: MapView

    /** 当前位置（WGS84） */
    var currentLocation: Pair<Double, Double>? = null

    /** 逆地理编码得到的地址名 */
    var markName: String? = null

    /** 标点位置（WGS84），first=latitude，second=longitude */
    var markedLoc: Pair<Double, Double>? = null

    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /** 是否跟随定位视角（等价于原先的 FOLLOWING 模式） */
    var followLocation = false

    var mMapIndicator: Drawable? = null

    /** 逆地理编码完成后的回调，由 MainActivity 注册，用于刷新信息窗 */
    var onReverseGeocode: ((Pair<Double, Double>) -> Unit)? = null

    fun reverseGeocode(lat: Double, lon: Double) {
        viewModelScope.launch {
            val address = runCatching { PhotonGeocoder.reverse(lat, lon) }.getOrNull()
            if (!address.isNullOrBlank()) markName = address
            onReverseGeocode?.invoke(lat to lon)
        }
    }
}
