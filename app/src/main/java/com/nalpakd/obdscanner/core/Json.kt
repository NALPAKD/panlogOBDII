package com.nalpakd.obdscanner.core

/**
 * Tiny dependency-free JSON writer/parser.
 * Values: Map<String, Any?>, List<Any?>, String, Number, Boolean, null.
 */
object Json {

    fun write(v: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun writeValue(sb: StringBuilder, v: Any?, level: Int) {
        val next = if (level < 0) -1 else level + 1
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else {
                val r = Math.round(v * 1000.0) / 1000.0
                if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) sb.append(r.toLong()) else sb.append(r)
            }
            is Float -> writeValue(sb, v.toDouble(), level)
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, next)
                    quote(sb, k.toString())
                    sb.append(if (level < 0) ":" else ": ")
                    writeValue(sb, value, next)
                }
                if (!first) indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, next)
                    writeValue(sb, item, next)
                }
                if (!first) indent(sb, level)
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, v.toList(), level)
            else -> quote(sb, v.toString())
        }
    }

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun quote(s: String): String = StringBuilder().also { quote(it, s) }.toString()

    // ---------------- parser ----------------

    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("Trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw ParseException("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Unexpected '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Bad literal at $i")
            i += word.length
            return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw ParseException("Bad number at $st")
        }
        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw ParseException("Unterminated string")
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (s[i] != '"') throw ParseException("Expected key at $i")
                val k = str()
                ws()
                if (s[i] != ':') throw ParseException("Expected ':' at $i")
                i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw ParseException("Expected ',' or '}' at ${i - 1}")
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw ParseException("Expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }

    // --------------- helpers for reading parsed data ---------------
    @Suppress("UNCHECKED_CAST")
    fun Any?.obj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
    fun Any?.list(): List<Any?> = (this as? List<Any?>) ?: emptyList()
    fun Any?.str(def: String = ""): String = when (this) {
        null -> def
        is String -> this
        is Double -> if (this == Math.floor(this)) this.toLong().toString() else this.toString()
        else -> this.toString()
    }
}
