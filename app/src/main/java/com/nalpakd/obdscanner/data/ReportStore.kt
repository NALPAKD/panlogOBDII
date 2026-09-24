package com.nalpakd.obdscanner.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.nalpakd.obdscanner.core.Json
import com.nalpakd.obdscanner.core.Json.list
import com.nalpakd.obdscanner.core.Json.obj
import com.nalpakd.obdscanner.core.Json.str
import com.nalpakd.obdscanner.core.ScanReport
import com.nalpakd.obdscanner.core.TextReport
import java.io.File

/**
 * Files per scan (stamp = yyyyMMdd_HHmmss):
 *   scan_<stamp>.json      full machine-readable scan (what gets sent to Claude)
 *   scan_<stamp>.txt       human-readable scan
 *   analysis_<stamp>.json  Claude's structured answer
 *   report_<stamp>.html    friendly AI report
 * Master copies live in the app's folder; copies are exported to Downloads/OBD Reports.
 */
class ReportStore(private val ctx: Context) {

    val dir: File = (ctx.getExternalFilesDir("reports") ?: File(ctx.filesDir, "reports")).apply { mkdirs() }

    fun scanJson(stamp: String) = File(dir, "scan_$stamp.json")
    fun scanTxt(stamp: String) = File(dir, "scan_$stamp.txt")
    fun analysisJson(stamp: String) = File(dir, "analysis_$stamp.json")
    fun reportHtml(stamp: String) = File(dir, "report_$stamp.html")

    /** Saves the scan locally and exports JSON + TXT to Downloads. Returns the stamp. */
    fun saveScan(r: ScanReport, exportToDownloads: Boolean = true): String {
        val stamp = r.fileStamp()
        scanJson(stamp).writeText(r.toJson())
        scanTxt(stamp).writeText(TextReport.render(r))
        if (exportToDownloads) {
            exportToDownloads(scanJson(stamp), "application/json")
            exportToDownloads(scanTxt(stamp), "text/plain")
        }
        return stamp
    }

    fun saveAnalysis(stamp: String, analysisJsonText: String, html: String) {
        analysisJson(stamp).writeText(analysisJsonText)
        reportHtml(stamp).writeText(html)
        exportToDownloads(reportHtml(stamp), "text/html")
    }

    /** Updates the "user_reported_symptoms" field in a saved scan. */
    fun updateSymptoms(stamp: String, symptoms: String): String {
        val f = scanJson(stamp)
        val m = LinkedHashMap(Json.parse(f.readText()).obj())
        m["user_reported_symptoms"] = symptoms.ifBlank { null }
        val text = Json.write(m)
        f.writeText(text)
        return text
    }

    data class Entry(
        val stamp: String, val date: String, val vehicle: String, val vin: String,
        val stored: Int, val pending: Int, val hasReport: Boolean, val milOn: Boolean?
    )

    fun list(): List<Entry> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.startsWith("scan_") && it.name.endsWith(".json") }
        .sortedByDescending { it.name }
        .mapNotNull { f ->
            try {
                val m = Json.parse(f.readText()).obj()
                val stamp = f.name.removePrefix("scan_").removeSuffix(".json")
                val codes = m["trouble_codes"].obj()
                Entry(
                    stamp, m["scan_time"].str(), m["vehicle_profile"].str(), m["vehicle"].obj()["vin"].str(),
                    codes["stored_confirmed"].list().size, codes["pending"].list().size,
                    reportHtml(stamp).exists(),
                    m["readiness_since_codes_cleared"].obj()["check_engine_light_on"] as? Boolean
                )
            } catch (e: Exception) { null }
        }

    fun delete(stamp: String) {
        listOf(scanJson(stamp), scanTxt(stamp), analysisJson(stamp), reportHtml(stamp)).forEach { it.delete() }
    }

    /** Copies a file into the public Downloads/OBD Reports folder (visible in the Files app). */
    fun exportToDownloads(file: File, mime: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OBD Reports")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri
        } catch (e: Exception) {
            null
        }
    }

    fun shareIntent(files: List<File>, mime: String): Intent {
        val uris = ArrayList(files.filter { it.exists() }.map {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", it)
        })
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.type = mime
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(i, "Share report")
    }
}
