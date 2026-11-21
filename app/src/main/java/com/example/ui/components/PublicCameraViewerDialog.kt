package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.DetectedNodeEntity
import com.example.ui.theme.LocalTacticalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PublicCameraViewerDialog(
    cameraNode: DetectedNodeEntity,
    onDismiss: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    val context = LocalContext.current
    val feedUrl = cameraNode.feedOrReferenceUrl.ifBlank {
        "https://www.openstreetmap.org/?mlat=${cameraNode.latitude}&mlon=${cameraNode.longitude}#map=18/${cameraNode.latitude}/${cameraNode.longitude}"
    }
    val isImageFeed = remember(feedUrl) {
        val lower = feedUrl.lowercase()
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.contains("mjpg")
    }

    var refreshNonce by remember { mutableIntStateOf(0) }
    var snapshotBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    if (isImageFeed) {
        LaunchedEffect(feedUrl, refreshNonce) {
            isLoading = true
            errorText = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder()
                        .url(feedUrl)
                        .header("User-Agent", "GridSight-CameraViewer/1.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: error("Empty image")
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            ?: error("Unsupported image stream")
                    }
                }
            }
            isLoading = false
            loaded.onSuccess { snapshotBitmap = it }
                .onFailure { errorText = it.message ?: "Unable to fetch snapshot" }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(palette.borderWidth, palette.cautionAmber),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(540.dp)
                .testTag("camera_viewer_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = palette.cautionAmber
                        )
                        Column {
                            Text(
                                text = "CAM FEED // ${cameraNode.title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "%.5f, %.5f • %s".format(
                                    cameraNode.latitude,
                                    cameraNode.longitude,
                                    cameraNode.subtitle
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accentCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row {
                        IconButton(
                            onClick = { refreshNonce++ },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("camera_refresh_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh camera feed")
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("camera_close_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close camera viewer")
                        }
                    }
                }

                // Viewport (Image Snapshot or Embedded WebView Portal)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    if (isImageFeed) {
                        when {
                            isLoading -> CircularProgressIndicator(color = palette.cautionAmber)
                            snapshotBitmap != null -> {
                                Image(
                                    bitmap = snapshotBitmap!!,
                                    contentDescription = "Public camera snapshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            else -> {
                                Text(
                                    text = "FEED OFFLINE / UNAVAILABLE: ${errorText ?: "Unknown error"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.alertCrimson,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                        }
                                    }
                                    loadUrl(feedUrl)
                                }
                            },
                            update = { webView ->
                                if (refreshNonce > 0) webView.reload()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isLoading) {
                            CircularProgressIndicator(color = palette.cautionAmber)
                        }
                    }

                    // HUD Overlay Badge inside Viewport
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, palette.cautionAmber),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "PUBLIC OPTIC // ${cameraNode.macOrId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.cautionAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Footer Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(feedUrl)))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("camera_open_browser_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("OPEN EXTERNAL", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("CLOSE FEED", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
