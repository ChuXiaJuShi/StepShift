package com.example.stepshift.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stepshift.ui.components.ControlPanel
import com.example.stepshift.ui.components.MapViewContainer
import com.example.stepshift.ui.components.SearchBarOverlay
import com.example.stepshift.ui.components.SettingsDialog
import com.example.stepshift.ui.components.TelemetryDashboard
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepShiftApp(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current

    val snapshot by viewModel.snapshot.collectAsState()
    val config by viewModel.config.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val startPoint by viewModel.startPoint.collectAsState()
    val endPoint by viewModel.endPoint.collectAsState()
    val routeResult by viewModel.routeResult.collectAsState()
    val isLoadingRoute by viewModel.isLoadingRoute.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()

    // Search state
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    val isTrackingEnabled by viewModel.isTrackingEnabled.collectAsState()
    val isControlPanelExpanded by viewModel.isControlPanelExpanded.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Telemetry HUD only earns its screen space while a simulation is active,
    // and must never compete with the search result dropdown.
    val showDashboard = snapshot.status != com.example.stepshift.model.SimulationStatus.IDLE && searchResults.isEmpty()

    // Toast listener
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsRun,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "StepShift",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                },
                actions = {
                    // Clickable Root Status Pill (tap to recheck / grant)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (isRootAvailable) {
                            true -> MaterialTheme.colorScheme.secondaryContainer
                            false -> MaterialTheme.colorScheme.errorContainer
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clickable(enabled = isRootAvailable != null) {
                                viewModel.checkRootAccess()
                                if (isRootAvailable == false) {
                                    viewModel.grantRootMockPermissions(context)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = when (isRootAvailable) {
                                    true -> Icons.Default.CheckCircle
                                    false -> Icons.Default.Warning
                                    null -> Icons.Default.Sync
                                },
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = when (isRootAvailable) {
                                    true -> MaterialTheme.colorScheme.secondary
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = when (isRootAvailable) {
                                    true -> "ROOT: OK"
                                    false -> "NO ROOT (点击赋权)"
                                    null -> "检测中..."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (isRootAvailable) {
                                    true -> MaterialTheme.colorScheme.onSecondaryContainer
                                    false -> MaterialTheme.colorScheme.onErrorContainer
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    IconButton(onClick = { viewModel.loadSampleRoute() }) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = "加载示例路线",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        }
    ) { innerPadding ->
        // Outer box spans the full content area INCLUDING the navigation-bar strip,
        // so the bottom panel's background can reach the physical screen edge.
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Consume the insets Scaffold already turned into padding, so
                    // children do not apply the navigation-bar inset a second time.
                    .consumeWindowInsets(innerPadding)
            ) {
                // 1. Fullscreen Map View with Real-time Camera Tracking
                MapViewContainer(
                    modifier = Modifier.fillMaxSize(),
                    startPoint = startPoint,
                    endPoint = endPoint,
                    routeResult = routeResult,
                    snapshot = snapshot,
                    isTrackingEnabled = isTrackingEnabled,
                    centerEvent = viewModel.mapCenterEvent,
                    onMapClick = { viewModel.onMapClick(it) },
                    onToggleTracking = { viewModel.toggleTracking(context) },
                    onUserPanMap = { viewModel.onUserPanMap() }
                )

                // 2. Top Floating Area (Search Bar + Telemetry HUD)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Search Bar Overlay
                    SearchBarOverlay(
                        query = searchQuery,
                        results = searchResults,
                        isSearching = isSearching,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        onClearQuery = { viewModel.clearSearch() },
                        onSelectLocation = { result, asStart ->
                            viewModel.onSearchResultSelected(result, asStart)
                        }
                    )

                    // Telemetry Dashboard HUD (hidden while idle or while search results are shown)
                    AnimatedVisibility(visible = showDashboard) {
                        TelemetryDashboard(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            snapshot = snapshot
                        )
                    }
                }
            }

            // 3. Bottom Control Panel — sibling overlay whose background extends
            //    to the physical bottom edge (seamless with the gesture pill);
            //    its own content applies navigationBarsPadding internally.
            ControlPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                snapshot = snapshot,
                config = config,
                routeResult = routeResult,
                selectionMode = selectionMode,
                isLoadingRoute = isLoadingRoute,
                isExpanded = isControlPanelExpanded,
                onToggleExpand = { viewModel.toggleControlPanel() },
                onStartClick = { viewModel.startSimulation(context) },
                onPauseClick = { viewModel.pauseSimulation() },
                onResumeClick = { viewModel.resumeSimulation() },
                onStopClick = { viewModel.stopSimulation(context) },
                onClearRouteClick = { viewModel.clearRoute() },
                onSpeedChange = { viewModel.updateSpeed(it) },
                onGpsDriftToggle = { viewModel.updateGpsDrift(it) }
            )
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            isRootAvailable = isRootAvailable,
            config = config,
            onDismiss = { showSettingsDialog = false },
            onGrantRootPermissions = { viewModel.grantRootMockPermissions(context) },
            onUpdateConfig = { viewModel.updateConfig(it) }
        )
    }
}
