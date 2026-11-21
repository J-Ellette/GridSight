package com.example.data

import com.example.crypto.CryptoEngine
import com.example.data.local.AccountRole
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.EncryptedMessageEntity
import com.example.data.local.GridSightDao
import com.example.data.local.NodeType
import com.example.data.local.OperatorAccountEntity
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.SecurityPosture
import com.example.data.local.SyncStatus
import com.example.data.remote.OverpassApiService
import kotlinx.coroutines.flow.Flow

class GridSightRepository(
    private val dao: GridSightDao,
    private val overpassApi: OverpassApiService
) {
    val allNodes: Flow<List<DetectedNodeEntity>> = dao.observeAllNodes()
    val allPeers: Flow<List<PeerDeviceEntity>> = dao.observePeers()
    val allMessages: Flow<List<EncryptedMessageEntity>> = dao.observeAllMessages()
    val allAccounts: Flow<List<OperatorAccountEntity>> = dao.observeAllAccounts()

    // --- Authentication & RBAC ---
    suspend fun getAccountCount(): Int = dao.getAccountCount()

    suspend fun registerAccount(
        username: String,
        callsign: String,
        password: String,
        requestedRole: AccountRole
    ): Result<OperatorAccountEntity> {
        val cleanUser = username.trim().lowercase()
        if (cleanUser.length < 3) {
            return Result.failure(IllegalArgumentException("Username must be at least 3 characters."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }
        if (dao.getAccountByUsername(cleanUser) != null) {
            return Result.failure(IllegalStateException("Operator account '$cleanUser' already exists."))
        }
        val hashed = CryptoEngine.hashPassword(password.toCharArray())
        val account = OperatorAccountEntity(
            username = cleanUser,
            callsign = callsign.trim().ifBlank { cleanUser.uppercase() }.take(20),
            passwordHashBase64 = hashed.hashBase64,
            saltBase64 = hashed.saltBase64,
            role = requestedRole,
            createdAt = System.currentTimeMillis(),
            lastLoginAt = System.currentTimeMillis()
        )
        dao.insertAccount(account)
        return Result.success(account)
    }

    suspend fun authenticate(username: String, password: String): Result<OperatorAccountEntity> {
        val cleanUser = username.trim().lowercase()
        val account = dao.getAccountByUsername(cleanUser)
            ?: return Result.failure(IllegalArgumentException("Invalid operator credentials."))
        val valid = CryptoEngine.verifyPassword(
            password.toCharArray(),
            account.passwordHashBase64,
            account.saltBase64
        )
        if (!valid) {
            return Result.failure(IllegalArgumentException("Invalid operator credentials."))
        }
        val now = System.currentTimeMillis()
        dao.updateLastLogin(cleanUser, now)
        return Result.success(account.copy(lastLoginAt = now))
    }

    suspend fun updateAccountRole(
        actorRole: AccountRole,
        targetUsername: String,
        newRole: AccountRole
    ): Result<Unit> {
        if (actorRole != AccountRole.ADMIN) {
            return Result.failure(SecurityException("RBAC DENIED: Administrator privilege required."))
        }
        dao.updateAccountRole(targetUsername, newRole)
        return Result.success(Unit)
    }

    suspend fun deleteAccount(actorRole: AccountRole, targetUsername: String): Result<Unit> {
        if (actorRole != AccountRole.ADMIN) {
            return Result.failure(SecurityException("RBAC DENIED: Administrator privilege required."))
        }
        dao.deleteAccount(targetUsername)
        return Result.success(Unit)
    }

    // --- Sensor & Civic Data ---
    suspend fun saveDetectedNode(node: DetectedNodeEntity) {
        dao.upsertNode(node)
    }

    suspend fun togglePin(nodeId: String, currentlyPinned: Boolean) {
        dao.setNodePinned(nodeId, !currentlyPinned)
    }

    suspend fun deleteNode(actorRole: AccountRole, nodeId: String): Result<Unit> {
        if (actorRole != AccountRole.ADMIN) {
            return Result.failure(SecurityException("RBAC DENIED: Only Admin can purge cached records."))
        }
        dao.deleteNode(nodeId)
        return Result.success(Unit)
    }

    suspend fun purgeAllTelemetry(actorRole: AccountRole): Result<Unit> {
        if (actorRole != AccountRole.ADMIN) {
            return Result.failure(SecurityException("RBAC DENIED: Only Admin can wipe offline database."))
        }
        dao.purgeAllNodes()
        dao.purgeAllMessages()
        return Result.success(Unit)
    }

    suspend fun upsertPeer(peer: PeerDeviceEntity) {
        dao.upsertPeer(peer)
    }

    suspend fun fetchCivicVicinityNodes(lat: Double, lon: Double, radiusMeters: Int = 1500): Result<Int> {
        return runCatching {
            val query = OverpassApiService.buildVicinityOverpassQuery(lat, lon, radiusMeters)
            val response = overpassApi.queryVicinityNodes(query)
            val now = System.currentTimeMillis()
            val mapped = response.elements.mapNotNull { el ->
                val elLat = el.lat ?: return@mapNotNull null
                val elLon = el.lon ?: return@mapNotNull null
                val tags = el.tags.orEmpty()
                val isCamera = tags["man_made"] == "surveillance"
                val nodeType = if (isCamera) NodeType.PUBLIC_CAMERA else NodeType.PUBLIC_HOTSPOT
                val name = tags["name"]
                    ?: tags["operator"]
                    ?: if (isCamera) "Civic Camera #${el.id.toString().takeLast(4)}"
                    else "Public Wi-Fi #${el.id.toString().takeLast(4)}"
                val subtitle = if (isCamera) {
                    val camType = tags["surveillance:type"] ?: tags["camera:type"] ?: "fixed"
                    val zone = tags["surveillance"] ?: "public"
                    "Type: $camType • Zone: $zone"
                } else {
                    val fee = tags["internet_access:fee"] ?: "public"
                    val ssid = tags["internet_access:ssid"] ?: "Open Civic WLAN"
                    "SSID: $ssid • Fee: $fee"
                }
                val refUrl = tags["camera:url"]
                    ?: tags["contact:webcam"]
                    ?: tags["image"]
                    ?: tags["website"]
                    ?: tags["contact:website"]
                    ?: tags["url"]
                    ?: "https://www.openstreetmap.org/node/${el.id}"
                val meta = tags.entries.take(4).joinToString(" | ") { "${it.key}=${it.value}" }
                    .ifBlank { "OpenStreetMap Civic Node ${el.id}" }

                DetectedNodeEntity(
                    id = "osm_${el.id}",
                    nodeType = nodeType,
                    title = name,
                    subtitle = subtitle,
                    macOrId = "OSM-${el.id}",
                    rssi = -55,
                    securityPosture = SecurityPosture.PUBLIC_CIVIC,
                    latitude = elLat,
                    longitude = elLon,
                    metadataSummary = meta,
                    feedOrReferenceUrl = refUrl,
                    isPinned = true,
                    sourcePeerCallsign = "OSM-OVERPASS",
                    lastSeenAt = now,
                    syncStatus = SyncStatus.LOCAL_ONLY
                )
            }
            if (mapped.isNotEmpty()) {
                dao.upsertNodes(mapped)
            }
            mapped.size
        }
    }
}
