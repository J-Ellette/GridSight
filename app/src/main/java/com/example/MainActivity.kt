package com.example

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AccountRole
import com.example.ui.GridSightViewModel
import com.example.ui.components.ExportDataSheet
import com.example.ui.components.ProfilerDossierOverlay
import com.example.ui.components.PublicCameraViewerDialog
import com.example.ui.components.TacticalHudHeader
import com.example.ui.components.ThreatAlertSheet
import com.example.ui.screens.AuthGateScreen
import com.example.ui.screens.BiometricsMeshScreen
import com.example.ui.screens.CommsScreen
import com.example.ui.screens.MapDashboardScreen
import com.example.ui.screens.ProfilerScannerScreen
import com.example.ui.theme.FieldVisualMode
import com.example.ui.theme.GridSightTheme

private enum class GridRoute(val route: String, val label: String, val icon: ImageVector) {
    MAP("map", "MAP HUD", Icons.Filled.Map),
    PROFILER("profiler", "PROFILER", Icons.Filled.Radar),
    BIOMETRICS("biometrics", "MESH/BIO", Icons.Filled.Favorite),
    COMMS("comms", "E2EE", Icons.AutoMirrored.Filled.Chat)
}

class MainActivity : ComponentActivity() {
    private val viewModel: GridSightViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        viewModel.handleNfcIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val visualMode = state.config?.visualMode ?: FieldVisualMode.CTOS_DARK

            GridSightTheme(visualMode = visualMode) {
                if (!state.auth.isAuthenticated) {
                    AuthGateScreen(
                        authState = state.auth,
                        visualMode = visualMode,
                        keyFingerprint = state.config?.keyFingerprint ?: "...",
                        onCycleVisualMode = viewModel::cycleVisualMode,
                        onLogin = viewModel::loginOperator,
                        onRegister = viewModel::registerOperator,
                        onClearError = viewModel::clearAuthError
                    )
                } else {
                    GridSightMainShell(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )
        runCatching { adapter.enableForegroundDispatch(this, pendingIntent, filters, null) }
    }

    override fun onPause() {
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleNfcIntent(intent)
    }
}

@Composable
private fun GridSightMainShell(viewModel: GridSightViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: GridRoute.MAP.route
    val snackbarHostState = remember { SnackbarHostState() }
    val operatorRole = state.auth.activeAccount?.role ?: AccountRole.STANDARD

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            viewModel.writeExportToUri(uri)
        }
    }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusBanner()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding()
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        GridRoute.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(GridRoute.MAP.route) { saveState = true }
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        GridRoute.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(GridRoute.MAP.route) { saveState = true }
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Persistent ctOS HUD Overlay Header
                    TacticalHudHeader(
                        state = state,
                        onToggleScan = viewModel::toggleScanning,
                        onCycleVisualMode = viewModel::cycleVisualMode,
                        onOpenThreats = { viewModel.setShowThreatSheet(true) },
                        onOpenExport = { viewModel.setShowExportSheet(true) },
                        onLogout = viewModel::logoutOperator
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = GridRoute.MAP.route,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(GridRoute.MAP.route) {
                                MapDashboardScreen(
                                    state = state,
                                    onSelectNode = viewModel::selectNode,
                                    onQueryCivicVicinity = viewModel::queryVicinityHotspotsAndCameras
                                )
                            }
                            composable(GridRoute.PROFILER.route) {
                                ProfilerScannerScreen(
                                    state = state,
                                    onSetFilter = viewModel::setProfilerFilter,
                                    onSelectNode = viewModel::selectNode,
                                    onConnectNode = viewModel::connectToNode,
                                    onPermissionsUpdated = viewModel::onPermissionsUpdated,
                                    onToggleScan = viewModel::toggleScanning
                                )
                            }
                            composable(GridRoute.BIOMETRICS.route) {
                                BiometricsMeshScreen(
                                    state = state,
                                    onUpdateBiometrics = viewModel::updateBiometrics,
                                    onPairPeerManual = viewModel::pairPeerManual,
                                    onFlushSync = viewModel::flushMeshSync,
                                    onUpdateOperatorRole = viewModel::updateOperatorRole,
                                    onRotateEcdhKeys = viewModel::rotateEcdhKeys,
                                    onUpdateChannelPassphrase = viewModel::updateChannelPassphrase,
                                    onPurgeDatabase = viewModel::purgeOfflineDatabase
                                )
                            }
                            composable(GridRoute.COMMS.route) {
                                CommsScreen(
                                    state = state,
                                    onSelectPeer = viewModel::selectPeerForComms,
                                    onSendMessage = viewModel::sendE2eeMessage,
                                    onToggleCiphertext = viewModel::toggleCiphertextView
                                )
                            }
                        }

                        // Persistent Expandable ctOS Profile HUD Overlay
                        state.selectedNode?.let { node ->
                            ProfilerDossierOverlay(
                                node = node,
                                operatorRole = operatorRole,
                                onConnectNode = { viewModel.connectToNode(node) },
                                onTogglePin = { viewModel.togglePinNode(node) },
                                onShareIntel = { viewModel.shareNodeToMesh(node) },
                                onDeleteNode = { viewModel.deleteNode(node.id) },
                                onDismiss = { viewModel.selectNode(null) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showThreatSheet) {
        ThreatAlertSheet(
            threats = state.threats,
            onInspectTarget = viewModel::inspectThreatTarget,
            onAcknowledge = viewModel::acknowledgeThreat,
            onDismiss = { viewModel.setShowThreatSheet(false) }
        )
    }

    if (state.showExportSheet) {
        ExportDataSheet(
            exportState = state.exportState,
            operatorRole = operatorRole,
            onUpdateConfig = viewModel::updateExportConfig,
            onLaunchSaveDocument = { format ->
                val filename = "gridsight_export_${System.currentTimeMillis()}.${format.extension}"
                exportDocLauncher.launch(filename)
            },
            onDismiss = { viewModel.setShowExportSheet(false) }
        )
    }

    state.activeCameraNode?.let { camNode ->
        PublicCameraViewerDialog(
            cameraNode = camNode,
            onDismiss = viewModel::closeCameraViewer
        )
    }
}
