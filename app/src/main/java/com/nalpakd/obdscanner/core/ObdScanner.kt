package com.nalpakd.obdscanner.core

/** Byte-level link to an ELM327 (Bluetooth socket, or the demo simulator). Blocking calls. */
interface ElmTransport {
    /** Sends a command (without CR) and returns everything received up to the '>' prompt. */
    fun send(cmd: String, timeoutMs: Long = 4000): String
    val isConnected: Boolean
    fun close()
}

class ScanCancelledException : Exception("Scan cancelled")

/**
 * High-level OBD-II operations on top of an [ElmTransport]. All calls are blocking;
 * run them from a background thread / Dispatchers.IO.
 */
class ObdScanner(private val t: ElmTransport) {

    var adapterInfo = AdapterInfo()
        private set
    private var supported01: List<Int>? = null
    private val isCan: Boolean get() = ElmParser.isCanProtocol(adapterInfo.protocolNumber)

    fun raw(cmd: String, timeoutMs: Long = 4000): String = t.send(cmd, timeoutMs)

    fun query(cmd: String, timeoutMs: Long = 4000): List<String> =
        ElmParser.messages(t.send(cmd, timeoutMs), cmd)

    /** Resets the adapter and lets it auto-detect the vehicle protocol. */
    fun initialize(): AdapterInfo {
        t.send("ATZ", 5000)
        Thread.sleep(500)
        val cmds = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
        for (c in cmds) t.send(c, 2000)
        val version = ElmParser.lines(t.send("ATI", 2000), "ATI").firstOrNull() ?: "ELM327"
        // First real request triggers the protocol search (can take 10+ seconds on J1850/ISO cars)
        val first = t.send("0100", 20000)
        val err = ElmParser.errorOf(first)
        if (err != null && ElmParser.messages(first, "0100").isEmpty()) {
            throw IllegalStateException("Vehicle did not respond ($err). Turn the ignition ON (engine running is best) and try again.")
        }
        val dpn = ElmParser.lines(t.send("ATDPN", 2000), "ATDPN").firstOrNull() ?: ""
        val dp = ElmParser.lines(t.send("ATDP", 2000), "ATDP").firstOrNull() ?: ""
        val volts = ElmParser.lines(t.send("ATRV", 2000), "ATRV").firstOrNull() ?: ""
        adapterInfo = AdapterInfo(version, volts, dp.removePrefix("AUTO, ").removePrefix("AUTO,"), dpn)
        supported01 = null
        return adapterInfo
    }

    fun batteryVoltage(): String = ElmParser.lines(t.send("ATRV", 2000), "ATRV").firstOrNull() ?: ""

    // ------------------------------------------------------------------ mode 01

    private fun supportedChain(mode: String, respHeader: String, bases: IntRange): List<Int> {
        val res = mutableListOf<Int>()
        var base = bases.first
        while (base <= bases.last) {
            if (base != 0 && base !in res) break
            // Mode 02 requests carry a frame number (always 00)
            val cmd = "$mode${ElmParser.hex(base)}" + if (mode == "02") "00" else ""
            val data = ElmParser.dataAfter(query(cmd), "$respHeader${ElmParser.hex(base)}") ?: break
            val d = if (mode == "02") data.drop(1).toIntArray() else data
            if (d.size < 4) break
            res.addAll(ElmParser.supportedFromMask(base, d))
            base += 0x20
        }
        return res
    }

    fun supportedPids(): List<Int> {
        supported01?.let { return it }
        val r = supportedChain("01", "41", 0x00..0xC0)
        supported01 = r
        return r
    }

    fun readPid(pid: Int): Reading? {
        val h = ElmParser.hex(pid)
        val data = ElmParser.dataAfter(query("01$h", 3000), "41$h") ?: return null
        return Pids.decode(pid, data)
    }

    fun readMonitorStatus(pid: Int = 0x01): MonitorStatus? {
        val h = ElmParser.hex(pid)
        val data = ElmParser.dataAfter(query("01$h"), "41$h") ?: return null
        return MonitorDecoder.decode(data)
    }

    // ------------------------------------------------------------------ DTCs

