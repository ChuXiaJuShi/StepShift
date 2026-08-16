package com.example.stepshift.ui.components

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/**
 * Multi-source tile provider for OsmDroid with ultra-fast domestic direct connection.
 */
object TileSourceManager {

    /**
     * 高德矢量街道地图 (国内直连 ~15ms 延迟，清晰街道与中文地名)
     */
    val AMAP_VECTOR: ITileSource = object : OnlineTileSourceBase(
        "AutoNavi-Vector",
        1,
        19,
        256,
        ".png",
        arrayOf(
            "https://wprd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
            "https://wprd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
            "https://wprd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
            "https://wprd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            val z = MapTileIndex.getZoom(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }

    /**
     * 高德卫星影像图
     */
    val AMAP_SATELLITE: ITileSource = object : OnlineTileSourceBase(
        "AutoNavi-Satellite",
        1,
        18,
        256,
        ".jpg",
        arrayOf(
            "https://webst01.is.autonavi.com/appmaptile?style=6",
            "https://webst02.is.autonavi.com/appmaptile?style=6",
            "https://webst03.is.autonavi.com/appmaptile?style=6",
            "https://webst04.is.autonavi.com/appmaptile?style=6"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            val z = MapTileIndex.getZoom(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }

    /**
     * OpenStreetMap 官方标准瓦片 (需代理)
     */
    val OSM_MAPNIK: ITileSource = TileSourceFactory.MAPNIK

    /**
     * Tile source descriptor. [isGcj02] marks sources whose tiles are rendered in
     * the GCJ-02 (Mars) datum — display coordinates must be converted from WGS-84
     * before drawing, and screen taps converted back to WGS-84.
     *
     * NOTE: the two AMap endpoints differ! The `wprd0x` vector endpoint (style=7)
     * serves WGS-84-aligned tiles (the community-known 无偏移 source), while the
     * `webst0x` satellite endpoint (style=6) serves GCJ-02-offset tiles.
     */
    data class TileSourceSpec(
        val label: String,
        val tileSource: ITileSource,
        val isGcj02: Boolean
    )

    val ALL_TILE_SOURCES = listOf(
        TileSourceSpec("高德街道地图 (推荐)", AMAP_VECTOR, isGcj02 = false),
        TileSourceSpec("高德卫星地图", AMAP_SATELLITE, isGcj02 = true),
        TileSourceSpec("OpenStreetMap 官方", OSM_MAPNIK, isGcj02 = false)
    )
}
