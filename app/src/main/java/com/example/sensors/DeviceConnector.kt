package com.example.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.SecurityPosture

class DeviceConnector(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var activeGatt: BluetoothGatt? = null
    private var activeWifiCallback: ConnectivityManager.NetworkCallback? = null

    fun connectToNode(
        node: DetectedNodeEntity,
        onStatus: (String, Boolean) -> Unit
    ) {
        when (node.nodeType) {
            NodeType.WIFI_AP, NodeType.PUBLIC_HOTSPOT -> connectToWifiNode(node, onStatus)
            NodeType.BLE_DEVICE -> connectToBleDevice(node, onStatus)
            NodeType.NFC_TAG -> handleNfcNodeConnect(node, onStatus)
            NodeType.PUBLIC_CAMERA -> openExternalLink(node.feedOrReferenceUrl, "camera feed", onStatus)
            NodeType.TEAM_PEER -> onStatus("Initiating E2EE mesh link to ${node.title}...", false)
        }
    }

    private fun connectToWifiNode(
        node: DetectedNodeEntity,
        onStatus: (String, Boolean) -> Unit
    ) {
        val ssid = node.title.takeIf { it.isNotBlank() && it != "<HIDDEN_SSID>" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ssid != null &&
            (node.securityPosture == SecurityPosture.OPEN_UNENCRYPTED ||
                node.securityPosture == SecurityPosture.PUBLIC_CIVIC)
        ) {
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (node.macOrId.contains(":") && node.macOrId.length == 17) {
                runCatching { MacAddress.fromString(node.macOrId) }.getOrNull()?.let { mac ->
                    specBuilder.setBssid(mac)
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specBuilder.build())
                .build()

            activeWifiCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onStatus("WI-FI CONNECTED: Associated with '$ssid'.", false)
                }

                override fun onUnavailable() {
                    onStatus("WI-FI: System association cancelled or '$ssid' unavailable.", true)
                }
            }
            activeWifiCallback = callback
            runCatching {
                connectivityManager?.requestNetwork(request, callback)
                onStatus("Requesting OS association with open WLAN '$ssid'...", false)
            }.onFailure {
                openWifiPanel(onStatus)
            }
        } else {
            openWifiPanel(onStatus)
        }
    }

    private fun openWifiPanel(onStatus: (String, Boolean) -> Unit) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
            onStatus("Opened system Wi-Fi selector for secured/manual association.", false)
        }.onFailure {
            onStatus("Unable to open Wi-Fi settings: ${it.message}", true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToBleDevice(
        node: DetectedNodeEntity,
        onStatus: (String, Boolean) -> Unit
    ) {
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasConnect) {
            onStatus("BLUETOOTH_CONNECT permission required to open GATT link.", true)
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth adapter inactive or unavailable.", true)
            return
        }
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(node.macOrId) }
            .getOrElse {
                onStatus("Invalid BLE MAC address '${node.macOrId}'.", true)
                return
            }

        activeGatt?.let { runCatching { it.close() } }
        onStatus("Opening BLE GATT connection to ${node.title} (${node.macOrId})...", false)

        activeGatt = device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            onStatus("BLE GATT CONNECTED: ${node.title} — discovering services...", false)
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            onStatus("BLE GATT DISCONNECTED from ${node.title} (status=$status).", status != BluetoothGatt.GATT_SUCCESS)
                            runCatching { gatt.close() }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val count = gatt.services?.size ?: 0
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        onStatus("BLE GATT READY: ${node.title} exposes $count GATT service(s).", false)
                    } else {
                        onStatus("BLE service discovery failed (status=$status).", true)
                    }
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun handleNfcNodeConnect(
        node: DetectedNodeEntity,
        onStatus: (String, Boolean) -> Unit
    ) {
        val url = node.feedOrReferenceUrl.takeIf { it.startsWith("http") }
            ?: extractUrlFromText(node.metadataSummary)
        if (url != null) {
            openExternalLink(url, "NFC URI target", onStatus)
        } else {
            val intent = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(intent)
                onStatus("Opened NFC settings; tap physical tag to read/connect.", false)
            }.onFailure {
                onStatus("NFC payload inspected: ${node.metadataSummary}", false)
            }
        }
    }

    private fun openExternalLink(
        url: String,
        label: String,
        onStatus: (String, Boolean) -> Unit
    ) {
        if (url.isBlank()) {
            onStatus("No reference link attached to this $label.", true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
            onStatus("Opened $label.", false)
        }.onFailure {
            onStatus("No handler found for $label URL.", true)
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val match = Regex("https?://[^\\s]+").find(text)
        return match?.value
    }

    fun release() {
        activeGatt?.let { runCatching { it.close() } }
        activeGatt = null
        activeWifiCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        activeWifiCallback = null
    }
}
