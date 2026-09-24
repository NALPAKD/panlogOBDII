package com.nalpakd.obdscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nalpakd.obdscanner.core.Dtc
import com.nalpakd.obdscanner.core.ScanCancelledException
import com.nalpakd.obdscanner.core.VehicleProfiles
import com.nalpakd.obdscanner.data.ObdSession
import com.nalpakd.obdscanner.data.Prefs
import com.nalpakd.obdscanner.data.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var connectBtn: MaterialButton
    private val actionButtons = mutableListOf<MaterialButton>()
    @Volatile private var cancelScan = false

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickDevice() else Ui.alert(this, "Bluetooth permission needed",
            "Allow \"Nearby devices\" so the app can talk to your Panlong adapter.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Panlong OBD Scanner"
        val col = Ui.scrollScreen(this)

        // --- connection card
        val (card, inner) = Ui.card(this)
        inner.addView(Ui.title(this, "Adapter"))
        status = Ui.text(this, "")
        inner.addView(status, Ui.matchWidth(this, 6))
        val r = Ui.row(this)
        connectBtn = Ui.button(this, "Connect Bluetooth") { onConnectClicked() }
        r.addView(connectBtn, Ui.weighted(this))
        r.addView(Ui.button(this, "Demo mode", outlined = true) { connectDemo() }, Ui.weighted(this))
        inner.addView(r, Ui.matchWidth(this, 10))
        col.addView(card, Ui.matchWidth(this, 0))

        // --- vehicle profile
        val (card2, inner2) = Ui.card(this)
        inner2.addView(Ui.title(this, "Vehicle"))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VehicleProfiles.ALL.map { it.displayName })
        spinner.setSelection(VehicleProfiles.ALL.indexOfFirst { it.id == prefs.profileId }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.profileId = VehicleProfiles.ALL[pos].id
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        inner2.addView(spinner, Ui.matchWidth(this, 6))
        inner2.addView(Ui.text(this, "Pick your vehicle so Claude can use model-specific known issues. \"Generic\" works for any 1996+ car.", 13f), Ui.matchWidth(this, 4))
        col.addView(card2, Ui.matchWidth(this, 12))

        // --- actions
        col.addView(Ui.title(this, "Scan").apply { setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0) })
        fun action(label: String, outlined: Boolean = false, f: () -> Unit) {
            val b = Ui.button(this, label, outlined) { f() }
            actionButtons.add(b)
            col.addView(b, Ui.matchWidth(this, 6))
        }
        action("Full scan + save report") { runFullScan() }
        action("Live data dashboard", outlined = true) { startActivity(Intent(this, LiveDataActivity::class.java)) }
        action("Quick read trouble codes", outlined = true) { quickCodes() }
        action("Clear trouble codes / check engine light", outlined = true) { confirmClear() }

        col.addView(Ui.title(this, "Reports & settings").apply { setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0) })
        col.addView(Ui.button(this, "Saved reports", outlined = true) { startActivity(Intent(this, ReportsActivity::class.java)) }, Ui.matchWidth(this, 6))
        col.addView(Ui.button(this, "Settings (Claude API key, units)", outlined = true) { startActivity(Intent(this, SettingsActivity::class.java)) }, Ui.matchWidth(this, 6))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val info = ObdSession.adapterInfo
        if (ObdSession.connected && info != null) {
            status.text = "Connected: ${ObdSession.deviceName}\nProtocol: ${info.protocol}\nAdapter: ${info.elmVersion}   Battery: ${info.batteryVoltage}"
            connectBtn.text = "Disconnect"
        } else {
            status.text = "Not connected.\n1. Plug the Panlong into the OBD port (under the dash).\n2. Turn the ignition ON (engine running is best).\n3. Pair it in Android Bluetooth settings (PIN 1234 or 0000).\n4. Tap Connect."
            connectBtn.text = "Connect Bluetooth"
        }
        actionButtons.forEach { it.isEnabled = ObdSession.connected }
    }

    // ------------------------------------------------------------ connect

    private fun onConnectClicked() {
        if (ObdSession.connected) {
            lifecycleScope.launch(Dispatchers.IO) { ObdSession.disconnect(); withContext(Dispatchers.Main) { refreshStatus() } }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        pickDevice()
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { Ui.alert(this, "No Bluetooth", "This phone has no Bluetooth adapter."); return }
        if (!adapter.isEnabled) {
            MaterialAlertDialogBuilder(this).setTitle("Bluetooth is off")
                .setMessage("Turn on Bluetooth, then tap Connect again.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val devices: List<BluetoothDevice> = adapter.bondedDevices.orEmpty().sortedByDescending { d ->
            val n = (d.name ?: "").uppercase()
            when {
                d.address == prefs.lastDevice -> 3
                listOf("OBD", "PANLONG", "ELM", "V-LINK", "VLINK").any { n.contains(it) } -> 2
                else -> 0
            }
        }
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("No paired devices")
                .setMessage("Pair your Panlong adapter first in Android Bluetooth settings (it usually shows as \"OBDII\"; PIN 1234 or 0000).")
                .setPositiveButton("Open Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Choose your OBD adapter")
            .setItems(labels) { _, which -> connectTo(devices[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val name = device.name ?: device.address
        val p = Ui.progress(this, "Connecting", null)
        p.update("Connecting to $name and detecting your car's protocol… (up to 30 seconds)", -1)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ObdSession.connectBluetooth(adapter, device, name) }
                prefs.lastDevice = device.address
                p.dismiss()
            } catch (e: Exception) {
                p.dismiss()
                Ui.alert(this@MainActivity, "Connection failed", (e.message ?: e.toString()) +
                    "\n\nCheck: ignition ON, adapter light on, adapter paired, and no other app is connected to it.")
            }
            refreshStatus()
        }
    }

    private fun connectDemo() {
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { ObdSession.connectDemo() } }
            catch (e: Exception) { Ui.alert(this@MainActivity, "Demo failed", e.toString()) }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------ scan

    private fun runFullScan() {
        val scanner = ObdSession.scanner ?: return
        cancelScan = false
        val p = Ui.progress(this, "Full vehicle scan", onCancel = { cancelScan = true })
        val profile = prefs.profile
        val imperial = prefs.imperial
        val passes = prefs.samplePasses
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    scanner.fullScan(profile, imperial, passes,
                        progress = { msg, pct -> runOnUiThread { p.update(msg, pct) } },
                        cancelled = { cancelScan })
                }
                val stamp = withContext(Dispatchers.IO) { ReportStore(this@MainActivity).saveScan(report) }
                p.dismiss()
                startActivity(Intent(this@MainActivity, ScanResultActivity::class.java).putExtra(ScanResultActivity.EXTRA_STAMP, stamp))
            } catch (e: ScanCancelledException) {
                p.dismiss()
            } catch (e: Exception) {
                p.dismiss()
                Ui.alert(this@MainActivity, "Scan failed", e.message ?: e.toString())
            }
            refreshStatus()
        }
    }

    private fun quickCodes() {
        val scanner = ObdSession.scanner ?: return
        val p = Ui.progress(this, "Reading codes", null)
        lifecycleScope.launch {
            try {
                val all: List<Dtc> = withContext(Dispatchers.IO) {
                    scanner.readDtcs("03") + scanner.readDtcs("07") + scanner.readDtcs("0A")
                }
                val mil = withContext(Dispatchers.IO) { try { scanner.readMonitorStatus()?.milOn } catch (e: Exception) { null } }
                p.dismiss()
                val body = buildString {
                    appendLine("Check engine light: ${when (mil) { true -> "ON"; false -> "OFF"; else -> "unknown" }}")
                    appendLine()
                    if (all.isEmpty()) append("No trouble codes found.")
                    all.forEach { appendLine("${it.code} (${it.kind})\n   ${it.description}\n") }
                }
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Trouble codes").setMessage(body)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Run full scan") { _, _ -> runFullScan() }.show()
            } catch (e: Exception) {
                p.dismiss()
                Ui.alert(this@MainActivity, "Read failed", e.message ?: e.toString())
                refreshStatus()
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this).setTitle("Clear trouble codes?")
            .setMessage("This turns off the check engine light and erases stored codes and freeze-frame data. " +
                "It also resets all emissions readiness monitors, so the car will NOT pass a smog/emissions test until it has been driven for a few days.\n\n" +
                "Tip: run a Full Scan first so you keep a record.\n\nIgnition must be ON with the engine OFF.")
            .setPositiveButton("Clear codes") { _, _ -> doClear() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doClear() {
        val scanner = ObdSession.scanner ?: return
        lifecycleScope.launch {
            val ok = try { withContext(Dispatchers.IO) { scanner.clearDtcs() } } catch (e: Exception) { false }
            Ui.alert(this@MainActivity, if (ok) "Codes cleared" else "Not confirmed",
                if (ok) "The ECU confirmed the codes were cleared. If a problem is still present the light will come back."
                else "The vehicle did not confirm. Make sure the ignition is ON and the engine is OFF, then try again.")
            refreshStatus()
        }
    }
}
