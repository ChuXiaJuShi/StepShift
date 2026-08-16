package com.example.stepshift.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.stepshift.model.SimulationConfig

@Composable
fun SettingsDialog(
    isRootAvailable: Boolean?,
    config: SimulationConfig,
    onDismiss: () -> Unit,
    onGrantRootPermissions: () -> Unit,
    onUpdateConfig: (SimulationConfig) -> Unit
) {
    var autoCadence by remember { mutableStateOf(config.autoCadence) }
    var customCadence by remember { mutableStateOf(config.customCadenceSpm.toString()) }
    var driftIntensity by remember { mutableStateOf(config.driftIntensityMeters.toFloat()) }
    var injectGps by remember { mutableStateOf(config.injectRootGps) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "参数与特权设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                HorizontalDivider()

                // Section 1: Root & Mock Privilege
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Root (SU) 特权状态",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (isRootAvailable) {
                                    true -> "已获取 Root 特权 (UID: 0)"
                                    false -> "未检测到 Root 权限"
                                    null -> "正在检测 Root 权限..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isRootAvailable == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                            )

                            Button(
                                onClick = onGrantRootPermissions,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("一键赋权", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Section 2: Location Mocking Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "向系统注入真实 GPS",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "通过 TestProvider 与 Root 接口修改系统位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = injectGps,
                        onCheckedChange = { injectGps = it }
                    )
                }

                // Section 3: Cadence & Physics
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "运动生理学步频设置",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "根据配速智能自适应步频",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Switch(
                            checked = autoCadence,
                            onCheckedChange = { autoCadence = it }
                        )
                    }

                    if (!autoCadence) {
                        OutlinedTextField(
                            value = customCadence,
                            onValueChange = { customCadence = it.filter { ch -> ch.isDigit() } },
                            label = { Text("固定步频 (步/分)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Section 4: GPS Drift Intensity
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "GPS 微漂移强度 (%.2f 米)".format(driftIntensity),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = driftIntensity,
                        onValueChange = { driftIntensity = it },
                        valueRange = 0.2f..3.0f,
                        steps = 28
                    )
                }

                // Save Button
                Button(
                    onClick = {
                        val customCad = customCadence.toIntOrNull() ?: 115
                        onUpdateConfig(
                            config.copy(
                                autoCadence = autoCadence,
                                customCadenceSpm = customCad,
                                driftIntensityMeters = driftIntensity.toDouble(),
                                injectRootGps = injectGps
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存配置", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
