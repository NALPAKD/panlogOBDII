package com.nalpakd.obdscanner.data

import android.content.Context
import com.nalpakd.obdscanner.core.ClaudePrompt
import com.nalpakd.obdscanner.core.VehicleProfile
import com.nalpakd.obdscanner.core.VehicleProfiles

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("obd_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = p.getString("model", ClaudePrompt.DEFAULT_MODEL) ?: ClaudePrompt.DEFAULT_MODEL
        set(v) = p.edit().putString("model", v.trim()).apply()

    var profileId: String
        get() = p.getString("profile", VehicleProfiles.GENERIC.id) ?: VehicleProfiles.GENERIC.id
        set(v) = p.edit().putString("profile", v).apply()

    val profile: VehicleProfile get() = VehicleProfiles.byId(profileId)

    var imperial: Boolean
        get() = p.getBoolean("imperial", true)
        set(v) = p.edit().putBoolean("imperial", v).apply()

    var samplePasses: Int
        get() = p.getInt("sample_passes", 5)
        set(v) = p.edit().putInt("sample_passes", v.coerceIn(1, 30)).apply()

    var lastDevice: String
        get() = p.getString("last_device", "") ?: ""
        set(v) = p.edit().putString("last_device", v).apply()

    var livePids: List<Int>
        get() = (p.getString("live_pids", "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = p.edit().putString("live_pids", v.joinToString(",")).apply()
}
