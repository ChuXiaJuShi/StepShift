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

    val ALL_TILE_SOURCES = listOf(
        "高德街道地图 (推荐)" to AMAP_VECTOR,
        "高德卫星地图" to AMAP_SATELLITE,
        "OpenStreetMap 官方" to OSM_MAPNIK
    )
}