    fun readDtcs(mode: String): List<Dtc> {
        val kind = when (mode) { "03" -> "stored"; "07" -> "pending"; "0A" -> "permanent"; else -> mode }
        val respSid = mode.toInt(16) + 0x40
        val msgs = query(mode, 6000)
        return DtcDecoder.parse(msgs, respSid).map { Dtc(it, DtcDatabase.describe(it), kind) }
    }

    /** Mode 04. Returns true if the ECU acknowledged. */
    fun clearDtcs(): Boolean {
        val msgs = query("04", 8000)
        return msgs.any { it.startsWith("44") }
    }

    // ------------------------------------------------------------------ mode 09

    private fun mode09Payloads(pid: Int): List<IntArray> {
        val h = ElmParser.hex(pid)
        return ElmParser.allDataAfter(query("09$h", 6000), "49$h")
    }

    /** Joins mode 09 data for both CAN (count byte + data) and legacy (seq byte + 4 bytes per line). */
    private fun mode09Bytes(pid: Int): IntArray {
        val payloads = mode09Payloads(pid)
        if (payloads.isEmpty()) return IntArray(0)
        val legacy = payloads.size > 1 && payloads.all { it.size == 5 }
        return if (legacy) {
            payloads.sortedBy { it[0] }.flatMap { it.drop(1) }.toIntArray()
        } else {
            payloads.first().drop(1).toIntArray()
        }
    }

    private fun ascii(b: IntArray): String =
        b.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    fun readVin(): String? {
        val s = ascii(mode09Bytes(0x02)).filter { it.isLetterOrDigit() }
        if (s.length < 11) return null
        return if (s.length > 17) s.takeLast(17) else s
    }

    fun readVehicleInfo(): VehicleInfo {
        val sup = supportedChain("09", "49", 0x00..0x00)
        val vin = if (sup.isEmpty() || 0x02 in sup) safe { readVin() } else null
        val cal = if (0x04 in sup) safe {
            val b = mode09Bytes(0x04)
            b.toList().chunked(16).map { ascii(it.toIntArray()) }.filter { it.isNotBlank() }
        } ?: emptyList() else emptyList()
        val cvn = if (0x06 in sup) safe {
            mode09Bytes(0x06).toList().chunked(4).filter { it.size == 4 }.map { c -> c.joinToString("") { ElmParser.hex(it) } }
        } ?: emptyList() else emptyList()
        val ecu = if (0x0A in sup) safe {
            ElmParser.allDataAfter(query("090A", 6000), "490A").map { ascii(it.drop(1).toIntArray()).replace("\u0000", "") }
                .filter { it.isNotBlank() }
        } ?: emptyList() else emptyList()
        return VehicleInfo(vin, cal, cvn, ecu)
    }

    // ------------------------------------------------------------------ mode 02

    fun readFreezeFrame(): Pair<String?, List<Reading>> {
        val dtcData = ElmParser.dataAfter(query("020200"), "4202")
        val dtc = if (dtcData != null && dtcData.size >= 3) DtcDecoder.decode(dtcData[1], dtcData[2]) else null
        if (dtc == null) return null to emptyList()
        val sup = supportedChain("02", "42", 0x00..0x60)
        val readings = mutableListOf<Reading>()
        for (pid in sup) {
            if (pid % 0x20 == 0 || pid == 0x02 || Pids.byPid[pid] == null) continue
            val h = ElmParser.hex(pid)
            val data = ElmParser.dataAfter(query("02${h}00", 3000), "42$h") ?: continue
            Pids.decode(pid, data.drop(1).toIntArray())?.let { readings.add(it) }
        }
        return dtc to readings
    }

    // ------------------------------------------------------------------ mode 06 (CAN only)

