package com.nalpakd.obdscanner.core

import kotlin.math.roundToInt

/** A decoded reading, always stored in metric; [display] converts for the UI/report. */
data class Reading(
    val pid: Int,
    val name: String,
    val value: Double?,      // numeric value in metric unit (null for text values)
    val unit: String,        // metric unit
    val text: String? = null // for enum/text PIDs
) {
    fun display(imperial: Boolean): String {
        if (text != null) return text
        val v = value ?: return "n/a"
        val (cv, cu) = Units.convert(v, unit, imperial)
        return "${Units.fmt(cv)} $cu".trim()
    }
    fun displayValue(imperial: Boolean): Double? = value?.let { Units.convert(it, unit, imperial).first }
    fun displayUnit(imperial: Boolean): String = if (text != null) "" else Units.convert(0.0, unit, imperial).second
}

object Units {
    fun convert(v: Double, unit: String, imperial: Boolean): Pair<Double, String> {
        if (!imperial) return v to unit
        return when (unit) {
            "°C" -> (v * 9.0 / 5.0 + 32.0) to "°F"
            "km/h" -> (v * 0.621371) to "mph"
            "km" -> (v * 0.621371) to "mi"
            "kPa" -> (v * 0.145038) to "psi"
            "L/h" -> (v * 0.264172) to "gal/h"
            "Pa" -> (v * 0.0040146) to "inH2O"
            else -> v to unit
        }
    }

    fun fmt(v: Double): String {
        val a = kotlin.math.abs(v)
        if (v == Math.rint(v) && a < 1e9) return v.toLong().toString()
        return when {
            a >= 100 -> v.roundToInt().toString()
            a >= 10 -> String.format(java.util.Locale.US, "%.1f", v)
            else -> String.format(java.util.Locale.US, "%.2f", v)
        }
    }
}

class PidDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decode: (IntArray) -> Double?,
    val textDecode: ((IntArray) -> String)? = null
)

object Pids {
    private fun ab(d: IntArray) = d[0] * 256.0 + d[1]
    private fun pct(d: IntArray) = d[0] * 100.0 / 255.0
    private fun trim(x: Int) = (x - 128) * 100.0 / 128.0

    private val list = mutableListOf<PidDef>()
    private fun def(pid: Int, name: String, unit: String, bytes: Int, f: (IntArray) -> Double?) {
        list.add(PidDef(pid, name, unit, bytes, f))
    }
    private fun tdef(pid: Int, name: String, bytes: Int, f: (IntArray) -> String) {
        list.add(PidDef(pid, name, "", bytes, { null }, f))
    }

