package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AccountRole(val code: String, val displayLabel: String) {
    STANDARD("STD", "Standard Operator"),
    ADMIN("ADM", "System Administrator")
}

@Entity(tableName = "operator_accounts")
data class OperatorAccountEntity(
    @PrimaryKey val username: String,
    val callsign: String,
    val passwordHashBase64: String,
    val saltBase64: String,
    val role: AccountRole,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis()
)

enum class NodeType(val code: String, val displayLabel: String) {
    BLE_DEVICE("BLE", "Bluetooth LE Node"),
    WIFI_AP("WI-FI", "Wi-Fi Access Point"),
    NFC_TAG("NFC", "NFC NDEF Tag"),
    PUBLIC_HOTSPOT("HOTSPOT", "Public Wi-Fi Hotspot"),
    PUBLIC_CAMERA("CAM", "Public Camera Feed"),
    TEAM_PEER("PEER", "Consented Mesh Peer")
}

enum class SecurityPosture(val code: String, val isWarning: Boolean) {
    SECURE_WPA3("WPA3-SAE", false),
    WPA2_STANDARD("WPA2-PSK", false),
    WEAK_WEP_LEGACY("WEAK: WEP/LEGACY", true),
    OPEN_UNENCRYPTED("OPEN / UNENCRYPTED", true),
    E2EE_VERIFIED("E2EE ECDH-GCM", false),
    PUBLIC_CIVIC("CIVIC OPEN DATA", false),
    NFC_NDEF("NDEF PAYLOAD", false)
}

enum class SyncStatus(val label: String) {
    LOCAL_ONLY("LOCAL"),
    QUEUED_SYNC("QUEUED"),
    SYNCED("SYNCED")
}

enum class ReadinessStatus(val label: String) {
    NOMINAL("NOMINAL"),
    CAUTION("CAUTION"),
    DISTRESS("DISTRESS"),
    STEALTH("STEALTH")
}

enum class DeliveryStatus(val label: String) {
    QUEUED_OFFLINE("QUEUED OFFLINE"),
    SENT_E2EE("SENT E2EE"),
    RECEIVED_E2EE("VERIFIED E2EE")
}

@Entity(tableName = "detected_nodes")
data class DetectedNodeEntity(
    @PrimaryKey val id: String,
    val nodeType: NodeType,
    val title: String,
    val subtitle: String,
    val macOrId: String,
    val rssi: Int,
    val frequencyMhz: Int = 0,
    val securityPosture: SecurityPosture,
    val latitude: Double,
    val longitude: Double,
    val metadataSummary: String,
    val feedOrReferenceUrl: String = "",
    val isPinned: Boolean = false,
    val isSharedToMesh: Boolean = false,
    val sourcePeerCallsign: String = "LOCAL-UNIT",
    val lastSeenAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)

@Entity(tableName = "peer_devices")
data class PeerDeviceEntity(
    @PrimaryKey val peerId: String,
    val callsign: String,
    val role: String,
    val publicKeyBase64: String,
    val keyFingerprint: String,
    val hostAddress: String,
    val port: Int,
    val latitude: Double,
    val longitude: Double,
    val heartRateBpm: Int,
    val motionState: String,
    val batteryPercent: Int,
    val readinessStatus: ReadinessStatus,
    val isOnline: Boolean,
    val lastSeenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "encrypted_messages")
data class EncryptedMessageEntity(
    @PrimaryKey val id: String,
    val peerId: String,
    val peerCallsign: String,
    val senderIsSelf: Boolean,
    val plaintext: String,
    val ciphertextPreview: String,
    val ivBase64: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryStatus: DeliveryStatus
)
