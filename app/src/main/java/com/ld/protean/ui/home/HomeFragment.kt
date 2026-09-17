package com.ld.protean.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.ld.protean.R
import com.ld.protean.databinding.FragmentHomeBinding
import com.ld.protean.ext.mapTileProvider
import com.ld.protean.ext.mapType
import com.ld.protean.ext.rawHistoricalLocations
import com.ld.protean.ext.selectRoute
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
import com.ld.protean.ui.viewmodel.HomeViewModel
import com.ld.protean.ui.viewmodel.MapViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.math.BigDecimal

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val homeViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<MapViewModel>()

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
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Fixed the issue that the Fab was opening incorrectly after switching back to Home for Fragments
        homeViewModel.mFabOpened = false

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

        // 记录缩放变化：osmdroid 放大到超出瓦片源范围时只会显示空白、不给任何提示，
        // 这条日志能直接看出「最大层级有没有被钳住」
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                Log.i("MapZoom", "zoom=${event?.zoomLevel} max=${mapView.maxZoomLevel}")
                return false
            }
        })

        binding.mapTypeGroup.check(
            when (initialStyle) {
                MapTiles.STYLE_SATELLITE -> R.id.map_type_satellite
                else -> R.id.map_type_normal
            }
        )

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.map_type_satellite -> MapTiles.STYLE_SATELLITE
                R.id.map_type_normal -> MapTiles.STYLE_STREET
                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                    MapTiles.STYLE_STREET
                }
            }
            mapView.applyMapStyle(context?.mapTileProvider, style)
            context?.mapType = style
        }

        // 系统定位取代百度 LocationClient（坐标即 WGS84，无需转换）
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

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabMyLocation,
                binding.fabGoto,
                binding.fabAdd
            )

            if (!homeViewModel.mFabOpened) {
                homeViewModel.mFabOpened = true

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
                homeViewModel.mFabOpened = false

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

        binding.fabMyLocation.setOnClickListener {
            val current = mapViewModel.currentLocation
            if (current == null) {
                Toast.makeText(requireContext(), "尚未获取到当前位置", Toast.LENGTH_SHORT).show()
            } else {
                mapView.controller.animateTo(current.toGeoPoint())
            }
        }

        binding.fabGoto.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabAdd.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.let {
                    previewRoute(it)
                    // 选中路线后，将视角移动到起点
                    it.firstOrNull()?.let { start -> mapView.controller.animateTo(start.toGeoPoint()) }
                }
            } else {
                mapView.resetOverlays(eventsOverlay, myLocationMarker)
            }
        }

        return root
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

    private fun previewRoute(points: List<Pair<Double, Double>>) {
        val mapView = _binding?.mapView ?: return
        // 清除之前的所有覆盖物（保留点击监听与我的位置）
        mapView.resetOverlays(eventsOverlay, myLocationMarker)

        // 绘制之前记录的点到点的线
        mapView.addRouteLine(points)
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddLocationDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        editAddress.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editAddress.error = "地址不能为空"
            }
        }
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else {
                val latLonArray = it.toString().split(",")
                if (latLonArray.size != 2) {
                    editLatLon.error = "经纬度格式错误"
                } else {
                    val lon = latLonArray[0].trim().toDoubleOrNull()
                    val lat = latLonArray[1].trim().toDoubleOrNull()
                    if (!checkLatLon(lat, lon)) {
                        editLatLon.error = "经纬度格式错误"
                    }
                }
            }
        }

        with(mapViewModel) {
            if (markedLoc == null) {
                currentLocation?.let {
                    showDetailView = false
                    reverseGeocode(it.first, it.second)
                    markedLoc = it
                }
                editName.setText("当前位置-" + System.currentTimeMillis())
            } else {
                editName.setText("标点位置-" + System.currentTimeMillis())
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            editAddress.setText(markName ?: "位置地址")
            editLatLon.setText("${lon.toPlainString()}, ${lat.toPlainString()}")

            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(null)
            builder
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val latLonArray = editLatLon.text.toString().split(",")
                    val newLon = latLonArray[0].trim().toDoubleOrNull()
                    val newLat = latLonArray[1].trim().toDoubleOrNull()
                    var name = editName.text?.toString()
                    val address = editAddress.text?.toString()
                    if (name.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!checkLatLon(newLat, newLon)) {
                        Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
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

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var count = 0
                        while (!locations.addLocation(name!!, address, newLat!!, newLon!!)) {
                            name = "$name(${++count})"
                        }
                        rawHistoricalLocations = locations
                    }

                    Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        return true
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)

        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        mapViewModel.currentLocation?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        latitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                latitudeEditText.error = "纬度不能为空"
            } else {
                val lat = it.toString().toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0) {
                    latitudeEditText.error = "纬度格式错误"
                }
            }
        }

        longitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                longitudeEditText.error = "经度不能为空"
            } else {
                val lon = it.toString().toDoubleOrNull()
                if (lon == null || lon !in -180.0..180.0) {
                    longitudeEditText.error = "经度格式错误"
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                kotlin.runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) with(mapViewModel) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }

                        this.markedLoc = lat to lon

                        markMap(true)

                        if (followLocation) {
                            followLocation = false
                        }

                        reverseGeocode(lat, lon)
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
