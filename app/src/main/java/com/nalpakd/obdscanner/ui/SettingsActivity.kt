package com.nalpakd.obdscanner.ui

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nalpakd.obdscanner.core.ClaudePrompt
import com.nalpakd.obdscanner.data.ClaudeClient
import com.nalpakd.obdscanner.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val col = Ui.scrollScreen(this)

        col.addView(Ui.title(this, "Claude AI"))
        col.addView(Ui.text(this, "Create an API key at console.anthropic.com → API keys. It is stored only on this phone and sent only to api.anthropic.com.", 13f), Ui.matchWidth(this, 4))

        val keyLayout = TextInputLayout(this).apply {
            hint = "Anthropic API key (sk-ant-…)"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val keyField = TextInputEditText(keyLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        keyLayout.addView(keyField)
        col.addView(keyLayout, Ui.matchWidth(this, 10))

        col.addView(Ui.text(this, "Model", 14f, true), Ui.matchWidth(this, 12))
        val modelIds = ClaudePrompt.MODELS.map { it.first }.toMutableList()
        val modelLabels = ClaudePrompt.MODELS.map { it.second }.toMutableList()
        if (prefs.model !in modelIds) { modelIds.add(prefs.model); modelLabels.add(prefs.model) }
        val modelSpinner = Spinner(this)
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelLabels)
        modelSpinner.setSelection(modelIds.indexOf(prefs.model).coerceAtLeast(0))
        col.addView(modelSpinner, Ui.matchWidth(this, 4))

        val customLayout = TextInputLayout(this).apply { hint = "Custom model ID (optional, overrides the list)" }
        val customField = TextInputEditText(customLayout.context).apply { inputType = InputType.TYPE_CLASS_TEXT }
        customLayout.addView(customField)
        col.addView(customLayout, Ui.matchWidth(this, 6))

        col.addView(Ui.title(this, "Scanning").apply { setPadding(0, Ui.dp(this@SettingsActivity, 18), 0, 0) })
        val units = MaterialSwitch(this).apply { text = "US units (°F, mph, psi)"; isChecked = prefs.imperial }
        col.addView(units, Ui.matchWidth(this, 6))

        val passLayout = TextInputLayout(this).apply { hint = "Key-sensor sample passes during full scan (1-30)" }
        val passField = TextInputEditText(passLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.samplePasses.toString())
        }
        passLayout.addView(passField)
        col.addView(passLayout, Ui.matchWidth(this, 8))

        fun save(): String {
            prefs.apiKey = keyField.text?.toString() ?: ""
            val custom = customField.text?.toString()?.trim().orEmpty()
            prefs.model = if (custom.isNotEmpty()) custom else modelIds[modelSpinner.selectedItemPosition.coerceAtLeast(0)]
            prefs.imperial = units.isChecked
            prefs.samplePasses = passField.text?.toString()?.toIntOrNull() ?: 5
            return prefs.model
        }

        col.addView(Ui.button(this, "Save") {
            save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish()
        }, Ui.matchWidth(this, 18))

        col.addView(Ui.button(this, "Save & test API key", outlined = true) {
            val model = save()
            val p = Ui.progress(this, "Testing key", null)
            lifecycleScope.launch {
                val msg = try { withContext(Dispatchers.IO) { ClaudeClient.testKey(prefs.apiKey, model) } }
                catch (e: Exception) { e.message ?: e.toString() }
                p.dismiss()
                Ui.alert(this@SettingsActivity, "API key test", msg)
            }
        }, Ui.matchWidth(this, 6))
    }
}
