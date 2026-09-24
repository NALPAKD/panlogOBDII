package com.nalpakd.obdscanner.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nalpakd.obdscanner.core.Pids
import com.nalpakd.obdscanner.core.Reading
import com.nalpakd.obdscanner.core.Units
import com.nalpakd.obdscanner.data.ObdSession
import com.nalpakd.obdscanner.data.Prefs
import com.nalpakd.obdscanner.data.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Real-time sensor dashboard with min/max tracking and CSV recording. */
class LiveDataActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var rowsBox: LinearLayout
    private lateinit var info: TextView
    private lateinit var recordBtn: MaterialButton
    private var supported: List<Int> = emptyList()
    private var selected: List<Int> = emptyList()
    private val valueViews = HashMap<Int, TextView>()
    private val rangeViews = HashMap<Int, TextView>()
    private val mins = HashMap<Int, Double>()
    private val maxs = HashMap<Int, Double>()
    private var pollJob: Job? = null
    @Volatile private var recorder: FileWriter? = null
    private var recordFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = Ui.scrollScreen(this)
        info = Ui.text(this, "Loading supported sensors…", 13f)
        col.addView(info)
        val bar = Ui.row(this)
        bar.addView(Ui.button(this, "Choose sensors", outlined = true) { chooseSensors() }, Ui.weighted(this))
        recordBtn = Ui.button(this, "Record CSV") { toggleRecording() }
        bar.addView(recordBtn, Ui.weighted(this))
        col.addView(bar, Ui.matchWidth(this, 8))
        col.addView(Ui.button(this, "Reset min/max", outlined = true) { mins.clear(); maxs.clear() }, Ui.matchWidth(this, 4))
        rowsBox = Ui.column(this)
        col.addView(rowsBox, Ui.matchWidth(this, 8))

        val scanner = ObdSession.scanner
        if (scanner == null) { info.text = "Not connected."; return }
        lifecycleScope.launch {
            supported = try {
                withContext(Dispatchers.IO) { scanner.supportedPids() }.filter { it != 0x01 && it != 0x41 && Pids.byPid[it] != null }
            } catch (e: Exception) { emptyList() }
            val saved = prefs.livePids.filter { it in supported }
            selected = saved.ifEmpty { Pids.DEFAULT_LIVE.filter { it in supported } }.ifEmpty { supported.take(12) }
            buildRows()
            startPolling()
        }
    }

    override fun onResume() {
        super.onResume()
        if (selected.isNotEmpty()) startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        if (isFinishing) stopRecording()
    }

    private fun buildRows() {
        rowsBox.removeAllViews(); valueViews.clear(); rangeViews.clear()
        info.text = "${ObdSession.deviceName} · ${supported.size} sensors supported · showing ${selected.size}"
        for (pid in selected) {
            val (card, inner) = Ui.card(this)
            val r = Ui.row(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.text(this, Pids.name(pid), 14f), LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = Ui.text(this, "—", 20f, true)
            r.addView(v)
            inner.addView(r)
            val range = Ui.text(this, "", 11f)
            inner.addView(range)
            valueViews[pid] = v; rangeViews[pid] = range
            rowsBox.addView(card, Ui.matchWidth(this, 6))
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val scanner = ObdSession.scanner ?: return
        val imperial = prefs.imperial
        pollJob = lifecycleScope.launch {
            while (isActive) {
                val pass = LinkedHashMap<Int, Reading?>()
                try {
                    withContext(Dispatchers.IO) { for (pid in selected) pass[pid] = scanner.readPid(pid) }
                } catch (e: Exception) {
                    info.text = "Connection lost: ${e.message}"
                    break
                }
                for ((pid, r) in pass) {
                    val tv = valueViews[pid] ?: continue
                    if (r == null) { tv.text = "n/a"; continue }
                    tv.text = r.display(imperial)
                    val dv = r.displayValue(imperial)
                    if (dv != null) {
                        mins[pid] = minOf(mins[pid] ?: dv, dv)
                        maxs[pid] = maxOf(maxs[pid] ?: dv, dv)
                        rangeViews[pid]?.text = "min ${Units.fmt(mins[pid]!!)}  ·  max ${Units.fmt(maxs[pid]!!)}"
                    }
                }
                recorder?.let { w ->
                    withContext(Dispatchers.IO) {
                        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                        w.write(t + "," + selected.joinToString(",") { pid ->
                            val r = pass[pid]
                            r?.text?.replace(",", ";") ?: r?.displayValue(imperial)?.let { Units.fmt(it) } ?: ""
                        } + "\n")
                    }
                }
                delay(if (ObdSession.isDemo) 400 else 50)
            }
        }
    }

    private fun chooseSensors() {
        if (supported.isEmpty()) return
        val names = supported.map { Pids.name(it) }.toTypedArray()
        val checked = BooleanArray(supported.size) { supported[it] in selected }
        MaterialAlertDialogBuilder(this).setTitle("Sensors to show")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                if (recorder != null) stopRecording()
                pollJob?.cancel()
                selected = supported.filterIndexed { i, _ -> checked[i] }
                prefs.livePids = selected
                buildRows()
                startPolling()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleRecording() {
        if (recorder != null) { stopRecording(); return }
        val store = ReportStore(this)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(store.dir, "live_$stamp.csv")
        val imperial = prefs.imperial
        val w = FileWriter(f)
        w.write("time," + selected.joinToString(",") { pid ->
            val def = Pids.byPid[pid]
            val unit = if (def == null || def.unit.isEmpty()) "" else " (${Units.convert(0.0, def.unit, imperial).second})"
            "\"${Pids.name(pid)}$unit\""
        } + "\n")
        recorder = w; recordFile = f
        recordBtn.text = "Stop recording"
    }

    private fun stopRecording() {
        val w = recorder ?: return
        recorder = null
        try { w.close() } catch (_: Exception) { }
        recordBtn.text = "Record CSV"
        recordFile?.let {
            ReportStore(this).exportToDownloads(it, "text/csv")
            Toast.makeText(this, "Saved ${it.name} to Downloads/OBD Reports", Toast.LENGTH_LONG).show()
        }
    }
}
