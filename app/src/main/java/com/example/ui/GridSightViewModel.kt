package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.GridSightRepository
import com.example.data.export.ExportCategory
import com.example.data.export.ExportFormat
import com.example.data.export.ExportTimeRange
import com.example.data.export.ScanExportManager
import com.example.data.local.AccountRole
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.EncryptedMessageEntity
import com.example.data.local.GridSightDatabase
import com.example.data.local.NodeType
import com.example.data.local.OperatorAccountEntity
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.ReadinessStatus
import com.example.data.preferences.OperatorConfig
import com.example.data.preferences.OperatorPreferences
import com.example.data.remote.OverpassApiService
import com.example.mesh.MeshEngineState
import com.example.mesh.P2pMeshEngine
import com.example.sensors.DeviceConnector
import com.example.sensors.RadioSensorManager
import com.example.sensors.SensorHardwareStatus
import com.example.sensors.TelemetryStreamEvent
import com.example.threat.ThreatAlert
import com.example.threat.ThreatDetectionEngine
import com.example.ui.components.MapTileLayer
import com.example.ui.theme.FieldVisualMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfilerFilter(val label: String) {
    ALL("ALL"),
    BLE("BLE"),
    WIFI("WI-FI"),
    NFC("NFC"),
    WEAK_SEC("RISK/OPEN"),
    CIVIC("HOTSPOT/CAM")
}

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val activeAccount: OperatorAccountEntity? = null,
    val accountCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class ExportUiState(
    val format: ExportFormat = ExportFormat.CSV,
    val timeRange: ExportTimeRange = ExportTimeRange.LAST_24H,
    val category: ExportCategory = ExportCategory.ALL,
    val matchingCount: Int = 0,
    val lastExportMessage: String? = null
)

data class GridSightUiState(
    val auth: AuthUiState = AuthUiState(),
    val config: OperatorConfig? = null,
    val hardware: SensorHardwareStatus = SensorHardwareStatus(),
    val mesh: MeshEngineState = MeshEngineState(),
    val nodes: List<DetectedNodeEntity> = emptyList(),
    val filteredNodes: List<DetectedNodeEntity> = emptyList(),
    val peers: List<PeerDeviceEntity> = emptyList(),
    val messages: List<EncryptedMessageEntity> = emptyList(),
    val accounts: List<OperatorAccountEntity> = emptyList(),
    val threats: List<ThreatAlert> = emptyList(),
    val telemetryFeed: List<TelemetryStreamEvent> = emptyList(),
    val selectedNode: DetectedNodeEntity? = null,
    val selectedPeerId: String? = null,
    val activeFilter: ProfilerFilter = ProfilerFilter.ALL,
    val isScanningActive: Boolean = false,
    val isCivicQueryLoading: Boolean = false,
    val showThreatSheet: Boolean = false,
    val showExportSheet: Boolean = false,
    val showCiphertextInComms: Boolean = false,
    val mapLayer: MapTileLayer = MapTileLayer.SATELLITE,
    val activeCameraNode: DetectedNodeEntity? = null,
    val exportState: ExportUiState = ExportUiState(),
    val statusBanner: String? = null
)

class GridSightViewModel(application: Application) : AndroidViewModel(application) {

    private val database = GridSightDatabase.getInstance(application)
    private val dao = database.dao()
    private val preferences = OperatorPreferences(application)
    private val overpassApi = OverpassApiService.create()
    private val repository = GridSightRepository(dao, overpassApi)
    val sensorManager = RadioSensorManager(application)
    private val deviceConnector = DeviceConnector(application)
    private val meshEngine = P2pMeshEngine(application, dao, viewModelScope)

