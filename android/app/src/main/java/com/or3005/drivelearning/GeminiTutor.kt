package com.or3005.drivelearning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live help from Gemini during the drive: grades an explain-back answer right away, and answers a
 * question asked out loud. The key stays on the phone. Without a key, or without network, the app
 * works as before.
 *
 * With a Google AI Pro subscription the key comes from a Cloud project whose monthly Developer
 * Program credit ($10) pays for the API. To never pay beyond it, every call's real token use is
 * priced and added up per month: Pro models until [Limits.proCap], then Flash until
 * [Limits.hardCap], then live help stops until next month.
 */
class GeminiTutor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim())
            .remove(KEY_MODEL + "pro").remove(KEY_MODEL + "flash").remove(KEY_LAST_MODEL).apply()

    val configured: Boolean get() = key.isNotBlank()

    /** The model used last, picked from the models this key can use (or set in app-config.json). */
    val model: String get() = prefs.getString(KEY_LAST_MODEL, "").orEmpty()

    /** Monthly spending limits in dollars, and token prices per million (input, output) by model family. */
    data class Limits(
        val proCap: Double = 7.0,
        val hardCap: Double = 9.0,
        val usePro: Boolean = true,
        val prices: Map<String, List<Double>> = DEFAULT_PRICES,
    )

    var limits = Limits()

    /** Estimated dollars spent this month, from the token counts Gemini reports. */
    val spentThisMonth: Double get() = prefs.getFloat(spentKey(), 0f).toDouble()

    data class Grade(val covered: List<Int>, val feedback: String)

    /** Checks the key and picks a model. Returns the model name. */
    suspend fun check(preferred: String = ""): Result<String> = withContext(Dispatchers.IO) {
        runCatching { pickModel(preferred, tier()).also { prefs.edit().putString(KEY_LAST_MODEL, it).apply() } }
    }

    /** Pro while the month's spending is low, then Flash, then nothing. */
    private fun tier(): String {
        val spent = spentThisMonth
        return when {
            spent >= limits.hardCap -> error("הגעת לתקרה החודשית של Gemini. העזרה החיה חוזרת בחודש הבא.")
            limits.usePro && spent < limits.proCap -> "pro"
            else -> "flash"
        }
    }

    suspend fun gradeExplain(
        prompt: String,
        rubric: List<String>,
        lesson: String,
        transcript: String,
        preferred: String = "",
    ): Result<Grade> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(TIMEOUT_MS) {
                val points = rubric.mapIndexed { i, p -> "$i. $p" }.joinToString("\n")
                val request = """
                    The learner was asked, out loud: $prompt
                    The key points a good answer mentions (numbered from 0):
                    $points
                    The lesson he heard, for reference:
                    $lesson
                    His spoken answer, from speech recognition (English terms are often misspelled in Hebrew letters; judge the ideas, not the spelling):
                    $transcript

                    Return JSON: {"covered": [numbers of the key points his answer really covers], "feedback": "..."}.
                    feedback: 3 to 5 short spoken sentences in Hebrew. First what he got right, specifically. Then what was
                    missing or wrong, with the correct idea in one or two sentences. End with one short line that is easy to remember.
                """.trimIndent()
                val json = JSONObject(generate(request, json = true, preferred = preferred))
                val covered = json.optJSONArray("covered") ?: JSONArray()
                Grade(
                    covered = (0 until covered.length()).map { covered.getInt(it) }.filter { it in rubric.indices }.distinct(),
                    feedback = clean(json.optString("feedback")),
                ).also { check(it.feedback.isNotBlank()) { "empty feedback" } }
            }
        }
    }

    suspend fun answer(question: String, context: String, preferred: String = ""): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(TIMEOUT_MS) {
                val request = """
                    While listening to a lesson, the learner asked out loud: $question
                    What he is learning now:
                    $context

                    Answer in Hebrew in 3 to 6 short spoken sentences. If the question is unclear because of speech
                    recognition, answer the most likely question. If it goes beyond the lesson, answer briefly and say
                    it will come later in the course.
                """.trimIndent()
                clean(generate(request, json = false, preferred = preferred)).also { check(it.isNotBlank()) { "empty answer" } }
            }
        }
    }

    // ---- API ------------------------------------------------------------------------------------------

    private fun generate(text: String, json: Boolean, preferred: String): String {
        val tier = tier()
        val model = pickModel(preferred, tier)
        prefs.edit().putString(KEY_LAST_MODEL, model).apply()
        fun body(thinking: Boolean): String {
            val config = JSONObject().put("temperature", 0.4)
            if (json) config.put("responseMimeType", "application/json")
            // Keep thinking short: thinking tokens are billed as output.
            if (thinking) {
                config.put(
                    "thinkingConfig",
                    if (version(model) >= 3) JSONObject().put("thinkingLevel", "low") else JSONObject().put("thinkingBudget", 1024),
                )
            }
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", config)
                .toString()
        }
        val url = "$BASE/models/${model.removePrefix("models/")}:generateContent"
        val response = try {
            request("POST", url, body(thinking = true))
        } catch (e: BadRequest) {
            request("POST", url, body(thinking = false)) // a model that doesn't take this thinking setting
        }
        val root = JSONObject(response)
        record(model, root.optJSONObject("usageMetadata"))
        val parts = root.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length())
            .map { parts.getJSONObject(it) }
            .filter { !it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
    }

    /** Adds this call's cost to the month, at the model family's price (thinking counts as output). */
    private fun record(model: String, usage: JSONObject?) {
        usage ?: return
        val input = usage.optLong("promptTokenCount")
        val output = usage.optLong("candidatesTokenCount") + usage.optLong("thoughtsTokenCount")
        val family = when {
            "flash-lite" in model -> "flash-lite"
            "flash" in model -> "flash"
            else -> "pro"
        }
        val price = limits.prices[family] ?: DEFAULT_PRICES.getValue("pro")
        val cost = input / 1e6 * price[0] + output / 1e6 * price[1]
        prefs.edit().putFloat(spentKey(), (spentThisMonth + cost).toFloat()).apply()
    }

    private fun spentKey() = "gemini_spent_" + java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())

    private class BadRequest(message: String) : Exception(message)

    /**
     * A model of the wanted tier that this key can use: the one set in app-config, the one picked
     * before, or the newest stable one ("pro" or "flash").
     */
    private fun pickModel(preferred: String, tier: String): String {
        if (preferred.isNotBlank()) return preferred
        prefs.getString(KEY_MODEL + tier, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val list = JSONObject(request("GET", "$BASE/models?pageSize=200", null)).optJSONArray("models") ?: JSONArray()
        val names = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { n -> n.startsWith("gemini") && listOf("tts", "image", "live", "audio", "embedding", "vision").none { it in n } }
            .filter { n -> if (tier == "pro") "pro" in n else "flash" in n }
        val ranked = names.sortedWith(
            compareBy<String> { "exp" in it }                    // experimental last
                .thenBy { "lite" in it }                         // full model over Lite
                .thenByDescending { version(it) }                // the newest
                .thenBy { "preview" in it },                     // stable over preview of the same version
        )
        val chosen = ranked.firstOrNull()
            ?: if (tier == "pro") return pickModel(preferred, "flash") else error("לא נמצא מודל Gemini זמין למפתח הזה.")
        prefs.edit().putString(KEY_MODEL + tier, chosen).apply()
        return chosen
    }

    private fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 400 && "thinking" in text.lowercase()) throw BadRequest(text)
            check(code in 200..299) { explain(code, text) }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String = when (code) {
        400, 401, 403 -> if ("API_KEY" in body || "API key" in body) "מפתח ה-Gemini לא תקין." else "Gemini סירב לבקשה ($code)."
        404 -> {
            // The model may have been retired: pick again next time.
            prefs.edit().remove(KEY_MODEL + "pro").remove(KEY_MODEL + "flash").apply()
            "המודל לא נמצא (404)."
        }
        429 -> "Gemini עמוס או שהמכסה נגמרה לבינתיים (429)."
        else -> "Gemini החזיר $code."
    }

    /** Text for the voice: no markdown, no list marks, no quotes. */
    private fun clean(text: String): String =
        text.replace(Regex("[*_#`\"]"), "").replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val KEY_API = "gemini_key"
        private const val KEY_MODEL = "gemini_model_"
        private const val KEY_LAST_MODEL = "gemini_last_model"
        /** Dollars per million tokens, input and output; on the high side, so the estimate never runs short. */
        val DEFAULT_PRICES = mapOf(
            "pro" to listOf(2.5, 15.0),
            "flash" to listOf(1.5, 9.0),
            "flash-lite" to listOf(0.3, 2.5),
        )
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val TIMEOUT_MS = 30_000L
        private const val SYSTEM =
            "You are a patient tutor for a junior software developer who listens while driving, through text-to-speech. " +
                "Write only in Hebrew, with technical terms in English in Latin script (Middleware, Event Loop). " +
                "Never use any other language or script. Write for the ear: short sentences, no lists, no markdown, " +
                "no code, no symbols, no URLs, no quotation marks. Be warm and direct. Be accurate; if unsure, say so."
    }
}
