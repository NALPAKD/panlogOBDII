package com.nalpakd.obdscanner.core

import com.nalpakd.obdscanner.core.Json.list
import com.nalpakd.obdscanner.core.Json.obj
import com.nalpakd.obdscanner.core.Json.str

/** Renders Claude's analysis + the raw scan into a friendly, self-contained, printable HTML page. */
object ReportHtml {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun e(v: Any?) = esc(v.str())

    private val CSS = """
:root{--bg:#f4f6f9;--card:#fff;--ink:#1c2430;--muted:#5b6675;--line:#e3e7ee;--good:#1f9d55;--warn:#d98a00;--bad:#d64545;--accent:#2f6fdf}
@media (prefers-color-scheme:dark){:root{--bg:#11151b;--card:#1b212a;--ink:#e8edf3;--muted:#9aa6b5;--line:#2a323d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 -apple-system,Roboto,"Segoe UI",sans-serif}
.wrap{max-width:760px;margin:0 auto;padding:16px}
header{background:linear-gradient(135deg,#1d3557,#2f6fdf);color:#fff;border-radius:16px;padding:20px}
header h1{margin:0 0 4px;font-size:22px}header .sub{opacity:.9;font-size:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin:14px 0}
h2{font-size:18px;margin:0 0 10px}h3{font-size:16px;margin:0}
.badge{display:inline-block;padding:4px 12px;border-radius:999px;font-weight:700;font-size:13px;color:#fff}
.good{background:var(--good)}.attention,.medium,.caution,.watch{background:var(--warn)}.urgent,.high,.critical,.no,.abnormal{background:var(--bad)}
.low,.yes,.normal{background:var(--good)}
.status{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.headline{font-size:18px;font-weight:600;margin:10px 0 6px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
.tile{background:var(--bg);border-radius:10px;padding:10px}.tile .k{font-size:12px;color:var(--muted)}.tile .v{font-size:18px;font-weight:700}
.issue{border-left:6px solid var(--warn)}.issue.sev-high,.issue.sev-critical{border-left-color:var(--bad)}.issue.sev-low{border-left-color:var(--good)}
.issue .top{display:flex;justify-content:space-between;gap:8px;align-items:flex-start}
.codes{font-family:monospace;color:var(--muted);font-size:13px;margin:2px 0 8px}
.label{font-weight:700;font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);margin-top:10px}
ul,ol{margin:6px 0;padding-left:22px}li{margin:3px 0}
.like{font-size:12px;color:var(--muted)}
.meta{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.chip{background:var(--bg);border-radius:8px;padding:4px 10px;font-size:13px}
table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:7px 6px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th{font-size:12px;color:var(--muted);text-transform:uppercase}
.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}
details summary{cursor:pointer;font-weight:700}
.small{font-size:13px;color:var(--muted)}
footer{font-size:12px;color:var(--muted);text-align:center;padding:10px 0 30px}
@media print{body{background:#fff}.card{break-inside:avoid}header{-webkit-print-color-adjust:exact;print-color-adjust:exact}}
""".trimIndent()

    private fun statusLabel(s: String) = when (s) {
        "good" -> "ALL GOOD"; "urgent" -> "URGENT"; else -> "NEEDS ATTENTION"
    }
    private fun driveLabel(s: String) = when (s) {
        "yes" -> "Safe to drive"; "no" -> "Do not drive"; else -> "Drive with caution"
    }

    fun render(analysis: Map<String, Any?>, scan: Map<String, Any?>): String {
        val sb = StringBuilder()
        val codes = scan["trouble_codes"].obj()
        val stored = codes["stored_confirmed"].list()
        val pending = codes["pending"].list()
        val permanent = codes["permanent"].list()
        val readiness = scan["readiness_since_codes_cleared"].obj()
        val vehicle = scan["vehicle"].obj()
        val status = analysis["overall_status"].str("attention")
        val drive = analysis["safe_to_drive"].str("caution")

        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Vehicle Health Report</title><style>").append(CSS).append("</style></head><body><div class=\"wrap\">")

        sb.append("<header><h1>Vehicle Health Report</h1><div class=\"sub\">")
        sb.append(e(analysis["vehicle_description"].str(scan["vehicle_profile"].str())))
        sb.append("<br>Scanned ").append(e(scan["scan_time"]))
        vehicle["vin"]?.let { sb.append(" &middot; VIN ").append(e(it)) }
        sb.append("</div></header>")

        // Verdict
        sb.append("<div class=\"card\"><div class=\"status\"><span class=\"badge $status\">${statusLabel(status)}</span>")
        sb.append("<span class=\"badge $drive\">${driveLabel(drive)}</span></div>")
        sb.append("<div class=\"headline\">").append(e(analysis["headline"])).append("</div>")
        sb.append("<p>").append(e(analysis["summary"])).append("</p>")
        analysis["safe_to_drive_explanation"]?.let { sb.append("<p class=\"small\"><b>Driving:</b> ").append(e(it)).append("</p>") }
        sb.append("</div>")

