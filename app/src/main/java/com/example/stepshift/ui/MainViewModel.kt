package com.example.stepshift.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepshift.engine.SimulationEngine
import com.example.stepshift.model.*
import com.example.stepshift.network.GeocodingApiClient
import com.example.stepshift.network.OsrmApiClient
import com.example.stepshift.network.SearchLocationResult
import com.example.stepshift.root.RootLocationMock
import com.example.stepshift.root.RootShellExecutor
import com.example.stepshift.service.MockForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SelectionMode {
    NONE,
    SET_START,
    SET_END
}

class MainViewModel(
    private val engine: SimulationEngine = SimulationEngine.instance,
    private val osrmClient: OsrmApiClient = OsrmApiClient(),
    private val geocodingClient: GeocodingApiClient = GeocodingApiClient(),
    private val rootExecutor: RootShellExecutor = RootShellExecutor.instance,
    private val rootMock: RootLocationMock = RootLocationMock.instance
) : ViewModel() {

    val snapshot: StateFlow<SimulationSnapshot> = engine.snapshot
    val config: StateFlow<SimulationConfig> = engine.config

    private val _isRootAvailable = MutableStateFlow<Boolean?>(null)
    val isRootAvailable: StateFlow<Boolean?> = _isRootAvailable.asStateFlow()

    private val _startPoint = MutableStateFlow<GeoPoint?>(null)
    val startPoint: StateFlow<GeoPoint?> = _startPoint.asStateFlow()

    private val _endPoint = MutableStateFlow<GeoPoint?>(null)
    val endPoint: StateFlow<GeoPoint?> = _endPoint.asStateFlow()

    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult.asStateFlow()

    private val _isLoadingRoute = MutableStateFlow(false)
    val isLoadingRoute: StateFlow<Boolean> = _isLoadingRoute.asStateFlow()

    private val _selectionMode = MutableStateFlow(SelectionMode.SET_START)
    val selectionMode: StateFlow<SelectionMode> = _selectionMode.asStateFlow()

    // Location search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchLocationResult>>(emptyList())
    val searchResults: StateFlow<List<SearchLocationResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    // Real device GPS location
    private val _deviceLocation = MutableStateFlow<GeoPoint?>(null)
    val deviceLocation: StateFlow<GeoPoint?> = _deviceLocation.asStateFlow()

    // Map Center Target Event
    private val _mapCenterEvent = MutableSharedFlow<GeoPoint>(replay = 1, extraBufferCapacity = 10)
    val mapCenterEvent: SharedFlow<GeoPoint> = _mapCenterEvent.asSharedFlow()

    // Control panel expanded state
    private val _isControlPanelExpanded = MutableStateFlow(true)
    val isControlPanelExpanded: StateFlow<Boolean> = _isControlPanelExpanded.asStateFlow()

    // Camera auto-tracking runner state
    private val _isTrackingEnabled = MutableStateFlow(true)
    val isTrackingEnabled: StateFlow<Boolean> = _isTrackingEnabled.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        checkRootAccess()
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            val rooted = rootExecutor.isRootAvailable()
            _isRootAvailable.value = rooted
        }
    }

    fun grantRootMockPermissions(context: Context) {
        viewModelScope.launch {
            val success = rootMock.grantMockPermissions(context)
            checkRootAccess()
            if (success) {
                _toastMessage.emit("Root 提权成功：已自动授予模拟位置与后台运行特权")
            } else {
                _toastMessage.emit("Root 赋权完成 (部分指令已执行)")
            }
        }
    }

    fun toggleControlPanel() {
        _isControlPanelExpanded.value = !_isControlPanelExpanded.value
    }

    fun setSelectionMode(mode: SelectionMode) {
        _selectionMode.value = mode
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.trim().isEmpty()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(400L) // Debounce typing
            _isSearching.value = true
            val res = geocodingClient.search(query)
            _isSearching.value = false
            res.onSuccess {
                _searchResults.value = it
            }.onFailure {
                _searchResults.value = emptyList()
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    fun onSearchResultSelected(result: SearchLocationResult, asStart: Boolean) {
        clearSearch()
        viewModelScope.launch {
            _mapCenterEvent.emit(result.point)
            if (asStart) {
                _startPoint.value = result.point
                _selectionMode.value = SelectionMode.SET_END
                _toastMessage.emit("已设置起点: ${result.shortTitle}")
                val end = _endPoint.value
                if (end != null) {
                    calculateRoute(result.point, end)
                }
            } else {
                _endPoint.value = result.point
                _selectionMode.value = SelectionMode.NONE
                _toastMessage.emit("已设置终点: ${result.shortTitle}")
                val start = _startPoint.value
                if (start != null) {
                    calculateRoute(start, result.point)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchAndCenterDeviceLocation(context: Context) {
        viewModelScope.launch {
            // 1. If currently in simulation (running, paused, or completed), center directly on the active mock point!
            val simPoint = snapshot.value.currentPoint
            if (simPoint != null && snapshot.value.status != SimulationStatus.IDLE) {
                _mapCenterEvent.emit(simPoint)
                _toastMessage.emit("已居中到当前运动仿真位置: %.4f, %.4f".format(simPoint.latitude, simPoint.longitude))
                return@launch
            }

            // 2. If a planned start point exists, center to start
            val start = _startPoint.value
            if (start != null && _endPoint.value != null) {
                _mapCenterEvent.emit(start)
                _toastMessage.emit("已居中到路线起点")
                return@launch
            }

            // 3. Otherwise query device real GPS / Network / Fused location
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@launch

            // Auto-enable system location via Root if turned off
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) {
                rootMock.ensureSystemLocationEnabled()
            }

            var bestLoc: Location? = null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (p in providers) {
                try {
                    val l = lm.getLastKnownLocation(p)
                    if (l != null && (bestLoc == null || l.time > bestLoc.time)) {
                        bestLoc = l
                    }
                } catch (ignored: Exception) {
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val l = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                    if (l != null && (bestLoc == null || l.time > bestLoc.time)) {
                        bestLoc = l
                    }
                } catch (ignored: Exception) {
                }
            }

            if (bestLoc != null) {
                val point = GeoPoint(bestLoc.latitude, bestLoc.longitude, bestLoc.altitude)
                _deviceLocation.value = point
                _mapCenterEvent.emit(point)
                _toastMessage.emit("已定位到当前位置 (%.4f, %.4f)".format(point.latitude, point.longitude))
            } else {
                // Request a fresh one-shot update via Android LocationListener
                try {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            val p = GeoPoint(location.latitude, location.longitude, location.altitude)
                            _deviceLocation.value = p
                            viewModelScope.launch {
                                _mapCenterEvent.emit(p)
                                _toastMessage.emit("已获取实时 GPS 定位 (%.4f, %.4f)".format(p.latitude, p.longitude))
                            }
                            lm.removeUpdates(this)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null)
                    }
                    _toastMessage.emit("正在请求卫星 GPS 定位，请稍候...")
                } catch (e: Exception) {
                    val defaultPoint = GeoPoint(39.9042, 116.4074)
                    _mapCenterEvent.emit(defaultPoint)
                    _toastMessage.emit("已移动至默认中心坐标")
                }
            }
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        _isTrackingEnabled.value = enabled
    }

    fun onUserPanMap() {
        if (_isTrackingEnabled.value) {
            _isTrackingEnabled.value = false
        }
    }

    fun toggleTracking(context: Context) {
        if (_isTrackingEnabled.value) {
            _isTrackingEnabled.value = false
            viewModelScope.launch {
                _toastMessage.emit("已关闭视角追踪 (自由浏览地图)")
            }
        } else {
            _isTrackingEnabled.value = true
            viewModelScope.launch {
                _toastMessage.emit("已开启视角追踪 (持续跟随运动者)")
            }
            fetchAndCenterDeviceLocation(context)
        }
    }

    fun onMapClick(point: GeoPoint) {
        if (snapshot.value.status == SimulationStatus.RUNNING) return

        when (_selectionMode.value) {
            SelectionMode.SET_START -> {
                _startPoint.value = point
                _selectionMode.value = SelectionMode.SET_END
                viewModelScope.launch {
                    _toastMessage.emit("起点已设置，请在地图上点击选择【终点】")
                }
                val currentEnd = _endPoint.value
                if (currentEnd != null) {
                    calculateRoute(point, currentEnd)
                }
            }
            SelectionMode.SET_END -> {
                _endPoint.value = point
                _selectionMode.value = SelectionMode.NONE
                viewModelScope.launch {
                    _toastMessage.emit("终点已设置，正在通过 OSRM 规划步行路网...")
                }
                val currentStart = _startPoint.value
                if (currentStart != null) {
                    calculateRoute(currentStart, point)
                }
            }
            SelectionMode.NONE -> {
                _startPoint.value = point
                _selectionMode.value = SelectionMode.SET_END
                viewModelScope.launch {
                    _toastMessage.emit("起点已更新，请点击选择【终点】")
                }
                // Mirror the SET_START branch: replan immediately if an end point exists
                val currentEnd = _endPoint.value
                if (currentEnd != null) {
                    calculateRoute(point, currentEnd)
                }
            }
        }
    }

    fun calculateRoute(start: GeoPoint, end: GeoPoint) {
        viewModelScope.launch {
            _isLoadingRoute.value = true
            val result = osrmClient.fetchWalkingRoute(start, end)
            _isLoadingRoute.value = false

            result.onSuccess { route ->
                _routeResult.value = route
                engine.setRoute(route.points)
                if (route.isFallbackDirect) {
                    _toastMessage.emit("路网规划完成 (离线平滑降级模式)")
                } else {
                    _toastMessage.emit("路网规划成功：全长 ${route.formatDistanceKm()}，预计 ${route.formatDuration()}")
                }
            }.onFailure { error ->
                _toastMessage.emit("路网规划失败: ${error.message}")
            }
        }
    }

    fun clearRoute() {
        if (snapshot.value.status == SimulationStatus.RUNNING) return
        _startPoint.value = null
        _endPoint.value = null
        _routeResult.value = null
        _selectionMode.value = SelectionMode.SET_START
        engine.stop()
        engine.setRoute(emptyList())
    }

    fun startSimulation(context: Context) {
        val route = _routeResult.value
        if (route == null || route.points.isEmpty()) {
            viewModelScope.launch {
                _toastMessage.emit("请先在地图上选择起点和终点规划路线！")
            }
            return
        }

        MockForegroundService.startService(context)
    }

    fun pauseSimulation() {
        engine.pause()
    }

    fun resumeSimulation() {
        engine.resume()
    }

    fun stopSimulation(context: Context) {
        MockForegroundService.stopService(context)
        engine.stop()
    }

    fun resetSimulation() {
        engine.reset()
    }

    fun updateSpeed(speedKmH: Double) {
        val currentCfg = config.value
        // Keep in sync with the ControlPanel slider range (1.0 ~ 25.0 km/h)
        engine.updateConfig(currentCfg.copy(speedKmH = speedKmH.coerceIn(1.0, 25.0)))
    }

    fun updateGpsDrift(enable: Boolean, intensity: Double = 0.85) {
        val currentCfg = config.value
        engine.updateConfig(currentCfg.copy(enableGpsDrift = enable, driftIntensityMeters = intensity))
    }

    fun updateCadence(autoCadence: Boolean, customSpm: Int = 115) {
        val currentCfg = config.value
        engine.updateConfig(currentCfg.copy(autoCadence = autoCadence, customCadenceSpm = customSpm))
    }

    fun updateTargetSteps(steps: Long?) {
        val currentCfg = config.value
        engine.updateConfig(currentCfg.copy(targetSteps = steps))
    }

    fun updateTargetDistance(distanceMeters: Double?) {
        val currentCfg = config.value
        engine.updateConfig(currentCfg.copy(targetDistanceM = distanceMeters))
    }

    fun loadSampleRoute() {
        val start = GeoPoint(39.9087, 116.3975) // Beijing Tiananmen
        val end = GeoPoint(39.9163, 116.4072)   // Wangfujing
        _startPoint.value = start
        _endPoint.value = end
        _selectionMode.value = SelectionMode.NONE
        viewModelScope.launch {
            _mapCenterEvent.emit(start)
            calculateRoute(start, end)
        }
    }

    fun updateConfig(newConfig: SimulationConfig) {
        engine.updateConfig(newConfig)
    }
}

