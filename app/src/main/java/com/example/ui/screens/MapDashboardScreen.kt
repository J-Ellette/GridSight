package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.example.data.local.PeerDeviceEntity
import com.example.data.local.ReadinessStatus
import com.example.ui.GridSightUiState
import com.example.ui.components.OfflineTileCache
import com.example.ui.components.SlippyMapMath
import com.example.ui.theme.LocalTacticalPalette
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun MapDashboardScreen(
    state: GridSightUiState,
    onSelectNode: (DetectedNodeEntity) -> Unit,
    onQueryCivicVicinity: (Double?, Double?) -> Unit
) {
    val palette = LocalTacticalPalette.current
    val context = LocalContext.current
    val tileCache = remember { OfflineTileCache(context.applicationContext) }

    val unitLat = if (state.hardware.hasLiveGpsFix) state.hardware.currentLat else (state.config?.centerLat ?: 41.8781)
    val unitLon = if (state.hardware.hasLiveGpsFix) state.hardware.currentLon else (state.config?.centerLon ?: -87.6298)

    var tileZoom by remember { mutableIntStateOf(15) }
    var viewCenterLat by remember(unitLat) { mutableDoubleStateOf(unitLat) }
    var viewCenterLon by remember(unitLon) { mutableDoubleStateOf(unitLon) }
    var latInput by remember(unitLat) { mutableStateOf("%.4f".format(unitLat)) }
    var lonInput by remember(unitLon) { mutableStateOf("%.4f".format(unitLon)) }

    val loadedTiles = remember { mutableStateMapOf<String, ImageBitmap>() }

    val infinite = rememberInfiniteTransition(label = "mapRadar")
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(4200, easing = LinearEasing)),
        label = "sweepAngle"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Vicinity Selector, Overpass Query & Map Layer Switcher
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(palette.borderWidth, palette.hudBorder),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = latInput,
                        onValueChange = { latInput = it },
                        label = { Text("LAT") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonInput,
                        onValueChange = { lonInput = it },
                        label = { Text("LON") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val newLat = latInput.toDoubleOrNull()
                            val newLon = lonInput.toDoubleOrNull()
                            if (newLat != null && newLon != null) {
                                viewCenterLat = newLat
                                viewCenterLon = newLon
                            }
                            onQueryCivicVicinity(newLat, newLon)
                        },
                        enabled = !state.isCivicQueryLoading,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("map_query_civic_button")
                    ) {
                        if (state.isCivicQueryLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Public, contentDescription = "Query public hotspots and cameras")
                            Spacer(Modifier.size(6.dp))
                            Text("CIVIC", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SATELLITE RADAR • OFFLINE CACHE: ${state.nodes.size} NODES",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accentCyan
                    )
                    Text(
                        text = "ZOOM Z$tileZoom",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Real Offline-Cached Slippy Tile Map + Radar Sweep Overlay
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(palette.borderWidth, palette.hudBorder),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tileZoom) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val tilePx = SlippyMapMath.TILE_SIZE_PX
                            val centerTileX = SlippyMapMath.lonToTileX(viewCenterLon, tileZoom) - (pan.x / tilePx)
                            val centerTileY = SlippyMapMath.latToTileY(viewCenterLat, tileZoom) - (pan.y / tilePx)
                            viewCenterLon = SlippyMapMath.tileXToLon(centerTileX, tileZoom).coerceIn(-179.9, 179.9)
                            viewCenterLat = SlippyMapMath.tileYToLat(centerTileY, tileZoom).coerceIn(-84.0, 84.0)
                            if (zoom > 1.25f && tileZoom < 18) tileZoom++
                            else if (zoom < 0.8f && tileZoom > 12) tileZoom--
                        }
                    }
            ) {
                val widthPx = constraints.maxWidth.toFloat()
                val heightPx = constraints.maxHeight.toFloat()
                val tileSizePx = SlippyMapMath.TILE_SIZE_PX
                val centerTileX = SlippyMapMath.lonToTileX(viewCenterLon, tileZoom)
                val centerTileY = SlippyMapMath.latToTileY(viewCenterLat, tileZoom)

                val minTileX = floor(centerTileX - (widthPx / 2.0) / tileSizePx).toInt()
                val maxTileX = ceil(centerTileX + (widthPx / 2.0) / tileSizePx).toInt()
                val minTileY = floor(centerTileY - (heightPx / 2.0) / tileSizePx).toInt()
                val maxTileY = ceil(centerTileY + (heightPx / 2.0) / tileSizePx).toInt()

                LaunchedEffect(state.mapLayer, tileZoom, minTileX, maxTileX, minTileY, maxTileY) {
                    val maxIdx = (1 shl tileZoom)
                    for (tx in minTileX..maxTileX) {
                        for (ty in minTileY..maxTileY) {
                            if (ty < 0 || ty >= maxIdx) continue
                            val wrappedX = ((tx % maxIdx) + maxIdx) % maxIdx
                            val key = "${state.mapLayer.name}_${tileZoom}_${wrappedX}_${ty}"
                            if (!loadedTiles.containsKey(key)) {
                                tileCache.getMemoryCached(state.mapLayer, tileZoom, wrappedX, ty)?.let {
                                    loadedTiles[key] = it
                                } ?: launch {
                                    tileCache.loadTile(state.mapLayer, tileZoom, wrappedX, ty)?.let { bmp ->
                                        loadedTiles[key] = bmp
                                    }
                                }
                            }
                        }
                    }
                }

                // Draw Real Map Tiles + Tactical Grid + Radar Sweep
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxIdx = (1 shl tileZoom)
                    val tilePxInt = tileSizePx.roundToInt()

                    // Fallback subtle vector grid if offline & tile not yet cached
                    val gridStep = 48.dp.toPx()
                    var gx = 0f
                    while (gx < size.width) {
                        drawLine(palette.gridLine.copy(alpha = 0.25f), Offset(gx, 0f), Offset(gx, size.height), 1f)
                        gx += gridStep
                    }
                    var gy = 0f
                    while (gy < size.height) {
                        drawLine(palette.gridLine.copy(alpha = 0.25f), Offset(0f, gy), Offset(size.width, gy), 1f)
                        gy += gridStep
                    }

                    // Render cached/fetched Esri World Imagery Satellite tiles
                    for (tx in minTileX..maxTileX) {
                        for (ty in minTileY..maxTileY) {
                            if (ty < 0 || ty >= maxIdx) continue
                            val wrappedX = ((tx % maxIdx) + maxIdx) % maxIdx
                            val key = "${state.mapLayer.name}_${tileZoom}_${wrappedX}_${ty}"
                            val img = loadedTiles[key] ?: continue
                            val left = ((tx - centerTileX) * tileSizePx + widthPx / 2.0).roundToInt()
                            val top = ((ty - centerTileY) * tileSizePx + heightPx / 2.0).roundToInt()
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(left, top),
                                dstSize = IntSize(tilePxInt + 1, tilePxInt + 1)
                            )
                        }
                    }

                    // Unit Position on Mercator Map + Radar Range Rings + Animated Sweep Beam
                    val unitTx = SlippyMapMath.lonToTileX(unitLon, tileZoom)
                    val unitTy = SlippyMapMath.latToTileY(unitLat, tileZoom)
                    val unitCenter = Offset(
                        ((unitTx - centerTileX) * tileSizePx + widthPx / 2.0).toFloat(),
                        ((unitTy - centerTileY) * tileSizePx + heightPx / 2.0).toFloat()
                    )

                    val metersPerTile = 40_075_016.686 * cos(Math.toRadians(unitLat)) / 2.0.pow(tileZoom)
                    val pxPerMeter = tileSizePx / metersPerTile.coerceAtLeast(1.0)
                    listOf(150.0, 400.0, 800.0).forEach { meters ->
                        val r = (meters * pxPerMeter).toFloat().coerceIn(28f, 900f)
                        drawCircle(
                            color = palette.accentCyan.copy(alpha = 0.45f),
                            radius = r,
                            center = unitCenter,
                            style = Stroke(width = 2f)
                        )
                    }

                    if (state.isScanningActive) {
                        val rad = Math.toRadians(sweepAngle.toDouble())
                        val beamLen = 220.dp.toPx()
                        val tip = Offset(
                            unitCenter.x + (beamLen * cos(rad)).toFloat(),
                            unitCenter.y + (beamLen * sin(rad)).toFloat()
                        )
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(palette.radarBeam.copy(alpha = 0.9f), Color.Transparent),
                                start = unitCenter,
                                end = tip
                            ),
                            start = unitCenter,
                            end = tip,
                            strokeWidth = 5f
                        )
                    }

                    drawCircle(color = palette.secureGreen, radius = 9.dp.toPx(), center = unitCenter)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = 16.dp.toPx(),
                        center = unitCenter,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Plot Detected Nodes (Hotspots, Cameras, BLE, Wi-Fi, NFC) at Exact Mercator Coordinates
                val density = LocalDensity.current
                val halfMarkerPx = with(density) { 24.dp.toPx() }
                state.nodes.take(50).forEach { node ->
                    val nTx = SlippyMapMath.lonToTileX(node.longitude, tileZoom)
                    val nTy = SlippyMapMath.latToTileY(node.latitude, tileZoom)
                    val px = (((nTx - centerTileX) * tileSizePx + widthPx / 2.0) - halfMarkerPx).roundToInt()
                    val py = (((nTy - centerTileY) * tileSizePx + heightPx / 2.0) - halfMarkerPx).roundToInt()

                    if (px in -80..widthPx.toInt() && py in -80..heightPx.toInt()) {
                        val isSelected = state.selectedNode?.id == node.id
                        val markerColor = when {
                            node.securityPosture.isWarning -> palette.alertCrimson
                            node.nodeType == NodeType.PUBLIC_CAMERA -> palette.cautionAmber
                            node.nodeType == NodeType.PUBLIC_HOTSPOT -> palette.secureGreen
                            else -> palette.accentCyan
                        }
                        Surface(
                            color = if (isSelected) markerColor else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            border = BorderStroke(if (isSelected) 2.5.dp else 1.5.dp, markerColor),
                            shape = CircleShape,
                            modifier = Modifier
                                .offset { IntOffset(px, py) }
                                .size(48.dp)
                                .clickable { onSelectNode(node) }
                                .testTag("map_node_${node.id}")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when (node.nodeType) {
                                        NodeType.PUBLIC_CAMERA -> Icons.Filled.Videocam
                                        NodeType.PUBLIC_HOTSPOT, NodeType.WIFI_AP -> Icons.Filled.Wifi
                                        else -> Icons.Filled.Router
                                    },
                                    contentDescription = node.title,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else markerColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Plot Consented Peer Biometric Beacons
                state.peers.forEach { peer ->
                    val pTx = SlippyMapMath.lonToTileX(peer.longitude, tileZoom)
                    val pTy = SlippyMapMath.latToTileY(peer.latitude, tileZoom)
                    val px = ((pTx - centerTileX) * tileSizePx + widthPx / 2.0).roundToInt()
                    val py = ((pTy - centerTileY) * tileSizePx + heightPx / 2.0).roundToInt()
                    if (px in -80..widthPx.toInt() && py in -80..heightPx.toInt()) {
                        PeerMapBeacon(
                            peer = peer,
                            modifier = Modifier.offset { IntOffset(px, py) }
                        )
                    }
                }

                // Zoom + / - & Re-center Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, palette.hudBorder),
                        shape = MaterialTheme.shapes.small
                    ) {
                        IconButton(
                            onClick = { if (tileZoom < 18) tileZoom++ },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("map_zoom_in_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = palette.accentCyan)
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, palette.hudBorder),
                        shape = MaterialTheme.shapes.small
                    ) {
                        IconButton(
                            onClick = { if (tileZoom > 12) tileZoom-- },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("map_zoom_out_button")
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = palette.accentCyan)
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, palette.hudBorder),
                        shape = MaterialTheme.shapes.small
                    ) {
                        IconButton(
                            onClick = {
                                viewCenterLat = unitLat
                                viewCenterLon = unitLon
                                tileZoom = 15
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("map_recenter_button")
                        ) {
                            Icon(Icons.Filled.MyLocation, contentDescription = "Center map on unit", tint = palette.accentCyan)
                        }
                    }
                }

                // Legend Pill
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, palette.hudBorder),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "${state.mapLayer.shortCode} • GREEN:HOTSPOT • AMBER:CAM • RED:RISK • CYAN:RF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerMapBeacon(peer: PeerDeviceEntity, modifier: Modifier = Modifier) {
    val palette = LocalTacticalPalette.current
    val color = when (peer.readinessStatus) {
        ReadinessStatus.DISTRESS -> palette.alertCrimson
        ReadinessStatus.CAUTION -> palette.cautionAmber
        else -> palette.secureGreen
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.5.dp, color),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
    ) {
        Text(
            text = "${peer.callsign} ${if (peer.heartRateBpm > 0) "${peer.heartRateBpm}BPM" else peer.readinessStatus.label}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
