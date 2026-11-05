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
 * Live help from Gemini during the drive, with the learner's own free API key (Google AI Studio,
 * no billing): grades an explain-back answer right away, and answers a question asked out loud.
 * The key stays on the phone. Without a key, or without network, the app works as before.
 */
class GeminiTutor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).remove(KEY_MODEL).apply()

    val configured: Boolean get() = key.isNotBlank()

    /** The model in use, picked from the models this key can use (or set in app-config.json). */
    val model: String get() = prefs.getString(KEY_MODEL, "").orEmpty()

    data class Grade(val covered: List<Int>, val feedback: String)

    /** Checks the key and picks a model. Returns the model name. */
    suspend fun check(preferred: String = ""): Result<String> = withContext(Dispatchers.IO) {
        runCatching { pickModel(preferred) }
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
        val model = pickModel(preferred)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.4).apply { if (json) put("responseMimeType", "application/json") },
            )
        val response = request("POST", "$BASE/models/${model.removePrefix("models/")}:generateContent", body.toString())
        val parts = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    /** A Flash model this key can use: the one set in app-config, the one picked before, or the newest stable Flash. */
    private fun pickModel(preferred: String): String {
        if (preferred.isNotBlank()) return preferred
        model.takeIf { it.isNotBlank() }?.let { return it }
        val list = JSONObject(request("GET", "$BASE/models?pageSize=200", null)).optJSONArray("models") ?: JSONArray()
        val names = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { n -> "flash" in n && listOf("tts", "image", "live", "audio", "embedding", "thinking").none { it in n } }
        val ranked = names.sortedWith(
            compareBy<String> { "preview" in it || "exp" in it }  // stable first
                .thenBy { "lite" in it }                         // then full Flash over Flash-Lite
                .thenByDescending { version(it) },               // then the newest
        )
        val chosen = ranked.firstOrNull() ?: error("לא נמצא מודל Flash זמין למפתח הזה.")
        prefs.edit().putString(KEY_MODEL, chosen).apply()
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
            check(code in 200..299) { explain(code, text) }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String = when (code) {
        400, 401, 403 -> if ("API_KEY" in body || "API key" in body) "מפתח ה-Gemini לא תקין." else "Gemini סירב לבקשה ($code)."
        404 -> {
            prefs.edit().remove(KEY_MODEL).apply() // the model may have been retired: pick again next time
            "המודל לא נמצא (404)."
        }
        429 -> "נגמרה המכסה החינמית של Gemini לבינתיים (429)."
        else -> "Gemini החזיר $code."
    }

    /** Text for the voice: no markdown, no list marks, no quotes. */
    private fun clean(text: String): String =
        text.replace(Regex("[*_#`\"]"), "").replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val KEY_API = "gemini_key"
        private const val KEY_MODEL = "gemini_model"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val TIMEOUT_MS = 30_000L
        private const val SYSTEM =
            "You are a patient tutor for a junior software developer who listens while driving, through text-to-speech. " +
                "Write only in Hebrew, with technical terms in English in Latin script (Middleware, Event Loop). " +
                "Never use any other language or script. Write for the ear: short sentences, no lists, no markdown, " +
                "no code, no symbols, no URLs, no quotation marks. Be warm and direct. Be accurate; if unsure, say so."
    }
}
