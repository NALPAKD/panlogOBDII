package com.nalpakd.obdscanner.core

/**
 * Parses raw ELM327 text responses into OBD messages.
 *
 * Handles:
 *  - echo, "SEARCHING...", "BUS INIT", prompt characters
 *  - ELM error strings (NO DATA, UNABLE TO CONNECT, CAN ERROR, ...)
 *  - CAN ISO-TP multi-frame output ("014" length line, then "0:", "1:" ... frames)
 *  - multiple ECUs answering the same request (one message per ECU)
 */
object ElmParser {

    val ERROR_STRINGS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY",
        "FB ERROR", "DATA ERROR", "BUFFER FULL", "STOPPED", "ERROR", "<RX ERROR", "ACT ALERT", "LV RESET"
    )

    /** Returns the error keyword if the response is an ELM error, else null. */
    fun errorOf(raw: String): String? {
        val up = raw.uppercase()
        for (e in ERROR_STRINGS) if (up.contains(e)) return e
        if (up.trim().trimEnd('>').trim() == "?") return "?"
        return null
    }

    /** Splits into clean, non-empty lines without echo/status chatter. */
    fun lines(raw: String, sentCommand: String? = null): List<String> {
        val echo = sentCommand?.replace(" ", "")?.uppercase()
        return raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.uppercase().startsWith("SEARCHING") }
            .filterNot { it.uppercase().startsWith("BUS INIT") }
            .filterNot { echo != null && it.replace(" ", "").uppercase() == echo }
    }

    private val FRAME_RE = Regex("^([0-9A-F]):\\s*(.*)$")
    private val HEX_ONLY = Regex("^[0-9A-F]+$")

    /**
     * Converts a raw response into a list of complete hex messages (no spaces, uppercase).
     * ISO-TP multi-frame sequences are joined into a single message.
     */
    fun messages(raw: String, sentCommand: String? = null): List<String> {
        val out = mutableListOf<String>()
        var pendingLen = -1
        val frameBuf = StringBuilder()

        fun flushFrames() {
            if (frameBuf.isNotEmpty()) {
                var s = frameBuf.toString()
                if (pendingLen > 0 && s.length > pendingLen * 2) s = s.substring(0, pendingLen * 2)
                out.add(s)
                frameBuf.clear()
            }
            pendingLen = -1
        }

        for (l0 in lines(raw, sentCommand)) {
            val l = l0.uppercase()
            if (errorOf(l) != null) continue
            val fm = FRAME_RE.find(l)
            if (fm != null) {
                val idx = fm.groupValues[1].toInt(16)
                val data = fm.groupValues[2].replace(" ", "")
                if (idx == 0 && frameBuf.isNotEmpty()) {
                    // a new multi-frame message from another ECU
                    val keepLen = pendingLen
                    flushFrames(); pendingLen = keepLen
                }
                frameBuf.append(data)
                continue
            }
            val compact = l.replace(" ", "")
            if (!HEX_ONLY.matches(compact)) continue
            if (compact.length == 3) {
                // ISO-TP total length line (e.g. "014" = 20 bytes)
                flushFrames()
                pendingLen = compact.toInt(16)
                continue
            }
            flushFrames()
            if (compact.length % 2 == 0) out.add(compact)
        }
        flushFrames()
        return out
    }

    /** Hex string -> byte values (0..255). */
    fun bytes(hex: String): IntArray {
        val clean = hex.replace(" ", "")
        return IntArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    fun hex(b: Int): String = b.toString(16).uppercase().padStart(2, '0')

    /**
     * Finds the data bytes following a response header (e.g. mode 01 pid 0C -> "410C").
     * Returns data from the first ECU that answered, or null.
     */
    fun dataAfter(messages: List<String>, header: String): IntArray? {
        for (m in messages) {
            var i = m.indexOf(header)
            while (i >= 0 && i % 2 != 0) i = m.indexOf(header, i + 1)
            if (i >= 0) return bytes(m.substring(i + header.length))
        }
        return null
    }

    /** Like [dataAfter] but returns every ECU's answer. */
    fun allDataAfter(messages: List<String>, header: String): List<IntArray> {
        val res = mutableListOf<IntArray>()
        for (m in messages) {
            var i = m.indexOf(header)
            while (i >= 0 && i % 2 != 0) i = m.indexOf(header, i + 1)
            if (i >= 0) res.add(bytes(m.substring(i + header.length)))
        }
        return res
    }

    /** Decodes a 4-byte "PIDs supported" bitmask starting at [base] (0x00, 0x20, ...). */
    fun supportedFromMask(base: Int, data: IntArray): List<Int> {
        val res = mutableListOf<Int>()
        for (byteIdx in 0 until minOf(4, data.size)) {
            for (bit in 0 until 8) {
                if (data[byteIdx] and (0x80 shr bit) != 0) res.add(base + byteIdx * 8 + bit + 1)
            }
        }
        return res
    }

    fun isCanProtocol(protocolNumber: String?): Boolean {
        val p = protocolNumber?.trim()?.uppercase()?.removePrefix("A") ?: return true
        return p in listOf("6", "7", "8", "9", "A", "B", "C")
    }
}