    private val _authState = MutableStateFlow(AuthUiState())
    private val _selectedNodeId = MutableStateFlow<String?>(null)
    private val _selectedPeerId = MutableStateFlow<String?>(null)
    private val _activeFilter = MutableStateFlow(ProfilerFilter.ALL)
    private val _isScanning = MutableStateFlow(false)
    private val _isCivicLoading = MutableStateFlow(false)
    private val _showThreatSheet = MutableStateFlow(false)
    private val _showExportSheet = MutableStateFlow(false)
    private val _showCiphertext = MutableStateFlow(false)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _telemetryFeed = MutableStateFlow<List<TelemetryStreamEvent>>(emptyList())
    private val _cryptoThreats = MutableStateFlow<List<ThreatAlert>>(emptyList())
    private val _acknowledgedThreatIds = MutableStateFlow<Set<String>>(emptySet())
    private val _exportFormat = MutableStateFlow(ExportFormat.CSV)
    private val _exportRange = MutableStateFlow(ExportTimeRange.LAST_24H)
    private val _exportCategory = MutableStateFlow(ExportCategory.ALL)
    private val _lastExportMsg = MutableStateFlow<String?>(null)
    private val _mapLayer = MutableStateFlow(MapTileLayer.SATELLITE)
    private val _activeCameraNode = MutableStateFlow<DetectedNodeEntity?>(null)

    private val baseDataFlow = combine(
        preferences.configFlow,
        sensorManager.status,
        meshEngine.state,
        repository.allNodes,
        repository.allPeers
    ) { cfg, hw, mesh, nodes, peers ->
        BaseDataBundle(cfg, hw, mesh, nodes, peers)
    }

    private val auxiliaryDataFlow = combine(
        repository.allMessages,
        repository.allAccounts,
        _telemetryFeed,
        _cryptoThreats,
        _acknowledgedThreatIds
    ) { msgs, accounts, feed, cryptoAlerts, ackIds ->
        AuxDataBundle(msgs, accounts, feed, cryptoAlerts, ackIds)
    }

    private val uiControlsFlow = combine(
        _authState,
        _selectedNodeId,
        _selectedPeerId,
        _activeFilter,
        _isScanning
    ) { auth, selNodeId, selPeerId, filter, scanning ->
        ControlBundleA(auth, selNodeId, selPeerId, filter, scanning)
    }

    private val sheetControlsFlow = combine(
        _isCivicLoading,
        _showThreatSheet,
        _showExportSheet,
        _showCiphertext,
        _statusBanner
    ) { civicLoading, threatSheet, exportSheet, showCipher, banner ->
        ControlBundleB(civicLoading, threatSheet, exportSheet, showCipher, banner)
    }

    private val exportControlsFlow = combine(
        _exportFormat,
        _exportRange,
        _exportCategory,
        _lastExportMsg,
        _mapLayer
    ) { fmt, range, cat, lastMsg, mapLayer ->
        ExportControls(fmt, range, cat, lastMsg, mapLayer)
    }