        // At a glance
        val mil = readiness["check_engine_light_on"]
        val incomplete = readiness["monitors"].obj().values.count { it.str() != "Complete" }
        val volts = scan["adapter"].obj()["battery_voltage_at_obd_port"].str("-")
        sb.append("<div class=\"card\"><h2>At a glance</h2><div class=\"grid\">")
        tile(sb, "Check engine light", when (mil) { true -> "ON"; false -> "Off"; else -> "?" })
        tile(sb, "Trouble codes", "${stored.size} stored / ${pending.size} pending")
        tile(sb, "Permanent codes", permanent.size.toString())
        tile(sb, "Monitors not ready", incomplete.toString())
        tile(sb, "Battery (at port)", volts.ifBlank { "-" })
        sb.append("</div></div>")

        // Issues
        val issues = analysis["issues"].list().map { it.obj() }
        sb.append("<div class=\"card\"><h2>What needs attention</h2>")
        if (issues.isEmpty()) sb.append("<p>No problems found. Nice!</p>")
        sb.append("</div>")
        for (iss in issues) {
            val sev = iss["severity"].str("medium")
            sb.append("<div class=\"card issue sev-$sev\"><div class=\"top\"><h3>").append(e(iss["title"])).append("</h3>")
            sb.append("<span class=\"badge $sev\">").append(esc(sev.uppercase())).append("</span></div>")
            val cs = iss["codes"].list().joinToString(", ") { it.str() }
            if (cs.isNotBlank()) sb.append("<div class=\"codes\">").append(esc(cs)).append("</div>")
            para(sb, "What it means", iss["what_it_means"])
            para(sb, "Why it matters", iss["why_it_matters"])
            para(sb, "What the data shows", iss["evidence"])
            val causes = iss["likely_causes"].list().map { it.obj() }
            if (causes.isNotEmpty()) {
                sb.append("<div class=\"label\">Likely causes</div><ul>")
                causes.forEach { c -> sb.append("<li>").append(e(c["cause"])).append(" <span class=\"like\">(").append(e(c["likelihood"])).append(")</span></li>") }
                sb.append("</ul>")
            }
            listSection(sb, "What to do", iss["what_to_do"].list(), ordered = true)
            sb.append("<div class=\"meta\">")
            iss["diy_difficulty"]?.let { sb.append("<span class=\"chip\">🔧 ").append(e(it)).append("</span>") }
            iss["estimated_cost"]?.let { sb.append("<span class=\"chip\">💲 ").append(e(it)).append("</span>") }
            iss["urgency"]?.let { sb.append("<span class=\"chip\">⏱ ").append(e(it)).append("</span>") }
            sb.append("</div></div>")
        }

        // Readiness
        val rd = analysis["readiness"].obj()
        if (rd.isNotEmpty()) {
            val ready = rd["inspection_ready"] == true
            sb.append("<div class=\"card\"><h2>Emissions / smog check readiness</h2>")
            sb.append("<span class=\"badge ${if (ready) "good" else "attention"}\">${if (ready) "READY" else "NOT READY"}</span>")
            sb.append("<p>").append(e(rd["summary"])).append("</p>")
            listSection(sb, "Monitors not finished", rd["incomplete_monitors"].list(), ordered = false)
            sb.append("</div>")
        }

        // Sensor observations
        val obs = analysis["sensor_observations"].list().map { it.obj() }
        if (obs.isNotEmpty()) {
            sb.append("<div class=\"card\"><h2>Sensor check-up</h2><table><tr><th>Item</th><th>Reading</th><th>Normal</th></tr>")
            obs.forEach { o ->
                val st = o["status"].str("normal")
                val color = when (st) { "abnormal" -> "var(--bad)"; "watch" -> "var(--warn)"; else -> "var(--good)" }
                sb.append("<tr><td><span class=\"dot\" style=\"background:$color\"></span>").append(e(o["item"]))
                sb.append("<div class=\"small\">").append(e(o["comment"])).append("</div></td>")
                sb.append("<td>").append(e(o["reading"])).append("</td><td>").append(e(o["normal_range"])).append("</td></tr>")
            }
            sb.append("</table></div>")
        }

        // Next steps etc.
        cardList(sb, "Your next steps", analysis["next_steps"].list(), ordered = true)
        cardList(sb, "Questions to ask your mechanic", analysis["questions_for_mechanic"].list(), ordered = false)
        cardList(sb, "Maintenance tips", analysis["maintenance_tips"].list(), ordered = false)
        analysis["confidence_note"]?.let {
            sb.append("<div class=\"card\"><h2>How sure is this?</h2><p class=\"small\">").append(e(it)).append("</p></div>")
        }

