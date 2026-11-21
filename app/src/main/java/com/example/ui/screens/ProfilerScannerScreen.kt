package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.ui.GridSightUiState
import com.example.ui.ProfilerFilter
import com.example.ui.theme.LocalTacticalPalette
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProfilerScannerScreen(
    state: GridSightUiState,
    onSetFilter: (ProfilerFilter) -> Unit,
    onSelectNode: (DetectedNodeEntity) -> Unit,
    onConnectNode: (DetectedNodeEntity) -> Unit,
    onPermissionsUpdated: () -> Unit,
    onToggleScan: () -> Unit
) {
    val palette = LocalTacticalPalette.current

    val requiredPermissions = rememberPermissionsList()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsUpdated()
    }

    val infinite = rememberInfiniteTransition(label = "profilerRadar")
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2600, easing = LinearEasing)),
        label = "radarSweep"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Permission Rationale Card if Location or Bluetooth permissions missing
        if (!state.hardware.locationPermissionGranted || !state.hardware.bluetoothPermissionGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(palette.borderWidth, palette.cautionAmber)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SENSOR AUTHORIZATION REQUIRED",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.cautionAmber
                        )
                        Text(
                            text = "Bluetooth LE and Wi-Fi RF sweeps require foreground Location & Bluetooth scan permissions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("authorize_sensors_button")
                        ) {
                            Text("AUTHORIZE RF SENSORS")
                        }
                    }
                }
            }
        }

        // Dynamic ctOS Radar + Hardware Status Telemetry
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(palette.borderWidth, palette.hudBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clickable(onClick = onToggleScan)
                            .testTag("radar_widget"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = size.minDimension / 2f
                            drawCircle(palette.gridLine, r, c, style = Stroke(2f))
                            drawCircle(palette.gridLine, r * 0.66f, c, style = Stroke(1.5f))
                            drawCircle(palette.gridLine, r * 0.33f, c, style = Stroke(1.5f))
                            drawLine(palette.gridLine, Offset(0f, c.y), Offset(size.width, c.y), 1f)
                            drawLine(palette.gridLine, Offset(c.x, 0f), Offset(c.x, size.height), 1f)

                            if (state.isScanningActive) {
                                val rad = Math.toRadians(sweepAngle.toDouble())
                                val tip = Offset(
                                    c.x + (r * cos(rad)).toFloat(),
                                    c.y + (r * sin(rad)).toFloat()
                                )
                                drawLine(
                                    brush = Brush.linearGradient(
                                        colors = listOf(palette.radarBeam, Color.Transparent),
                                        start = c,
                                        end = tip
                                    ),
                                    start = c,
                                    end = tip,
                                    strokeWidth = 4f
                                )
                            }

                            state.filteredNodes.take(16).forEachIndexed { idx, node ->
                                val angle = (node.macOrId.hashCode() % 360) * Math.PI / 180.0
                                val distNorm = ((-node.rssi).coerceIn(30, 100) - 25) / 80f
                                val dotPos = Offset(
                                    c.x + (r * distNorm * cos(angle)).toFloat(),
                                    c.y + (r * distNorm * sin(angle)).toFloat()
                                )
                                drawCircle(
                                    color = if (node.securityPosture.isWarning) palette.alertCrimson else palette.secureGreen,
                                    radius = if (idx == 0) 5f else 3.5f,
                                    center = dotPos
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (state.isScanningActive) "MULTI-SENSOR SWEEP ACTIVE" else "SENSOR SWEEP STANDBY",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.isScanningActive) palette.secureGreen else palette.accentCyan
                        )
                        Text(
                            text = "BLE: ${if (state.hardware.bleAvailable) "READY" else "UNAVAILABLE"} • WI-FI: ${if (state.hardware.wifiAvailable) "READY" else "N/A"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "NFC: ${if (state.hardware.nfcEnabled) "FOREGROUND ARMED" else if (state.hardware.nfcAvailable) "DISABLED" else "NO HARDWARE"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "TARGETS: ${state.filteredNodes.size} FILTERED / ${state.nodes.size} TOTAL",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentCyan
                        )
                    }
                }
            }
        }

        // Category Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilerFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.activeFilter == filter,
                        onClick = { onSetFilter(filter) },
                        label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("profiler_filter_${filter.name}")
                    )
                }
            }
        }

        // Empty State or Detected Target Cards
        if (state.filteredNodes.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = null,
                            tint = palette.accentCyan,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "NO TARGETS IN CURRENT FILTER",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap SCAN in the HUD to sweep Bluetooth LE & 802.11 Wi-Fi radios, tap an NFC tag to the device, or query public hotspots & cameras on the Map HUD.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.filteredNodes, key = { it.id }) { node ->
                val isSelected = state.selectedNode?.id == node.id
                val accent = when {
                    node.securityPosture.isWarning -> palette.alertCrimson
                    isSelected -> palette.accentCyan
                    else -> MaterialTheme.colorScheme.outline
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (isSelected) 2.dp else palette.borderWidth, accent),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectNode(node) }
                        .testTag("profiler_node_card_${node.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = when (node.nodeType) {
                                NodeType.BLE_DEVICE -> Icons.Filled.Bluetooth
                                NodeType.WIFI_AP, NodeType.PUBLIC_HOTSPOT -> Icons.Filled.Wifi
                                NodeType.NFC_TAG -> Icons.Filled.Nfc
                                NodeType.PUBLIC_CAMERA -> Icons.Filled.Videocam
                                NodeType.TEAM_PEER -> Icons.Filled.Security
                            },
                            contentDescription = node.nodeType.displayLabel,
                            tint = if (node.securityPosture.isWarning) palette.alertCrimson else palette.accentCyan,
                            modifier = Modifier.size(26.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = node.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (node.securityPosture.isWarning) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = "Insecure posture",
                                        tint = palette.alertCrimson,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${node.nodeType.code} • ${node.securityPosture.code} • ${node.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (node.securityPosture.isWarning) palette.cautionAmber else palette.accentCyan
                            )
                            Text(
                                text = node.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { onConnectNode(node) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("profiler_connect_${node.id}")
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = if (node.nodeType == NodeType.PUBLIC_CAMERA) "VIEW" else "CONNECT",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

private fun rememberPermissionsList(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms.add(Manifest.permission.BLUETOOTH_SCAN)
        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    return perms.toTypedArray()
}
