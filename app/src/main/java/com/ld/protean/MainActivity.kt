package com.ld.protean

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.Manifest.permission.FOREGROUND_SERVICE
import android.Manifest.permission.INTERNET
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.VIBRATE
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView.OnItemClickListener
import android.widget.ImageView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavController.OnDestinationChangedListener
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ld.protean.android.permission.RequestPermissions
import com.ld.protean.android.root.ShellUtils
import com.ld.protean.android.window.OverlayUtils
import com.ld.protean.databinding.ActivityMainBinding
import com.ld.protean.map.PhotonGeocoder
import com.ld.protean.map.Poi
import com.ld.protean.map.addSelectionMarker
import com.ld.protean.map.toGeoPoint
import com.ld.protean.ui.notification.NotificationUtils
import com.ld.protean.ui.viewmodel.MapViewModel
import com.ld.protean.ui.viewmodel.MockServiceViewModel
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    /* Permission */
    private val requestMultiplePermissions = RequestPermissions(this)

    /* Map */
    private val mapViewModel by viewModels<MapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()

    private var searchJob: Job? = null

    private fun getRequiredPermissions(): MutableSet<String> {
        val permissions = mutableSetOf(
            ACCESS_FINE_LOCATION,
            ACCESS_COARSE_LOCATION,
            ACCESS_LOCATION_EXTRA_COMMANDS,
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            READ_PHONE_STATE,
            INTERNET,
            ACCESS_NETWORK_STATE,
            VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(FOREGROUND_SERVICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 运行通知需要该权限
            permissions.add(POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun handleDeniedPermissions(denied: Set<String>) {
        denied.forEach { permission ->
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                showPermissionDeniedToast(permission)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_PERMISSIONS_CODE)
            }
        }

        if (denied.isEmpty()) {
            requireFloatWindows()
        }
    }

    private fun showPermissionDeniedToast(permission: String) {
        val message = when (permission) {
            ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION -> "Protean需要完整位置权限"
            ACCESS_LOCATION_EXTRA_COMMANDS -> "Protean需要额外位置命令权限和系统交互"
            CHANGE_WIFI_STATE, ACCESS_WIFI_STATE -> "Protean需要访问Wi-Fi状态"
            READ_PHONE_STATE -> "Protean需要读取设备信息"
            ACCESS_NETWORK_STATE, INTERNET -> "Protean需要访问网络"
            VIBRATE -> "Protean需要访问传感器"
            else -> "需要 $permission 才能运行"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun checkPermission(): Boolean {
        val permissions = getRequiredPermissions()
        val (_, denied) = requestMultiplePermissions.request(permissions)
        handleDeniedPermissions(denied)
        return denied.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false // 状态栏字体颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.statusBarColor = ContextCompat.getColor(this, R.color.theme_appbar_color)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        if (!ShellUtils.hasRoot()) {
            Toast.makeText(this, "无Root可能导致传感器Hook失效", Toast.LENGTH_LONG).show()
        }

        // 地图标点图标与逆地理编码回调（原先由百度 GeoCoder 的监听器承担）
        mapViewModel.mMapIndicator =
            ContextCompat.getDrawable(this, R.drawable.icon_selected_location_16)
        mapViewModel.onReverseGeocode = { loc ->
            if (mapViewModel.showDetailView) {
                showDetailInfo(loc)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if(checkPermission()) {
                    mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
                }

                initNotification()

                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)

                setSupportActionBar(binding.appBarMain.toolbar)

                binding.appBarMain.toolbar.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.theme_appbar_color))
                val drawerLayout: DrawerLayout = binding.drawerLayout
                val navView: NavigationView = binding.navView
                // 菜单图标保留图片自带配色，不做统一 tint（抽屉是白底，原色更清楚）
                navView.itemIconTintList = null
                val navController = findNavController(R.id.nav_host_fragment_content_main)

                // Passing each menu ID as a set of Ids because each
                // menu should be considered as top level destinations.
                appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.nav_home, R.id.nav_mock, R.id.nav_gnss_mock, R.id.nav_route_gallery, R.id.nav_settings
                    ), drawerLayout
                )

                setupActionBarWithNavController(navController, appBarConfiguration)
                navView.setupWithNavController(navController)

                binding.appBarMain.toolbar.navigationIcon?.colorFilter = PorterDuffColorFilter(
                    ContextCompat.getColor(this@MainActivity, R.color.theme_appbar_icon_color), PorterDuff.Mode.SRC_IN
                )

                navController.addOnDestinationChangedListener(object: OnDestinationChangedListener {
                    val menuIdMapping = mapOf(
                        R.id.nav_home to R.id.action_search,
                        //R.id.nav_settings to R.id.action_info
                    )

                    override fun onDestinationChanged(
                        controller: NavController,
                        destination: NavDestination,
                        arguments: Bundle?
                    ) {
                        menuIdMapping.forEach { (key, value) ->
                            val menu = binding.appBarMain.toolbar.menu
                            menu.findItem(value)?.isVisible = key == destination.id
                        }
                    }
                })
            }
        }

        mockServiceViewModel.initRocker(this)
    }

    private fun initNotification() {
        with(mapViewModel) {
            mNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationUtils = NotificationUtils(this@MainActivity)
                val builder = notificationUtils.getAndroidChannelNotification(
                    "Protean后台定位服务",
                    "正在后台定位"
                )
                builder.build()
            } else {
                val builder = Notification.Builder(this@MainActivity)
                val nfIntent = Intent(
                    this@MainActivity,
                    MainActivity::class.java
                )
                builder.setContentIntent(PendingIntent.getActivity(
                    this@MainActivity, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE
                ))
                    .setContentTitle("Protean后台定位服务")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentText("正在后台定位")
                    .setWhen(System.currentTimeMillis())

                builder.build()
            }.also {
                it.defaults = Notification.DEFAULT_SOUND
            }
        }

        // 原先这条通知是靠百度定位 SDK 的前台服务弹出的，SDK 移除后改为直接投递
        postRunningNotification()
    }

    @SuppressLint("MissingPermission")
    private fun postRunningNotification() {
        val notification = mapViewModel.mNotification ?: return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e("MainActivity", "Failed to post running notification", it)
        }
    }

    private fun requireFloatWindows(): Boolean {
        fun requestSettingCanDrawOverlays() {
            kotlin.runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }.onFailure {
                Log.e("MainActivity", "requestSettingCanDrawOverlays: ", it) // boom in Redmi K60
                Toast.makeText(this, "跳转失败，请手动去设置授权", Toast.LENGTH_LONG).show()
            }
            finish()
        }

        if (!OverlayUtils.hasOverlayPermissions(this)) {
            Toast.makeText(this, "快给我悬浮窗权限", Toast.LENGTH_LONG).show()
            requestSettingCanDrawOverlays()
            return false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val denied = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (denied.isEmpty()) {
                mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
                return
            }

            for (permission in denied) {
                Toast.makeText(this, when(permission) {
                    ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION -> "Protean需要完整位置权限"
                    ACCESS_LOCATION_EXTRA_COMMANDS -> "Protean需要额外位置命令权限和系统交互"
                    CHANGE_WIFI_STATE, ACCESS_WIFI_STATE -> "Protean需要访问Wi-Fi状态"
                    READ_PHONE_STATE -> "Protean需要读取设备信息"
                    ACCESS_NETWORK_STATE, INTERNET -> "Protean需要访问网络"
                    VIBRATE -> "Protean需要访问传感器"
                    else -> "需要 $permission 才能运行"
                } + "，请手动授权！", Toast.LENGTH_SHORT).show()
            }

            setContentView(R.layout.activity_no_permission)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val searchItem: MenuItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.onActionViewExpanded()

        val searchClose = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
        val searchBack = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_go_btn)
        val voiceBack = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_voice_btn)
        val color = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        ImageViewCompat.setImageTintList(searchClose, color)
        ImageViewCompat.setImageTintList(searchBack, color)
        ImageViewCompat.setImageTintList(voiceBack, color)

        val mSearchList = binding.appBarMain.searchListView
        mSearchList.onItemClickListener = OnItemClickListener { parent, view, pos, id ->
            val lngText = (view.findViewById<View>(R.id.poi_longitude) as TextView).text.toString()
            val latText = (view.findViewById<View>(R.id.poi_latitude) as TextView).text.toString()
            with(mapViewModel) {
                markName = (view.findViewById<View>(R.id.poi_name) as TextView).text.toString()

                val lng = lngText.toDouble() // wgs84
                val lat = latText.toDouble()
                markedLoc = lat to lng
                if (isExists) {
                    mapView.controller.animateTo((lat to lng).toGeoPoint())
                } else {
                    Toast.makeText(this@MainActivity, "地图未加载", Toast.LENGTH_SHORT).show()
                }

                markMap()

                binding.appBarMain.searchLinear.visibility = View.INVISIBLE
                searchItem.collapseActionView()
            }
        }

        searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query.isNullOrBlank()) return false
                runSearch(query, submit = true)
                binding.appBarMain.searchLinear.visibility = View.INVISIBLE
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!newText.isNullOrBlank()) {
                    runSearch(newText, submit = false)
                } else {
                    binding.appBarMain.searchLinear.visibility = View.GONE
                }
                return true
            }
        })
        return true
    }

    /**
     * 用 Photon 取代百度的 SuggestionSearch。
     * 输入过程中做防抖，避免每敲一个字就请求一次。
     */
    private fun runSearch(query: String, submit: Boolean) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!submit) delay(SEARCH_DEBOUNCE_MS)

            val results = runCatching {
                PhotonGeocoder.search(query, mapViewModel.currentLocation)
            }.getOrElse {
                Log.e("MainActivity", "Search error: ${it.stackTraceToString()}")
                Toast.makeText(this@MainActivity, "搜索出错", Toast.LENGTH_SHORT).show()
                emptyList()
            }

            if (results.isEmpty()) {
                if (submit) {
                    Toast.makeText(this@MainActivity, "未搜索到相关位置", Toast.LENGTH_SHORT).show()
                }
                binding.appBarMain.searchLinear.visibility = View.INVISIBLE
                return@launch
            }

            val data = results.map { it.toMap() } // wgs84
            val simAdapt = SimpleAdapter(
                this@MainActivity, data,
                R.layout.layout_search_poi_item,
                arrayOf(Poi.KEY_NAME, Poi.KEY_ADDRESS, Poi.KEY_LONGITUDE_RAW, Poi.KEY_LATITUDE_RAW, Poi.KEY_TAG),
                intArrayOf(R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude, R.id.poi_tag)
            )
            binding.appBarMain.searchListView.setAdapter(simAdapt)
            binding.appBarMain.searchLinear.visibility = View.VISIBLE
        }
    }

    private fun markMap() = with(mapViewModel) {
        if (markedLoc == null) return

        if (followLocation) {
            followLocation = false
        }

        if (!isExists) return

        val loc = markedLoc!!
        mapView.overlays.removeAll { it is Marker }
        mapView.addSelectionMarker(loc.toGeoPoint(), mMapIndicator)
        mapView.invalidate()

        showDetailInfo(loc)
    }

    @SuppressLint("SetTextI18n")
    private fun showDetailInfo(wgsLoc: Pair<Double, Double>) {
        if (!mapViewModel.isExists) return
        val mapView = mapViewModel.mapView

        val infoView = layoutInflater.inflate(R.layout.layout_loc_detail, null)
        val locDetail = infoView.findViewById<TextView>(R.id.loc_detail)
        locDetail.text = "${wgsLoc.second.toString().take(10)}, ${wgsLoc.first.toString().take(10)}"
        val locAddr = infoView.findViewById<TextView>(R.id.loc_addr)
        locAddr.text = mapViewModel.markName ?: "未知地址"

        // 原先用百度的 InfoWindow(BitmapDescriptorFactory.fromView(infoView), ...)，
        // 这里换成 osmdroid 的自定义 InfoWindow：内容已在上面填好，onOpen 无需再做处理
        val infoWindow = object : InfoWindow(infoView, mapView) {
            override fun onOpen(item: Any?) = Unit

            override fun onClose() = Unit
        }

        val marker = Marker(mapView).apply {
            position = wgsLoc.toGeoPoint()
            icon = mapViewModel.mMapIndicator
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(infoWindow)
        }
        mapView.overlays.add(marker)
        marker.showInfoWindow()
        mapView.invalidate()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()

        searchJob?.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 111
        private const val NOTIFICATION_ID = 1
        private const val SEARCH_DEBOUNCE_MS = 400L
    }
}
