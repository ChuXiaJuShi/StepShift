package com.example.stepshift.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepshift.model.RouteResult
import com.example.stepshift.model.SimulationConfig
import com.example.stepshift.model.SimulationSnapshot
import com.example.stepshift.model.SimulationStatus
import com.example.stepshift.ui.SelectionMode

@Composable
fun ControlPanel(
    modifier: Modifier = Modifier,
    snapshot: SimulationSnapshot,
    config: SimulationConfig,
    routeResult: RouteResult?,
    selectionMode: SelectionMode,
    isLoadingRoute: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    onClearRouteClick: () -> Unit,
    onSpeedChange: (Double) -> Unit,
    onGpsDriftToggle: (Boolean) -> Unit
) {
    val isRunning = snapshot.status == SimulationStatus.RUNNING
    val isPaused = snapshot.status == SimulationStatus.PAUSED
    val isCompleted = snapshot.status == SimulationStatus.COMPLETED

    // NOTE: no navigationBarsPadding on the Surface itself — the panel background
    // must extend to the physical bottom edge so it is visually seamless with the
    // system gesture pill. Only the interactive content is inset-aware.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Collapse handle — generous 24dp touch target (was ~8dp and nearly untappable)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable { onToggleExpand() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            // Status strip — always visible; the whole strip toggles expand/collapse.
            // When collapsed it also carries compact action buttons so the panel can
            // shrink to a single slim row without losing control of the simulation.
            Surface(
                onClick = onToggleExpand,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLoadingRoute) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "正在规划路网...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (routeResult != null) {
                            Icon(
                                imageVector = Icons.Default.AltRoute,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "路网已就绪 (${routeResult.formatDistanceKm()})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = when (selectionMode) {
                                    SelectionMode.SET_START -> "请点击地图或搜索设置【起点】"
                                    SelectionMode.SET_END -> "请点击地图或搜索设置【终点】"
                                    SelectionMode.NONE -> "点击地图重选起点"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!isExpanded) {
                        CompactActionButton(
                            snapshot = snapshot,
                            routeResult = routeResult,
                            isLoadingRoute = isLoadingRoute,
                            onStartClick = onStartClick,
                            onPauseClick = onPauseClick,
                            onResumeClick = onResumeClick,
                            onStopClick = onStopClick
                        )
                    }

                    if (routeResult != null && !isRunning) {
                        TextButton(
                            onClick = onClearRouteClick,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(text = "重选", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        modifier = Modifier
                            .size(36.dp)
                            .padding(6.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded area: detailed settings + full-width action buttons
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Speed Slider & Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "配速调节",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "%.1f km/h".format(config.speedKmH),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = config.speedKmH.toFloat(),
                        onValueChange = { onSpeedChange(it.toDouble()) },
                        valueRange = 1.0f..25.0f,
                        steps = 47,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    // Quick Presets Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpeedChip(label = "🚶 散步 3.5 km/h", targetSpeed = 3.5, currentSpeed = config.speedKmH) { onSpeedChange(3.5) }
                        SpeedChip(label = "🚶 健走 5.0 km/h", targetSpeed = 5.0, currentSpeed = config.speedKmH) { onSpeedChange(5.0) }
                        SpeedChip(label = "🏃 慢跑 8.0 km/h", targetSpeed = 8.0, currentSpeed = config.speedKmH) { onSpeedChange(8.0) }
                        SpeedChip(label = "🏃 畅跑 12.0 km/h", targetSpeed = 12.0, currentSpeed = config.speedKmH) { onSpeedChange(12.0) }
                    }

                    // GPS Noise Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Grain,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (config.enableGpsDrift) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "GPS 微漂移扰动 (抗反作弊)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Switch(
                            checked = config.enableGpsDrift,
                            onCheckedChange = { onGpsDriftToggle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.secondary,
                                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }

                    // Full-width Primary Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isRunning) {
                            // Pause Button
                            Button(
                                onClick = onPauseClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "暂停", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }

                            // Stop Button
                            Button(
                                onClick = onStopClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "结束", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (isPaused) {
                            // Resume Button
                            Button(
                                onClick = onResumeClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "继续", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }

                            // Stop Button
                            Button(
                                onClick = onStopClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "结束", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            // Start Button (disabled while planning / without route; relabeled after completion)
                            val startEnabled = !isLoadingRoute && routeResult != null && routeResult.points.isNotEmpty()
                            Button(
                                onClick = onStartClick,
                                enabled = startEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                when {
                                    isLoadingRoute -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "正在规划路网...",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    isCompleted -> {
                                        Icon(imageVector = Icons.Default.Replay, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "重新开始仿真",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    else -> {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (routeResult != null) "开始运动仿真" else "请先选点规划路线",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact in-strip action shown only when the panel is collapsed, so the slim
 * bar never loses control over the simulation. Icon-only buttons keep the
 * collapsed strip on a single row.
 */
@Composable
private fun CompactActionButton(
    snapshot: SimulationSnapshot,
    routeResult: RouteResult?,
    isLoadingRoute: Boolean,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        when (snapshot.status) {
            SimulationStatus.RUNNING -> {
                CompactIconButton(
                    onClick = onPauseClick,
                    icon = Icons.Default.Pause,
                    contentDescription = "暂停",
                    tint = MaterialTheme.colorScheme.tertiary
                )
                CompactIconButton(
                    onClick = onStopClick,
                    icon = Icons.Default.Stop,
                    contentDescription = "结束",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            SimulationStatus.PAUSED -> {
                CompactIconButton(
                    onClick = onResumeClick,
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "继续",
                    tint = MaterialTheme.colorScheme.primary
                )
                CompactIconButton(
                    onClick = onStopClick,
                    icon = Icons.Default.Stop,
                    contentDescription = "结束",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            SimulationStatus.COMPLETED -> {
                CompactIconButton(
                    onClick = onStartClick,
                    icon = Icons.Default.Replay,
                    contentDescription = "重新开始仿真",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            SimulationStatus.IDLE -> {
                if (routeResult != null && !isLoadingRoute) {
                    CompactIconButton(
                        onClick = onStartClick,
                        icon = Icons.Default.PlayArrow,
                        contentDescription = "开始运动仿真",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.18f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(34.dp)
                .padding(7.dp),
            tint = tint
        )
    }
}

@Composable
private fun SpeedChip(
    label: String,
    targetSpeed: Double,
    currentSpeed: Double,
    onClick: () -> Unit
) {
    val isSelected = Math.abs(currentSpeed - targetSpeed) < 0.2
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
