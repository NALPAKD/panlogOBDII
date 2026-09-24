package com.nalpakd.obdscanner.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdapterInfo(
    val elmVersion: String = "",
    val batteryVoltage: String = "",
    val protocol: String = "",
    val protocolNumber: String = ""
)

data class VehicleInfo(
    val vin: String? = null,
    val calibrationIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    val ecuNames: List<String> = emptyList()
)

data class SampleStat(
    val pid: Int, val name: String, val unit: String,
    val min: Double, val max: Double, val avg: Double, val count: Int
)

data class Mode6Result(
    val mid: Int, val tid: Int, val value: Int, val min: Int, val max: Int
) {
    val passed: Boolean get() = value in min..max
    val midName: String get() = Mode6Names.mid(mid)
}

object Mode6Names {
    fun mid(m: Int): String = when (m) {
        in 0x01..0x10 -> "O2 sensor monitor B${if (m <= 0x04) 1 else if (m <= 0x08) 2 else if (m <= 0x0C) 3 else 4}S${(m - 1) % 4 + 1}"
        0x21 -> "Catalyst monitor Bank 1"
        0x22 -> "Catalyst monitor Bank 2"
        0x31 -> "EGR monitor Bank 1"
        0x32 -> "EGR monitor Bank 2"
        0x35 -> "VVT monitor Bank 1"
        0x36 -> "VVT monitor Bank 2"
        0x39 -> "EVAP monitor (cap off / 0.150\")"
        0x3A -> "EVAP monitor (0.090\")"
        0x3B -> "EVAP monitor (0.040\")"
        0x3C -> "EVAP monitor (0.020\")"
        0x3D -> "Purge flow monitor"
        in 0x41..0x50 -> "O2 sensor heater monitor #${m - 0x40}"
        0x61 -> "Heated catalyst monitor Bank 1"
        0x71 -> "Secondary air monitor 1"
        0x81 -> "Fuel system monitor Bank 1"
        0x82 -> "Fuel system monitor Bank 2"
        0xA1 -> "Misfire monitor - general"
        in 0xA2..0xAD -> "Misfire cylinder ${m - 0xA1}"
        0xB0 -> "Particulate filter monitor"
        else -> "Monitor 0x${ElmParser.hex(m)}"
    }
}

class ScanReport(
    val timestamp: Long,
    val profile: VehicleProfile,
    val imperial: Boolean,
    val adapter: AdapterInfo,
    val vehicle: VehicleInfo,
    val supportedPids: List<Int>,
    val monitorsSinceClear: MonitorStatus?,
    val monitorsThisCycle: MonitorStatus?,
    val storedDtcs: List<Dtc>,
    val pendingDtcs: List<Dtc>,
    val permanentDtcs: List<Dtc>,
    val freezeFrameDtc: String?,
    val freezeFrame: List<Reading>,
    val snapshot: List<Reading>,
    val samples: List<SampleStat>,
    val mode6: List<Mode6Result>,
    val scanNotes: List<String>,
    var symptoms: String = ""
) {
    val allDtcs: List<Dtc> get() = storedDtcs + pendingDtcs + permanentDtcs

    fun dateString(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
    fun fileStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))

    private fun readingMap(r: Reading): Map<String, Any?> = linkedMapOf(
        "pid" to "0x${ElmParser.hex(r.pid)}",
        "name" to r.name,
        "value" to (r.text ?: r.displayValue(imperial)),
        "unit" to r.displayUnit(imperial)
    )

    private fun monitorMap(m: MonitorStatus?): Map<String, Any?>? = m?.let {
        linkedMapOf(
            "check_engine_light_on" to it.milOn,
            "confirmed_dtc_count" to it.dtcCount,
            "engine_type" to if (it.compressionIgnition) "diesel" else "gasoline",
            "monitors" to it.monitors
        )
    }

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "report_type" to "panlong_obd_full_scan",
        "app_version" to "1.0",
        "scan_time" to dateString(),
        "units" to if (imperial) "imperial" else "metric",
        "vehicle_profile" to profile.displayName,
        "vehicle_profile_id" to profile.id,
        "user_reported_symptoms" to symptoms.ifBlank { null },
        "adapter" to linkedMapOf(
            "elm_version" to adapter.elmVersion,
            "battery_voltage_at_obd_port" to adapter.batteryVoltage,
            "protocol" to adapter.protocol,
            "protocol_number" to adapter.protocolNumber
        ),
        "vehicle" to linkedMapOf(
            "vin" to vehicle.vin,
            "calibration_ids" to vehicle.calibrationIds,
            "calibration_verification_numbers" to vehicle.cvns,
            "ecu_names" to vehicle.ecuNames
        ),
        "trouble_codes" to linkedMapOf(
            "stored_confirmed" to storedDtcs.map { linkedMapOf("code" to it.code, "description" to it.description) },
            "pending" to pendingDtcs.map { linkedMapOf("code" to it.code, "description" to it.description) },
            "permanent" to permanentDtcs.map { linkedMapOf("code" to it.code, "description" to it.description) }
        ),
        "readiness_since_codes_cleared" to monitorMap(monitorsSinceClear),
        "readiness_this_drive_cycle" to monitorMap(monitorsThisCycle),
        "freeze_frame" to linkedMapOf(
            "triggering_dtc" to freezeFrameDtc,
            "data" to freezeFrame.map { readingMap(it) }
        ),
        "live_snapshot" to snapshot.map { readingMap(it) },
        "live_sample_statistics" to samples.map {
            val (mn, u) = Units.convert(it.min, it.unit, imperial)
            val mx = Units.convert(it.max, it.unit, imperial).first
            val av = Units.convert(it.avg, it.unit, imperial).first
            linkedMapOf("pid" to "0x${ElmParser.hex(it.pid)}", "name" to it.name, "unit" to u,
                "min" to mn, "avg" to av, "max" to mx, "samples" to it.count)
        },
        "mode6_test_results" to mode6.map {
            linkedMapOf("monitor" to it.midName, "mid" to "0x${ElmParser.hex(it.mid)}", "tid" to "0x${ElmParser.hex(it.tid)}",
                "value_raw" to it.value, "min_raw" to it.min, "max_raw" to it.max,
                "result" to if (it.passed) "PASS" else "FAIL")
        },
        "supported_pids" to supportedPids.map { "0x${ElmParser.hex(it)}" },
        "scan_notes" to scanNotes
    )

    fun toJson(): String = Json.write(toJsonMap())
}
