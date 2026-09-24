package com.nalpakd.obdscanner.core

import kotlin.random.Random

/**
 * Simulated ELM327 on a CAN vehicle with a lean condition (P0171/P0174) and a pending EVAP leak (P0442).
 * Lets you try every screen of the app without a car or adapter, and is used by the unit tests.
 */
class DemoTransport(private val rnd: Random = Random.Default) : ElmTransport {
    override var isConnected = true
        private set

    override fun close() { isConnected = false }

    private val sup01 = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13,
        0x14, 0x15, 0x18, 0x19, 0x1C, 0x1F, 0x20,
        0x21, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x40,
        0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x51
    )
    private val supFF = setOf(0x02, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x11)
    private val sup09 = setOf(0x02, 0x04, 0x06, 0x0A)
    private val sup06 = setOf(0x01, 0x02, 0x05, 0x20, 0x21, 0x22)

    private fun h(b: Int) = ElmParser.hex(b and 0xFF)

    private fun mask(base: Int, set: Set<Int>): String {
        val b = IntArray(4)
        for (p in set) {
            val off = p - base - 1
            if (off in 0..31) b[off / 8] = b[off / 8] or (0x80 shr (off % 8))
        }
        return b.joinToString("") { h(it) }
    }

    private fun ascii(s: String) = s.map { h(it.code) }.joinToString("")

    /** Formats a payload as the ELM327 prints CAN ISO-TP responses with spaces and headers off. */
    fun isotp(payloadHex: String): String {
        val n = payloadHex.length / 2
        if (n <= 7) return payloadHex
        val sb = StringBuilder()
        sb.append(n.toString(16).uppercase().padStart(3, '0')).append('\r')
        sb.append("0:").append(payloadHex.substring(0, 12)).append('\r')
        var pos = 12
        var idx = 1
        while (pos < payloadHex.length) {
            val end = minOf(payloadHex.length, pos + 14)
            sb.append((idx % 16).toString(16).uppercase()).append(':').append(payloadHex.substring(pos, end)).append('\r')
            pos = end; idx++
        }
        return sb.toString().trimEnd('\r')
    }

    private fun pidBytes(pid: Int): String? = when (pid) {
        0x01 -> "8207E504"
        0x41 -> "0007E524"
        0x03 -> "0200"
        0x04 -> h(60 + rnd.nextInt(10))
        0x05 -> h(90 + 40 + rnd.nextInt(3))            // ~91 °C
        0x06 -> h(128 + 12 + rnd.nextInt(5))           // STFT B1 ~ +10%
        0x07 -> h(128 + 23)                            // LTFT B1 ~ +18%
        0x08 -> h(128 + 10 + rnd.nextInt(5))
        0x09 -> h(128 + 21)
        0x0B -> h(34 + rnd.nextInt(3))
        0x0C -> { val r = (720 + rnd.nextInt(40)) * 4; h(r shr 8) + h(r) }
        0x0D -> "00"
        0x0E -> h((12 + 64) * 2 + rnd.nextInt(4))
        0x0F -> h(40 + 32)
        0x10 -> { val m = 380 + rnd.nextInt(30); h(m shr 8) + h(m) }   // 3.8 g/s (a bit low -> unmetered air)
        0x11 -> h(38)
        0x13 -> "33"
        0x14 -> h(20 + rnd.nextInt(150)) + "FF"
        0x15 -> h(120 + rnd.nextInt(30)) + "FF"
        0x18 -> h(20 + rnd.nextInt(150)) + "FF"
        0x19 -> h(125 + rnd.nextInt(30)) + "FF"
        0x1C -> "01"
        0x1F -> { val s = 312 + rnd.nextInt(5); h(s shr 8) + h(s) }
        0x21 -> "0052"
        0x2E -> h(40)
        0x2F -> h(150)
        0x30 -> h(12)
        0x31 -> "0141"
        0x33 -> h(92)
        0x3C -> { val v = (620 + 40) * 10; h(v shr 8) + h(v) }
        0x42 -> { val v = 14100 + rnd.nextInt(200); h(v shr 8) + h(v) }
        0x43 -> "0040"
        0x44 -> "8000"
        0x45 -> h(10)
        0x46 -> h(40 + 24)
        0x47 -> h(40)
        0x49 -> h(38)
        0x4A -> h(19)
        0x4C -> h(12)
        0x4D -> "0049"
        0x4E -> "02F5"
        0x51 -> "01"
        else -> null
    }

    override fun send(cmd: String, timeoutMs: Long): String {
        Thread.sleep(15)
        val c = cmd.uppercase().replace(" ", "")
        val body: String = when {
            c == "ATZ" -> "\r\rELM327 v1.5"
            c == "ATI" -> "ELM327 v1.5 (DEMO MODE)"
            c == "ATRV" -> "14.${rnd.nextInt(10)}V"
            c == "ATDPN" -> "A6"
            c == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            c.startsWith("AT") -> "OK"
            c == "03" -> "430201710174"
            c == "07" -> "47010442"
            c == "0A" -> "4A00"
            c == "04" -> "44"
            c.length == 4 && c.startsWith("01") -> {
                val pid = c.substring(2).toInt(16)
                if (pid % 0x20 == 0 && pid <= 0xC0) {
                    val m = mask(pid, sup01)
                    if (pid == 0 || pid in sup01) "41${h(pid)}$m" else "NO DATA"
                } else if (pid in sup01) pidBytes(pid)?.let { "41${h(pid)}$it" } ?: "NO DATA" else "NO DATA"
            }
            c == "020200" -> "4202000171"
            c.length == 6 && c.startsWith("02") -> {
                val pid = c.substring(2, 4).toInt(16)
                when {
                    pid == 0 -> "420000${mask(0, supFF)}"
                    pid % 0x20 == 0 -> "NO DATA"
                    pid in supFF -> pidBytes(pid)?.let { "42${h(pid)}00$it" } ?: "NO DATA"
                    else -> "NO DATA"
                }
            }
            c == "0900" -> "4900${mask(0, sup09)}"
            c == "0902" -> isotp("490201" + ascii("1GNDT13S472234567"))
            c == "0904" -> isotp("490401" + ascii("12639856") + "0000000000000000")
            c == "0906" -> "4906011A2B3C4D"
            c == "090A" -> isotp("490A01" + ascii("ECM") + "00" + ascii("-EngineControl") + "00")
            c == "0600" -> "4600${mask(0, sup06)}"
            c == "0620" -> "4620${mask(0x20, sup06)}"
            c == "0601" -> isotp("46" + "010187" + "0190" + "0000" + "02BC" + "010287" + "0064" + "0000" + "00C8")
            c == "0602" -> isotp("46" + "02058A" + "0320" + "0000" + "07D0")
            c == "0605" -> isotp("46" + "05058A" + "0300" + "0000" + "07D0")
            c == "0621" -> isotp("46" + "218024" + "00A0" + "0000" + "0190")
            c == "0622" -> isotp("46" + "228024" + "0110" + "0000" + "0190")
            else -> "NO DATA"
        }
        return "$body\r\r>"
    }
}
