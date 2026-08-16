package com.example.stepshift.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.stepshift.model.GeoPoint
import com.example.stepshift.model.RouteResult
import com.example.stepshift.model.SimulationSnapshot
import com.example.stepshift.model.SimulationStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    startPoint: GeoPoint?,
    endPoint: GeoPoint?,
    routeResult: RouteResult?,
    snapshot: SimulationSnapshot,
    isTrackingEnabled: Boolean = true,
    centerEvent: SharedFlow<GeoPoint>? = null,
    onMapClick: (GeoPoint) -> Unit,
    onToggleTracking: () -> Unit,
    onUserPanMap: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Tile source state (Default to ultra-fast domestic AutoNavi Vector)
    var currentTileSourceIndex by remember { mutableIntStateOf(0) }
    var showTileMenu by remember { mutableStateOf(false) }

    // Overlays
    var startMarker by remember { mutableStateOf<Marker?>(null) }
    var endMarker by remember { mutableStateOf<Marker?>(null) }
    var walkerMarker by remember { mutableStateOf<Marker?>(null) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var routeUnderlay by remember { mutableStateOf<Polyline?>(null) }

    // Applied-state bookkeeping: the 1Hz snapshot recomposition must NOT rebuild
    // every overlay and bitmap each tick — only diff against what is on the map.
    var appliedRoute by remember { mutableStateOf<RouteResult?>(null) }
    var appliedStart by remember { mutableStateOf<GeoPoint?>(null) }
    var appliedEnd by remember { mutableStateOf<GeoPoint?>(null) }
    var appliedBearingBucket by remember { mutableIntStateOf(Int.MIN_VALUE) }

    // Marker icons are immutable — create once instead of every recomposition.
    val startMarkerIcon = remember(context) { createCircleMarkerDrawable(ctx = context, colorHex = "#00E676", label = "起") }
    val endMarkerIcon = remember(context) { createCircleMarkerDrawable(ctx = context, colorHex = "#FF5252", label = "终") }

    val defaultCenter = remember { OsmGeoPoint(39.9042, 116.4074) }

    // Receiver reference
    val receiver = remember {
        object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                if (p != null) {
                    Log.d("StepShift", "Map single tap: lat=${p.latitude}, lon=${p.longitude}")
                    onMapClick(GeoPoint(p.latitude, p.longitude))
                    return true
                }
                return false
            }

            override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                if (p != null) {
                    Log.d("StepShift", "Map long press: lat=${p.latitude}, lon=${p.longitude}")
                    onMapClick(GeoPoint(p.latitude, p.longitude))
                    return true
                }
                return false
            }
        }
    }

    // Lifecycle binding for MapView
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapViewRef?.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 1. One-shot Center Events (Search result / manual re-center)
    LaunchedEffect(mapViewRef, centerEvent) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        centerEvent?.collectLatest { target ->
            val osmTarget = target.toOsmGeoPoint()
            Log.d("StepShift", "Map center event -> lat=${target.latitude}, lon=${target.longitude}")
            mapView.post {
                mapView.controller.apply {
                    setZoom(17.0)
                    setCenter(osmTarget)
                }
                mapView.invalidate()
            }
        }
    }

    // 2. Real-time Continuous Camera Tracking of the Runner
    LaunchedEffect(snapshot.currentPoint, isTrackingEnabled) {
        val pt = snapshot.currentPoint
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (isTrackingEnabled && pt != null && (snapshot.status == SimulationStatus.RUNNING || snapshot.status == SimulationStatus.PAUSED)) {
            mapView.post {
                mapView.controller.setCenter(pt.toOsmGeoPoint())
                mapView.invalidate()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceManager.AMAP_VECTOR)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    controller.setZoom(16.5)
                    controller.setCenter(defaultCenter)
                    overlays.add(MapEventsOverlay(receiver))

                    // Detect user touch and drag to automatically toggle to free-look mode
                    var startX = 0f
                    var startY = 0f
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.x
                                startY = event.y
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = abs(event.x - startX)
                                val dy = abs(event.y - startY)
                                if (dx > 20f || dy > 20f) {
                                    onUserPanMap()
                                }
                            }
                        }
                        false // Let osmdroid handle pinch/zoom/pan
                    }

                    mapViewRef = this
                    onResume() // Force initial resume
                }
            },
            update = { mapView ->
                // Ensure MapEventsOverlay is always present in overlays
                var hasEventOverlay = false
                for (o in mapView.overlays) {
                    if (o is MapEventsOverlay) {
                        hasEventOverlay = true
                        break
                    }
                }
                if (!hasEventOverlay) {
                    mapView.overlays.add(0, MapEventsOverlay(receiver))
                }

                // 1. Route polyline (dark underlay + cyan core) — rebuilt only when the route changes
                if (routeResult !== appliedRoute) {
                    routeUnderlay?.let { mapView.overlays.remove(it) }
                    routePolyline?.let { mapView.overlays.remove(it) }
                    routeUnderlay = null
                    routePolyline = null
                    if (routeResult != null && routeResult.points.size >= 2) {
                        val geoPoints = routeResult.points.map { it.toOsmGeoPoint() }
                        val underlay = Polyline(mapView).apply {
                            outlinePaint.color = android.graphics.Color.parseColor("#66101825")
                            outlinePaint.strokeWidth = 22f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                            setPoints(geoPoints)
                        }
                        val polyline = Polyline(mapView).apply {
                            outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                            outlinePaint.strokeWidth = 14f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                            setPoints(geoPoints)
                        }
                        mapView.overlays.add(underlay)
                        mapView.overlays.add(polyline)
                        routeUnderlay = underlay
                        routePolyline = polyline
                    }
                    appliedRoute = routeResult

                    // Polylines must stay below markers: force markers to re-add on top.
                    startMarker?.let { mapView.overlays.remove(it) }
                    startMarker = null
                    appliedStart = if (startPoint == null) null else GeoPoint(Double.NaN, Double.NaN)
                    endMarker?.let { mapView.overlays.remove(it) }
                    endMarker = null
                    appliedEnd = if (endPoint == null) null else GeoPoint(Double.NaN, Double.NaN)
                    walkerMarker?.let { mapView.overlays.remove(it) }
                    walkerMarker = null
                    appliedBearingBucket = Int.MIN_VALUE
                }

                // 2. Start marker — rebuilt only when the point changes
                if (startPoint != appliedStart) {
                    startMarker?.let { mapView.overlays.remove(it) }
                    startMarker = null
                    if (startPoint != null) {
                        val marker = Marker(mapView).apply {
                            position = startPoint.toOsmGeoPoint()
                            title = "起点"
                            icon = startMarkerIcon
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                        startMarker = marker
                    }
                    appliedStart = startPoint
                }

                // 3. End marker — rebuilt only when the point changes
                if (endPoint != appliedEnd) {
                    endMarker?.let { mapView.overlays.remove(it) }
                    endMarker = null
                    if (endPoint != null) {
                        val marker = Marker(mapView).apply {
                            position = endPoint.toOsmGeoPoint()
                            title = "终点"
                            icon = endMarkerIcon
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                        endMarker = marker
                    }
                    appliedEnd = endPoint
                }

                // 4. Walker marker — updated in place; the icon bitmap is regenerated
                //    only when the 5° bearing bucket changes, so the 1Hz tick no
                //    longer allocates new bitmaps every second.
                val currentPos = snapshot.currentPoint
                if (currentPos != null &&
                    (snapshot.status == SimulationStatus.RUNNING || snapshot.status == SimulationStatus.PAUSED)
                ) {
                    val bucket = (snapshot.currentBearing / 5f).toInt()
                    val marker = walkerMarker ?: Marker(mapView).apply {
                        title = "当前运动仿真位置"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }.also { newMarker ->
                        mapView.overlays.add(newMarker)
                        walkerMarker = newMarker
                        appliedBearingBucket = Int.MIN_VALUE
                    }
                    marker.position = currentPos.toOsmGeoPoint()
                    if (bucket != appliedBearingBucket) {
                        marker.icon = createWalkerDirectionalDrawable(ctx = context, bearingDegrees = bucket * 5f)
                        appliedBearingBucket = bucket
                    }
                } else {
                    walkerMarker?.let { mapView.overlays.remove(it) }
                    walkerMarker = null
                    appliedBearingBucket = Int.MIN_VALUE
                }

                mapView.invalidate()
            }
        )

        // Floating Map Controls (Right Side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Layer switcher button
            Box {
                FloatingActionButton(
                    onClick = { showTileMenu = true },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(imageVector = Icons.Default.Layers, contentDescription = "切换地图图层")
                }

                DropdownMenu(
                    expanded = showTileMenu,
                    onDismissRequest = { showTileMenu = false }
                ) {
                    TileSourceManager.ALL_TILE_SOURCES.forEachIndexed { index, pair ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = pair.first,
                                    fontWeight = if (index == currentTileSourceIndex) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    color = if (index == currentTileSourceIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                currentTileSourceIndex = index
                                showTileMenu = false
                                mapViewRef?.setTileSource(pair.second)
                                mapViewRef?.invalidate()
                            }
                        )
                    }
                }
            }

            // Primary Camera Tracking / Location Follow Toggle Button
            val trackButtonColor by animateColorAsState(
                targetValue = if (isTrackingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                label = "trackBtnColor"
            )
            val trackIconColor by animateColorAsState(
                targetValue = if (isTrackingEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                label = "trackIconColor"
            )

            FloatingActionButton(
                onClick = onToggleTracking,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                containerColor = trackButtonColor,
                contentColor = trackIconColor
            ) {
                Icon(
                    imageVector = if (isTrackingEnabled) Icons.Default.Navigation else Icons.Default.MyLocation,
                    contentDescription = if (isTrackingEnabled) "追踪中 (点击取消追踪)" else "开启视角追踪"
                )
            }

            // Zoom In Button
            FloatingActionButton(
                onClick = { mapViewRef?.controller?.zoomIn() },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(imageVector = Icons.Default.ZoomIn, contentDescription = "放大")
            }

            // Zoom Out Button
            FloatingActionButton(
                onClick = { mapViewRef?.controller?.zoomOut() },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(imageVector = Icons.Default.ZoomOut, contentDescription = "缩小")
            }
        }
    }
}

private fun createCircleMarkerDrawable(ctx: Context, colorHex: String, label: String): Drawable {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#40000000")
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawCircle(size / 2f, size / 2f + 4f, size / 2.6f, shadowPaint)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor(colorHex)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, bgPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(label, size / 2f, yPos, textPaint)

    return BitmapDrawable(ctx.resources, bitmap)
}

private fun createWalkerDirectionalDrawable(ctx: Context, bearingDegrees: Float): Drawable {
    val size = 110
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.save()
    canvas.rotate(bearingDegrees, size / 2f, size / 2f)

    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#4D00E5FF")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, 44f, glowPaint)

    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, 26f, corePaint)

    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        moveTo(size / 2f, 18f)
        lineTo(size / 2f + 16f, size / 2f + 14f)
        lineTo(size / 2f, size / 2f + 6f)
        lineTo(size / 2f - 16f, size / 2f + 14f)
        close()
    }
    canvas.drawPath(path, arrowPaint)

    canvas.restore()
    return BitmapDrawable(ctx.resources, bitmap)
}
