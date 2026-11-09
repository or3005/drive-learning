package com.or3005.drivelearning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub sign-in with the device flow: the app shows a short code, the learner approves it once on
 * github.com, and the app gets a token for uploading progress. No token to copy, no secret in the app:
 * the OAuth app's client id is public.
 */
class GitHubLogin(private val clientId: String) {

    data class Code(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    suspend fun requestCode(): Result<Code> = withContext(Dispatchers.IO) {
        runCatching {
            val json = post("https://github.com/login/device/code", "client_id" to clientId, "scope" to "public_repo")
            json.optString("error").takeIf { it.isNotBlank() }?.let { error(describe(json)) }
            Code(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                intervalSeconds = json.optInt("interval", 5),
                expiresInSeconds = json.optInt("expires_in", 900),
            )
        }
    }

    /**
     * What GitHub gives after sign-in. The access token may expire after 8 hours; then there is a
     * refresh token (6 months, renewed on every use) that gets a new one without signing in again.
     */
    data class Tokens(
        val access: String,
        val refresh: String = "",
        /** When [access] stops working, in epoch millis; 0 when it doesn't expire. */
        val accessExpiresAt: Long = 0,
        val refreshExpiresAt: Long = 0,
    )

    /** Waits until the code is approved on github.com, and returns the tokens. */
    suspend fun waitForToken(code: Code): Result<Tokens> = withContext(Dispatchers.IO) {
        runCatching {
            var interval = code.intervalSeconds
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var tokens: Tokens? = null
            while (tokens == null) {
                check(System.currentTimeMillis() < deadline) { "הקוד פג תוקף. לחץ שוב על התחברות." }
                delay(interval * 1000L)
                val json = post(
                    "https://github.com/login/oauth/access_token",
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                )
                tokens = parseTokens(json)
                if (tokens == null) {
                    when (json.optString("error")) {
                        "authorization_pending" -> Unit
                        "slow_down" -> interval = json.optInt("interval", interval + 5)
                        else -> error(describe(json))
                    }
                }
            }
            tokens
        }
    }

    /**
     * Gets a new access token with the refresh token. For tokens from the device flow GitHub doesn't need
     * the client secret, so the app can do this by itself. The old refresh token stops working.
     */
    suspend fun refresh(refreshToken: String): Result<Tokens> = withContext(Dispatchers.IO) {
        runCatching {
            val json = post(
                "https://github.com/login/oauth/access_token",
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            )
            parseTokens(json) ?: error(describe(json))
        }
    }

    private fun parseTokens(json: JSONObject): Tokens? {
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        fun at(field: String) = json.optLong(field, 0).let { if (it > 0) now + it * 1000 else 0L }
        return Tokens(access, json.optString("refresh_token"), at("expires_in"), at("refresh_token_expires_in"))
    }

    private fun describe(json: JSONObject): String = when (json.optString("error")) {
        "expired_token" -> "הקוד פג תוקף. לחץ שוב על התחברות."
        "access_denied" -> "ההתחברות בוטלה ב-GitHub."
        "device_flow_disabled" -> "צריך לסמן Enable Device Flow בהגדרות ה-OAuth App ב-GitHub."
        "incorrect_client_credentials", "unauthorized_client" -> "ה-Client ID לא נכון."
        "bad_refresh_token" -> "החיבור ל-GitHub פג. צריך להתחבר פעם אחת מחדש."
        else -> json.optString("error_description").ifBlank { json.optString("error") }
    }

    private fun post(url: String, vararg fields: Pair<String, String>): JSONObject {
        val body = fields.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { error("GitHub החזיר ${conn.responseCode}") }
        } finally {
            conn.disconnect()
        }
    }
}
