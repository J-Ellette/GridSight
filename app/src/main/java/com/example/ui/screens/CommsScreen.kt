package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.DeliveryStatus
import com.example.data.local.PeerDeviceEntity
import com.example.ui.GridSightUiState
import com.example.ui.theme.LocalTacticalPalette

@Composable
fun CommsScreen(
    state: GridSightUiState,
    onSelectPeer: (String) -> Unit,
    onSendMessage: (PeerDeviceEntity, String) -> Unit,
    onToggleCiphertext: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    val selectedPeer = state.peers.firstOrNull { it.peerId == state.selectedPeerId } ?: state.peers.firstOrNull()
    val threadMessages = state.messages.filter { selectedPeer != null && it.peerId == selectedPeer.peerId }
    var messageInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Peer Selector + Ciphertext Inspection Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.peers.isEmpty()) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("NO PAIRED PEERS") },
                        modifier = Modifier.height(48.dp)
                    )
                } else {
                    state.peers.forEach { peer ->
                        FilterChip(
                            selected = peer.peerId == selectedPeer?.peerId,
                            onClick = { onSelectPeer(peer.peerId) },
                            label = { Text("${peer.callsign} [${peer.keyFingerprint.take(5)}]") },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onToggleCiphertext,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("comms_toggle_cipher_button")
            ) {
                Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(if (state.showCiphertextInComms) "PLAIN" else "CIPHER", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (selectedPeer == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = palette.accentCyan,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "E2EE PEER CHANNEL READY",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Pair with a nearby GridSight handheld on the Biometrics & Mesh tab (NSD auto-discovery, LAN IP:Port, or NFC tap) to open an ECDH P-256 + AES-256-GCM encrypted thread.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, palette.hudBorder),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "E2EE KEY FP: ${selectedPeer.keyFingerprint} • ${if (selectedPeer.isOnline) "DIRECT TCP" else "OFFLINE QUEUE MODE"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secureGreen,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(threadMessages, key = { it.id }) { msg ->
                    val align = if (msg.senderIsSelf) Alignment.End else Alignment.Start
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = align
                    ) {
                        Surface(
                            color = if (msg.senderIsSelf) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                palette.borderWidth,
                                if (msg.senderIsSelf) palette.accentCyan else MaterialTheme.colorScheme.outline
                            ),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (state.showCiphertextInComms) {
                                        "GCM[IV:${msg.ivBase64.take(10)}] ${msg.ciphertextPreview}"
                                    } else {
                                        msg.plaintext
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${if (msg.senderIsSelf) "YOU" else msg.peerCallsign} • ${msg.deliveryStatus.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (msg.deliveryStatus == DeliveryStatus.QUEUED_OFFLINE) palette.cautionAmber else palette.secureGreen
                                )
                            }
                        }
                    }
                }
            }

            // Quick-attach latest pinned node summary
            val pinnedNode = state.nodes.firstOrNull { it.isPinned }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (pinnedNode != null) {
                    IconButton(
                        onClick = {
                            messageInput = "INTEL [${pinnedNode.nodeType.code}] ${pinnedNode.title} @ %.4f,%.4f (%s)".format(
                                pinnedNode.latitude,
                                pinnedNode.longitude,
                                pinnedNode.securityPosture.code
                            )
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("comms_attach_intel_button")
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "Attach pinned intel",
                            tint = palette.accentCyan
                        )
                    }
                }
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    label = { Text("Encrypted payload...") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("comms_message_input")
                )
                IconButton(
                    onClick = {
                        onSendMessage(selectedPeer, messageInput)
                        messageInput = ""
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("comms_send_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send E2EE message",
                        tint = palette.accentCyan
                    )
                }
            }
        }
    }
}