    val FUEL_SYSTEM_STATUS = mapOf(
        0 to "Not active", 1 to "Open loop (engine cold)", 2 to "Closed loop (normal)",
        4 to "Open loop (engine load / fuel cut)", 8 to "Open loop (system fault)",
        16 to "Closed loop (feedback fault)"
    )
    val OBD_STANDARDS = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD and OBD-II", 4 to "OBD-I",
        5 to "Not OBD compliant", 6 to "EOBD (Europe)", 7 to "EOBD and OBD-II", 8 to "EOBD and OBD",
        9 to "EOBD, OBD and OBD-II", 10 to "JOBD (Japan)", 11 to "JOBD and OBD-II", 12 to "JOBD and EOBD",
        13 to "JOBD, EOBD and OBD-II"
    )
    val FUEL_TYPES = mapOf(
        0 to "Not available", 1 to "Gasoline", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel",
        5 to "LPG", 6 to "CNG", 7 to "Propane", 8 to "Electric", 9 to "Bifuel gasoline",
        17 to "Hybrid gasoline", 18 to "Hybrid ethanol", 19 to "Hybrid diesel", 20 to "Hybrid electric",
        21 to "Hybrid mixed fuel", 22 to "Hybrid regenerative"
    )
    val SECONDARY_AIR = mapOf(1 to "Upstream", 2 to "Downstream of catalyst", 4 to "From outside / off", 8 to "Pump commanded on for diagnostics")

    init {
        tdef(0x03, "Fuel system status", 2) { d ->
            val s1 = FUEL_SYSTEM_STATUS[d[0]] ?: "Code ${d[0]}"
            if (d.size > 1 && d[1] != 0) "$s1 / Bank 2: ${FUEL_SYSTEM_STATUS[d[1]] ?: d[1]}" else s1
        }
        def(0x04, "Calculated engine load", "%", 1) { pct(it) }
        def(0x05, "Engine coolant temperature", "°C", 1) { it[0] - 40.0 }
        def(0x06, "Short term fuel trim - Bank 1", "%", 1) { trim(it[0]) }
        def(0x07, "Long term fuel trim - Bank 1", "%", 1) { trim(it[0]) }
        def(0x08, "Short term fuel trim - Bank 2", "%", 1) { trim(it[0]) }
        def(0x09, "Long term fuel trim - Bank 2", "%", 1) { trim(it[0]) }
        def(0x0A, "Fuel pressure (gauge)", "kPa", 1) { it[0] * 3.0 }
        def(0x0B, "Intake manifold pressure", "kPa", 1) { it[0].toDouble() }
        def(0x0C, "Engine RPM", "rpm", 2) { ab(it) / 4.0 }
        def(0x0D, "Vehicle speed", "km/h", 1) { it[0].toDouble() }
        def(0x0E, "Timing advance", "° BTDC", 1) { it[0] / 2.0 - 64.0 }
        def(0x0F, "Intake air temperature", "°C", 1) { it[0] - 40.0 }
        def(0x10, "Mass air flow rate", "g/s", 2) { ab(it) / 100.0 }
        def(0x11, "Throttle position", "%", 1) { pct(it) }
        tdef(0x12, "Secondary air status", 1) { d -> SECONDARY_AIR[d[0]] ?: "Code ${d[0]}" }
        tdef(0x13, "O2 sensors present", 1) { d -> o2Present(d[0]) }
        for (i in 0 until 8) {
            val bank = if (i < 4) 1 else 2
            val sensor = i % 4 + 1
            def(0x14 + i, "O2 sensor voltage B${bank}S$sensor", "V", 2) { it[0] / 200.0 }
        }
        tdef(0x1C, "OBD standard", 1) { d -> OBD_STANDARDS[d[0]] ?: "Code ${d[0]}" }
        def(0x1F, "Run time since engine start", "s", 2) { ab(it) }
        def(0x21, "Distance traveled with check engine light on", "km", 2) { ab(it) }
        def(0x22, "Fuel rail pressure (rel. vacuum)", "kPa", 2) { ab(it) * 0.079 }
        def(0x23, "Fuel rail gauge pressure", "kPa", 2) { ab(it) * 10.0 }
        for (i in 0 until 8) {
            def(0x24 + i, "O2 sensor ${i + 1} equivalence ratio (lambda)", "λ", 4) { ab(it) * 2.0 / 65536.0 }
        }
        def(0x2C, "Commanded EGR", "%", 1) { pct(it) }
        def(0x2D, "EGR error", "%", 1) { trim(it[0]) }
        def(0x2E, "Commanded evaporative purge", "%", 1) { pct(it) }
        def(0x2F, "Fuel tank level", "%", 1) { pct(it) }
        def(0x30, "Warm-ups since codes cleared", "", 1) { it[0].toDouble() }
        def(0x31, "Distance since codes cleared", "km", 2) { ab(it) }
        def(0x32, "Evap system vapor pressure", "Pa", 2) { d ->
            val raw = (d[0] shl 8) or d[1]
            (if (raw >= 0x8000) raw - 0x10000 else raw) / 4.0
        }
        def(0x33, "Barometric pressure", "kPa", 1) { it[0].toDouble() }
        for (i in 0 until 8) {
            def(0x34 + i, "O2 sensor ${i + 1} wide-range lambda", "λ", 4) { ab(it) * 2.0 / 65536.0 }
        }
        def(0x3C, "Catalyst temperature B1S1", "°C", 2) { ab(it) / 10.0 - 40.0 }
        def(0x3D, "Catalyst temperature B2S1", "°C", 2) { ab(it) / 10.0 - 40.0 }
        def(0x3E, "Catalyst temperature B1S2", "°C", 2) { ab(it) / 10.0 - 40.0 }
        def(0x3F, "Catalyst temperature B2S2", "°C", 2) { ab(it) / 10.0 - 40.0 }
        def(0x42, "Control module voltage", "V", 2) { ab(it) / 1000.0 }
        def(0x43, "Absolute load value", "%", 2) { ab(it) * 100.0 / 255.0 }
        def(0x44, "Commanded air-fuel equivalence ratio", "λ", 2) { ab(it) * 2.0 / 65536.0 }
        def(0x45, "Relative throttle position", "%", 1) { pct(it) }
        def(0x46, "Ambient air temperature", "°C", 1) { it[0] - 40.0 }
        def(0x47, "Absolute throttle position B", "%", 1) { pct(it) }
        def(0x48, "Absolute throttle position C", "%", 1) { pct(it) }
        def(0x49, "Accelerator pedal position D", "%", 1) { pct(it) }
        def(0x4A, "Accelerator pedal position E", "%", 1) { pct(it) }
        def(0x4B, "Accelerator pedal position F", "%", 1) { pct(it) }
        def(0x4C, "Commanded throttle actuator", "%", 1) { pct(it) }
        def(0x4D, "Time run with check engine light on", "min", 2) { ab(it) }
        def(0x4E, "Time since codes cleared", "min", 2) { ab(it) }
        tdef(0x51, "Fuel type", 1) { d -> FUEL_TYPES[d[0]] ?: "Code ${d[0]}" }
        def(0x52, "Ethanol fuel %", "%", 1) { pct(it) }
        def(0x59, "Fuel rail absolute pressure", "kPa", 2) { ab(it) * 10.0 }
        def(0x5A, "Relative accelerator pedal position", "%", 1) { pct(it) }
        def(0x5B, "Hybrid battery pack remaining life", "%", 1) { pct(it) }
        def(0x5C, "Engine oil temperature", "°C", 1) { it[0] - 40.0 }
        def(0x5D, "Fuel injection timing", "°", 2) { ab(it) / 128.0 - 210.0 }
        def(0x5E, "Engine fuel rate", "L/h", 2) { ab(it) / 20.0 }
        def(0x61, "Driver's demand engine torque", "%", 1) { it[0] - 125.0 }
        def(0x62, "Actual engine torque", "%", 1) { it[0] - 125.0 }
        def(0x63, "Engine reference torque", "Nm", 2) { ab(it) }
        def(0xA6, "Odometer", "km", 4) { d -> ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0 }
    }

    val byPid: Map<Int, PidDef> = list.associateBy { it.pid }

    /** PIDs we know how to show as live data, in a sensible order. */
    val decodable: List<Int> get() = list.map { it.pid }

    /** Key PIDs sampled repeatedly during a full scan (what a mechanic looks at first). */
    val KEY_SAMPLE_PIDS = listOf(0x0C, 0x05, 0x04, 0x06, 0x07, 0x08, 0x09, 0x10, 0x0B, 0x0F, 0x11, 0x0E, 0x14, 0x15, 0x18, 0x19, 0x42, 0x44, 0x24, 0x34)

    /** Default live-data dashboard. */
    val DEFAULT_LIVE = listOf(0x0C, 0x0D, 0x05, 0x04, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x0F, 0x42, 0x2F, 0x5C)

    fun decode(pid: Int, data: IntArray): Reading? {
        val def = byPid[pid] ?: return null
        if (data.size < def.bytes) return null
        return try {
            val td = def.textDecode
            if (td != null) Reading(pid, def.name, null, "", td(data))
            else Reading(pid, def.name, def.decode(data), def.unit)
        } catch (e: Exception) {
            null
        }
    }

    fun o2Present(b: Int): String {
        val names = mutableListOf<String>()
        for (i in 0 until 8) if (b and (1 shl i) != 0) names.add("B${if (i < 4) 1 else 2}S${i % 4 + 1}")
        return if (names.isEmpty()) "None reported" else names.joinToString(", ")
    }

    fun name(pid: Int): String = byPid[pid]?.name ?: "PID 0x${ElmParser.hex(pid)}"
}
