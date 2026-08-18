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
import com.example.stepshift.ui.LsposedModuleStatus

@Composable
fun SettingsDialog(
    isRootAvailable: Boolean?,
    config: SimulationConfig,
    moduleStatus: LsposedModuleStatus,
    hcWriteGranted: Boolean?,
    isLsposedChannelEnabled: Boolean,
    appVersion: String,
    onDismiss: () -> Unit,
    onGrantRootPermissions: () -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onOpenLsposedManager: () -> Unit,
    onOpenHealthSettings: () -> Unit,
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

                // Section 1.5: LSPosed module self-check
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LSPosed 步数注入模块",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = onRefreshSystemStatus, modifier = Modifier.size(22.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重新检测",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val (statusIcon, statusTint) = when {
                                moduleStatus.active -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
                                moduleStatus.installed -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
                                else -> Icons.Default.Cancel to MaterialTheme.colorScheme.error
                            }
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = statusTint
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        moduleStatus.active ->
                                            "已激活 · 传感器钩子运行中" +
                                                    (moduleStatus.versionName?.let { " (v$it)" } ?: "")
                                        moduleStatus.installed ->
                                            "已安装" + (moduleStatus.versionName?.let { " v$it" } ?: "") + " · 未激活"
                                        else -> "未安装模块"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusTint
                                )
                                Text(
                                    text = when {
                                        moduleStatus.active ->
                                            "虚拟步数可注入微信/QQ/支付宝 (作用域内需勾选目标应用)"
                                        moduleStatus.installed ->
                                            "请在 LSPosed 管理器启用模块，作用域勾选 StepShift 及目标应用，然后强行停止并重启对应应用"
                                        else ->
                                            "安装 xposed 模块 APK 并在 LSPosed 中启用后，步数才能注入微信/QQ/支付宝"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!moduleStatus.active && isLsposedChannelEnabled) {
                                    Text(
                                        text = "当前已选择 LSPosed 推送渠道，但模块未生效，该渠道不会推送步数",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (moduleStatus.installed && !moduleStatus.active) {
                                Button(
                                    onClick = onOpenLsposedManager,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("打开管理器", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // Section 1.6: Health Connect permission
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "系统健康 (Health Connect)",
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val (hcIcon, hcTint) = when (hcWriteGranted) {
                                true -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
                                false -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
                                null -> Icons.Default.Sync to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(
                                imageVector = hcIcon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = hcTint
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (hcWriteGranted) {
                                        true -> "步数写入已授权"
                                        false -> "未授予步数写入权限"
                                        null -> "正在检测权限..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = hcTint
                                )
                                if (hcWriteGranted == false) {
                                    Text(
                                        text = "虚拟步数将无法写入系统健康数据页",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (hcWriteGranted == false) {
                                Button(
                                    onClick = onOpenHealthSettings,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("去授权", style = MaterialTheme.typography.labelSmall)
                                }
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

                // About line
                Text(
                    text = "StepShift v$appVersion · LSPosed 模块 " +
                            (moduleStatus.versionName?.let { "v$it" } ?: "未安装"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

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
