package com.example.data.export

import com.example.data.local.AccountRole
import com.example.data.local.DetectedNodeEntity
import com.example.data.local.NodeType
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportFormat(val label: String, val mimeType: String, val extension: String) {
    CSV("CSV Spreadsheet", "text/csv", "csv"),
    JSON("JSON Bundle", "application/json", "json")
}

enum class ExportTimeRange(val label: String, val durationMs: Long?) {
    LAST_1H("Last 1 Hour", 60 * 60 * 1000L),
    LAST_24H("Last 24 Hours", 24 * 60 * 60 * 1000L),
    LAST_7D("Last 7 Days", 7 * 24 * 60 * 60 * 1000L),
    ALL_TIME("All Recorded Data", null)
}

enum class ExportCategory(val label: String) {
    ALL("All Targets"),
    RF_SCANS("Devices (BLE / Wi-Fi / NFC)"),
    HOTSPOTS("Public Wi-Fi Hotspots"),
    CAMERAS("Public Camera Feeds")
}

@JsonClass(generateAdapter = true)
data class ExportBundleJson(
    val exportedAtIso: String,
    val exportedByCallsign: String,
    val operatorRole: String,
    val timeRange: String,
    val categoryFilter: String,
    val recordCount: Int,
    val records: List<ExportRecordJson>
)

@JsonClass(generateAdapter = true)
data class ExportRecordJson(
    val id: String,
    val nodeType: String,
    val title: String,
    val subtitle: String,
    val macOrId: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val securityPosture: String,
    val latitude: Double,
    val longitude: Double,
    val feedOrReferenceUrl: String,
    val metadataSummary: String,
    val pinned: Boolean,
    val sourceCallsign: String,
    val capturedAtIso: String
)

object ScanExportManager {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val bundleAdapter = moshi.adapter(ExportBundleJson::class.java).indent("  ")

    fun filterNodes(
        nodes: List<DetectedNodeEntity>,
        timeRange: ExportTimeRange,
        category: ExportCategory,
        nowMs: Long = System.currentTimeMillis()
    ): List<DetectedNodeEntity> {
        val cutoff = timeRange.durationMs?.let { nowMs - it } ?: 0L
        return nodes.filter { node ->
            val inTime = node.lastSeenAt >= cutoff
            val inCat = when (category) {
                ExportCategory.ALL -> true
                ExportCategory.RF_SCANS ->
                    node.nodeType == NodeType.BLE_DEVICE ||
                        node.nodeType == NodeType.WIFI_AP ||
                        node.nodeType == NodeType.NFC_TAG
                ExportCategory.HOTSPOTS ->
                    node.nodeType == NodeType.PUBLIC_HOTSPOT ||
                        (node.nodeType == NodeType.WIFI_AP && node.securityPosture.isWarning)
                ExportCategory.CAMERAS ->
                    node.nodeType == NodeType.PUBLIC_CAMERA
            }
            inTime && inCat
        }
    }

    fun buildExportContent(
        nodes: List<DetectedNodeEntity>,
        format: ExportFormat,
        timeRange: ExportTimeRange,
        category: ExportCategory,
        operatorCallsign: String,
        role: AccountRole,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val filtered = filterNodes(nodes, timeRange, category, nowMs)
        return when (format) {
            ExportFormat.CSV -> buildCsv(filtered, role)
            ExportFormat.JSON -> buildJson(filtered, timeRange, category, operatorCallsign, role, nowMs)
        }
    }

    private fun buildCsv(nodes: List<DetectedNodeEntity>, role: AccountRole): String {
        val sb = StringBuilder()
        sb.appendLine(
            "id,node_type,title,subtitle,mac_or_id,rssi_dbm,frequency_mhz,security_posture,latitude,longitude,feed_or_reference_url,metadata_summary,pinned,source_callsign,captured_at_iso"
        )
        nodes.forEach { n ->
            val safeMeta = if (role == AccountRole.ADMIN) n.metadataSummary else redactIfStandard(n.metadataSummary)
            val row = listOf(
                n.id,
                n.nodeType.name,
                n.title,
                n.subtitle,
                n.macOrId,
                n.rssi.toString(),
                n.frequencyMhz.toString(),
                n.securityPosture.code,
                "%.6f".format(Locale.US, n.latitude),
                "%.6f".format(Locale.US, n.longitude),
                n.feedOrReferenceUrl,
                safeMeta,
                n.isPinned.toString(),
                n.sourcePeerCallsign,
                formatIso(n.lastSeenAt)
            ).joinToString(",") { csvEscape(it) }
            sb.appendLine(row)
        }
        return sb.toString()
    }

    private fun buildJson(
        nodes: List<DetectedNodeEntity>,
        timeRange: ExportTimeRange,
        category: ExportCategory,
        operatorCallsign: String,
        role: AccountRole,
        nowMs: Long
    ): String {
        val records = nodes.map { n ->
            ExportRecordJson(
                id = n.id,
                nodeType = n.nodeType.name,
                title = n.title,
                subtitle = n.subtitle,
                macOrId = n.macOrId,
                rssiDbm = n.rssi,
                frequencyMhz = n.frequencyMhz,
                securityPosture = n.securityPosture.code,
                latitude = n.latitude,
                longitude = n.longitude,
                feedOrReferenceUrl = n.feedOrReferenceUrl,
                metadataSummary = if (role == AccountRole.ADMIN) n.metadataSummary else redactIfStandard(n.metadataSummary),
                pinned = n.isPinned,
                sourceCallsign = n.sourcePeerCallsign,
                capturedAtIso = formatIso(n.lastSeenAt)
            )
        }
        val bundle = ExportBundleJson(
            exportedAtIso = formatIso(nowMs),
            exportedByCallsign = operatorCallsign,
            operatorRole = role.displayLabel,
            timeRange = timeRange.label,
            categoryFilter = category.label,
            recordCount = records.size,
            records = records
        )
        return bundleAdapter.toJson(bundle)
    }

    private fun redactIfStandard(meta: String): String {
        return meta.take(120)
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuotes) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun formatIso(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}
