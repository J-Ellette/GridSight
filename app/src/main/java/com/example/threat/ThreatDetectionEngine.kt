package com.example.threat

import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.ReadinessStatus
import com.example.data.local.SecurityPosture
import kotlin.math.abs

enum class ThreatSeverity(val rank: Int, val label: String) {
    CRITICAL(4, "CRITICAL"),
    HIGH(3, "HIGH"),
    MEDIUM(2, "MEDIUM"),
    LOW(1, "ADVISORY")
}

enum class ThreatCategory(val label: String) {
    EVIL_TWIN_COLLISION("Evil-Twin / SSID Collision"),
    INSECURE_ENCRYPTION("Unencrypted / Legacy Cipher"),
    PROXIMITY_RF_SPIKE("Close-Range RF / Hidden SSID"),
    BLE_TRACKER_SIGNATURE("Persistent BLE Tracker Signature"),
    PEER_DISTRESS("Peer Biometric Distress"),
    CRYPTO_INTEGRITY("Cipher / Integrity Anomaly"),
    SURVEILLANCE_DENSITY("High Camera Chokepoint")
}

data class ThreatAlert(
    val id: String,
    val severity: ThreatSeverity,
    val category: ThreatCategory,
    val title: String,
    val description: String,
    val mitigation: String,
    val targetNodeId: String? = null,
    val detectedAt: Long = System.currentTimeMillis()
)

object ThreatDetectionEngine {

    // Known BLE tracker / beacon manufacturer hints (Apple FindMy / Tile / SmartTag common names/prefixes)
    private val trackerNameHints = listOf("airtag", "findmy", "tile", "smarttag", "chipolo", "tracker")

