package com.example.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.crypto.CryptoEngine
import com.example.data.local.DeliveryStatus
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.EncryptedMessageEntity
import com.example.data.local.GridSightDao
import com.example.data.local.NodeType
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.ReadinessStatus
import com.example.data.local.SecurityPosture
import com.example.data.local.SyncStatus
import com.example.data.preferences.OperatorConfig
import com.example.sensors.TelemetryStreamEvent
import com.example.threat.ThreatAlert
import com.example.threat.ThreatCategory
import com.example.threat.ThreatSeverity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

@JsonClass(generateAdapter = true)
data class MeshWireEnvelope(
    val packetType: String, // HELLO, INTEL_E2EE, DIRECT_E2EE
    val senderPeerId: String,
    val senderCallsign: String,
    val senderRole: String,
    val senderPublicKey: String,
    val senderPort: Int,
    val ivBase64: String = "",
    val ciphertextBase64: String = ""
)

@JsonClass(generateAdapter = true)
data class BiometricBeaconPayload(
    val lat: Double,
    val lon: Double,
    val heartRateBpm: Int,
    val motionState: String,
    val batteryPercent: Int,
    val readiness: String
)

@JsonClass(generateAdapter = true)
data class SharedIntelPayload(
    val id: String,
    val nodeType: String,
    val title: String,
    val subtitle: String,
    val macOrId: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val securityPosture: String,
    val latitude: Double,
    val longitude: Double,
    val metadataSummary: String,
    val feedOrReferenceUrl: String = ""
)

@JsonClass(generateAdapter = true)
data class DirectChatPayload(
    val messageId: String,
    val text: String,
    val timestamp: Long
)

data class MeshEngineState(
    val isListening: Boolean = false,
    val localIp: String = "127.0.0.1",
    val listenPort: Int = 0,
    val nsdRegistered: Boolean = false,
    val nsdDiscovering: Boolean = false
)

