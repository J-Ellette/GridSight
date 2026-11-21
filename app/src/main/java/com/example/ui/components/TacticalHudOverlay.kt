package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.export.ExportCategory
import com.example.data.export.ExportFormat
import com.example.data.export.ExportTimeRange
import com.example.data.local.AccountRole
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.SecurityPosture
import com.example.sensors.TelemetryStreamEvent
import com.example.threat.ThreatAlert
import com.example.threat.ThreatSeverity
import com.example.ui.ExportUiState
import com.example.ui.GridSightUiState
import com.example.ui.theme.LocalTacticalPalette

@Composable
fun TacticalHudHeader(
    state: GridSightUiState,
    onToggleScan: () -> Unit,
    onCycleVisualMode: () -> Unit,
    onOpenThreats: () -> Unit,
    onOpenExport: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    val infinite = rememberInfiniteTransition(label = "hudPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val highestThreat = state.threats.firstOrNull()
    val role = state.auth.activeAccount?.role ?: AccountRole.STANDARD
    val callsign = state.auth.activeAccount?.callsign ?: state.config?.callsign ?: "UNIT-01"

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = palette.hudPanelAlpha),
        border = BorderStroke(palette.borderWidth, palette.hudBorder),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = "Scan status",
                        tint = if (state.isScanningActive) palette.secureGreen else palette.accentCyan,
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(if (state.isScanningActive) pulseAlpha else 1f)
                    )
                    Column {
                        Text(
                            text = "$callsign [${role.code}]",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (state.isScanningActive) {
                                "SCAN LIVE • BLE:${state.hardware.bleScanning} • E2EE"
                            } else {
                                "HUD STANDBY • ${state.nodes.size} CACHED • E2EE"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accentCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Threat Badge
                    Surface(
                        color = when (highestThreat?.severity) {
                            ThreatSeverity.CRITICAL -> palette.alertCrimson
                            ThreatSeverity.HIGH, ThreatSeverity.MEDIUM -> palette.cautionAmber
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .height(48.dp)
                            .clickable(onClick = onOpenThreats)
                            .testTag("hud_threat_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (highestThreat != null) Icons.Filled.Warning else Icons.Filled.Shield,
                                contentDescription = "Threat alerts",
                                tint = if (highestThreat != null) MaterialTheme.colorScheme.onError else palette.secureGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${state.threats.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (highestThreat != null) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenExport,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hud_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Export scan data",
                            tint = palette.accentCyan
                        )
                    }

                    IconButton(
                        onClick = onCycleVisualMode,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hud_visual_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Contrast,
                            contentDescription = "Cycle visual mode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onLogout,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hud_logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Log out",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Animated Live Telemetry Data Stream + Master Scan Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryDataStreamStrip(
                    events = state.telemetryFeed,
                    isScanning = state.isScanningActive,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onToggleScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isScanningActive) palette.alertCrimson else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("hud_master_scan_button")
                ) {
                    Text(
                        text = if (state.isScanningActive) "HALT" else "SCAN",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryDataStreamStrip(
    events: List<TelemetryStreamEvent>,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalTacticalPalette.current
    val latest = events.firstOrNull()
    val scrollState = rememberScrollState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isScanning) "RX//STREAM:" else "BUF//LOG:",
                style = MaterialTheme.typography.labelSmall,
                color = if (isScanning) palette.secureGreen else palette.accentCyan,
                fontWeight = FontWeight.Bold
            )
            if (latest == null) {
                Text(
                    text = "AWAITING RF / NFC / MESH PACKETS...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                events.take(4).forEach { ev ->
                    Text(
                        text = "[${ev.protocol}] ${ev.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ev.isAlert) palette.alertCrimson else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ProfilerDossierOverlay(
    node: DetectedNodeEntity,
    operatorRole: AccountRole,
    onConnectNode: () -> Unit,
    onTogglePin: () -> Unit,
    onShareIntel: () -> Unit,
    onDeleteNode: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTacticalPalette.current
    val context = LocalContext.current
    var expanded by remember(node.id) { mutableStateOf(true) }
    val signalNormalized = ((node.rssi + 100).coerceIn(0, 70) / 70f)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(
            palette.borderWidth,
            if (node.securityPosture.isWarning) palette.alertCrimson else palette.accentCyan
        ),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .drawBehind {
                // ctOS corner bracket ticks
                val stroke = 3.dp.toPx()
                val len = 14.dp.toPx()
                val accent = if (node.securityPosture.isWarning) palette.alertCrimson else palette.accentCyan
                drawLine(accent, Offset(0f, 0f), Offset(len, 0f), stroke)
                drawLine(accent, Offset(0f, 0f), Offset(0f, len), stroke)
                drawLine(accent, Offset(size.width, size.height), Offset(size.width - len, size.height), stroke)
                drawLine(accent, Offset(size.width, size.height), Offset(size.width, size.height - len), stroke)
            }
            .testTag("profiler_overlay_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${node.nodeType.code} // ${node.securityPosture.code}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (node.securityPosture.isWarning) palette.alertCrimson else palette.accentCyan
                    )
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Expand profile details"
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("profiler_overlay_close")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close target overlay")
                    }
                }
            }

            Text(
                text = node.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SIG ${node.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { signalNormalized },
                    color = if (node.securityPosture.isWarning) palette.cautionAmber else palette.secureGreen,
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                )
                Text(
                    text = node.syncStatus.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentCyan
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "COORD: %.5f, %.5f • SRC: %s".format(
                            node.latitude,
                            node.longitude,
                            node.sourcePeerCallsign
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = node.metadataSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val connectLabel = when (node.nodeType) {
                        NodeType.WIFI_AP, NodeType.PUBLIC_HOTSPOT ->
                            if (node.securityPosture == SecurityPosture.OPEN_UNENCRYPTED || node.securityPosture == SecurityPosture.PUBLIC_CIVIC) {
                                "CLICK TO CONNECT (OPEN WI-FI)"
                            } else {
                                "CLICK TO CONNECT (WI-FI)"
                            }
                        NodeType.BLE_DEVICE -> "CLICK TO CONNECT (BLE GATT)"
                        NodeType.NFC_TAG -> "CLICK TO CONNECT (NFC)"
                        NodeType.PUBLIC_CAMERA -> "CLICK TO VIEW (PUBLIC CAMERA)"
                        NodeType.TEAM_PEER -> "CLICK TO CONNECT (E2EE PEER)"
                    }
                    Button(
                        onClick = onConnectNode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (node.securityPosture.isWarning) palette.cautionAmber else palette.secureGreen,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("overlay_connect_button")
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(connectLabel, style = MaterialTheme.typography.labelLarge)
                    }

                    if (node.feedOrReferenceUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(node.feedOrReferenceUrl))
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("open_feed_link_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "OPEN CIVIC / CAMERA LINK",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onTogglePin,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("overlay_pin_button")
                        ) {
                            Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(if (node.isPinned) "UNPIN" else "PIN", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onShareIntel,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("overlay_share_button")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("SHARE E2EE", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(
                            onClick = onDeleteNode,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_delete_button")
                        ) {
                            Icon(
                                imageVector = if (operatorRole == AccountRole.ADMIN) Icons.Filled.Delete else Icons.Filled.Lock,
                                contentDescription = if (operatorRole == AccountRole.ADMIN) "Delete node" else "Admin only delete",
                                tint = if (operatorRole == AccountRole.ADMIN) palette.alertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreatAlertSheet(
    threats: List<ThreatAlert>,
    onInspectTarget: (ThreatAlert) -> Unit,
    onAcknowledge: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "REAL-TIME THREAT & ANOMALY ENGINE (${threats.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (threats.isEmpty()) {
                Text(
                    text = "No active RF, Evil-Twin, cipher integrity, or peer biometric anomalies detected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secureGreen,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    items(threats, key = { it.id }) { alert ->
                        val accent = when (alert.severity) {
                            ThreatSeverity.CRITICAL -> palette.alertCrimson
                            ThreatSeverity.HIGH -> palette.cautionAmber
                            ThreatSeverity.MEDIUM -> MaterialTheme.colorScheme.secondary
                            ThreatSeverity.LOW -> palette.accentCyan
                        }
                        Card(
                            border = BorderStroke(palette.borderWidth, accent),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "[${alert.severity.label}] ${alert.category.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent
                                )
                                Text(
                                    text = alert.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = alert.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "MITIGATION: ${alert.mitigation}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.secureGreen
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (alert.targetNodeId != null) {
                                        Button(
                                            onClick = { onInspectTarget(alert) },
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Text("INSPECT TARGET")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { onAcknowledge(alert.id) },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("ACKNOWLEDGE")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDataSheet(
    exportState: ExportUiState,
    operatorRole: AccountRole,
    onUpdateConfig: (ExportFormat?, ExportTimeRange?, ExportCategory?) -> Unit,
    onLaunchSaveDocument: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "EXPORT COLLECTED SCAN DATA",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Includes device RF telemetry, detected hotspots, and civic camera feed links (${operatorRole.displayLabel}).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("FORMAT", style = MaterialTheme.typography.labelMedium, color = palette.accentCyan)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { fmt ->
                    FilterChip(
                        selected = exportState.format == fmt,
                        onClick = { onUpdateConfig(fmt, null, null) },
                        label = { Text(fmt.label) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("export_format_${fmt.extension}")
                    )
                }
            }

            Text("DATA TIME RANGE", style = MaterialTheme.typography.labelMedium, color = palette.accentCyan)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportTimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = exportState.timeRange == range,
                        onClick = { onUpdateConfig(null, range, null) },
                        label = { Text(range.label) },
                        modifier = Modifier.height(48.dp)
                    )
                }
            }

            Text("CATEGORY FILTER", style = MaterialTheme.typography.labelMedium, color = palette.accentCyan)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = exportState.category == cat,
                        onClick = { onUpdateConfig(null, null, cat) },
                        label = { Text(cat.label) },
                        modifier = Modifier.height(48.dp)
                    )
                }
            }

            Text(
                text = "MATCHING RECORDS IN RANGE: ${exportState.matchingCount}",
                style = MaterialTheme.typography.labelLarge,
                color = palette.secureGreen
            )

            exportState.lastExportMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.accentCyan
                )
            }

            Button(
                onClick = { onLaunchSaveDocument(exportState.format) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("export_confirm_button")
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("SAVE ${exportState.format.extension.uppercase()} TO DEVICE")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
