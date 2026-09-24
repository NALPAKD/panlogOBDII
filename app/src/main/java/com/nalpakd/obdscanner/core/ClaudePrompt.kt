package com.nalpakd.obdscanner.core

import com.nalpakd.obdscanner.core.Json.list
import com.nalpakd.obdscanner.core.Json.obj
import com.nalpakd.obdscanner.core.Json.str

/** Builds the Claude Messages API request and parses Claude's answer. Pure Kotlin (no Android). */
object ClaudePrompt {

    const val API_URL = "https://api.anthropic.com/v1/messages"
    const val API_VERSION = "2023-06-01"
    const val DEFAULT_MODEL = "claude-sonnet-5"
    val MODELS = listOf(
        "claude-sonnet-5" to "Claude Sonnet 5 (recommended)",
        "claude-opus-5-5" to "Claude Opus 5.5 (most thorough)",
        "claude-haiku-4-5-20251001" to "Claude Haiku 4.5 (fastest, cheapest)"
    )

    val SYSTEM = """
You are an ASE-certified master automotive technician who is also great at explaining things to people who are not mechanics.
You will receive a full OBD-II scan captured by a Panlong ELM327 Bluetooth adapter as JSON: trouble codes (stored, pending, permanent),
emissions readiness monitors, freeze-frame data, a live sensor snapshot, repeated samples of key sensors (min/avg/max),
Mode 06 on-board test results and vehicle identification. There may also be symptoms typed in by the owner.

Your job:
1. Diagnose. Correlate the codes with the live data (fuel trims, O2 sensors, MAF, coolant temp, voltage, misfire and Mode 06 results)
   to rank the most likely root causes, not just restate code definitions. Use the VIN and vehicle notes for model-specific pattern failures.
2. Notice problems even when there are no codes (e.g. high long-term fuel trims, low charging voltage, coolant not reaching temperature,
   failed Mode 06 tests, incomplete monitors that would fail a smog/emissions inspection).
3. Explain everything in plain, friendly English at an 8th-grade reading level. Define any jargon the first time you use it.
4. Be honest about uncertainty. Never invent readings that are not in the data. If data is missing, say what extra test would confirm the cause.
5. Give realistic US cost ranges (parts for DIY, and parts+labor at an independent shop) in USD.

Respond with ONLY a single JSON object (no markdown fences, no text before or after) using exactly this schema:
{
  "vehicle_description": "string - year/make/model/engine as best identified",
  "overall_status": "good" | "attention" | "urgent",
  "headline": "string - one short sentence verdict",
  "summary": "string - 2 to 4 sentence plain-English overview",
  "safe_to_drive": "yes" | "caution" | "no",
  "safe_to_drive_explanation": "string",
  "issues": [
    {
      "title": "string - plain-English name of the problem",
      "codes": ["P0171"],
      "severity": "low" | "medium" | "high" | "critical",
      "what_it_means": "string - what the car is telling you, in plain words",
      "why_it_matters": "string - what happens if ignored",
      "evidence": "string - which readings support this diagnosis",
      "likely_causes": [ { "cause": "string", "likelihood": "most likely" | "possible" | "less likely" } ],
      "what_to_do": ["string - ordered, practical steps starting with cheapest/easiest checks"],
      "diy_difficulty": "easy" | "moderate" | "hard" | "shop recommended",
      "estimated_cost": "string - e.g. \"DIY ${'$'}20-${'$'}60, Shop ${'$'}150-${'$'}300\"",
      "urgency": "string - e.g. \"Fix within the next few weeks\""
    }
  ],
  "readiness": {
    "inspection_ready": true,
    "summary": "string - would it pass an emissions/smog check today and why",
    "incomplete_monitors": ["string"]
  },
  "sensor_observations": [
    { "item": "string", "reading": "string", "normal_range": "string", "status": "normal" | "watch" | "abnormal", "comment": "string" }
  ],
  "maintenance_tips": ["string"],
  "questions_for_mechanic": ["string"],
  "next_steps": ["string - short prioritized action list"],
  "confidence_note": "string - how confident you are and what would increase confidence"
}
Order issues from most to least important. If there are no problems, return an empty issues array and say so warmly in the summary.
Include 6-12 of the most informative sensor_observations (use the units given in the data).
""".trim()

    fun userMessage(scanJson: String): String {
        val profileId = try { Json.parse(scanJson).obj()["vehicle_profile_id"].str() } catch (e: Exception) { "" }
        val profile = VehicleProfiles.byId(profileId)
        return buildString {
            appendLine("Vehicle notes: ${profile.aiNotes}")
            appendLine()
            appendLine("Here is the full scan JSON:")
            appendLine(scanJson)
        }
    }

    fun requestBody(model: String, scanJson: String, maxTokens: Int = 8000): String = Json.write(
        linkedMapOf(
            "model" to model,
            "max_tokens" to maxTokens,
            "system" to SYSTEM,
            "messages" to listOf(linkedMapOf("role" to "user", "content" to userMessage(scanJson)))
        ), pretty = false
    )

    class ApiException(msg: String) : Exception(msg)

    /** Extracts the assistant text from a Messages API response body (or throws with the API's error message). */
    fun extractText(responseBody: String): String {
        val root = try { Json.parse(responseBody).obj() } catch (e: Exception) {
            throw ApiException("Unexpected response from Claude: ${responseBody.take(300)}")
        }
        if (root["type"].str() == "error") {
            val err = root["error"].obj()
            throw ApiException("Claude API error (${err["type"].str()}): ${err["message"].str()}")
        }
        val text = root["content"].list().map { it.obj() }.filter { it["type"].str() == "text" }
            .joinToString("") { it["text"].str() }
        if (text.isBlank()) throw ApiException("Claude returned an empty answer (stop_reason=${root["stop_reason"].str()})")
        return text
    }

    /** Parses Claude's JSON analysis, tolerating code fences or stray text around the object. */
    fun parseAnalysis(text: String): Map<String, Any?> {
        var t = text.trim()
        if (t.startsWith("```")) t = t.substringAfter('\n').substringBeforeLast("```")
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException("Claude's answer did not contain a JSON report")
        return try {
            Json.parse(t.substring(start, end + 1)).obj()
        } catch (e: Json.ParseException) {
            throw ApiException("Could not read Claude's report (${e.message}). Try again or pick a different model.")
        }
    }
}
