package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountRole
import com.example.ui.AuthUiState
import com.example.ui.theme.FieldVisualMode
import com.example.ui.theme.LocalTacticalPalette

@Composable
fun AuthGateScreen(
    authState: AuthUiState,
    visualMode: FieldVisualMode,
    keyFingerprint: String,
    onCycleVisualMode: () -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, callsign: String, password: String, role: AccountRole) -> Unit,
    onClearError: () -> Unit
) {
    val palette = LocalTacticalPalette.current
    var isRegisterMode by rememberSaveable { mutableStateOf(authState.accountCount == 0) }
    var username by rememberSaveable { mutableStateOf("") }
    var callsign by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var selectedRole by rememberSaveable { mutableStateOf(AccountRole.STANDARD) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = "GridSight emblem",
                        tint = palette.accentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = "GRIDSIGHT // OS",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "OFFLINE-FIRST TACTICAL PROFILER",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accentCyan
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCycleVisualMode,
                    border = BorderStroke(palette.borderWidth, palette.hudBorder),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("auth_visual_mode_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Contrast,
                        contentDescription = "Switch visual mode",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(visualMode.shortCode, style = MaterialTheme.typography.labelLarge)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(palette.borderWidth, palette.hudBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isRegisterMode) "PROVISION OPERATOR" else "OPERATOR AUTHENTICATION",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "PBKDF2 encrypted storage",
                            tint = palette.secureGreen
                        )
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !isRegisterMode,
                            onClick = {
                                isRegisterMode = false
                                onClearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.testTag("auth_mode_login")
                        ) {
                            Text("LOG IN", style = MaterialTheme.typography.labelLarge)
                        }
                        SegmentedButton(
                            selected = isRegisterMode,
                            onClick = {
                                isRegisterMode = true
                                onClearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.testTag("auth_mode_register")
                        ) {
                            Text("CREATE ACCOUNT", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; onClearError() },
                        label = { Text("Operator Username") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_username_input")
                    )

                    if (isRegisterMode) {
                        OutlinedTextField(
                            value = callsign,
                            onValueChange = { callsign = it },
                            label = { Text("Tactical Callsign (e.g. FOX-01)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_callsign_input")
                        )
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; onClearError() },
                        label = { Text("Passphrase (min 6 chars)") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle passphrase visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input")
                    )

                    if (isRegisterMode) {
                        Text(
                            text = "RBAC ACCESS CLEARANCE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilterChip(
                                selected = selectedRole == AccountRole.STANDARD,
                                onClick = { selectedRole = AccountRole.STANDARD },
                                label = { Text("Standard Operator") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Security,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("role_standard_chip")
                            )
                            FilterChip(
                                selected = selectedRole == AccountRole.ADMIN,
                                onClick = { selectedRole = AccountRole.ADMIN },
                                label = { Text("Administrator") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.AdminPanelSettings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("role_admin_chip")
                            )
                        }
                        Text(
                            text = if (selectedRole == AccountRole.ADMIN) {
                                "ADMIN: Full access — manage operator roles, rotate ECDH/channel keys, purge offline store, unredacted exports."
                            } else {
                                "STANDARD: Run RF/NFC scans, query public hotspots/cameras, view E2EE comms & biometrics, export scan CSV/JSON."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    authState.errorMessage?.let { err ->
                        Text(
                            text = "ACCESS DENIED // $err",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.alertCrimson,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            if (isRegisterMode) {
                                onRegister(username, callsign, password, selectedRole)
                            } else {
                                onLogin(username, password)
                            }
                        },
                        enabled = !authState.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("auth_submit_button")
                    ) {
                        Icon(
                            imageVector = if (isRegisterMode) Icons.Filled.PersonAdd else Icons.Filled.Lock,
                            contentDescription = null
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (isRegisterMode) "PROVISION & ENTER HUD" else "AUTHENTICATE",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CRYPTOGRAPHIC SUBSYSTEM",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accentCyan
                    )
                    Text(
                        text = "AUTH: PBKDF2-HMAC-SHA256 (100k iter + 128-bit salt)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "MESH E2EE: ECDH P-256 + AES-256-GCM • FP $keyFingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
