package com.nalpakd.obdscanner.data

import com.nalpakd.obdscanner.core.ClaudePrompt
import com.nalpakd.obdscanner.core.Json
import com.nalpakd.obdscanner.core.ReportHtml
import java.net.HttpURLConnection
import java.net.URL

/** Sends a saved scan to the Claude Messages API and turns the answer into the friendly HTML report. */
object ClaudeClient {

    class Result(val analysisJson: String, val html: String)

    private fun post(apiKey: String, body: String, readTimeoutMs: Int): Pair<Int, String> {
        val conn = URL(ClaudePrompt.API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", ClaudePrompt.API_VERSION)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking. Throws ClaudePrompt.ApiException with a readable message on failure. */
    fun analyze(apiKey: String, model: String, scanJson: String): Result {
        if (apiKey.isBlank()) throw ClaudePrompt.ApiException("No Claude API key set. Add one in Settings.")
        val (code, text) = post(apiKey, ClaudePrompt.requestBody(model, scanJson), 240_000)
        if (code == 429) throw ClaudePrompt.ApiException("Claude is rate-limiting requests. Wait a minute and try again.")
        if (code == 529) throw ClaudePrompt.ApiException("Claude is overloaded right now. Try again in a few minutes.")
        val answer = ClaudePrompt.extractText(text)
        val analysis = ClaudePrompt.parseAnalysis(answer)
        val scan = Json.parse(scanJson)
        @Suppress("UNCHECKED_CAST")
        val html = ReportHtml.render(analysis, (scan as? Map<String, Any?>) ?: emptyMap())
        return Result(Json.write(analysis), html)
    }

    /** Cheap request used by Settings > Test key. Returns a short status message. */
    fun testKey(apiKey: String, model: String): String {
        val body = Json.write(
            linkedMapOf(
                "model" to model, "max_tokens" to 20,
                "messages" to listOf(linkedMapOf("role" to "user", "content" to "Reply with the single word OK."))
            ), pretty = false
        )
        val (code, text) = post(apiKey, body, 60_000)
        return if (code in 200..299) "Key works with $model: ${ClaudePrompt.extractText(text).trim()}"
        else try { ClaudePrompt.extractText(text) } catch (e: Exception) { "HTTP $code: ${e.message}" }
    }
}
