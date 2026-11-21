package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.crypto.CryptoEngine
import com.example.data.local.ReadinessStatus
import com.example.ui.theme.FieldVisualMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gridsight_prefs")

data class OperatorConfig(
    val peerId: String,
    val callsign: String,
    val role: String,
    val visualMode: FieldVisualMode,
    val publicKeyBase64: String,
    val privateKeyBase64: String,
    val keyFingerprint: String,
    val channelPassphrase: String,
    val shareBiometricsOptIn: Boolean,
    val selfHeartRateBpm: Int,
    val readinessStatus: ReadinessStatus,
    val centerLat: Double,
    val centerLon: Double
)

class OperatorPreferences(private val context: Context) {
    private object Keys {
        val PEER_ID = stringPreferencesKey("peer_id")
        val CALLSIGN = stringPreferencesKey("callsign")
        val ROLE = stringPreferencesKey("role")
        val VISUAL_MODE = stringPreferencesKey("visual_mode")
        val PUB_KEY = stringPreferencesKey("pub_key")
        val PRIV_KEY = stringPreferencesKey("priv_key")
        val CHANNEL_KEY = stringPreferencesKey("channel_key")
        val SHARE_BIO = booleanPreferencesKey("share_bio")
        val SELF_HR = intPreferencesKey("self_hr")
        val READINESS = stringPreferencesKey("readiness")
        val CENTER_LAT = doublePreferencesKey("center_lat")
        val CENTER_LON = doublePreferencesKey("center_lon")
    }

    val configFlow: Flow<OperatorConfig> = context.dataStore.data.map { prefs ->
        val pub = prefs[Keys.PUB_KEY].orEmpty()
        val priv = prefs[Keys.PRIV_KEY].orEmpty()
        val mode = prefs[Keys.VISUAL_MODE]?.let {
            runCatching { FieldVisualMode.valueOf(it) }.getOrNull()
        } ?: FieldVisualMode.CTOS_DARK
        val readiness = prefs[Keys.READINESS]?.let {
            runCatching { ReadinessStatus.valueOf(it) }.getOrNull()
        } ?: ReadinessStatus.NOMINAL

        OperatorConfig(
            peerId = prefs[Keys.PEER_ID] ?: "LOCAL-UNINIT",
            callsign = prefs[Keys.CALLSIGN] ?: "UNIT-01",
            role = prefs[Keys.ROLE] ?: "Field Recon",
            visualMode = mode,
            publicKeyBase64 = pub,
            privateKeyBase64 = priv,
            keyFingerprint = if (pub.isNotBlank()) CryptoEngine.computeFingerprint(pub) else "GENERATING...",
            channelPassphrase = prefs[Keys.CHANNEL_KEY] ?: "SECTOR-7-TACTICAL",
            shareBiometricsOptIn = prefs[Keys.SHARE_BIO] ?: true,
            selfHeartRateBpm = prefs[Keys.SELF_HR] ?: 74,
            readinessStatus = readiness,
            centerLat = prefs[Keys.CENTER_LAT] ?: 41.8781,
            centerLon = prefs[Keys.CENTER_LON] ?: -87.6298
        )
    }

    suspend fun ensureInitialized(): OperatorConfig {
        val current = context.dataStore.data.first()
        if (current[Keys.PUB_KEY].isNullOrBlank() || current[Keys.PEER_ID].isNullOrBlank()) {
            val keys = CryptoEngine.generateEcKeyPair()
            val generatedId = "NODE-" + UUID.randomUUID().toString().take(8).uppercase()
            context.dataStore.edit { prefs ->
                if (prefs[Keys.PEER_ID].isNullOrBlank()) prefs[Keys.PEER_ID] = generatedId
                if (prefs[Keys.CALLSIGN].isNullOrBlank()) {
                    prefs[Keys.CALLSIGN] = "OP-${generatedId.takeLast(4)}"
                }
                if (prefs[Keys.ROLE].isNullOrBlank()) prefs[Keys.ROLE] = "Field Recon"
                prefs[Keys.PUB_KEY] = keys.publicKeyBase64
                prefs[Keys.PRIV_KEY] = keys.privateKeyBase64
            }
        }
        return configFlow.first()
    }

    suspend fun setVisualMode(mode: FieldVisualMode) {
        context.dataStore.edit { it[Keys.VISUAL_MODE] = mode.name }
    }

    suspend fun updateProfile(callsign: String, role: String, channelPassphrase: String) {
        context.dataStore.edit {
            if (callsign.isNotBlank()) it[Keys.CALLSIGN] = callsign.trim().take(24)
            if (role.isNotBlank()) it[Keys.ROLE] = role.trim().take(24)
            if (channelPassphrase.isNotBlank()) it[Keys.CHANNEL_KEY] = channelPassphrase.trim()
        }
    }

    suspend fun updateBiometricSelfReport(
        shareOptIn: Boolean,
        heartRateBpm: Int,
        readiness: ReadinessStatus
    ) {
        context.dataStore.edit {
            it[Keys.SHARE_BIO] = shareOptIn
            it[Keys.SELF_HR] = heartRateBpm.coerceIn(40, 210)
            it[Keys.READINESS] = readiness.name
        }
    }

    suspend fun updateCoordinates(lat: Double, lon: Double) {
        context.dataStore.edit {
            it[Keys.CENTER_LAT] = lat
            it[Keys.CENTER_LON] = lon
        }
    }

    suspend fun rotateKeyPair(): OperatorConfig {
        val keys = CryptoEngine.generateEcKeyPair()
        context.dataStore.edit {
            it[Keys.PUB_KEY] = keys.publicKeyBase64
            it[Keys.PRIV_KEY] = keys.privateKeyBase64
        }
        return configFlow.first()
    }
}
