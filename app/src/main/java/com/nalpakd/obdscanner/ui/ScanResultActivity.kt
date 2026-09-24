package com.nalpakd.obdscanner.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nalpakd.obdscanner.core.Json
import com.nalpakd.obdscanner.core.Json.obj
import com.nalpakd.obdscanner.core.Json.str
import com.nalpakd.obdscanner.data.ClaudeClient
import com.nalpakd.obdscanner.data.Prefs
import com.nalpakd.obdscanner.data.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows a saved scan and lets the user add symptoms, send it to Claude, export or share it. */
class ScanResultActivity : AppCompatActivity() {

    companion object { const val EXTRA_STAMP = "stamp" }

    private lateinit var store: ReportStore
    private lateinit var stamp: String
    private lateinit var viewReportBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ReportStore(this)
        stamp = intent.getStringExtra(EXTRA_STAMP) ?: run { finish(); return }
        val jsonFile = store.scanJson(stamp)
        if (!jsonFile.exists()) { finish(); return }

        val col = Ui.scrollScreen(this)
        col.addView(Ui.title(this, "Scan saved"))
        col.addView(Ui.text(this, "Saved to your phone in Downloads › OBD Reports as scan_$stamp.json and .txt", 13f), Ui.matchWidth(this, 4))

        // Symptoms help Claude a lot
        val til = TextInputLayout(this).apply { hint = "Symptoms you've noticed (optional)" }
        val symptoms = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setText(try { Json.parse(jsonFile.readText()).obj()["user_reported_symptoms"].str() } catch (e: Exception) { "" })
        }
        til.addView(symptoms)
        col.addView(til, Ui.matchWidth(this, 12))
        col.addView(Ui.text(this, "e.g. \"rough idle when cold\", \"hesitates going uphill\", \"smells like gas\"", 12f))

        col.addView(Ui.button(this, "Analyze with Claude → easy-to-read report") {
            analyze(symptoms.text?.toString() ?: "")
        }, Ui.matchWidth(this, 12))

        viewReportBtn = Ui.button(this, "View AI report", outlined = true) {
            startActivity(Intent(this, ReportViewActivity::class.java).putExtra(EXTRA_STAMP, stamp))
        }
        col.addView(viewReportBtn, Ui.matchWidth(this, 6))

        val r = Ui.row(this)
        r.addView(Ui.button(this, "Save copy", outlined = true) {
            store.exportToDownloads(store.scanJson(stamp), "application/json")
            store.exportToDownloads(store.scanTxt(stamp), "text/plain")
            Toast.makeText(this, "Saved to Downloads/OBD Reports", Toast.LENGTH_SHORT).show()
        }, Ui.weighted(this))
        r.addView(Ui.button(this, "Share", outlined = true) {
            startActivity(store.shareIntent(listOf(store.scanTxt(stamp), store.scanJson(stamp)), "text/plain"))
        }, Ui.weighted(this))
        col.addView(r, Ui.matchWidth(this, 6))

        val (card, inner) = Ui.card(this)
        inner.addView(Ui.mono(this, if (store.scanTxt(stamp).exists()) store.scanTxt(stamp).readText() else jsonFile.readText()))
        col.addView(card, Ui.matchWidth(this, 14))
    }

    override fun onResume() {
        super.onResume()
        if (::viewReportBtn.isInitialized) viewReportBtn.isEnabled = store.reportHtml(stamp).exists()
    }

    private fun analyze(symptoms: String) {
        val prefs = Prefs(this)
        if (prefs.apiKey.isBlank()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Claude API key needed")
                .setMessage("Add your Anthropic API key in Settings (get one at console.anthropic.com). It's stored only on this phone.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val p = Ui.progress(this, "Asking Claude", null)
        p.update("Claude is reviewing your codes and sensor data… this usually takes 30–90 seconds.", -1)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val scanJson = store.updateSymptoms(stamp, symptoms)
                    val res = ClaudeClient.analyze(prefs.apiKey, prefs.model, scanJson)
                    store.saveAnalysis(stamp, res.analysisJson, res.html)
                }
                p.dismiss()
                viewReportBtn.isEnabled = true
                startActivity(Intent(this@ScanResultActivity, ReportViewActivity::class.java).putExtra(EXTRA_STAMP, stamp))
            } catch (e: Exception) {
                p.dismiss()
                Ui.alert(this@ScanResultActivity, "Analysis failed",
                    (e.message ?: e.toString()) + "\n\nYour scan is still saved; you can retry from Saved reports.")
            }
        }
    }
}