class P2pMeshEngine(
    private val context: Context,
    private val dao: GridSightDao,
    private val scope: CoroutineScope
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val serviceType = "_gridsight._tcp."
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(MeshWireEnvelope::class.java)
    private val beaconAdapter = moshi.adapter(BiometricBeaconPayload::class.java)
    private val intelAdapter = moshi.adapter(SharedIntelPayload::class.java)
    private val chatAdapter = moshi.adapter(DirectChatPayload::class.java)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var heartbeatJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var currentConfig: OperatorConfig? = null
    private var latestLat: Double = 41.8781
    private var latestLon: Double = -87.6298
    private var latestMotion: String = "STATIONARY"
    private var latestBattery: Int = 100

    private val _state = MutableStateFlow(MeshEngineState())
    val state: StateFlow<MeshEngineState> = _state.asStateFlow()

    private val _meshLogs = MutableSharedFlow<TelemetryStreamEvent>(extraBufferCapacity = 64)
    val meshLogs: SharedFlow<TelemetryStreamEvent> = _meshLogs.asSharedFlow()

    private val _cryptoThreatAlerts = MutableSharedFlow<ThreatAlert>(extraBufferCapacity = 16)
    val cryptoThreatAlerts: SharedFlow<ThreatAlert> = _cryptoThreatAlerts.asSharedFlow()

    fun updateOperatorSnapshot(
        config: OperatorConfig,
        lat: Double,
        lon: Double,
        motionState: String,
        batteryPercent: Int
    ) {
        currentConfig = config
        latestLat = lat
        latestLon = lon
        latestMotion = motionState
        latestBattery = batteryPercent
    }

    fun startMesh() {
        if (_state.value.isListening) return
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket(0)
                serverSocket = ss
                val port = ss.localPort
                val localIp = resolveLocalIpv4()
                _state.value = _state.value.copy(
                    isListening = true,
                    localIp = localIp,
                    listenPort = port
                )
                _meshLogs.tryEmit(
                    TelemetryStreamEvent("MESH_TCP", "E2EE socket listening $localIp:$port")
                )
                registerNsdService(port)
                startNsdDiscovery()

                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleIncomingSocket(client) }
                }
            }.onFailure { err ->
                _meshLogs.tryEmit(
                    TelemetryStreamEvent("MESH_ERR", err.message ?: "Socket bind error", isAlert = true)
                )
            }
        }

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000L)
                flushOfflineSyncQueue()
            }
        }
    }

    fun stopMesh() {
        heartbeatJob?.cancel()
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        registrationListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        _state.value = MeshEngineState()
    }

    suspend fun pairAndPingPeer(host: String, port: Int): Result<Unit> {
        val cfg = currentConfig ?: return Result.failure(IllegalStateException("Config not ready"))
        return runCatching {
            val beacon = BiometricBeaconPayload(
                lat = latestLat,
                lon = latestLon,
                heartRateBpm = if (cfg.shareBiometricsOptIn) cfg.selfHeartRateBpm else 0,
                motionState = latestMotion,
                batteryPercent = latestBattery,
                readiness = cfg.readinessStatus.name
            )
            val channelKey = CryptoEngine.deriveChannelAesKey(cfg.channelPassphrase)
            val encryptedBeacon = CryptoEngine.encrypt(beaconAdapter.toJson(beacon), channelKey)
            val envelope = MeshWireEnvelope(
                packetType = "HELLO",
                senderPeerId = cfg.peerId,
                senderCallsign = cfg.callsign,
                senderRole = cfg.role,
                senderPublicKey = cfg.publicKeyBase64,
                senderPort = _state.value.listenPort,
                ivBase64 = encryptedBeacon.ivBase64,
                ciphertextBase64 = encryptedBeacon.ciphertextBase64
            )
            sendEnvelope(host, port, envelope)
            _meshLogs.tryEmit(
                TelemetryStreamEvent("ECDH_SYN", "Handshaked $host:$port via AES-256-GCM")
            )
        }
    }

    suspend fun sendDirectE2eeMessage(peer: PeerDeviceEntity, plaintext: String): Result<EncryptedMessageEntity> {
        val cfg = currentConfig ?: return Result.failure(IllegalStateException("Config missing"))
        return runCatching {
            val sharedKey = CryptoEngine.deriveSharedAesKey(cfg.privateKeyBase64, peer.publicKeyBase64)
            val payload = DirectChatPayload(
                messageId = UUID.randomUUID().toString(),
                text = plaintext,
                timestamp = System.currentTimeMillis()
            )
            val encrypted = CryptoEngine.encrypt(chatAdapter.toJson(payload), sharedKey)
            val envelope = MeshWireEnvelope(
                packetType = "DIRECT_E2EE",
                senderPeerId = cfg.peerId,
                senderCallsign = cfg.callsign,
                senderRole = cfg.role,
                senderPublicKey = cfg.publicKeyBase64,
                senderPort = _state.value.listenPort,
                ivBase64 = encrypted.ivBase64,
                ciphertextBase64 = encrypted.ciphertextBase64
            )

            val delivered = runCatching { sendEnvelope(peer.hostAddress, peer.port, envelope) }.isSuccess
            val entity = EncryptedMessageEntity(
                id = payload.messageId,
                peerId = peer.peerId,
                peerCallsign = peer.callsign,
                senderIsSelf = true,
                plaintext = plaintext,
                ciphertextPreview = encrypted.ciphertextBase64.take(28) + "…",
                ivBase64 = encrypted.ivBase64,
                timestamp = payload.timestamp,
                deliveryStatus = if (delivered) DeliveryStatus.SENT_E2EE else DeliveryStatus.QUEUED_OFFLINE
            )
            dao.insertMessage(entity)
            _meshLogs.tryEmit(
                TelemetryStreamEvent(
                    protocol = if (delivered) "E2EE_TX" else "E2EE_Q",
                    summary = "${peer.callsign} // IV:${encrypted.ivBase64.take(8)} // ${encrypted.ciphertextBase64.take(12)}"
                )
            )
            entity
        }
    }

    suspend fun broadcastIntelToPeers(node: DetectedNodeEntity) {
        val cfg = currentConfig ?: return
        dao.queueNodeForSync(node.id, SyncStatus.QUEUED_SYNC)
        flushOfflineSyncQueue()
        _meshLogs.tryEmit(
            TelemetryStreamEvent("INTEL_Q", "Encrypted & queued ${node.title} for mesh sync")
        )
    }

    suspend fun flushOfflineSyncQueue() {
        val cfg = currentConfig ?: return
        val queuedNodes = dao.getNodesBySyncStatus(SyncStatus.QUEUED_SYNC)
        val queuedMsgs = dao.getQueuedMessages(DeliveryStatus.QUEUED_OFFLINE)
        if (queuedNodes.isEmpty() && queuedMsgs.isEmpty()) return

        val channelKey = CryptoEngine.deriveChannelAesKey(cfg.channelPassphrase)
        val syncedNodeIds = mutableListOf<String>()

        for (msg in queuedMsgs) {
            val peer = dao.getPeerById(msg.peerId) ?: continue
            if (peer.hostAddress.isBlank() || peer.port <= 0) continue
            val sharedKey = runCatching {
                CryptoEngine.deriveSharedAesKey(cfg.privateKeyBase64, peer.publicKeyBase64)
            }.getOrNull() ?: continue
            val payload = DirectChatPayload(msg.id, msg.plaintext, msg.timestamp)
            val encrypted = CryptoEngine.encrypt(chatAdapter.toJson(payload), sharedKey)
            val env = MeshWireEnvelope(
                packetType = "DIRECT_E2EE",
                senderPeerId = cfg.peerId,
                senderCallsign = cfg.callsign,
                senderRole = cfg.role,
                senderPublicKey = cfg.publicKeyBase64,
                senderPort = _state.value.listenPort,
                ivBase64 = encrypted.ivBase64,
                ciphertextBase64 = encrypted.ciphertextBase64
            )
            if (runCatching { sendEnvelope(peer.hostAddress, peer.port, env) }.isSuccess) {
                dao.updateMessageStatus(msg.id, DeliveryStatus.SENT_E2EE)
            }
        }

        val peersSnapshot = dao.observePeers().firstOrNull().orEmpty()
            .filter { it.hostAddress.isNotBlank() && it.port > 0 }
        if (peersSnapshot.isEmpty()) return

        for (node in queuedNodes) {
            val intelPayload = SharedIntelPayload(
                id = node.id,
                nodeType = node.nodeType.name,
                title = node.title,
                subtitle = node.subtitle,
                macOrId = node.macOrId,
                rssi = node.rssi,
                frequencyMhz = node.frequencyMhz,
                securityPosture = node.securityPosture.name,
                latitude = node.latitude,
                longitude = node.longitude,
                metadataSummary = node.metadataSummary,
                feedOrReferenceUrl = node.feedOrReferenceUrl
            )
            val enc = CryptoEngine.encrypt(intelAdapter.toJson(intelPayload), channelKey)
            val env = MeshWireEnvelope(
                packetType = "INTEL_E2EE",
                senderPeerId = cfg.peerId,
                senderCallsign = cfg.callsign,
                senderRole = cfg.role,
                senderPublicKey = cfg.publicKeyBase64,
                senderPort = _state.value.listenPort,
                ivBase64 = enc.ivBase64,
                ciphertextBase64 = enc.ciphertextBase64
            )
            var sentAny = false
            peersSnapshot.forEach { peer ->
                if (runCatching { sendEnvelope(peer.hostAddress, peer.port, env) }.isSuccess) {
                    sentAny = true
                }
            }
            if (sentAny) syncedNodeIds.add(node.id)
        }
        if (syncedNodeIds.isNotEmpty()) {
            dao.updateNodesSyncStatus(syncedNodeIds, SyncStatus.SYNCED)
            _meshLogs.tryEmit(
                TelemetryStreamEvent("MESH_SYNC", "Synced ${syncedNodeIds.size} E2EE intel packets")
            )
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        socket.use { s ->
            val remoteIp = s.inetAddress?.hostAddress ?: return
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val line = reader.readLine() ?: return
            val envelope = runCatching { envelopeAdapter.fromJson(line) }.getOrNull() ?: return
            val cfg = currentConfig ?: return
            if (envelope.senderPeerId == cfg.peerId) return

            val fingerprint = CryptoEngine.computeFingerprint(envelope.senderPublicKey)
            val existingPeer = dao.getPeerById(envelope.senderPeerId)

            when (envelope.packetType) {
                "HELLO" -> {
                    val channelKey = CryptoEngine.deriveChannelAesKey(cfg.channelPassphrase)
                    val decryptedJson = runCatching {
                        CryptoEngine.decrypt(envelope.ciphertextBase64, envelope.ivBase64, channelKey)
                    }.getOrNull()
                    val beacon = decryptedJson?.let { runCatching { beaconAdapter.fromJson(it) }.getOrNull() }
                    val peer = PeerDeviceEntity(
                        peerId = envelope.senderPeerId,
                        callsign = envelope.senderCallsign,
                        role = envelope.senderRole,
                        publicKeyBase64 = envelope.senderPublicKey,
                        keyFingerprint = fingerprint,
                        hostAddress = remoteIp,
                        port = envelope.senderPort,
                        latitude = beacon?.lat ?: existingPeer?.latitude ?: latestLat,
                        longitude = beacon?.lon ?: existingPeer?.longitude ?: latestLon,
                        heartRateBpm = beacon?.heartRateBpm ?: existingPeer?.heartRateBpm ?: 72,
                        motionState = beacon?.motionState ?: existingPeer?.motionState ?: "STATIONARY",
                        batteryPercent = beacon?.batteryPercent ?: existingPeer?.batteryPercent ?: 100,
                        readinessStatus = beacon?.readiness?.let {
                            runCatching { ReadinessStatus.valueOf(it) }.getOrNull()
                        } ?: ReadinessStatus.NOMINAL,
                        isOnline = true,
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dao.upsertPeer(peer)
                    _meshLogs.tryEmit(
                        TelemetryStreamEvent("PEER_RX", "${peer.callsign} verified ($fingerprint)")
                    )
                }

                "INTEL_E2EE" -> {
                    val channelKey = CryptoEngine.deriveChannelAesKey(cfg.channelPassphrase)
                    val json = runCatching {
                        CryptoEngine.decrypt(envelope.ciphertextBase64, envelope.ivBase64, channelKey)
                    }.getOrElse {
                        _cryptoThreatAlerts.tryEmit(
                            ThreatAlert(
                                id = "threat_gcm_intel_${envelope.senderPeerId}",
                                severity = ThreatSeverity.HIGH,
                                category = ThreatCategory.CRYPTO_INTEGRITY,
                                title = "Rejected Untrusted Mesh Packet",
                                description = "AES-256-GCM tag verification failed for packet from ${envelope.senderCallsign} ($remoteIp).",
                                mitigation = "Verify team channel passphrase and ECDH key fingerprint before trusting peer."
                            )
                        )
                        return
                    }
                    val intel = runCatching { intelAdapter.fromJson(json) }.getOrNull() ?: return
                    val node = DetectedNodeEntity(
                        id = intel.id,
                        nodeType = runCatching { NodeType.valueOf(intel.nodeType) }
                            .getOrDefault(NodeType.WIFI_AP),
                        title = intel.title,
                        subtitle = intel.subtitle,
                        macOrId = intel.macOrId,
                        rssi = intel.rssi,
                        frequencyMhz = intel.frequencyMhz,
                        securityPosture = runCatching { SecurityPosture.valueOf(intel.securityPosture) }
                            .getOrDefault(SecurityPosture.PUBLIC_CIVIC),
                        latitude = intel.latitude,
                        longitude = intel.longitude,
                        metadataSummary = intel.metadataSummary,
                        feedOrReferenceUrl = intel.feedOrReferenceUrl,
                        isPinned = true,
                        isSharedToMesh = true,
                        sourcePeerCallsign = envelope.senderCallsign,
                        lastSeenAt = System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED
                    )
                    dao.upsertNode(node)
                    _meshLogs.tryEmit(
                        TelemetryStreamEvent("INTEL_RX", "Decrypted ${intel.title} from ${envelope.senderCallsign}")
                    )
                }

                "DIRECT_E2EE" -> {
                    val sharedKey = runCatching {
                        CryptoEngine.deriveSharedAesKey(cfg.privateKeyBase64, envelope.senderPublicKey)
                    }.getOrNull() ?: return
                    val json = runCatching {
                        CryptoEngine.decrypt(envelope.ciphertextBase64, envelope.ivBase64, sharedKey)
                    }.getOrElse {
                        _cryptoThreatAlerts.tryEmit(
                            ThreatAlert(
                                id = "threat_gcm_dm_${envelope.senderPeerId}",
                                severity = ThreatSeverity.CRITICAL,
                                category = ThreatCategory.CRYPTO_INTEGRITY,
                                title = "E2EE Message Integrity Failure",
                                description = "Rejected tampered or key-mismatched direct message from ${envelope.senderCallsign} ($remoteIp).",
                                mitigation = "Re-verify ECDH public key fingerprint out-of-band via NFC or voice."
                            )
                        )
                        return
                    }
                    val chat = runCatching { chatAdapter.fromJson(json) }.getOrNull() ?: return

                    if (existingPeer == null) {
                        dao.upsertPeer(
                            PeerDeviceEntity(
                                peerId = envelope.senderPeerId,
                                callsign = envelope.senderCallsign,
                                role = envelope.senderRole,
                                publicKeyBase64 = envelope.senderPublicKey,
                                keyFingerprint = fingerprint,
                                hostAddress = remoteIp,
                                port = envelope.senderPort,
                                latitude = latestLat,
                                longitude = latestLon,
                                heartRateBpm = 0,
                                motionState = "UNKNOWN",
                                batteryPercent = 100,
                                readinessStatus = ReadinessStatus.NOMINAL,
                                isOnline = true
                            )
                        )
                    }

                    dao.insertMessage(
                        EncryptedMessageEntity(
                            id = chat.messageId,
                            peerId = envelope.senderPeerId,
                            peerCallsign = envelope.senderCallsign,
                            senderIsSelf = false,
                            plaintext = chat.text,
                            ciphertextPreview = envelope.ciphertextBase64.take(28) + "…",
                            ivBase64 = envelope.ivBase64,
                            timestamp = chat.timestamp,
                            deliveryStatus = DeliveryStatus.RECEIVED_E2EE
                        )
                    )
                    _meshLogs.tryEmit(
                        TelemetryStreamEvent("E2EE_RX", "Decrypted message from ${envelope.senderCallsign}")
                    )
                }
            }
        }
    }

    private fun sendEnvelope(host: String, port: Int, envelope: MeshWireEnvelope) {
        val json = envelopeAdapter.toJson(envelope)
        Socket(host, port).use { socket ->
            socket.soTimeout = 4000
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(json)
            writer.flush()
        }
    }

    private fun registerNsdService(port: Int) {
        val cfg = currentConfig ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "GridSight_${cfg.callsign}"
            serviceType = this@P2pMeshEngine.serviceType
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                _state.value = _state.value.copy(nsdRegistered = true)
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                _state.value = _state.value.copy(nsdRegistered = false)
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _state.value = _state.value.copy(nsdDiscovering = true)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("gridsight")) return
                if (service.serviceName == "GridSight_${currentConfig?.callsign}") return
                @Suppress("DEPRECATION")
                runCatching {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = serviceInfo.host?.hostAddress ?: return
                            val port = serviceInfo.port
                            scope.launch(Dispatchers.IO) { pairAndPingPeer(host, port) }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {
                _state.value = _state.value.copy(nsdDiscovering = false)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun resolveLocalIpv4(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }
}
