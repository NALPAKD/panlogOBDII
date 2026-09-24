package com.nalpakd.obdscanner.core

/** Plain-text version of a scan: shown on screen and saved alongside the JSON. */
object TextReport {

    fun render(s: ScanReport): String = buildString {
        val imp = s.imperial
        appendLine("PANLONG OBD-II FULL SCAN REPORT")
        appendLine("=".repeat(40))
        appendLine("Date:       ${s.dateString()}")
        appendLine("Vehicle:    ${s.profile.displayName}")
        appendLine("VIN:        ${s.vehicle.vin ?: "not reported"}")
        appendLine("Protocol:   ${s.adapter.protocol} (${s.adapter.protocolNumber})")
        appendLine("Adapter:    ${s.adapter.elmVersion}")
        appendLine("Battery:    ${s.adapter.batteryVoltage}")
        if (s.symptoms.isNotBlank()) appendLine("Symptoms:   ${s.symptoms}")
        appendLine()

        val mil = s.monitorsSinceClear?.milOn
        appendLine("CHECK ENGINE LIGHT: ${when (mil) { true -> "ON"; false -> "OFF"; null -> "unknown" }}")
        appendLine()

        fun codes(title: String, list: List<Dtc>) {
            appendLine("$title (${list.size})")
            if (list.isEmpty()) appendLine("  none")
            list.forEach { appendLine("  ${it.code}  ${it.description}") }
            appendLine()
        }
        codes("STORED CODES", s.storedDtcs)
        codes("PENDING CODES", s.pendingDtcs)
        codes("PERMANENT CODES", s.permanentDtcs)

        s.monitorsSinceClear?.let { m ->
            appendLine("READINESS MONITORS (since codes cleared)")
            m.monitors.forEach { (k, v) -> appendLine("  ${if (v == "Complete") "[OK]" else "[--]"} $k: $v") }
            appendLine()
        }

        if (s.freezeFrameDtc != null) {
            appendLine("FREEZE FRAME (captured when ${s.freezeFrameDtc} set)")
            s.freezeFrame.forEach { appendLine("  ${it.name}: ${it.display(imp)}") }
            appendLine()
        }

        if (s.samples.isNotEmpty()) {
            appendLine("KEY SENSOR SAMPLES (min / avg / max)")
            s.samples.forEach {
                val (mn, u) = Units.convert(it.min, it.unit, imp)
                val av = Units.convert(it.avg, it.unit, imp).first
                val mx = Units.convert(it.max, it.unit, imp).first
                appendLine("  ${it.name}: ${Units.fmt(mn)} / ${Units.fmt(av)} / ${Units.fmt(mx)} $u")
            }
            appendLine()
        }

        appendLine("LIVE SNAPSHOT (${s.snapshot.size} sensors)")
        s.snapshot.forEach { appendLine("  ${it.name}: ${it.display(imp)}") }
        appendLine()

        if (s.mode6.isNotEmpty()) {
            appendLine("ON-BOARD TEST RESULTS (Mode 06)")
            s.mode6.forEach {
                appendLine("  ${if (it.passed) "PASS" else "FAIL"}  ${it.midName} TID ${ElmParser.hex(it.tid)}: ${it.value} (limits ${it.min}..${it.max})")
            }
            appendLine()
        }

        if (s.vehicle.calibrationIds.isNotEmpty()) appendLine("Calibration IDs: ${s.vehicle.calibrationIds.joinToString()}")
        if (s.vehicle.ecuNames.isNotEmpty()) appendLine("ECUs: ${s.vehicle.ecuNames.joinToString()}")
        if (s.scanNotes.isNotEmpty()) {
            appendLine()
            appendLine("Notes:")
            s.scanNotes.forEach { appendLine("  - $it") }
        }
    }
}
