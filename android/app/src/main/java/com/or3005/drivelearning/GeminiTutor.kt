package com.or3005.drivelearning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Live help from Gemini during the drive: grades an explain-back answer right away, and answers a
 * question asked out loud. The credential stays on the phone. Without one, or without network, the
 * app works as before.
 *
 * Two ways in:
 *  - Vertex AI (Gemini Enterprise Agent Platform) with a service account JSON key. This is the way the
 *    Google AI Pro monthly Cloud credit ($10) pays for Gemini: Vertex is billed to the regular Cloud
 *    billing account, where the credit sits.
 *  - An API key from Google AI Studio. Free tier (Flash), or a paid AI Studio project.
 *
 * To never pay beyond the credit, every call's real token use is priced and added up per month: Pro
 * models until [Limits.proCap], then Flash until [Limits.hardCap], then live help stops until next month.
 */
class GeminiTutor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** AI Studio API key. */
    val key: String get() = prefs.getString(KEY_API, "").orEmpty()

    /** Vertex AI service account key file (JSON). */
    private val serviceAccount: String get() = prefs.getString(KEY_SA, "").orEmpty()

    val configured: Boolean get() = key.isNotBlank() || serviceAccount.isNotBlank()

    val usesVertex: Boolean get() = serviceAccount.isNotBlank()

    /** The Cloud project of the service account, for the screen. */
    val project: String get() = runCatching { JSONObject(serviceAccount).optString("project_id") }.getOrDefault("")

    /** The model used last. */
    val model: String get() = prefs.getString(KEY_LAST_MODEL, "").orEmpty()

    /**
     * Saves an AI Studio key or a Vertex service account file (whichever [raw] is), replacing the other.
     * Returns null when saved, or what is wrong with it.
     */
    fun saveCredential(raw: String): String? {
        val text = raw.trim()
        val edit = prefs.edit()
        if (text.startsWith("{")) {
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return "הקובץ לא נקרא כ-JSON."
            if (json.optString("type") != "service_account" || json.optString("private_key").isBlank() ||
                json.optString("client_email").isBlank() || json.optString("project_id").isBlank()
            ) return "זה לא קובץ מפתח של חשבון שירות (service account) מ-Google Cloud."
            edit.putString(KEY_SA, text).remove(KEY_API)
        } else {
            if (text.length < 20 || text.any { it.isWhitespace() }) return "זה לא נראה כמו מפתח של Gemini."
            edit.putString(KEY_API, text).remove(KEY_SA)
        }
        token = ""
        edit.remove(KEY_MODEL + "pro").remove(KEY_MODEL + "flash").remove(KEY_LAST_MODEL).remove(KEY_BAD)
            .remove(proBlockedKey()).apply()
        return null
    }

    /** Monthly spending limits in dollars, token prices per million (input, output) by model family, and Vertex model names to try. */
    data class Limits(
        val proCap: Double = 7.0,
        val hardCap: Double = 9.0,
        val usePro: Boolean = true,
        val prices: Map<String, List<Double>> = DEFAULT_PRICES,
        val vertexModels: Map<String, List<String>> = DEFAULT_VERTEX_MODELS,
    )

    var limits = Limits()

    /** Estimated dollars spent this month, from the token counts Gemini reports. */
    val spentThisMonth: Double get() = prefs.getFloat(spentKey(), 0f).toDouble()

    data class Grade(val covered: List<Int>, val feedback: String)

    /** Checks the credential with a tiny request. Returns the model that answered. */
    suspend fun check(preferred: String = ""): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(TIMEOUT_MS) {
                generate("Reply with one Hebrew word: מוכן", json = false, preferred = preferred)
                model
            }
        }
    }

    /** Pro while the month's spending is low (and Pro is open to this credential), then Flash, then nothing. */
    private fun tier(): String {
        val spent = spentThisMonth
        return when {
            spent >= limits.hardCap -> error("הגעת לתקרה החודשית של Gemini. העזרה החיה חוזרת בחודש הבא.")
            limits.usePro && spent < limits.proCap && !prefs.getBoolean(proBlockedKey(), false) -> "pro"
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
                val json = JSONObject(stripFence(generate(request, json = true, preferred = preferred)))
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

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    /**
     * Sends [text] to the best model allowed now. A model that is gone is skipped for the next one; Pro
     * refused for quota falls to Flash.
     */
    private fun generate(text: String, json: Boolean, preferred: String): String {
        var tier = tier()
        var last: HttpError? = null
        for (attempt in 0 until 6) {
            var model = ""
            try {
                model = pickModel(preferred, tier)
                prefs.edit().putString(KEY_LAST_MODEL, model).apply()
                return call(model, text, json)
            } catch (e: HttpError) {
                last = e
                when {
                    e.code == 404 && preferred.isBlank() && model.isNotBlank() -> markBad(model, tier)
                    e.code == 429 && tier == "pro" && !isPrepay(e.body) -> {
                        // Pro has no quota for this credential (free tier), or is busy: Flash this time.
                        if ("limit: 0" in e.body) prefs.edit().putBoolean(proBlockedKey(), true).apply()
                        tier = "flash"
                    }
                    else -> error(explain(e))
                }
            }
        }
        error(last?.let { explain(it) } ?: "Gemini לא זמין כרגע.")
    }

    private fun call(model: String, text: String, json: Boolean): String {
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
        fun send(thinking: Boolean): String {
            if (!usesVertex) return request("POST", "$STUDIO/models/${model.removePrefix("models/")}:generateContent", body(thinking))
            val path = "projects/$project/locations/global/publishers/google/models/$model:generateContent"
            return try {
                request("POST", "$VERTEX/v1/$path", body(thinking))
            } catch (e: HttpError) {
                if (e.code != 404) throw e
                request("POST", "$VERTEX/v1beta1/$path", body(thinking)) // preview models may live only here
            }
        }
        val response = try {
            send(thinking = true)
        } catch (e: HttpError) {
            if (e.code == 400 && "thinking" in e.body.lowercase()) send(thinking = false) else throw e
        }
        val root = JSONObject(response)
        record(model, root.optJSONObject("usageMetadata"))
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) error("Gemini לא החזיר תשובה.")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: error("Gemini לא החזיר תשובה.")
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

    private fun month() = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
    private fun spentKey() = "gemini_spent_" + month()
    private fun proBlockedKey() = "gemini_pro_blocked_" + month()

    private fun bad(): Set<String> = prefs.getStringSet(KEY_BAD, emptySet()).orEmpty()

    private fun markBad(model: String, tier: String) {
        prefs.edit().putStringSet(KEY_BAD, bad() + model).remove(KEY_MODEL + tier).apply()
    }

    /**
     * A model of the wanted tier for this credential: the one set in app-config, the one picked before,
     * or the best one available ("pro" or "flash"). Falls from Pro to Flash when no Pro model works.
     */
    private fun pickModel(preferred: String, tier: String): String {
        if (preferred.isNotBlank()) return preferred
        prefs.getString(KEY_MODEL + tier, null)?.takeIf { it.isNotBlank() && it !in bad() }?.let { return it }
        val chosen = if (usesVertex) {
            (limits.vertexModels[tier] ?: DEFAULT_VERTEX_MODELS.getValue(tier)).firstOrNull { it !in bad() }
        } else {
            studioModels(tier).firstOrNull { it !in bad() }
        }
        if (chosen == null) {
            if (tier == "pro") return pickModel(preferred, "flash")
            error("לא נמצא מודל Gemini זמין.")
        }
        prefs.edit().putString(KEY_MODEL + tier, chosen).apply()
        return chosen
    }

    /** The models this AI Studio key can use, best first. */
    private fun studioModels(tier: String): List<String> {
        val list = JSONObject(request("GET", "$STUDIO/models?pageSize=200", null)).optJSONArray("models") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { n -> n.startsWith("gemini") && listOf("tts", "image", "live", "audio", "embedding", "vision", "omni").none { it in n } }
            .filter { n -> if (tier == "pro") "pro" in n else "flash" in n }
            .sortedWith(
                compareBy<String> { "exp" in it }                    // experimental last
                    .thenBy { "lite" in it }                         // full model over Lite
                    .thenByDescending { version(it) }                // the newest
                    .thenBy { "preview" in it },                     // stable over preview of the same version
            )
    }

    private fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    // ---- Vertex sign-in with the service account ------------------------------------------------------

    @Volatile private var token = ""
    @Volatile private var tokenExpiry = 0L

    /** A short-lived access token, from a JWT signed with the service account's private key. */
    private fun accessToken(): String {
        if (token.isNotBlank() && System.currentTimeMillis() < tokenExpiry - 60_000) return token
        val sa = JSONObject(serviceAccount)
        val tokenUri = sa.optString("token_uri").ifBlank { "https://oauth2.googleapis.com/token" }
        val now = System.currentTimeMillis() / 1000
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = b64(JSONObject().put("alg", "RS256").put("typ", "JWT").toString().toByteArray())
        val claims = b64(
            JSONObject()
                .put("iss", sa.getString("client_email"))
                .put("scope", "https://www.googleapis.com/auth/cloud-platform")
                .put("aud", tokenUri)
                .put("iat", now)
                .put("exp", now + 3600)
                .toString().toByteArray(),
        )
        val pem = sa.getString("private_key")
            .replace(Regex("-----[A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("$header.$claims".toByteArray())
            sign()
        }
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=$header.$claims.${b64(signature)}"
        val conn = URL(tokenUri).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(form.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error(
                    if ("invalid_grant" in text) "קובץ המפתח של Vertex בוטל או לא תקין. צריך ליצור קובץ חדש."
                    else "הכניסה ל-Google Cloud נכשלה ($code).",
                )
            }
            val json = JSONObject(text)
            token = json.getString("access_token")
            tokenExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
            return token
        } finally {
            conn.disconnect()
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------------

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            if (usesVertex) conn.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            else conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) token = ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun isPrepay(body: String): Boolean = body.lowercase().let { "prepay" in it || "prepaid" in it }

    /** What went wrong, in Hebrew, with what to do. Never includes the credential. */
    private fun explain(e: HttpError): String {
        val body = e.body
        val status = runCatching { JSONObject(body).getJSONObject("error").optString("status") }.getOrDefault("")
        val lower = body.lowercase()
        return when {
            isPrepay(body) ->
                "בפרויקט של AI Studio אין יתרה בתשלום מראש, והקרדיט של AI Pro לא עובד שם. משתמשים בקובץ JSON של Vertex AI."
            "billing" in lower ->
                "בפרויקט אין חיוב פעיל. צריך לקשר אותו לחשבון החיוב שקיבל את הקרדיט החודשי."
            "service_disabled" in lower || "has not been used" in lower || "it is disabled" in lower ->
                if (usesVertex) "צריך להפעיל את Vertex AI API בפרויקט $project." else "צריך להפעיל את Gemini API בפרויקט של המפתח."
            e.code == 403 && usesVertex ->
                "לחשבון השירות אין הרשאה. צריך לתת לו את התפקיד Vertex AI User בפרויקט $project."
            e.code in listOf(400, 401, 403) && !usesVertex && ("API_KEY" in body || "API key" in body) ->
                "מפתח ה-Gemini לא תקין."
            e.code == 404 -> "המודל לא נמצא (404)."
            e.code == 429 -> "Gemini עמוס או שהמכסה נגמרה לבינתיים (429)."
            else -> "Gemini החזיר ${e.code}" + (if (status.isBlank()) "." else " ($status).")
        }
    }

    /** Some models wrap JSON in a code fence even when asked for JSON. */
    private fun stripFence(text: String): String =
        text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    /** Text for the voice: no markdown, no list marks, no quotes. */
    private fun clean(text: String): String =
        text.replace(Regex("[*_#`\"]"), "").replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ").trim()

    companion object {
        /**
         * Live help is switched off for now (the learner's choice, 2026-11-05): no calls to Gemini, and its
         * section is hidden from the screen. All the code stays here, compiled and ready. To bring it
         * back, set this to true and release a new build.
         */
        const val LIVE_HELP = false

        private const val KEY_API = "gemini_key"
        private const val KEY_SA = "gemini_vertex_sa"
        private const val KEY_MODEL = "gemini_model_"
        private const val KEY_LAST_MODEL = "gemini_last_model"
        private const val KEY_BAD = "gemini_bad_models"
        /** Dollars per million tokens, input and output; on the high side, so the estimate never runs short. */
        val DEFAULT_PRICES = mapOf(
            "pro" to listOf(2.5, 15.0),
            "flash" to listOf(1.5, 9.0),
            "flash-lite" to listOf(0.3, 2.5),
        )
        /** Vertex has no model list for a service account; these are tried in order (app-config.json can replace them). */
        val DEFAULT_VERTEX_MODELS = mapOf(
            "pro" to listOf("gemini-3.1-pro-preview", "gemini-3-pro-preview"),
            "flash" to listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-3-flash-preview"),
        )
        private const val STUDIO = "https://generativelanguage.googleapis.com/v1beta"
        private const val VERTEX = "https://aiplatform.googleapis.com"
        private const val TIMEOUT_MS = 30_000L
        private const val SYSTEM =
            "You are a patient tutor for a junior software developer who listens while driving, through text-to-speech. " +
                "Write only in Hebrew, with technical terms in English in Latin script (Middleware, Event Loop). " +
                "Never use any other language or script. Write for the ear: short sentences, no lists, no markdown, " +
                "no code, no symbols, no URLs, no quotation marks. Be warm and direct. Be accurate; if unsure, say so."
    }
}
