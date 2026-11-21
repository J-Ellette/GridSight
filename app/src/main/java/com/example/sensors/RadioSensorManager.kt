package com.example.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.SecurityPosture
import com.example.data.local.SyncStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TelemetryStreamEvent(
    val protocol: String,
    val summary: String,
    val isAlert: Boolean = false,
    val id: Long = System.nanoTime()
)

data class SensorHardwareStatus(
    val bleAvailable: Boolean = false,
    val bleScanning: Boolean = false,
    val wifiAvailable: Boolean = false,
    val wifiScanning: Boolean = false,
    val nfcAvailable: Boolean = false,
    val nfcEnabled: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val batteryPercent: Int = 100,
    val motionState: String = "STATIONARY",
    val currentLat: Double = 41.8781,
    val currentLon: Double = -87.6298,
    val hasLiveGpsFix: Boolean = false
)

data class NfcPeerHandshake(
    val peerId: String,
    val callsign: String,
    val publicKeyBase64: String,
    val host: String,
    val port: Int
)

class RadioSensorManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    private val _status = MutableStateFlow(SensorHardwareStatus())
    val status: StateFlow<SensorHardwareStatus> = _status.asStateFlow()

    private val _capturedNodes = MutableSharedFlow<DetectedNodeEntity>(extraBufferCapacity = 64)
    val capturedNodes: SharedFlow<DetectedNodeEntity> = _capturedNodes.asSharedFlow()

    private val _telemetryStream = MutableSharedFlow<TelemetryStreamEvent>(extraBufferCapacity = 64)
    val telemetryStream: SharedFlow<TelemetryStreamEvent> = _telemetryStream.asSharedFlow()

    private val _nfcPeerHandshake = MutableSharedFlow<NfcPeerHandshake>(extraBufferCapacity = 8)
    val nfcPeerHandshake: SharedFlow<NfcPeerHandshake> = _nfcPeerHandshake.asSharedFlow()

    private var wifiReceiverRegistered = false
    private var locationListening = false
    private var motionListening = false

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else true
            val rawName = if (hasConnect) runCatching { device.name }.getOrNull() else null
            val advName = result.scanRecord?.deviceName
            val displayName = rawName?.takeIf { it.isNotBlank() }
                ?: advName?.takeIf { it.isNotBlank() }
                ?: "BLE Beacon ${address.takeLast(5)}"

            val serviceCount = result.scanRecord?.serviceUuids?.size ?: 0
            val mfgSize = result.scanRecord?.manufacturerSpecificData?.size() ?: 0
            val (lat, lon) = offsetCoordinateForSignal(
                _status.value.currentLat,
                _status.value.currentLon,
                address,
                result.rssi
            )

            val node = DetectedNodeEntity(
                id = "ble_$address",
                nodeType = NodeType.BLE_DEVICE,
                title = displayName,
                subtitle = "MAC $address • ${result.rssi} dBm",
                macOrId = address,
                rssi = result.rssi,
                securityPosture = SecurityPosture.PUBLIC_CIVIC,
                latitude = lat,
                longitude = lon,
                metadataSummary = "SVC:$serviceCount | MFG:$mfgSize | TX:${result.txPower}dBm",
                lastSeenAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.LOCAL_ONLY
            )
            _capturedNodes.tryEmit(node)
            _telemetryStream.tryEmit(
                TelemetryStreamEvent(
                    protocol = "BLE_ADV",
                    summary = "$displayName [$address] RSSI=${result.rssi}dBm"
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = _status.value.copy(bleScanning = false)
            _telemetryStream.tryEmit(
                TelemetryStreamEvent(
                    protocol = "BLE_ERR",
                    summary = "BLE scanner error code=$errorCode",
                    isAlert = true
                )
            )
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val results = runCatching { wifiManager?.scanResults.orEmpty() }.getOrDefault(emptyList())
            results.take(40).forEach { sr ->
                @Suppress("DEPRECATION")
                val ssid = sr.SSID?.takeIf { it.isNotBlank() } ?: "<HIDDEN_SSID>"
                val bssid = sr.BSSID ?: return@forEach
                val caps = sr.capabilities.orEmpty().uppercase()
                val posture = when {
                    caps.contains("SAE") || caps.contains("WPA3") -> SecurityPosture.SECURE_WPA3
                    caps.contains("WPA2") || caps.contains("RSN") || caps.contains("WPA") ->
                        SecurityPosture.WPA2_STANDARD
                    caps.contains("WEP") -> SecurityPosture.WEAK_WEP_LEGACY
                    else -> SecurityPosture.OPEN_UNENCRYPTED
                }
                val (lat, lon) = offsetCoordinateForSignal(
                    _status.value.currentLat,
                    _status.value.currentLon,
                    bssid,
                    sr.level
                )
                val node = DetectedNodeEntity(
                    id = "wifi_$bssid",
                    nodeType = NodeType.WIFI_AP,
                    title = ssid,
                    subtitle = "BSSID $bssid • ${sr.frequency} MHz",
                    macOrId = bssid,
                    rssi = sr.level,
                    frequencyMhz = sr.frequency,
                    securityPosture = posture,
                    latitude = lat,
                    longitude = lon,
                    metadataSummary = "POSTURE:${posture.code} | ${sr.level}dBm | ${sr.frequency}MHz",
                    lastSeenAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.LOCAL_ONLY
                )
                _capturedNodes.tryEmit(node)
                _telemetryStream.tryEmit(
                    TelemetryStreamEvent(
                        protocol = "802.11",
                        summary = "$ssid [${posture.code}] ${sr.level}dBm",
                        isAlert = posture.isWarning
                    )
                )
            }
            _status.value = _status.value.copy(wifiScanning = false)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _status.value = _status.value.copy(
                currentLat = location.latitude,
                currentLon = location.longitude,
                hasLiveGpsFix = true
            )
            _telemetryStream.tryEmit(
                TelemetryStreamEvent(
                    protocol = "GNSS",
                    summary = "FIX %.5f, %.5f (±%.0fm)".format(
                        location.latitude,
                        location.longitude,
                        location.accuracy
                    )
                )
            )
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
            val state = when {
                mag > 3.2f -> "TRANSIT / SPRINT"
                mag > 0.9f -> "ON FOOT"
                else -> "STATIONARY"
            }
            val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 } ?: _status.value.batteryPercent
            if (state != _status.value.motionState || battery != _status.value.batteryPercent) {
                _status.value = _status.value.copy(
                    motionState = state,
                    batteryPercent = battery
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun refreshCapabilities() {
        val locGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            locGranted
        }
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 100

        _status.value = _status.value.copy(
            bleAvailable = bluetoothAdapter?.isEnabled == true &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            wifiAvailable = wifiManager != null,
            nfcAvailable = nfcAdapter != null,
            nfcEnabled = nfcAdapter?.isEnabled == true,
            locationPermissionGranted = locGranted,
            bluetoothPermissionGranted = btGranted,
            batteryPercent = battery
        )
    }

    @SuppressLint("MissingPermission")
    fun startAllScans() {
        refreshCapabilities()
        startTelemetryListeners()
        startBleScan()
        triggerWifiScan()
    }

    @SuppressLint("MissingPermission")
    fun stopAllScans() {
        stopBleScan()
        if (wifiReceiverRegistered) {
            runCatching { context.unregisterReceiver(wifiReceiver) }
            wifiReceiverRegistered = false
        }
        if (locationListening) {
            runCatching { locationManager?.removeUpdates(locationListener) }
            locationListening = false
        }
        if (motionListening) {
            runCatching { sensorManager?.unregisterListener(motionListener) }
            motionListening = false
        }
        _status.value = _status.value.copy(bleScanning = false, wifiScanning = false)
    }

    @SuppressLint("MissingPermission")
    private fun startTelemetryListeners() {
        if (!motionListening) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
                sensorManager.registerListener(
                    motionListener,
                    accel,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                motionListening = true
            }
        }
        if (!locationListening && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.forEach { provider ->
                if (runCatching { locationManager?.isProviderEnabled(provider) == true }.getOrDefault(false)) {
                    runCatching {
                        locationManager?.getLastKnownLocation(provider)?.let { loc ->
                            _status.value = _status.value.copy(
                                currentLat = loc.latitude,
                                currentLon = loc.longitude,
                                hasLiveGpsFix = true
                            )
                        }
                        locationManager?.requestLocationUpdates(provider, 10_000L, 10f, locationListener)
                        locationListening = true
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!btGranted) {
            _telemetryStream.tryEmit(
                TelemetryStreamEvent("BLE_AUTH", "Bluetooth scan permission required", isAlert = true)
            )
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _telemetryStream.tryEmit(
                TelemetryStreamEvent("BLE_HW", "Bluetooth adapter inactive or unavailable", isAlert = true)
            )
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            bleScanner?.startScan(null, settings, bleScanCallback)
            _status.value = _status.value.copy(bleScanning = true)
            _telemetryStream.tryEmit(
                TelemetryStreamEvent("BLE_SCAN", "LE radio sweep initiated")
            )
        }.onFailure { err ->
            _telemetryStream.tryEmit(
                TelemetryStreamEvent("BLE_ERR", err.message ?: "BLE scan failed", isAlert = true)
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else true
        if (btGranted) {
            runCatching { bleScanner?.stopScan(bleScanCallback) }
        }
        _status.value = _status.value.copy(bleScanning = false)
    }

    @SuppressLint("MissingPermission")
    fun triggerWifiScan() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            _telemetryStream.tryEmit(
                TelemetryStreamEvent("WIFI_AUTH", "Fine location permission required for 802.11 scan", isAlert = true)
            )
            return
        }
        val wm = wifiManager ?: return
        if (!wifiReceiverRegistered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            ContextCompat.registerReceiver(
                context,
                wifiReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wifiReceiverRegistered = true
        }
        _status.value = _status.value.copy(wifiScanning = true)
        @Suppress("DEPRECATION")
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        _telemetryStream.tryEmit(
            TelemetryStreamEvent(
                protocol = "802.11",
                summary = if (started) "Wi-Fi probe sweep dispatched" else "Reading cached Wi-Fi scan buffer"
            )
        )
        // Always read current scanResults buffer immediately as well (Android throttles startScan)
        val cached = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            wifiReceiver.onReceive(context, Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } else {
            _status.value = _status.value.copy(wifiScanning = false)
        }
    }

    fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val tagHexId = tag?.id?.joinToString(":") { "%02X".format(it) } ?: "UNKNOWN_TAG"

        val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        val recordsText = mutableListOf<String>()
        rawMsgs?.forEach { msgObj ->
            val msg = msgObj as? NdefMessage ?: return@forEach
            msg.records.forEach { record ->
                val payloadStr = String(record.payload).trim()
                recordsText.add(payloadStr)
                if (payloadStr.contains("gridsight://peer")) {
                    parsePeerUri(payloadStr)?.let { handshake ->
                        _nfcPeerHandshake.tryEmit(handshake)
                    }
                }
            }
        }

        val summary = recordsText.firstOrNull()?.take(120) ?: "ISO-14443 / Tech Tag Captured"
        val node = DetectedNodeEntity(
            id = "nfc_$tagHexId",
            nodeType = NodeType.NFC_TAG,
            title = "NFC Tag $tagHexId",
            subtitle = "Near-Field NDEF Contact",
            macOrId = tagHexId,
            rssi = -20,
            securityPosture = SecurityPosture.NFC_NDEF,
            latitude = _status.value.currentLat,
            longitude = _status.value.currentLon,
            metadataSummary = summary,
            lastSeenAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.LOCAL_ONLY
        )
        _capturedNodes.tryEmit(node)
        _telemetryStream.tryEmit(
            TelemetryStreamEvent("NFC_NDEF", "Captured tag $tagHexId :: $summary")
        )
    }

    private fun parsePeerUri(raw: String): NfcPeerHandshake? {
        val idx = raw.indexOf("gridsight://")
        if (idx < 0) return null
        val uri = runCatching { Uri.parse(raw.substring(idx)) }.getOrNull() ?: return null
        val peerId = uri.getQueryParameter("id") ?: return null
        val callsign = uri.getQueryParameter("callsign") ?: "NFC-PEER"
        val pub = uri.getQueryParameter("pub") ?: return null
        val host = uri.getQueryParameter("host") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        return NfcPeerHandshake(peerId, callsign, pub, host, port)
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun offsetCoordinateForSignal(
        baseLat: Double,
        baseLon: Double,
        seedId: String,
        rssi: Int
    ): Pair<Double, Double> {
        val hash = seedId.hashCode()
        val angleRad = ((hash and 0xFFFF) / 65535.0) * 2.0 * Math.PI
        // Approximate radial offset in degrees (~10m to ~180m depending on RSSI)
        val distanceMeters = ((-rssi).coerceIn(30, 100) - 25) * 2.2
        val dLat = (distanceMeters * cos(angleRad)) / 111_320.0
        val dLon = (distanceMeters * sin(angleRad)) / (111_320.0 * cos(Math.toRadians(baseLat)).coerceAtLeast(0.2))
        return (baseLat + dLat) to (baseLon + dLon)
    }
}
