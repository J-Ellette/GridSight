package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountRole
import com.example.data.local.ReadinessStatus
import com.example.ui.GridSightUiState
import com.example.ui.theme.LocalTacticalPalette
import kotlin.math.roundToInt

@Composable
fun BiometricsMeshScreen(
    state: GridSightUiState,
    onUpdateBiometrics: (shareOptIn: Boolean, bpm: Int, readiness: ReadinessStatus) -> Unit,
    onPairPeerManual: (host: String, port: String) -> Unit,
    onFlushSync: () -> Unit,
    onUpdateOperatorRole: (username: String, role: AccountRole) -> Unit,
    onRotateEcdhKeys: () -> Unit,
    onUpdateChannelPassphrase: (String) -> Unit,
    onPurgeDatabase: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    val cfg = state.config
    val activeRole = state.auth.activeAccount?.role ?: AccountRole.STANDARD
    val isAdmin = activeRole == AccountRole.ADMIN

    var shareOptIn by remember(cfg?.shareBiometricsOptIn) { mutableStateOf(cfg?.shareBiometricsOptIn ?: true) }
    var bpmSlider by remember(cfg?.selfHeartRateBpm) { mutableFloatStateOf((cfg?.selfHeartRateBpm ?: 74).toFloat()) }
    var readiness by remember(cfg?.readinessStatus) { mutableStateOf(cfg?.readinessStatus ?: ReadinessStatus.NOMINAL) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var channelInput by remember(cfg?.channelPassphrase) { mutableStateOf(cfg?.channelPassphrase ?: "") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Self Biometric & Field Telemetry Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(palette.borderWidth, palette.hudBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = palette.alertCrimson)
                            Text("SELF TELEMETRY & BIOMETRIC OPT-IN", style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(
                            checked = shareOptIn,
                            onCheckedChange = {
                                shareOptIn = it
                                onUpdateBiometrics(it, bpmSlider.roundToInt(), readiness)
                            },
                            modifier = Modifier.testTag("bio_opt_in_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "MOTION: ${state.hardware.motionState}",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentCyan
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryFull, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.secureGreen)
                            Text(
                                text = " ${state.hardware.batteryPercent}% BAT",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.secureGreen
                            )
                        }
                    }

                    Text(
                        text = "HEART RATE TELEMETRY: ${bpmSlider.roundToInt()} BPM",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = bpmSlider,
                        onValueChange = { bpmSlider = it },
                        onValueChangeFinished = {
                            onUpdateBiometrics(shareOptIn, bpmSlider.roundToInt(), readiness)
                        },
                        valueRange = 45f..185f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReadinessStatus.entries.forEach { status ->
                            FilterChip(
                                selected = readiness == status,
                                onClick = {
                                    readiness = status
                                    onUpdateBiometrics(shareOptIn, bpmSlider.roundToInt(), status)
                                },
                                label = { Text(status.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }
        }

        // Local P2P Mesh Discovery & Offline Sync
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(palette.borderWidth, palette.hudBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = palette.accentCyan)
                        Text("LOCAL P2P MESH & OFFLINE SYNC", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "ENDPOINT: ${state.mesh.localIp}:${state.mesh.listenPort} • mDNS NSD: ${if (state.mesh.nsdRegistered) "ACTIVE" else "INIT"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.secureGreen
                    )
                    Text(
                        text = "ECDH FP: ${cfg?.keyFingerprint ?: "..."}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("Peer LAN IP") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onPairPeerManual(manualHost, manualPort) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mesh_pair_button")
                        ) {
                            Text("PAIR E2EE")
                        }
                        OutlinedButton(
                            onClick = onFlushSync,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mesh_sync_button")
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("FLUSH QUEUE")
                        }
                    }
                }
            }
        }

        // Consented Nearby Peers Roster
        item {
            Text(
                text = "CONSENTED TEAM BIOMETRICS (${state.peers.size})",
                style = MaterialTheme.typography.labelLarge,
                color = palette.accentCyan
            )
        }

        if (state.peers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "No nearby team handhelds paired yet. Handhelds on the same Wi-Fi/Hotspot auto-discover via NSD or can pair via LAN IP:Port / NFC handshake.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(state.peers, key = { it.peerId }) { peer ->
                val bioColor = when (peer.readinessStatus) {
                    ReadinessStatus.DISTRESS -> palette.alertCrimson
                    ReadinessStatus.CAUTION -> palette.cautionAmber
                    else -> palette.secureGreen
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(palette.borderWidth, bioColor)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${peer.callsign} (${peer.role})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = peer.readinessStatus.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = bioColor
                            )
                        }
                        Text(
                            text = "HR: ${if (peer.heartRateBpm > 0) "${peer.heartRateBpm} BPM" else "HIDDEN"} • ${peer.motionState} • ${peer.batteryPercent}% BAT",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ECDH: ${peer.keyFingerprint} • ${peer.hostAddress}:${peer.port}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // RBAC Admin Command Console
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    palette.borderWidth,
                    if (isAdmin) palette.accentCyan else MaterialTheme.colorScheme.outline
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isAdmin) Icons.Filled.AdminPanelSettings else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isAdmin) palette.accentCyan else palette.cautionAmber
                        )
                        Text(
                            text = if (isAdmin) "RBAC ADMIN COMMAND CONSOLE" else "ADMIN CONSOLE [LOCKED: STANDARD ROLE]",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isAdmin) palette.accentCyan else palette.cautionAmber
                        )
                    }

                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        enabled = isAdmin,
                        label = { Text("Team AES-256 Channel Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onUpdateChannelPassphrase(channelInput) },
                            enabled = isAdmin,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("admin_update_channel_button")
                        ) {
                            Text("SET CIPHER")
                        }
                        OutlinedButton(
                            onClick = onRotateEcdhKeys,
                            enabled = isAdmin,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("admin_rotate_keys_button")
                        ) {
                            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("ROTATE ECDH")
                        }
                    }

                    state.accounts.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${acc.callsign} (@${acc.username})",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "ROLE: ${acc.role.displayLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accentCyan
                                )
                            }
                            if (isAdmin) {
                                val nextRole = if (acc.role == AccountRole.ADMIN) AccountRole.STANDARD else AccountRole.ADMIN
                                OutlinedButton(
                                    onClick = { onUpdateOperatorRole(acc.username, nextRole) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(if (acc.role == AccountRole.ADMIN) "DEMOTE" else "PROMOTE")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onPurgeDatabase,
                        enabled = isAdmin,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.alertCrimson),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_purge_db_button")
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("PURGE OFFLINE TELEMETRY DB")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}