    fun analyze(
        nodes: List<DetectedNodeEntity>,
        peers: List<PeerDeviceEntity>,
        cryptoFailures: List<ThreatAlert> = emptyList(),
        acknowledgedIds: Set<String> = emptySet()
    ): List<ThreatAlert> {
        val alerts = mutableListOf<ThreatAlert>()
        alerts.addAll(cryptoFailures)

        // 1. Evil-Twin / Rogue AP Collision Detection:
        // Same SSID with different BSSIDs AND mismatched security postures (e.g., WPA2/WPA3 vs OPEN)
        val wifiAps = nodes.filter { it.nodeType == NodeType.WIFI_AP }
        val bySsid = wifiAps
            .filter { it.title.isNotBlank() && it.title != "<HIDDEN_SSID>" }
            .groupBy { it.title.lowercase() }

        bySsid.forEach { (ssidLower, group) ->
            if (group.size >= 2) {
                val distinctPostures = group.map { it.securityPosture }.toSet()
                val hasWeakAndStrong = distinctPostures.any { it.isWarning } && distinctPostures.any { !it.isWarning }
                if (hasWeakAndStrong) {
                    val primary = group.first()
                    alerts.add(
                        ThreatAlert(
                            id = "threat_eviltwin_$ssidLower",
                            severity = ThreatSeverity.CRITICAL,
                            category = ThreatCategory.EVIL_TWIN_COLLISION,
                            title = "Evil-Twin Anomaly: ${primary.title}",
                            description = "${group.size} BSSIDs share SSID '${primary.title}' with conflicting security postures (${distinctPostures.joinToString { it.code }}).",
                            mitigation = "Do not associate with the unencrypted BSSID. Verify BSSID hardware MAC before transmitting traffic.",
                            targetNodeId = primary.id,
                            detectedAt = primary.lastSeenAt
                        )
                    )
                }
            }
        }

        // 2. Weak / Unencrypted Wi-Fi Posture Analysis
        wifiAps.forEach { ap ->
            when (ap.securityPosture) {
                SecurityPosture.WEAK_WEP_LEGACY -> alerts.add(
                    ThreatAlert(
                        id = "threat_wep_${ap.macOrId}",
                        severity = ThreatSeverity.HIGH,
                        category = ThreatCategory.INSECURE_ENCRYPTION,
                        title = "Legacy WEP Cipher: ${ap.title}",
                        description = "Access point ${ap.macOrId} advertises deprecated WEP encryption (${ap.rssi} dBm), vulnerable to rapid passive key recovery.",
                        mitigation = "Avoid transmitting unencrypted traffic; require VPN or E2EE overlay if relay is unavoidable.",
                        targetNodeId = ap.id,
                        detectedAt = ap.lastSeenAt
                    )
                )
                SecurityPosture.OPEN_UNENCRYPTED -> alerts.add(
                    ThreatAlert(
                        id = "threat_open_${ap.macOrId}",
                        severity = ThreatSeverity.MEDIUM,
                        category = ThreatCategory.INSECURE_ENCRYPTION,
                        title = "Unencrypted WLAN: ${ap.title}",
                        description = "Open 802.11 network (${ap.macOrId}) has zero link-layer encryption. Cleartext traffic is exposed to local capture.",
                        mitigation = "Use GridSight AES-256-GCM mesh channel or TLS-pinned sessions only.",
                        targetNodeId = ap.id,
                        detectedAt = ap.lastSeenAt
                    )
                )
                else -> Unit
            }

            // 3. Hidden SSID + Strong Close-Range Signal Spike
            if (ap.title == "<HIDDEN_SSID>" && ap.rssi >= -55) {
                alerts.add(
                    ThreatAlert(
                        id = "threat_hidden_${ap.macOrId}",
                        severity = ThreatSeverity.MEDIUM,
                        category = ThreatCategory.PROXIMITY_RF_SPIKE,
                        title = "Close-Range Hidden SSID (${ap.rssi} dBm)",
                        description = "Cloaked Wi-Fi transmitter ${ap.macOrId} operating at strong proximity signal (${ap.frequencyMhz} MHz).",
                        mitigation = "Inspect directional RSSI change as operator moves to rule out mobile rogue hotspot.",
                        targetNodeId = ap.id,
                        detectedAt = ap.lastSeenAt
                    )
                )
            }
        }

        // 4. BLE Proximity Spike & Potential Tracker Signature
        nodes.filter { it.nodeType == NodeType.BLE_DEVICE }.forEach { ble ->
            val lowerTitle = ble.title.lowercase()
            val matchesTracker = trackerNameHints.any { lowerTitle.contains(it) }
            if (matchesTracker) {
                alerts.add(
                    ThreatAlert(
                        id = "threat_tracker_${ble.macOrId}",
                        severity = ThreatSeverity.HIGH,
                        category = ThreatCategory.BLE_TRACKER_SIGNATURE,
                        title = "BLE Tracker Beacon: ${ble.title}",
                        description = "Advertiser ${ble.macOrId} matches known personal item tracker signature at ${ble.rssi} dBm.",
                        mitigation = "Confirm whether beacon belongs to friendly kit or persistent unknown tag.",
                        targetNodeId = ble.id,
                        detectedAt = ble.lastSeenAt
                    )
                )
            } else if (ble.rssi >= -38) {
                alerts.add(
                    ThreatAlert(
                        id = "threat_blespike_${ble.macOrId}",
                        severity = ThreatSeverity.LOW,
                        category = ThreatCategory.PROXIMITY_RF_SPIKE,
                        title = "Immediate BLE Proximity: ${ble.title}",
                        description = "Strong BLE transmitter (${ble.rssi} dBm) within ~1-2m radius (${ble.macOrId}).",
                        mitigation = "Verify nearby handheld or peripheral ownership.",
                        targetNodeId = ble.id,
                        detectedAt = ble.lastSeenAt
                    )
                )
            }
        }

        // 5. Consented Peer Biometric / Readiness Distress
        peers.forEach { peer ->
            if (peer.readinessStatus == ReadinessStatus.DISTRESS ||
                (peer.heartRateBpm in 1..44) ||
                peer.heartRateBpm >= 165
            ) {
                alerts.add(
                    ThreatAlert(
                        id = "threat_peer_${peer.peerId}",
                        severity = ThreatSeverity.CRITICAL,
                        category = ThreatCategory.PEER_DISTRESS,
                        title = "Biometric Distress: ${peer.callsign}",
                        description = "Team unit ${peer.callsign} reports ${peer.readinessStatus.label} (${peer.heartRateBpm} BPM, ${peer.motionState}, ${peer.batteryPercent}% BAT).",
                        mitigation = "Initiate direct E2EE comms check-in and verify map coordinates immediately.",
                        detectedAt = peer.lastSeenAt
                    )
                )
            }
        }

        // 6. Surveillance Camera Chokepoint Cluster
        val cameras = nodes.filter { it.nodeType == NodeType.PUBLIC_CAMERA }
        if (cameras.size >= 3) {
            val anchor = cameras.first()
            val nearbyCams = cameras.count { cam ->
                abs(cam.latitude - anchor.latitude) < 0.0025 &&
                    abs(cam.longitude - anchor.longitude) < 0.0025
            }
            if (nearbyCams >= 3) {
                alerts.add(
                    ThreatAlert(
                        id = "threat_cam_cluster_${anchor.id}",
                        severity = ThreatSeverity.LOW,
                        category = ThreatCategory.SURVEILLANCE_DENSITY,
                        title = "High Optical Chokepoint ($nearbyCams Cameras)",
                        description = "$nearbyCams mapped public surveillance nodes clustered within ~250m of sector anchor.",
                        mitigation = "Review camera overlay pins on Map HUD for urban situational awareness.",
                        targetNodeId = anchor.id,
                        detectedAt = anchor.lastSeenAt
                    )
                )
            }
        }

        return alerts
            .filterNot { it.id in acknowledgedIds }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ThreatAlert> { it.severity.rank }.thenByDescending { it.detectedAt })
    }
}