        // Technical appendix
        sb.append("<div class=\"card\"><details><summary>Technical details (raw scan data)</summary>")
        fun codeRows(title: String, l: List<Any?>) {
            sb.append("<div class=\"label\">").append(esc(title)).append("</div>")
            if (l.isEmpty()) sb.append("<div class=\"small\">none</div>")
            else { sb.append("<ul>"); l.forEach { c -> val m = c.obj(); sb.append("<li><code>").append(e(m["code"])).append("</code> ").append(e(m["description"])).append("</li>") }; sb.append("</ul>") }
        }
        codeRows("Stored codes", stored); codeRows("Pending codes", pending); codeRows("Permanent codes", permanent)
        val mons = readiness["monitors"].obj()
        if (mons.isNotEmpty()) {
            sb.append("<div class=\"label\">Readiness monitors</div><table>")
            mons.forEach { (k, v) -> sb.append("<tr><td>").append(esc(k)).append("</td><td>").append(e(v)).append("</td></tr>") }
            sb.append("</table>")
        }
        val ff = scan["freeze_frame"].obj()
        val ffData = ff["data"].list()
        if (ffData.isNotEmpty()) {
            sb.append("<div class=\"label\">Freeze frame (").append(e(ff["triggering_dtc"])).append(")</div>")
            readingTable(sb, ffData)
        }
        val stats = scan["live_sample_statistics"].list()
        if (stats.isNotEmpty()) {
            sb.append("<div class=\"label\">Key sensor samples</div><table><tr><th>Sensor</th><th>Min</th><th>Avg</th><th>Max</th></tr>")
            stats.forEach { s0 -> val s = s0.obj()
                sb.append("<tr><td>").append(e(s["name"])).append(" <span class=\"small\">").append(e(s["unit"])).append("</span></td><td>")
                    .append(e(s["min"])).append("</td><td>").append(e(s["avg"])).append("</td><td>").append(e(s["max"])).append("</td></tr>") }
            sb.append("</table>")
        }
        val snap = scan["live_snapshot"].list()
        if (snap.isNotEmpty()) { sb.append("<div class=\"label\">Live snapshot</div>"); readingTable(sb, snap) }
        val m6 = scan["mode6_test_results"].list()
        if (m6.isNotEmpty()) {
            sb.append("<div class=\"label\">On-board test results (Mode 06)</div><table><tr><th>Monitor</th><th>Result</th></tr>")
            m6.forEach { r0 -> val r = r0.obj()
                sb.append("<tr><td>").append(e(r["monitor"])).append(" <span class=\"small\">TID ").append(e(r["tid"])).append("</span></td><td>")
                    .append(e(r["result"])).append(" <span class=\"small\">").append(e(r["value_raw"])).append(" (").append(e(r["min_raw"])).append("..").append(e(r["max_raw"])).append(")</span></td></tr>") }
            sb.append("</table>")
        }
        val ad = scan["adapter"].obj()
        sb.append("<p class=\"small\">Protocol: ").append(e(ad["protocol"])).append(" &middot; Adapter: ").append(e(ad["elm_version"])).append("</p>")
        sb.append("</details></div>")

        sb.append("<footer>Generated by Panlong OBD Scanner with Claude AI. This is an AI-assisted interpretation of your car's computer data &mdash; ")
        sb.append("confirm with a qualified mechanic before major repairs.</footer>")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun tile(sb: StringBuilder, k: String, v: String) {
        sb.append("<div class=\"tile\"><div class=\"k\">").append(esc(k)).append("</div><div class=\"v\">").append(esc(v)).append("</div></div>")
    }

    private fun para(sb: StringBuilder, label: String, v: Any?) {
        val t = v.str()
        if (t.isBlank()) return
        sb.append("<div class=\"label\">").append(esc(label)).append("</div><div>").append(esc(t)).append("</div>")
    }

    private fun listSection(sb: StringBuilder, label: String, items: List<Any?>, ordered: Boolean) {
        if (items.isEmpty()) return
        val tag = if (ordered) "ol" else "ul"
        sb.append("<div class=\"label\">").append(esc(label)).append("</div><$tag>")
        items.forEach { sb.append("<li>").append(e(it)).append("</li>") }
        sb.append("</$tag>")
    }

    private fun cardList(sb: StringBuilder, title: String, items: List<Any?>, ordered: Boolean) {
        if (items.isEmpty()) return
        val tag = if (ordered) "ol" else "ul"
        sb.append("<div class=\"card\"><h2>").append(esc(title)).append("</h2><$tag>")
        items.forEach { sb.append("<li>").append(e(it)).append("</li>") }
        sb.append("</$tag></div>")
    }

    private fun readingTable(sb: StringBuilder, rows: List<Any?>) {
        sb.append("<table>")
        rows.forEach { r0 -> val r = r0.obj()
            sb.append("<tr><td>").append(e(r["name"])).append("</td><td>").append(e(r["value"])).append(" ").append(e(r["unit"])).append("</td></tr>") }
        sb.append("</table>")
    }
}
