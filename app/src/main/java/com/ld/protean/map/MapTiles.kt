package com.ld.protean.map

import android.util.Log
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无需 API Key 的在线底图，可切换。
 *
 * 选型说明（都是实测结果，不是猜的）：
 * - OSM 官方瓦片（tile.openstreetmap.org）在中国大陆网络下通常不可达；
 * - Esri 的 World_Street_Map 在 z14 以上会给全球任何位置返回同一张 2521 字节占位图
 *   （连纽约也一样），也就是说街道图实际只有 z0~z13 可用，卫星图（World_Imagery）正常；
 * - 高德瓦片国内速度最快、中文标注、z18 以内都有真实内容，代价是 GCJ-02 偏移，
 *   需要靠 [CoordTransform] 做显示层换算。
 *
 * 所以默认用高德，Esri 卫星图与 OSM 作为备选。
 */
object MapTiles {
    const val STYLE_STREET = 0
    const val STYLE_SATELLITE = 1

    /**
     * 一套瓦片。模板支持 {z} {x} {y} {s} 四个占位符，{s} 会在 [subdomains] 之间轮换。
     *
     * [maxZoom] 必须不大于该服务真正有瓦片的最大层级：osmdroid 默认允许放到 20 多级，
     * 一旦超过瓦片源范围就是一片空白，这正是「放大地图后又没显示」的原因。
     */
    data class TileSet(
        val name: String,
        val templateUrl: String,
        val subdomains: List<String>,
        val minZoom: Int,
        val maxZoom: Int,
    ) {
        /** 按模板拼瓦片 URL，[subdomain] 为空时表示该源没有子域 */
        fun url(z: Int, x: Int, y: Int, subdomain: String = ""): String =
            templateUrl
                .replace("{s}", subdomain)
                .replace("{z}", z.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
    }

    /**
     * 一个底图供应商。街道图与卫星图成对出现，对应界面上「普通图 / 卫星图」两个单选框。
     *
     * [gcj02] 表示瓦片是偏移后的加密坐标，显示前需要换算，否则标点会和底图错开几百米。
     */
    data class Provider(
        val id: String,
        val label: String,
        val description: String,
        val gcj02: Boolean,
        val street: TileSet,
        val satellite: TileSet,
    )

    private const val AMAP_STREET_URL =
        "https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"
    private const val AMAP_SATELLITE_URL =
        "https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"
    private const val ESRI_STREET_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"
    private const val ESRI_IMAGERY_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    private const val OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    private val AMAP_SUBDOMAINS = listOf("1", "2", "3", "4")
    private val NO_SUBDOMAIN = listOf("")

    val providers: List<Provider> = listOf(
        Provider(
            id = "amap",
            label = "高德地图",
            description = "国内最快，中文标注，最大 18 级（默认）",
            gcj02 = true,
            street = TileSet("AmapStreet", AMAP_STREET_URL, AMAP_SUBDOMAINS, 3, 18),
            satellite = TileSet("AmapSatellite", AMAP_SATELLITE_URL, AMAP_SUBDOMAINS, 3, 18),
        ),
        Provider(
            id = "esri",
            label = "Esri 卫星图",
            description = "街道图已失效，仅卫星图可用，最大 19 级",
            gcj02 = false,
            street = TileSet("EsriWorldStreetMap", ESRI_STREET_URL, NO_SUBDOMAIN, 0, 13),
            satellite = TileSet("EsriWorldImagery", ESRI_IMAGERY_URL, NO_SUBDOMAIN, 0, 19),
        ),
        Provider(
            id = "osm",
            label = "OpenStreetMap",
            description = "WGS84 标准底图，国内网络可能无法访问",
            gcj02 = false,
            street = TileSet("OpenStreetMap", OSM_URL, NO_SUBDOMAIN, 0, 19),
            satellite = TileSet("OpenStreetMap", OSM_URL, NO_SUBDOMAIN, 0, 19),
        ),
    )

    const val DEFAULT_PROVIDER_ID = "amap"

    fun provider(id: String?): Provider =
        providers.firstOrNull { it.id == id } ?: providers.first()

    fun tileSet(providerId: String?, style: Int): TileSet =
        provider(providerId).let { if (style == STYLE_SATELLITE) it.satellite else it.street }

    private val sourceCache = HashMap<String, ITileSource>()

    /** 同一个瓦片源复用实例：osmdroid 会按源名做缓存，实例每次都新建反而会影响命中 */
    fun tileSource(providerId: String?, style: Int): ITileSource {
        val set = tileSet(providerId, style)
        return sourceCache.getOrPut(set.name) { TemplatedTileSource(set) }
    }

    /** 当前生效的底图是否是 GCJ-02，决定显示时要不要做坐标偏移 */
    var activeGcj02: Boolean = false
        internal set

    /**
     * 模型层统一 WGS-84（系统定位、模拟定位、Photon 逆地理编码都是 WGS-84）。
     * 底图若是 GCJ-02，显示前换过去，让标点落在底图的正确位置上。
     */
    fun toDisplay(lat: Double, lon: Double): Pair<Double, Double> =
        if (activeGcj02) CoordTransform.wgs84ToGcj02(lat, lon) else lat to lon

    /** 反向：用户在地图上点的位置（GCJ-02 底图）换回模型层的 WGS-84 */
    fun toModel(lat: Double, lon: Double): Pair<Double, Double> =
        if (activeGcj02) CoordTransform.gcj02ToWgs84(lat, lon) else lat to lon
}

private const val TILE_SIZE = 256

private const val TAG = "MapTiles"

/** 按 [MapTiles.TileSet] 的模板拼瓦片 URL */
private class TemplatedTileSource(
    private val set: MapTiles.TileSet,
) : OnlineTileSourceBase(
    set.name, set.minZoom, set.maxZoom, TILE_SIZE, "", arrayOf("")
) {
    private val counter = AtomicInteger(0)

    override fun getTileURLString(pMapTileIndex: Long): String {
        val subdomain = if (set.subdomains.size <= 1) {
            set.subdomains.firstOrNull() ?: ""
        } else {
            // 轮换子域，避免单域名并发过高被限流
            set.subdomains[(counter.getAndIncrement() and Int.MAX_VALUE) % set.subdomains.size]
        }

        return set.url(
            MapTileIndex.getZoom(pMapTileIndex),
            MapTileIndex.getX(pMapTileIndex),
            MapTileIndex.getY(pMapTileIndex),
            subdomain
        )
    }
}

/**
 * 切换底图。
 *
 * 除了换瓦片源，还要把 [MapView] 的缩放上下限对齐到该瓦片源的实际上限，
 * 否则用户能一路放大到没有瓦片的层级，看到的就是一片空白。
 */
fun MapView.applyMapStyle(providerId: String?, style: Int) {
    val set = MapTiles.tileSet(providerId, style)

    setTileSource(MapTiles.tileSource(providerId, style))
    maxZoomLevel = set.maxZoom.toDouble()
    minZoomLevel = set.minZoom.toDouble()

    if (zoomLevelDouble > set.maxZoom) {
        controller.setZoom(set.maxZoom.toDouble())
    }
    if (zoomLevelDouble < set.minZoom) {
        controller.setZoom(set.minZoom.toDouble())
    }

    MapTiles.activeGcj02 = MapTiles.provider(providerId).gcj02
    invalidate()

    Log.i(
        TAG,
        "底图=${set.name} provider=${MapTiles.provider(providerId).id} style=$style " +
            "层级=${set.minZoom}~${set.maxZoom} 当前=${zoomLevelDouble} GCJ02=${MapTiles.activeGcj02}"
    )
}
