package com.nalpakd.obdscanner.core

import com.nalpakd.obdscanner.core.Json.list
import com.nalpakd.obdscanner.core.Json.obj
import com.nalpakd.obdscanner.core.Json.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    @Test
    fun dtcDecode() {
        assertEquals("P0171", DtcDecoder.decode(0x01, 0x71))
        assertEquals("C0035", DtcDecoder.decode(0x40, 0x35))
        assertEquals("B1234", DtcDecoder.decode(0x92, 0x34))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        assertEquals(null, DtcDecoder.decode(0, 0))
    }

    @Test
    fun dtcParseCanAndLegacy() {
        // CAN, count byte
        assertEquals(listOf("P0171", "P0174"), DtcDecoder.parse(ElmParser.messages("430201710174\r\r>"), 0x43))
        // CAN, no codes
        assertEquals(emptyList<String>(), DtcDecoder.parse(ElmParser.messages("4300\r>"), 0x43))
        // J1850 / ISO: 3 codes per line, zero padded, multiple lines
        val legacy = "43 01 71 01 74 04 42\r43 03 00 00 00 00 00\r\r>"
        assertEquals(listOf("P0171", "P0174", "P0442", "P0300"), DtcDecoder.parse(ElmParser.messages(legacy), 0x43))
        // CAN multi-frame with 4 codes
        val mf = "00A\r0:430401710174\r1:04420300000000\r\r>"
        assertEquals(listOf("P0171", "P0174", "P0442", "P0300"), DtcDecoder.parse(ElmParser.messages(mf), 0x43))
    }

    @Test
    fun elmCleaning() {
        val raw = "0100\rSEARCHING...\r4100BE3FA813\r\r>"
        val msgs = ElmParser.messages(raw, "0100")
        assertEquals(listOf("4100BE3FA813"), msgs)
        assertEquals("NO DATA", ElmParser.errorOf("NO DATA\r\r>"))
        val sup = ElmParser.supportedFromMask(0, ElmParser.dataAfter(msgs, "4100")!!)
        assertTrue(0x01 in sup && 0x0C in sup && 0x20 in sup && 0x02 !in sup)
    }

    @Test
    fun pidDecoding() {
        assertEquals(1000.0, Pids.decode(0x0C, intArrayOf(0x0F, 0xA0))!!.value!!, 0.01)
        assertEquals(90.0, Pids.decode(0x05, intArrayOf(130))!!.value!!, 0.01)
        assertEquals(194.0, Pids.decode(0x05, intArrayOf(130))!!.displayValue(true)!!, 0.01)
        assertEquals(17.97, Pids.decode(0x07, intArrayOf(151))!!.value!!, 0.01)
        assertEquals("Closed loop (normal)", Pids.decode(0x03, intArrayOf(2, 0))!!.text)
    }

    @Test
    fun monitors() {
        val m = MonitorDecoder.decode(intArrayOf(0x82, 0x07, 0xE5, 0x04))!!
        assertTrue(m.milOn)
        assertEquals(2, m.dtcCount)
        assertEquals("Not complete", m.monitors["Evaporative (EVAP) system"])
        assertEquals("Complete", m.monitors["Catalyst"])
        assertEquals(1, m.incompleteCount)
    }

    @Test
    fun jsonRoundTrip() {
        val src = linkedMapOf("a" to "he said \"hi\"\n", "b" to listOf(1, 2.5, true, null), "c" to linkedMapOf("x" to -3.25))
        val back = Json.parse(Json.write(src)).obj()
        assertEquals("he said \"hi\"\n", back["a"])
        assertEquals(2.5, back["b"].list()[1])
        assertEquals(-3.25, back["c"].obj()["x"])
    }

    @Test
    fun fullDemoScanAndReport() {
        val scanner = ObdScanner(DemoTransport(kotlin.random.Random(42)))
        val info = scanner.initialize()
        assertEquals("A6", info.protocolNumber)
        val report = scanner.fullScan(VehicleProfiles.TRAILBLAZER_2007, imperial = true, samplePasses = 2)
        assertEquals("1GNDT13S472234567", report.vehicle.vin)
        assertEquals(listOf("P0171", "P0174"), report.storedDtcs.map { it.code })
        assertEquals(listOf("P0442"), report.pendingDtcs.map { it.code })
        assertEquals("P0171", report.freezeFrameDtc)
        assertTrue(report.freezeFrame.isNotEmpty())
        assertTrue(report.snapshot.size > 20)
        assertTrue(report.samples.isNotEmpty())
        assertEquals(listOf("12639856"), report.vehicle.calibrationIds)
        assertEquals(listOf("1A2B3C4D"), report.vehicle.cvns)
        assertTrue(report.vehicle.ecuNames.first().startsWith("ECM"))
        assertTrue(report.mode6.size >= 5)
        assertTrue(report.mode6.all { it.passed })

        val json = report.toJson()
        val parsed = Json.parse(json).obj()
        assertEquals("trailblazer2007", parsed["vehicle_profile_id"])
        assertTrue(TextReport.render(report).contains("P0171"))

        val body = Json.parse(ClaudePrompt.requestBody("claude-sonnet-5", json)).obj()
        assertEquals("claude-sonnet-5", body["model"])
        assertTrue(body["messages"].list()[0].obj()["content"].str().contains("GMT360"))

        // Simulated Claude reply wrapped in a code fence
        val fakeReply = """{"content":[{"type":"text","text":"```json\n{\"overall_status\":\"attention\",\"headline\":\"Engine running lean\",\"summary\":\"Air leak likely.\",\"safe_to_drive\":\"caution\",\"issues\":[{\"title\":\"Lean <both banks>\",\"codes\":[\"P0171\",\"P0174\"],\"severity\":\"medium\",\"likely_causes\":[{\"cause\":\"Vacuum leak\",\"likelihood\":\"most likely\"}],\"what_to_do\":[\"Smoke test\"]}],\"readiness\":{\"inspection_ready\":false,\"summary\":\"EVAP not done\",\"incomplete_monitors\":[\"EVAP\"]}}\n```"}],"stop_reason":"end_turn"}"""
        val analysis = ClaudePrompt.parseAnalysis(ClaudePrompt.extractText(fakeReply))
        val html = ReportHtml.render(analysis, parsed)
        assertTrue(html.contains("Lean &lt;both banks&gt;"))
        assertTrue(html.contains("NEEDS ATTENTION"))
        assertTrue(html.contains("1GNDT13S472234567"))
        assertNotNull(html)
    }

    @Test
    fun apiErrorSurfaced() {
        try {
            ClaudePrompt.extractText("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
            throw AssertionError("expected exception")
        } catch (e: ClaudePrompt.ApiException) {
            assertTrue(e.message!!.contains("invalid x-api-key"))
        }
    }
}
