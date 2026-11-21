package com.example

import com.example.crypto.CryptoEngine
import com.example.data.export.ExportCategory
import com.example.data.export.ExportFormat
import com.example.data.export.ExportTimeRange
import com.example.data.export.ScanExportManager
import com.example.data.local.AccountRole
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.ReadinessStatus
import com.example.data.local.SecurityPosture
import com.example.threat.ThreatCategory
import com.example.threat.ThreatDetectionEngine
import com.example.threat.ThreatSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSightCoreUnitTest {

    @Test
    fun pbkdf2PasswordHashAndVerify() {
        val hashed = CryptoEngine.hashPassword("TacticalPass#42".toCharArray())
        assertTrue(CryptoEngine.verifyPassword("TacticalPass#42".toCharArray(), hashed.hashBase64, hashed.saltBase64))
        assertFalse(CryptoEngine.verifyPassword("WrongPass".toCharArray(), hashed.hashBase64, hashed.saltBase64))
    }

    @Test
    fun ecdhAndAesGcmRoundTrip() {
        val alice = CryptoEngine.generateEcKeyPair()
        val bob = CryptoEngine.generateEcKeyPair()

        val aliceShared = CryptoEngine.deriveSharedAesKey(alice.privateKeyBase64, bob.publicKeyBase64)
        val bobShared = CryptoEngine.deriveSharedAesKey(bob.privateKeyBase64, alice.publicKeyBase64)

        val encrypted = CryptoEngine.encrypt("Sector 7 clear // 41.8781,-87.6298", aliceShared)
        val decrypted = CryptoEngine.decrypt(encrypted.ciphertextBase64, encrypted.ivBase64, bobShared)
        assertEquals("Sector 7 clear // 41.8781,-87.6298", decrypted)
    }

    @Test
    fun threatEngineFlagsEvilTwinAndPeerDistress() {
        val now = 1_700_000_000_000L
        val nodes = listOf(
            DetectedNodeEntity(
                id = "wifi_1",
                nodeType = NodeType.WIFI_AP,
                title = "CityPublicNet",
                subtitle = "BSSID AA:BB:CC:11:22:33",
                macOrId = "AA:BB:CC:11:22:33",
                rssi = -48,
                securityPosture = SecurityPosture.WPA2_STANDARD,
                latitude = 41.88,
                longitude = -87.63,
                metadataSummary = "Legit WPA2",
                lastSeenAt = now
            ),
            DetectedNodeEntity(
                id = "wifi_2",
                nodeType = NodeType.WIFI_AP,
                title = "CityPublicNet",
                subtitle = "BSSID DD:EE:FF:44:55:66",
                macOrId = "DD:EE:FF:44:55:66",
                rssi = -42,
                securityPosture = SecurityPosture.OPEN_UNENCRYPTED,
                latitude = 41.88,
                longitude = -87.63,
                metadataSummary = "Open clone",
                lastSeenAt = now
            )
        )
        val peers = listOf(
            PeerDeviceEntity(
                peerId = "peer_1",
                callsign = "VANGUARD-2",
                role = "Recon",
                publicKeyBase64 = "PUB",
                keyFingerprint = "11:22",
                hostAddress = "192.168.1.44",
                port = 9999,
                latitude = 41.88,
                longitude = -87.63,
                heartRateBpm = 178,
                motionState = "SPRINT",
                batteryPercent = 40,
                readinessStatus = ReadinessStatus.DISTRESS,
                isOnline = true,
                lastSeenAt = now
            )
        )

        val alerts = ThreatDetectionEngine.analyze(nodes, peers)
        assertTrue(alerts.any { it.category == ThreatCategory.EVIL_TWIN_COLLISION && it.severity == ThreatSeverity.CRITICAL })
        assertTrue(alerts.any { it.category == ThreatCategory.PEER_DISTRESS && it.severity == ThreatSeverity.CRITICAL })
    }

    @Test
    fun exportFiltersByTimeRangeAndFormatsCsvAndJson() {
        val now = 2_000_000_000_000L
        val recentCam = DetectedNodeEntity(
            id = "cam_1",
            nodeType = NodeType.PUBLIC_CAMERA,
            title = "Civic Cam, Plaza",
            subtitle = "Fixed Dome",
            macOrId = "OSM-101",
            rssi = -55,
            securityPosture = SecurityPosture.PUBLIC_CIVIC,
            latitude = 41.8781,
            longitude = -87.6298,
            metadataSummary = "Intersection monitor",
            feedOrReferenceUrl = "https://www.openstreetmap.org/node/101",
            lastSeenAt = now - 30 * 60 * 1000L
        )
        val oldHotspot = DetectedNodeEntity(
            id = "hs_1",
            nodeType = NodeType.PUBLIC_HOTSPOT,
            title = "Old Library Wi-Fi",
            subtitle = "Open",
            macOrId = "OSM-99",
            rssi = -65,
            securityPosture = SecurityPosture.PUBLIC_CIVIC,
            latitude = 41.8790,
            longitude = -87.6280,
            metadataSummary = "Public WLAN",
            feedOrReferenceUrl = "https://www.openstreetmap.org/node/99",
            lastSeenAt = now - 10 * 24 * 60 * 60 * 1000L
        )

        val csv = ScanExportManager.buildExportContent(
            nodes = listOf(recentCam, oldHotspot),
            format = ExportFormat.CSV,
            timeRange = ExportTimeRange.LAST_24H,
            category = ExportCategory.ALL,
            operatorCallsign = "FOX-01",
            role = AccountRole.ADMIN,
            nowMs = now
        )
        assertTrue(csv.contains("\"Civic Cam, Plaza\""))
        assertTrue(csv.contains("https://www.openstreetmap.org/node/101"))
        assertFalse(csv.contains("Old Library Wi-Fi"))

        val json = ScanExportManager.buildExportContent(
            nodes = listOf(recentCam, oldHotspot),
            format = ExportFormat.JSON,
            timeRange = ExportTimeRange.ALL_TIME,
            category = ExportCategory.CAMERAS,
            operatorCallsign = "FOX-01",
            role = AccountRole.STANDARD,
            nowMs = now
        )
        assertTrue(json.contains("\"recordCount\": 1"))
        assertTrue(json.contains("Civic Cam, Plaza"))
    }

    @Test
    fun slippyMapMathRoundTripProjection() {
        val lat = 41.8781
        val lon = -87.6298
        val zoom = 15
        val tx = com.example.ui.components.SlippyMapMath.lonToTileX(lon, zoom)
        val ty = com.example.ui.components.SlippyMapMath.latToTileY(lat, zoom)
        val backLon = com.example.ui.components.SlippyMapMath.tileXToLon(tx, zoom)
        val backLat = com.example.ui.components.SlippyMapMath.tileYToLat(ty, zoom)
        assertEquals(lon, backLon, 0.0001)
        assertEquals(lat, backLat, 0.0001)
    }
}