    val uiState: StateFlow<GridSightUiState> = combine(
        combine(baseDataFlow, auxiliaryDataFlow, uiControlsFlow) { base, aux, ctrlA ->
            Triple(base, aux, ctrlA)
        },
        combine(sheetControlsFlow, exportControlsFlow, _activeCameraNode) { ctrlB, exp, activeCam ->
            Triple(ctrlB, exp, activeCam)
        }
    ) { (base, aux, ctrlA), (ctrlB, exp, activeCam) ->
        val filteredNodes = filterNodesForProfiler(base.nodes, ctrlA.filter)
        val threats = ThreatDetectionEngine.analyze(
            nodes = base.nodes,
            peers = base.peers,
            cryptoFailures = aux.cryptoAlerts,
            acknowledgedIds = aux.ackIds
        )
        val matchingExportCount = ScanExportManager.filterNodes(
            nodes = base.nodes,
            timeRange = exp.range,
            category = exp.category
        ).size

        GridSightUiState(
            auth = ctrlA.auth,
            config = base.config,
            hardware = base.hardware,
            mesh = base.mesh,
            nodes = base.nodes,
            filteredNodes = filteredNodes,
            peers = base.peers,
            messages = aux.messages,
            accounts = aux.accounts,
            threats = threats,
            telemetryFeed = aux.feed,
            selectedNode = base.nodes.firstOrNull { it.id == ctrlA.selectedNodeId },
            selectedPeerId = ctrlA.selectedPeerId ?: base.peers.firstOrNull()?.peerId,
            activeFilter = ctrlA.filter,
            isScanningActive = ctrlA.isScanning,
            isCivicQueryLoading = ctrlB.civicLoading,
            showThreatSheet = ctrlB.threatSheet,
            showExportSheet = ctrlB.exportSheet,
            showCiphertextInComms = ctrlB.showCipher,
            mapLayer = exp.mapLayer,
            activeCameraNode = activeCam,
            exportState = ExportUiState(
                format = exp.format,
                timeRange = exp.range,
                category = exp.category,
                matchingCount = matchingExportCount,
                lastExportMessage = exp.lastMsg
            ),
            statusBanner = ctrlB.banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GridSightUiState()
    )

    init {
        viewModelScope.launch {
            val cfg = preferences.ensureInitialized()
            val count = repository.getAccountCount()
            _authState.value = _authState.value.copy(accountCount = count)
            sensorManager.refreshCapabilities()
            meshEngine.updateOperatorSnapshot(
                config = cfg,
                lat = cfg.centerLat,
                lon = cfg.centerLon,
                motionState = "STATIONARY",
                batteryPercent = sensorManager.status.value.batteryPercent
            )
            meshEngine.startMesh()
        }

        // Persist captured RF / NFC nodes directly into Room offline-first DB
        viewModelScope.launch(Dispatchers.IO) {
            sensorManager.capturedNodes.collect { node ->
                val callsign = _authState.value.activeAccount?.callsign ?: "LOCAL-UNIT"
                repository.saveDetectedNode(node.copy(sourcePeerCallsign = callsign))
            }
        }

        // Collect live telemetry stream for HUD ticker
        viewModelScope.launch {
            sensorManager.telemetryStream.collect { ev -> appendTelemetry(ev) }
        }
        viewModelScope.launch {
            meshEngine.meshLogs.collect { ev -> appendTelemetry(ev) }
        }
        viewModelScope.launch {
            meshEngine.cryptoThreatAlerts.collect { alert ->
                _cryptoThreats.value = (listOf(alert) + _cryptoThreats.value).distinctBy { it.id }.take(12)
            }
        }

        // Handle NFC peer handshakes automatically
        viewModelScope.launch(Dispatchers.IO) {
            sensorManager.nfcPeerHandshake.collect { hs ->
                val cfg = uiState.value.config ?: return@collect
                meshEngine.pairAndPingPeer(hs.host, hs.port)
                appendTelemetry(TelemetryStreamEvent("NFC_PAIR", "Paired with ${hs.callsign} via NFC tap"))
            }
        }

        // Keep Mesh Engine snapshot updated with live operator telemetry
        viewModelScope.launch {
            combine(preferences.configFlow, sensorManager.status) { cfg, hw ->
                cfg to hw
            }.collect { (cfg, hw) ->
                meshEngine.updateOperatorSnapshot(
                    config = cfg,
                    lat = if (hw.hasLiveGpsFix) hw.currentLat else cfg.centerLat,
                    lon = if (hw.hasLiveGpsFix) hw.currentLon else cfg.centerLon,
                    motionState = hw.motionState,
                    batteryPercent = hw.batteryPercent
                )
            }
        }
    }

    // --- Authentication & RBAC ---
    fun registerOperator(username: String, callsign: String, password: String, role: AccountRole) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
            val result = withContext(Dispatchers.IO) {
                repository.registerAccount(username, callsign, password, role)
            }
            result.onSuccess { account ->
                preferences.updateProfile(
                    callsign = account.callsign,
                    role = account.role.displayLabel,
                    channelPassphrase = uiState.value.config?.channelPassphrase ?: "SECTOR-7-TACTICAL"
                )
                val count = withContext(Dispatchers.IO) { repository.getAccountCount() }
                _authState.value = AuthUiState(
                    isAuthenticated = true,
                    activeAccount = account,
                    accountCount = count,
                    isLoading = false
                )
                appendTelemetry(TelemetryStreamEvent("AUTH_OK", "Registered ${account.callsign} [${account.role.code}]"))
            }.onFailure { err ->
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Registration failed."
                )
            }
        }
    }

    fun loginOperator(username: String, password: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
            val result = withContext(Dispatchers.IO) {
                repository.authenticate(username, password)
            }
            result.onSuccess { account ->
                preferences.updateProfile(
                    callsign = account.callsign,
                    role = account.role.displayLabel,
                    channelPassphrase = uiState.value.config?.channelPassphrase ?: "SECTOR-7-TACTICAL"
                )
                _authState.value = AuthUiState(
                    isAuthenticated = true,
                    activeAccount = account,
                    accountCount = _authState.value.accountCount,
                    isLoading = false
                )
                appendTelemetry(TelemetryStreamEvent("AUTH_OK", "Session active: ${account.callsign} [${account.role.code}]"))
            }.onFailure { err ->
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Authentication failed."
                )
            }
        }
    }

    fun logoutOperator() {
        stopScanning()
        _authState.value = _authState.value.copy(
            isAuthenticated = false,
            activeAccount = null,
            errorMessage = null
        )
    }

    fun clearAuthError() {
        _authState.value = _authState.value.copy(errorMessage = null)
    }

    fun updateOperatorRole(targetUsername: String, newRole: AccountRole) {
        val actorRole = _authState.value.activeAccount?.role ?: AccountRole.STANDARD
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                repository.updateAccountRole(actorRole, targetUsername, newRole)
            }
            res.onSuccess {
                _statusBanner.value = "RBAC: Updated $targetUsername to ${newRole.displayLabel}."
                if (_authState.value.activeAccount?.username == targetUsername) {
                    _authState.value = _authState.value.copy(
                        activeAccount = _authState.value.activeAccount?.copy(role = newRole)
                    )
                }
            }.onFailure { _statusBanner.value = it.message }
        }
    }

    fun purgeOfflineDatabase() {
        val actorRole = _authState.value.activeAccount?.role ?: AccountRole.STANDARD
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { repository.purgeAllTelemetry(actorRole) }
            res.onSuccess {
                _selectedNodeId.value = null
                _statusBanner.value = "ADMIN PURGE: Offline telemetry & message cache cleared."
                appendTelemetry(TelemetryStreamEvent("ADMIN_OP", "Telemetry store purged by Admin", isAlert = true))
            }.onFailure { _statusBanner.value = it.message }
        }
    }

    fun rotateEcdhKeys() {
        val actorRole = _authState.value.activeAccount?.role ?: AccountRole.STANDARD
        if (actorRole != AccountRole.ADMIN) {
            _statusBanner.value = "RBAC DENIED: Administrator privilege required to rotate mesh keys."
            return
        }
        viewModelScope.launch {
            val updated = preferences.rotateKeyPair()
            _statusBanner.value = "ECDH P-256 Key Rotated: ${updated.keyFingerprint}"
            appendTelemetry(TelemetryStreamEvent("KEY_ROT", "New ECDH fingerprint ${updated.keyFingerprint}"))
        }
    }

    // --- Scanning & Sensors ---
    fun toggleScanning() {
        if (_isScanning.value) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    fun startScanning() {
        sensorManager.startAllScans()
        _isScanning.value = true
    }

    fun stopScanning() {
        sensorManager.stopAllScans()
        _isScanning.value = false
    }

    fun onPermissionsUpdated() {
        sensorManager.refreshCapabilities()
        if (_isScanning.value) sensorManager.startAllScans()
    }

    fun handleNfcIntent(intent: Intent?) {
        sensorManager.handleNfcIntent(intent)
    }

    fun setProfilerFilter(filter: ProfilerFilter) {
        _activeFilter.value = filter
    }

    fun selectNode(node: DetectedNodeEntity?) {
        _selectedNodeId.value = node?.id
    }

    fun deleteNode(nodeId: String) {
        val actorRole = _authState.value.activeAccount?.role ?: AccountRole.STANDARD
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { repository.deleteNode(actorRole, nodeId) }
            res.onSuccess {
                if (_selectedNodeId.value == nodeId) _selectedNodeId.value = null
                _statusBanner.value = "Node removed from offline store."
            }.onFailure { _statusBanner.value = it.message }
        }
    }

    fun togglePinNode(node: DetectedNodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.togglePin(node.id, node.isPinned)
        }
    }

    fun shareNodeToMesh(node: DetectedNodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            meshEngine.broadcastIntelToPeers(node)
            _statusBanner.value = "Encrypted & queued '${node.title}' for connected peers."
        }
    }

    // --- Overpass Civic Vicinity Lookup ---
    fun queryVicinityHotspotsAndCameras(customLat: Double? = null, customLon: Double? = null) {
        viewModelScope.launch {
            _isCivicLoading.value = true
            val hw = sensorManager.status.value
            val cfg = uiState.value.config
            val lat = customLat ?: if (hw.hasLiveGpsFix) hw.currentLat else (cfg?.centerLat ?: 41.8781)
            val lon = customLon ?: if (hw.hasLiveGpsFix) hw.currentLon else (cfg?.centerLon ?: -87.6298)
            preferences.updateCoordinates(lat, lon)

            val res = withContext(Dispatchers.IO) {
                repository.fetchCivicVicinityNodes(lat, lon, 1500)
            }
            _isCivicLoading.value = false
            res.onSuccess { count ->
                _statusBanner.value = if (count > 0) {
                    "Cached $count civic Wi-Fi hotspots & public cameras offline."
                } else {
                    "No civic nodes mapped in this 1.5km radius; serving offline cache."
                }
                appendTelemetry(TelemetryStreamEvent("OSM_CIVIC", "Fetched $count public nodes around %.4f,%.4f".format(lat, lon)))
            }.onFailure {
                _statusBanner.value = "Offline mode active — serving cached map & sensor nodes."
                appendTelemetry(TelemetryStreamEvent("OFFLINE_DB", "Network unavailable; offline cache active", isAlert = true))
            }
        }
    }

    // --- Visual Mode & Biometric Readiness ---
    fun cycleVisualMode() {
        val current = uiState.value.config?.visualMode ?: FieldVisualMode.CTOS_DARK
        val next = when (current) {
            FieldVisualMode.CTOS_DARK -> FieldVisualMode.NIGHT_OPS
            FieldVisualMode.NIGHT_OPS -> FieldVisualMode.RUGGED_DAYLIGHT
            FieldVisualMode.RUGGED_DAYLIGHT -> FieldVisualMode.CTOS_DARK
        }
        setVisualMode(next)
    }

    fun setVisualMode(mode: FieldVisualMode) {
        viewModelScope.launch { preferences.setVisualMode(mode) }
    }

    fun updateBiometrics(shareOptIn: Boolean, heartRateBpm: Int, readiness: ReadinessStatus) {
        viewModelScope.launch {
            preferences.updateBiometricSelfReport(shareOptIn, heartRateBpm, readiness)
            _statusBanner.value = "Biometric readiness telemetry updated (${readiness.label})."
        }
    }

    fun updateChannelPassphrase(passphrase: String) {
        val actorRole = _authState.value.activeAccount?.role ?: AccountRole.STANDARD
        if (actorRole != AccountRole.ADMIN) {
            _statusBanner.value = "RBAC DENIED: Only Admin can modify team cipher key."
            return
        }
        viewModelScope.launch {
            val cfg = uiState.value.config ?: return@launch
            preferences.updateProfile(cfg.callsign, cfg.role, passphrase)
            _statusBanner.value = "Team AES-256 channel cipher updated."
        }
    }

    // --- P2P Mesh & E2EE Comms ---
    fun pairPeerManual(host: String, portStr: String) {
        val port = portStr.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _statusBanner.value = "Enter valid LAN IP address and port."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            meshEngine.pairAndPingPeer(host.trim(), port)
                .onSuccess {
                    _statusBanner.value = "ECDH handshake complete with $host:$port."
                    meshEngine.flushOfflineSyncQueue()
                }
                .onFailure {
                    _statusBanner.value = "Peer unreachable ($host:$port) — outbound data queued offline."
                }
        }
    }

    fun flushMeshSync() {
        viewModelScope.launch(Dispatchers.IO) {
            meshEngine.flushOfflineSyncQueue()
            _statusBanner.value = "Offline sync queue processed."
        }
    }

    fun selectPeerForComms(peerId: String) {
        _selectedPeerId.value = peerId
    }

    fun sendE2eeMessage(peer: PeerDeviceEntity, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            meshEngine.sendDirectE2eeMessage(peer, text.trim())
        }
    }

    fun toggleCiphertextView() {
        _showCiphertext.value = !_showCiphertext.value
    }

    // --- Threat & Export Sheets ---
    fun setShowThreatSheet(show: Boolean) {
        _showThreatSheet.value = show
    }

    fun acknowledgeThreat(threatId: String) {
        _acknowledgedThreatIds.value = _acknowledgedThreatIds.value + threatId
    }

    fun inspectThreatTarget(alert: ThreatAlert) {
        _showThreatSheet.value = false
        alert.targetNodeId?.let { _selectedNodeId.value = it }
    }

    fun setShowExportSheet(show: Boolean) {
        _showExportSheet.value = show
    }

    fun updateExportConfig(
        format: ExportFormat? = null,
        range: ExportTimeRange? = null,
        category: ExportCategory? = null
    ) {
        format?.let { _exportFormat.value = it }
        range?.let { _exportRange.value = it }
        category?.let { _exportCategory.value = it }
    }

    fun writeExportToUri(uri: Uri) {
        val state = uiState.value
        val role = state.auth.activeAccount?.role ?: AccountRole.STANDARD
        val callsign = state.auth.activeAccount?.callsign ?: state.config?.callsign ?: "UNIT-01"
        viewModelScope.launch(Dispatchers.IO) {
            val content = ScanExportManager.buildExportContent(
                nodes = state.nodes,
                format = state.exportState.format,
                timeRange = state.exportState.timeRange,
                category = state.exportState.category,
                operatorCallsign = callsign,
                role = role
            )
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Cannot open output stream")
            }.onSuccess {
                _lastExportMsg.value = "Exported ${state.exportState.matchingCount} records (${state.exportState.format.extension.uppercase()})."
                _statusBanner.value = _lastExportMsg.value
                appendTelemetry(TelemetryStreamEvent("EXPORT", _lastExportMsg.value.orEmpty()))
            }.onFailure { err ->
                _lastExportMsg.value = "Export failed: ${err.message}"
            }
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    private fun appendTelemetry(event: TelemetryStreamEvent) {
        _telemetryFeed.value = (listOf(event) + _telemetryFeed.value).take(25)
    }

    private fun filterNodesForProfiler(
        nodes: List<DetectedNodeEntity>,
        filter: ProfilerFilter
    ): List<DetectedNodeEntity> {
        return when (filter) {
            ProfilerFilter.ALL -> nodes
            ProfilerFilter.BLE -> nodes.filter { it.nodeType == NodeType.BLE_DEVICE }
            ProfilerFilter.WIFI -> nodes.filter { it.nodeType == NodeType.WIFI_AP }
            ProfilerFilter.NFC -> nodes.filter { it.nodeType == NodeType.NFC_TAG }
            ProfilerFilter.WEAK_SEC -> nodes.filter { it.securityPosture.isWarning }
            ProfilerFilter.CIVIC -> nodes.filter {
                it.nodeType == NodeType.PUBLIC_HOTSPOT || it.nodeType == NodeType.PUBLIC_CAMERA
            }
        }
    }

    fun closeCameraViewer() {
        _activeCameraNode.value = null
    }

    fun connectToNode(node: DetectedNodeEntity) {
        if (node.nodeType == NodeType.PUBLIC_CAMERA) {
            _activeCameraNode.value = node
            appendTelemetry(TelemetryStreamEvent("CAM_VIEW", "Opened public camera viewport: ${node.title}"))
            return
        }
        deviceConnector.connectToNode(node) { msg, isAlert ->
            _statusBanner.value = msg
            appendTelemetry(TelemetryStreamEvent("CONN", msg, isAlert = isAlert))
        }
    }

    override fun onCleared() {
        sensorManager.stopAllScans()
        deviceConnector.release()
        meshEngine.stopMesh()
        super.onCleared()
    }
}

private data class BaseDataBundle(
    val config: OperatorConfig,
    val hardware: SensorHardwareStatus,
    val mesh: MeshEngineState,
    val nodes: List<DetectedNodeEntity>,
    val peers: List<PeerDeviceEntity>
)

private data class AuxDataBundle(
    val messages: List<EncryptedMessageEntity>,
    val accounts: List<OperatorAccountEntity>,
    val feed: List<TelemetryStreamEvent>,
    val cryptoAlerts: List<ThreatAlert>,
    val ackIds: Set<String>
)

private data class ControlBundleA(
    val auth: AuthUiState,
    val selectedNodeId: String?,
    val selectedPeerId: String?,
    val filter: ProfilerFilter,
    val isScanning: Boolean
)

private data class ControlBundleB(
    val civicLoading: Boolean,
    val threatSheet: Boolean,
    val exportSheet: Boolean,
    val showCipher: Boolean,
    val banner: String?
)

private data class ExportControls(
    val format: ExportFormat,
    val range: ExportTimeRange,
    val category: ExportCategory,
    val lastMsg: String?,
    val mapLayer: MapTileLayer
)