    fun readMode6(limit: Int = 48): List<Mode6Result> {
        if (!isCan) return emptyList()
        val mids = supportedChain("06", "46", 0x00..0xE0).filter { it % 0x20 != 0 }.take(limit)
        val out = mutableListOf<Mode6Result>()
        for (mid in mids) {
            val h = ElmParser.hex(mid)
            val msgs = query("06$h", 3000)
            for (payload in ElmParser.allDataAfter(msgs, "46")) {
                var i = 0
                while (i + 9 <= payload.size) {
                    val m = payload[i]
                    val tid = payload[i + 1]
                    val uas = payload[i + 2]
                    fun v(o: Int): Int {
                        val raw = (payload[i + o] shl 8) or payload[i + o + 1]
                        return if (uas >= 0x80 && raw >= 0x8000) raw - 0x10000 else raw
                    }
                    out.add(Mode6Result(m, tid, v(3), v(5), v(7)))
                    i += 9
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ full scan

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: ScanCancelledException) { throw e } catch (e: Exception) { null }

    /**
     * Runs every read-only test and returns a complete report.
     * [progress] receives (message, percent 0-100). [cancelled] is polled between steps.
     */
    fun fullScan(
        profile: VehicleProfile,
        imperial: Boolean,
        samplePasses: Int = 5,
        progress: (String, Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false }
    ): ScanReport {
        val notes = mutableListOf<String>()
        fun step(msg: String, pct: Int) {
            if (cancelled()) throw ScanCancelledException()
            progress(msg, pct)
        }

        step("Reading adapter & battery voltage", 2)
        val volts = safe { batteryVoltage() } ?: ""
        adapterInfo = adapterInfo.copy(batteryVoltage = volts.ifBlank { adapterInfo.batteryVoltage })

        step("Finding supported sensors", 6)
        val sup = safe { supportedPids() } ?: emptyList<Int>().also { notes.add("Could not read supported PID list") }

        step("Reading VIN and ECU information", 12)
        val vehicle = safe { readVehicleInfo() } ?: VehicleInfo().also { notes.add("Vehicle info (Mode 09) not available") }

        step("Checking emissions readiness monitors", 20)
        val monClear = safe { readMonitorStatus(0x01) }
        val monCycle = if (0x41 in sup) safe { readMonitorStatus(0x41) } else null

        step("Reading stored trouble codes", 28)
        val stored = safe { readDtcs("03") } ?: emptyList<Dtc>().also { notes.add("Stored DTC read failed") }
        step("Reading pending trouble codes", 34)
        val pending = safe { readDtcs("07") } ?: emptyList()
        step("Reading permanent trouble codes", 40)
        val permanent = safe { readDtcs("0A") } ?: emptyList()

        step("Reading freeze frame data", 46)
        val (ffDtc, ff) = safe { readFreezeFrame() } ?: (null to emptyList())

        step("Reading all live sensors", 55)
        val skip = setOf(0x01, 0x41)
        val snapshot = mutableListOf<Reading>()
        val decodable = sup.filter { it !in skip && Pids.byPid[it] != null }
        decodable.forEachIndexed { idx, pid ->
            if (cancelled()) throw ScanCancelledException()
            if (idx % 4 == 0) progress("Reading live sensors (${idx + 1}/${decodable.size})", 55 + idx * 15 / maxOf(1, decodable.size))
            safe { readPid(pid) }?.let { snapshot.add(it) }
        }

        val keyPids = Pids.KEY_SAMPLE_PIDS.filter { it in sup }
        val acc = LinkedHashMap<Int, MutableList<Double>>()
        for (pass in 1..samplePasses) {
            step("Sampling key sensors (pass $pass of $samplePasses)", 70 + pass * 15 / samplePasses)
            for (pid in keyPids) {
                val r = safe { readPid(pid) } ?: continue
                val v = r.value ?: continue
                acc.getOrPut(pid) { mutableListOf() }.add(v)
            }
        }
        val samples = acc.filter { it.value.isNotEmpty() }.map { (pid, vs) ->
            val def = Pids.byPid[pid]!!
            SampleStat(pid, def.name, def.unit, vs.minOrNull()!!, vs.maxOrNull()!!, vs.average(), vs.size)
        }

        step("Reading on-board test results (Mode 06)", 88)
        val m6 = safe { readMode6() } ?: emptyList()
        if (!isCan) notes.add("Mode 06 test results skipped (non-CAN protocol)")

        step("Finishing report", 98)
        val s = ScanReport(
            System.currentTimeMillis(), profile, imperial, adapterInfo, vehicle, sup,
            monClear, monCycle, stored, pending, permanent, ffDtc, ff, snapshot, samples, m6, notes
        )
        progress("Scan complete", 100)
        return s
    }
}
