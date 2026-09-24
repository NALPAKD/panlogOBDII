package com.nalpakd.obdscanner.core

data class Dtc(val code: String, val description: String, val kind: String) {
    // kind: "stored", "pending", "permanent", "freeze-frame"
}

object DtcDecoder {

    /** Decodes 2 raw bytes into "P0133" style code, or null for 0000 padding. */
    fun decode(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val letter = "PCBU"[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return "$letter$d1${d2.toString(16).uppercase()}${ElmParser.hex(b)}"
    }

    /**
     * Parses a Mode 03 / 07 / 0A response.
     * CAN responses carry a count byte after the service ID (payload length is odd);
     * J1850/ISO/KWP responses are fixed 6-byte lines, zero padded (payload length is even).
     */
    fun parse(messages: List<String>, responseSid: Int): List<String> {
        val header = ElmParser.hex(responseSid)
        val codes = LinkedHashSet<String>()
        for (payload in ElmParser.allDataAfter(messages.map { it }, header)) {
            // allDataAfter finds first occurrence; for DTC services the header is at position 0 normally
            var start = 0
            var end = payload.size
            if (payload.size % 2 == 1) {
                val count = payload[0]
                start = 1
                end = minOf(payload.size, 1 + count * 2)
            }
            var i = start
            while (i + 1 < end) {
                decode(payload[i], payload[i + 1])?.let { codes.add(it) }
                i += 2
            }
        }
        return codes.toList()
    }
}

data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    /** name -> "Complete" / "Not complete" (only supported monitors are listed) */
    val monitors: LinkedHashMap<String, String>
) {
    val incompleteCount: Int get() = monitors.values.count { it != "Complete" }
}

object MonitorDecoder {
    private val SPARK = listOf(
        "Catalyst", "Heated catalyst", "Evaporative (EVAP) system", "Secondary air system",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system"
    )
    private val DIESEL = listOf(
        "NMHC catalyst", "NOx/SCR aftertreatment", "Reserved", "Boost pressure",
        "Reserved", "Exhaust gas sensor", "PM filter (DPF)", "EGR / VVT system"
    )
    private val CONTINUOUS = listOf("Misfire", "Fuel system", "Comprehensive components")

    /** Decodes PID 01 (since codes cleared) or PID 41 (this drive cycle, byte A ignored). */
    fun decode(d: IntArray): MonitorStatus? {
        if (d.size < 4) return null
        val a = d[0]; val b = d[1]; val c = d[2]; val e = d[3]
        val diesel = b and 0x08 != 0
        val mons = LinkedHashMap<String, String>()
        for (i in 0 until 3) {
            if (b and (1 shl i) != 0) mons[CONTINUOUS[i]] = if (b and (1 shl (i + 4)) != 0) "Not complete" else "Complete"
        }
        val names = if (diesel) DIESEL else SPARK
        for (i in 0 until 8) {
            if (names[i] == "Reserved") continue
            if (c and (1 shl i) != 0) mons[names[i]] = if (e and (1 shl i) != 0) "Not complete" else "Complete"
        }
        return MonitorStatus(a and 0x80 != 0, a and 0x7F, diesel, mons)
    }
}
