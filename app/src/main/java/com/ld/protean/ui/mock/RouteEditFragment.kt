package com.ld.protean.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.ld.protean.R
import com.ld.protean.databinding.FragmentRouteEditBinding
import com.ld.protean.ext.jsonHistoricalRoutes
import com.ld.protean.ext.mapTileProvider
import com.ld.protean.ext.mapType
import com.ld.protean.map.MapTiles
import com.ld.protean.map.SystemLocationTracker
import com.ld.protean.map.addRouteLine
import com.ld.protean.map.addSelectionMarker
import com.ld.protean.map.applyMapStyle
import com.ld.protean.map.myLocationIcon
import com.ld.protean.map.probeTileSourceAsync
import com.ld.protean.map.resetOverlays
import com.ld.protean.map.selectedLocationIcon
import com.ld.protean.map.toGeoPoint
import com.ld.protean.map.toModelLatLon
import com.ld.protean.ui.mock.HistoricalRoute
import com.ld.protean.ui.viewmodel.HomeViewModel
import com.ld.protean.ui.viewmodel.MapViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.math.BigDecimal


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<MapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var lastPoint: Pair<Double, Double>? = null

    private var eventsOverlay: MapEventsOverlay? = null
    private var myLocationMarker: Marker? = null
    private var locationTracker: SystemLocationTracker? = null

    /** 首次拿到定位后自动把镜头移过去，之后不再打扰用户 */
    private var autoCentered = false


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)

        val mapView = binding.mapView
        mapViewModel.isExists = true
        mapViewModel.mapView = mapView

        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.minZoomLevel = MIN_ZOOM_LEVEL
        mapView.maxZoomLevel = MAX_ZOOM_LEVEL
        // osmdroid 默认中心是 (0,0)（大西洋），不显式设中心打开就是一片空白海面
        val hasLocation = mapViewModel.currentLocation != null
        mapView.controller.setCenter((mapViewModel.currentLocation ?: DEFAULT_CENTER).toGeoPoint())
        mapView.controller.setZoom(if (hasLocation) DEFAULT_ZOOM_LEVEL else FALLBACK_ZOOM_LEVEL)

        val initialStyle = context?.mapType ?: MapTiles.STYLE_STREET
        mapView.applyMapStyle(context?.mapTileProvider, initialStyle)

        // 底图自检：地图空白时用户看不出原因，这里取一张瓦片把结果写进日志，失败就提示换源
        val probeCenter = mapViewModel.currentLocation ?: DEFAULT_CENTER
        probeTileSourceAsync(
            context?.mapTileProvider, initialStyle,
            probeCenter.first, probeCenter.second, DEFAULT_ZOOM_LEVEL.toInt()
        )

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // 底图若是 GCJ-02，这里换回模型层 WGS-84
                mapViewModel.markedLoc = p.toModelLatLon()
                mapViewModel.showDetailView = false
                mapViewModel.reverseGeocode(p.latitude, p.longitude)
                markMap()
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                mapViewModel.markedLoc = p.toModelLatLon()
                mapViewModel.showDetailView = true
                mapViewModel.reverseGeocode(p.latitude, p.longitude)
                markMap()
                return true
            }
        }.also { eventsOverlay = MapEventsOverlay(it) }

        mapView.overlays.add(eventsOverlay)

        binding.mapTypeGroup.check(
            when (initialStyle) {
                MapTiles.STYLE_SATELLITE -> R.id.map_type_satellite
                else -> R.id.map_type_normal
            }
        )

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            routeEditViewModel.mFabOpened = true

            if (!routeEditViewModel.mFabOpened) {
                routeEditViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 1f
                    fab.scaleX = 1f
                    fab.scaleY = 1f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 20f + index * 8f)
                    translationX.duration = 200
                    animators.add(translationX)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                routeEditViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", 0f, -20f - index * 8f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
                    alpha.duration = 200
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach { it.visibility = View.GONE }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        // 系统定位取代百度 LocationClient
        locationTracker = SystemLocationTracker(requireContext()) { lat, lon, accuracy ->
            mapViewModel.currentLocation = lat to lon
            updateMyLocationMarker(lat, lon, accuracy)

            // 首次定位成功就把镜头移过去，否则用户看到的还是默认中心
            if (!autoCentered) {
                autoCentered = true
                if (mapViewModel.markedLoc == null) {
                    mapView.controller.animateTo((lat to lon).toGeoPoint())
                }
            }
        }.also { it.start() }

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.map_type_satellite -> MapTiles.STYLE_SATELLITE
                R.id.map_type_normal -> MapTiles.STYLE_STREET
                else -> {
                    Log.e("RouteEditFragment", "Unknown location view mode: $checkedId")
                    MapTiles.STYLE_STREET
                }
            }
            mapView.applyMapStyle(context?.mapTileProvider, style)
            context?.mapType = style
        }

        // 原实现读取的是百度地图的 mapStatus.target，这里对应 osmdroid 的 mapCenter
        // 注意要换回模型层 WGS-84，否则 GCJ-02 底图上画出来的路线会整体偏移
        mapView.setOnTouchListener { _, event ->
            if (isDrawing) {
                val currentPoint = mapView.mapCenter.let { MapTiles.toModel(it.latitude, it.longitude) }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { // 新增 DOWN 事件处理
                        if (mPoints.size <= 0) {
                            mPoints.add(currentPoint)
                        }
                        lastPoint = currentPoint
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (lastPoint == null) {
                            lastPoint = currentPoint
                        }
                        lastPoint?.let { lp -> drawLine(lp, currentPoint) }
                    }

                    MotionEvent.ACTION_UP -> {
                        mPoints.add(currentPoint)
                        lastPoint = null // 关键修改：重置起点
                    }
                }
            }
            false
        }

        binding.fabStart.setOnClickListener {
            isDrawing = true;
            mPoints = arrayListOf()
            lastPoint = null; // 重置上一个点
        }

        binding.fabRollback.setOnClickListener {
            // 撤回上一个点并且刷新地图
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            val current = mapViewModel.currentLocation
            if (current == null) {
                Toast.makeText(requireContext(), "尚未获取到当前位置", Toast.LENGTH_SHORT).show()
            } else {
                mapView.controller.animateTo(current.toGeoPoint())
            }
        }

        return binding.root
    }

    private fun updateMyLocationMarker(lat: Double, lon: Double, accuracy: Float) {
        val mapView = _binding?.mapView ?: return
        val marker = myLocationMarker ?: Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mapView.overlays.add(this)
            myLocationMarker = this
        }
        marker.icon = requireContext().myLocationIcon()
        marker.position = (lat to lon).toGeoPoint()
        // 精度写进标题，点一下标记就能看到；网络定位通常是百米级，室内往往拿不到 GPS
        marker.title = "我的位置 ±${accuracy.toInt()}m"
        marker.snippet = "%.6f, %.6f".format(lat, lon)
        mapView.invalidate()
    }

    private fun refresh() {
        val mapView = _binding?.mapView ?: return
        // 清除之前的所有覆盖物（保留点击监听与我的位置）
        mapView.resetOverlays(eventsOverlay, myLocationMarker)

        // 绘制之前记录的点到点的线
        mapView.addRouteLine(mPoints)
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val mapView = _binding?.mapView ?: return
        // 清除之前的所有覆盖物（保留点击监听与我的位置）
        mapView.resetOverlays(eventsOverlay, myLocationMarker)

        // 绘制之前记录的点到点的线
        mapView.addRouteLine(mPoints)

        mapView.addRouteLine(listOf(start, end))
    }


    private fun markMap(moveEyes: Boolean = false) {
        val mapView = _binding?.mapView ?: return
        val markedLoc = mapViewModel.markedLoc ?: return
        val point = markedLoc.toGeoPoint()

        mapView.resetOverlays(eventsOverlay, myLocationMarker)
        mapView.addSelectionMarker(
            point,
            mapViewModel.mMapIndicator ?: requireContext().selectedLocationIcon()
        )

        if (moveEyes) {
            mapView.controller.animateTo(point)
        }
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        editRoute.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editRoute.error = "路线经纬度不能为空"
            } else {
                try {
                    val json = it.toString()
                    // 转为 LatLng 数组
                    val points = JSON.parseArray(json)
                    if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    }
                    // 循环检查每个点的经纬度是否合法
                    for (point in points) {
                        val jsonObject = point as JSONObject
                        val latitude = jsonObject.getDouble("first")
                        val longitude = jsonObject.getDouble("second")
                        if (!checkLatLon(latitude, longitude)) {
                            editRoute.error = "路线经纬度格式错误"
                            return@addTextChangedListener
                        }
                    }
                } catch (e: Exception) {
                    editRoute.error = "路线经纬度json格式错误"
                }
            }
        }

        editRoute.setText(JSON.toJSONString(mPoints))

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val routeJson = editRoute.text.toString()

                var name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val points = JSON.parseArray(routeJson)
                if (points.size < 2) {
                    Toast.makeText(requireContext(), "路线经纬度至少需要两个点", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                // 循环检查每个点的经纬度是否合法
                for (point in points) {
                    val jsonObject = point as JSONObject
                    val latitude = jsonObject.getDouble("first")
                    val longitude = jsonObject.getDouble("second")
                    if (!checkLatLon(latitude, longitude)) {
                        Toast.makeText(requireContext(), "路线经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                }

                fun MutableSet<String>.addLocation(
                    name: String,
                    address: String,
                    lat: Double,
                    lon: Double
                ): Boolean {
                    if (any { it.split(",")[0] == name }) {
                        return false
                    }
                    add(
                        "$name,$address,${
                            BigDecimal.valueOf(lat).toPlainString()
                        },${BigDecimal.valueOf(lon).toPlainString()}"
                    )
                    return true
                }

                val route = JSON.toJSONString(points)
                with(requireContext()) {
                    val routes = jsonHistoricalRoutes
                    val jsonArray: JSONArray = if (routes.isNotEmpty()) {
                        JSON.parseArray(routes)
                    } else {
                        JSONArray()
                    }
                    val historicalRoute = HistoricalRoute(name, mPoints)
                    jsonArray.add(historicalRoute)
                    jsonArray.toJSONString().also {
                        jsonHistoricalRoutes = it
                    }
                }

                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()

        return true
    }

    override fun onResume() {
        super.onResume()

        _binding?.mapView?.onResume()
        locationTracker?.start()
    }

    override fun onPause() {
        super.onPause()

        _binding?.mapView?.onPause()
        locationTracker?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        locationTracker?.stop()
        locationTracker = null
        mapViewModel.isExists = false
        myLocationMarker = null
        _binding?.mapView?.onDetach()
        _binding = null
    }

    companion object {
        private const val MIN_ZOOM_LEVEL = 3.0
        private const val MAX_ZOOM_LEVEL = 19.0
        private const val DEFAULT_ZOOM_LEVEL = 17.0

        /** 还没拿到定位时的兜底缩放：太小看不出街区，太大又不像地图 */
        private const val FALLBACK_ZOOM_LEVEL = 12.0

        /** 定位不可用时的兜底中心（北京），总比 osmdroid 默认的 (0,0) 大西洋好 */
        private val DEFAULT_CENTER = 39.9042 to 116.4074
    }
}
