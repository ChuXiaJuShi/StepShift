package com.example.stepshift.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepshift.engine.SimulationEngine
import com.example.stepshift.health.HealthConnectWriter
import com.example.stepshift.health.HealthDataManager
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
    SET_END,
    SET_MOCK
}

class MainViewModel(
    private val engine: SimulationEngine = SimulationEngine.instance,
    private val osrmClient: OsrmApiClient = OsrmApiClient(),
    private val geocodingClient: GeocodingApiClient = GeocodingApiClient(),
    private val rootExecutor: RootShellExecutor = RootShellExecutor.instance,
    private val rootMock: RootLocationMock = RootLocationMock.instance,
    private val healthManager: HealthDataManager = HealthDataManager.instance,
    private val healthConnectWriter: HealthConnectWriter = HealthConnectWriter.instance
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

    // ------------------------------------------------------------------
    // Standalone override subsystem (independent step / location spoofing)
    // ------------------------------------------------------------------

    // Real cumulative steps reported by the hardware step-counter sensor
    private val _sensorSteps = MutableStateFlow<Long?>(null)
    val sensorSteps: StateFlow<Long?> = _sensorSteps.asStateFlow()

    // Manually pushed virtual step count (persisted across launches)
    private val _overrideSteps = MutableStateFlow<Long?>(null)
    val overrideSteps: StateFlow<Long?> = _overrideSteps.asStateFlow()

    // Standalone virtual position (persisted across launches; initialized from
    // the real device position when no previous value exists)
    private val _mockLocation = MutableStateFlow<GeoPoint?>(null)
    val mockLocation: StateFlow<GeoPoint?> = _mockLocation.asStateFlow()

    // Whether the fixed-point injection service is currently locking the
    // system location onto the standalone virtual position
    private val _isFixedInjectEnabled = MutableStateFlow(false)
    val isFixedInjectEnabled: StateFlow<Boolean> = _isFixedInjectEnabled.asStateFlow()

    private var appContext: Context? = null
    private var stepSensorListener: SensorEventListener? = null

    /** The virtual position currently visible to other apps: the engine point while a route simulation is live, otherwise the standalone mock point. */
    val effectiveMockLocation: GeoPoint?
        get() = snapshot.value.currentPoint?.takeIf {
            snapshot.value.status == SimulationStatus.RUNNING ||
                    snapshot.value.status == SimulationStatus.PAUSED
        } ?: _mockLocation.value

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
        // B2: when a route simulation completes, adopt the finish point as the new
        // standalone virtual position (and persist it) so nothing "jumps back".
        viewModelScope.launch {
            var prevStatus = snapshot.value.status
            snapshot.collect { snap ->
                if (snap.status == SimulationStatus.COMPLETED && prevStatus != SimulationStatus.COMPLETED) {
                    snap.currentPoint?.let { p ->
                        _mockLocation.value = p
                        persistOverrideState()
                    }
                }
                prevStatus = snap.status
            }
        }
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

    // ------------------------------------------------------------------
    // Standalone override: initialization & persistence
    // ------------------------------------------------------------------

    /**
     * One-shot bootstrap for the standalone override subsystem, invoked from the
     * UI after the first composition:
     * 1. restore the last virtual position / virtual steps / injection toggle;
     * 2. start listening to the hardware step-counter sensor;
     * 3. resolve the real device position — on very first launch (no persisted
     *    virtual position) the real position becomes the initial virtual one;
     * 4. re-push the persisted virtual steps and (optionally) resume the
     *    fixed-point injection so the previous session's state is fully kept.
     */
    fun initializeOverride(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val prefs = appContext!!.getSharedPreferences(OVERRIDE_PREFS_NAME, Context.MODE_PRIVATE)
        // B1: coordinates persist as String (Double-exact); legacy Float entries are
        // still readable (~1m quantization) and get upgraded on the next write.
        fun readCoord(key: String): Double? = when (val v = prefs.all[key]) {
            is String -> v.toDoubleOrNull()
            is Float -> v.toDouble()
            else -> null
        }
        val savedLat = readCoord(PREF_MOCK_LAT)
        val savedLon = readCoord(PREF_MOCK_LON)
        if (savedLat != null && savedLon != null) {
            _mockLocation.value = GeoPoint(savedLat, savedLon, readCoord(PREF_MOCK_ALT) ?: 0.0)
        }
        _overrideSteps.value = if (prefs.contains(PREF_OVERRIDE_STEPS)) prefs.getLong(PREF_OVERRIDE_STEPS, 0L) else null
        _isFixedInjectEnabled.value = prefs.getBoolean(PREF_FIXED_INJECT, false)

        registerStepSensor()
        refreshRealLocation(silent = true)

        viewModelScope.launch {
            // Re-push persisted virtual steps so other apps pick them up again
            _overrideSteps.value?.let { steps ->
                healthManager.dispatchStepOverride(appContext!!, steps)
                delay(1500L)
                healthManager.dispatchStepOverride(appContext!!, steps)
                // HC records are date-scoped: re-write for the new day after rollover
                ensureHealthConnectPermission()
                healthConnectWriter.applySteps(appContext!!, steps)
            }
            // Resume fixed-point injection from the previous session
            if (_isFixedInjectEnabled.value && _mockLocation.value != null &&
                snapshot.value.status == SimulationStatus.IDLE
            ) {
                MockForegroundService.startFixedService(appContext!!, _mockLocation.value!!)
            }
        }
    }

    /** Try to ensure the Health Connect WRITE_STEPS grant (Root pm grant fallback). */
    private suspend fun ensureHealthConnectPermission(): Boolean {
        val ctx = appContext ?: return false
        if (healthConnectWriter.hasWritePermission(ctx)) return true
        // Attempt the privileged grant path (works via Root); then re-check once.
        rootExecutor.execute("pm grant ${ctx.packageName} android.permission.health.WRITE_STEPS")
        return healthConnectWriter.hasWritePermission(ctx)
    }

    private fun persistOverrideState() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(OVERRIDE_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val mock = _mockLocation.value
        if (mock != null) {
            editor.putString(PREF_MOCK_LAT, mock.latitude.toString())
            editor.putString(PREF_MOCK_LON, mock.longitude.toString())
            editor.putString(PREF_MOCK_ALT, mock.altitude.toString())
        } else {
            editor.remove(PREF_MOCK_LAT).remove(PREF_MOCK_LON).remove(PREF_MOCK_ALT)
        }
        val steps = _overrideSteps.value
        if (steps != null) editor.putLong(PREF_OVERRIDE_STEPS, steps) else editor.remove(PREF_OVERRIDE_STEPS)
        editor.putBoolean(PREF_FIXED_INJECT, _isFixedInjectEnabled.value)
        editor.apply()
    }

    private fun registerStepSensor() {
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            _sensorSteps.value = null
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    _sensorSteps.value = event.values.firstOrNull()?.toLong() ?: _sensorSteps.value
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        stepSensorListener = listener
    }

    private fun unregisterStepSensor() {
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        stepSensorListener?.let { sm.unregisterListener(it) }
        stepSensorListener = null
    }

    /**
     * Refresh the real device position. Skipped while a location source is being
     * mocked (fixed injection active or route simulation live) — TestProvider
     * locations would otherwise be mistaken for the real position.
     */
    @SuppressLint("MissingPermission")
    fun refreshRealLocation(silent: Boolean = false) {
        val ctx = appContext ?: return
        if (_isFixedInjectEnabled.value) {
            if (!silent) viewModelScope.launch { _toastMessage.emit("定点注入开启中，真实定位已暂停读取") }
            return
        }
        if (snapshot.value.status == SimulationStatus.RUNNING || snapshot.value.status == SimulationStatus.PAUSED) {
            if (!silent) viewModelScope.launch { _toastMessage.emit("仿真运行中，由路线驱动虚拟位置") }
            return
        }

        viewModelScope.launch {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@launch

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
                    if (l != null && (bestLoc == null || l.time > bestLoc.time)) bestLoc = l
                } catch (ignored: Exception) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val l = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                    if (l != null && (bestLoc == null || l.time > bestLoc.time)) bestLoc = l
                } catch (ignored: Exception) {
                }
            }

            if (bestLoc != null) {
                onRealLocationResolved(GeoPoint(bestLoc.latitude, bestLoc.longitude, bestLoc.altitude), silent)
            } else {
                try {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            unregisterSelf()
                            onRealLocationResolved(
                                GeoPoint(location.latitude, location.longitude, location.altitude),
                                silent
                            )
                        }

                        fun unregisterSelf() {
                            lm.removeUpdates(this)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    val fired = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null); true
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null); true
                    } else false
                    if (!fired && !silent) _toastMessage.emit("系统定位未开启，无法获取真实位置")
                } catch (e: Exception) {
                    if (!silent) _toastMessage.emit("真实定位获取失败: ${e.message}")
                }
            }
        }
    }

    private fun onRealLocationResolved(point: GeoPoint, silent: Boolean) {
        _deviceLocation.value = point
        // First-ever launch (no persisted virtual position): adopt the real
        // position as the initial virtual position.
        if (_mockLocation.value == null) {
            _mockLocation.value = point
            persistOverrideState()
            // B3: center the map so the user can actually see the adopted position
            viewModelScope.launch {
                _mapCenterEvent.emit(point)
                if (!silent) {
                    _toastMessage.emit("虚拟位置已初始化为当前真实位置")
                }
            }
        } else if (!silent) {
            viewModelScope.launch {
                _toastMessage.emit("真实位置已刷新 (%.4f, %.4f)".format(point.latitude, point.longitude))
            }
        }
    }

    // ------------------------------------------------------------------
    // Standalone override: public actions
    // ------------------------------------------------------------------

    /** Set (or move) the standalone virtual position; restarts the fixed injection if it is active. */
    fun setMockLocation(point: GeoPoint) {
        _mockLocation.value = point
        persistOverrideState()
        viewModelScope.launch {
            _mapCenterEvent.emit(point)
            _toastMessage.emit("虚拟位置已设置: %.5f, %.5f".format(point.latitude, point.longitude))
        }
        val ctx = appContext
        if (ctx != null && _isFixedInjectEnabled.value) {
            MockForegroundService.startFixedService(ctx, point)
        }
    }

    /** Sync the virtual position back to the current real device position. */
    fun syncMockToRealLocation() {
        val real = _deviceLocation.value
        if (real == null) {
            viewModelScope.launch { _toastMessage.emit("尚未获取真实位置，无法同步") }
            return
        }
        setMockLocation(real)
    }

    /** Enable / disable the 1Hz fixed-point location injection service. */
    fun setFixedInjectionEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            val status = snapshot.value.status
            if (status == SimulationStatus.RUNNING || status == SimulationStatus.PAUSED) {
                viewModelScope.launch { _toastMessage.emit("仿真运行中，虚拟位置由路线驱动") }
                return
            }
            // B5: without Root/mock privilege the injection fails silently — refuse
            // to flip the switch and tell the user what to do instead.
            if (isRootAvailable.value != true) {
                viewModelScope.launch { _toastMessage.emit("未获取 Root 权限，请先点顶部徽标一键赋权") }
                checkRootAccess()
                return
            }
            val point = _mockLocation.value ?: run {
                viewModelScope.launch { _toastMessage.emit("请先设置虚拟位置，再开启定点注入") }
                return
            }
            MockForegroundService.startFixedService(context, point)
            _isFixedInjectEnabled.value = true
            persistOverrideState()
            viewModelScope.launch { _toastMessage.emit("定点注入已开启：系统位置已锁定到虚拟位置") }
        } else {
            MockForegroundService.stopFixedService(context)
            _isFixedInjectEnabled.value = false
            persistOverrideState()
            viewModelScope.launch { _toastMessage.emit("定点注入已关闭，系统定位恢复正常") }
        }
    }

    /** Push a manually chosen step count to the health ecosystem (standalone, no route needed). */
    fun applyStepOverride(steps: Long) {
        if (steps < 0) return
        _overrideSteps.value = steps
        persistOverrideState()
        viewModelScope.launch {
            // Feedback first — the multi-pulse + root boost runs in the background (C1)
            _toastMessage.emit("已推送虚拟步数: $steps 步")
            val ctx = appContext
            if (ctx != null) {
                // Multiple pulses: some health apps only sample the counter on screen-on
                healthManager.dispatchStepOverride(ctx, steps)
                delay(2000L)
                healthManager.dispatchStepOverride(ctx, steps)
                delay(3000L)
                healthManager.dispatchStepOverride(ctx, steps)
            }
            // Root boost: re-broadcast the WeChat-Sport intent from system context
            rootExecutor.execute(
                "am broadcast -a com.tencent.mm.plugin.sport.ACTION_STEP_COUNTER " +
                        "--ei step_count ${steps.coerceAtMost(Int.MAX_VALUE.toLong())} " +
                        "--el step_timestamp ${System.currentTimeMillis()}"
            )
            // The effective channel: write into Health Connect so the system health
            // dashboard and HC-reading apps actually see the overridden steps.
            if (ctx != null) {
                if (ensureHealthConnectPermission()) {
                    val ok = healthConnectWriter.applySteps(ctx, steps)
                    if (!ok) {
                        _toastMessage.emit("Health Connect 步数写入失败")
                    }
                } else {
                    _toastMessage.emit("未获得 Health Connect 写入权限，步数仅通过广播推送")
                }
            }
        }
    }

    /** Leave SET_MOCK map-pick mode without choosing a point — restores the route flow. */
    fun cancelMockSelection() {
        if (_selectionMode.value != SelectionMode.SET_MOCK) return
        _selectionMode.value = when {
            _routeResult.value != null -> SelectionMode.NONE
            _startPoint.value != null -> SelectionMode.SET_END
            else -> SelectionMode.SET_START
        }
    }

    fun clearStepOverride() {
        _overrideSteps.value = null
        persistOverrideState()
        viewModelScope.launch {
            appContext?.let { healthConnectWriter.clearSteps(it) }
            _toastMessage.emit("已清除虚拟步数")
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
        // Same guard as onMapClick: never rewire the route while a simulation is live
        if (snapshot.value.status == SimulationStatus.RUNNING || snapshot.value.status == SimulationStatus.PAUSED) {
            viewModelScope.launch {
                _toastMessage.emit("仿真运行中，请先【结束】再修改路线")
            }
            return
        }
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
            // A2: while the fixed-point injection owns the system location, every
            // lastKnown/GPS read returns OUR OWN mock point — never treat it as the
            // real device position. Just center onto the virtual position instead.
            if (_isFixedInjectEnabled.value) {
                val mock = _mockLocation.value
                if (mock != null) {
                    _mapCenterEvent.emit(mock)
                    _toastMessage.emit("定点注入中，已居中到虚拟位置")
                } else {
                    _toastMessage.emit("定点注入中，真实定位读取已停用")
                }
                return@launch
            }

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
        // Block while a simulation is live (RUNNING *or* PAUSED) — re-planning would
        // reset the engine mid-run and desync the foreground service / notification.
        if (snapshot.value.status == SimulationStatus.RUNNING || snapshot.value.status == SimulationStatus.PAUSED) {
            viewModelScope.launch {
                _toastMessage.emit(
                    if (_selectionMode.value == SelectionMode.SET_MOCK) {
                        "仿真运行中，虚拟位置由路线驱动，请先【结束】"
                    } else {
                        "仿真运行中，请先【结束】再修改路线"
                    }
                )
            }
            return
        }

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
            SelectionMode.SET_MOCK -> {
                _selectionMode.value = SelectionMode.NONE
                setMockLocation(point)
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

    fun clearRoute(context: Context) {
        val status = snapshot.value.status
        if (status == SimulationStatus.RUNNING || status == SimulationStatus.PAUSED) return
        _startPoint.value = null
        _endPoint.value = null
        _routeResult.value = null
        _selectionMode.value = SelectionMode.SET_START
        engine.stop()
        engine.setRoute(emptyList())
        // After COMPLETED the service lingers in a detached state — shut it down so
        // no orphaned foreground service / summary notification is left behind.
        if (status == SimulationStatus.COMPLETED) {
            MockForegroundService.stopService(context)
        }
    }

    fun startSimulation(context: Context) {
        val route = _routeResult.value
        if (route == null || route.points.isEmpty()) {
            viewModelScope.launch {
                _toastMessage.emit("请先在地图上选择起点和终点规划路线！")
            }
            return
        }

        // The route engine takes over the injected location — drop the standalone
        // fixed-point injection state first (the service also cancels its ticker).
        if (_isFixedInjectEnabled.value) {
            _isFixedInjectEnabled.value = false
            persistOverrideState()
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

    override fun onCleared() {
        unregisterStepSensor()
        super.onCleared()
    }

    companion object {
        private const val OVERRIDE_PREFS_NAME = "stepshift_override_prefs"
        private const val PREF_MOCK_LAT = "mock_lat"
        private const val PREF_MOCK_LON = "mock_lon"
        private const val PREF_MOCK_ALT = "mock_alt"
        private const val PREF_OVERRIDE_STEPS = "override_steps"
        private const val PREF_FIXED_INJECT = "fixed_inject"
    }
}

