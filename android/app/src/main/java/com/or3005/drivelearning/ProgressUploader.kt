package com.or3005.drivelearning

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uploads each course's progress file to progress/<course>.json in the repo, so the after-drive
 * runs can read it. Uses a fine-grained GitHub token with Contents read and write on this repo only.
 */
class ProgressUploader(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            // A token pasted by hand: no refresh token, no expiry.
            prefs.edit().putString(KEY_TOKEN, value.trim()).remove(KEY_REFRESH).remove(KEY_EXPIRES).commit()
        }

    /** Saves the tokens from signing in, with what's needed to renew them later. */
    fun saveTokens(tokens: GitHubLogin.Tokens, clientId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, tokens.access)
            .putString(KEY_REFRESH, tokens.refresh)
            .putLong(KEY_EXPIRES, tokens.accessExpiresAt)
            .putString(KEY_CLIENT, clientId)
            .commit() // a used refresh token is gone, so the new one must be on disk before anything else
    }

    private val refreshToken: String get() = prefs.getString(KEY_REFRESH, "").orEmpty()
    private val expiresAt: Long get() = prefs.getLong(KEY_EXPIRES, 0)
    private val refreshLock = Mutex()

    /** Renews the access token with the refresh token. False when there's nothing to renew with. */
    private suspend fun renew(): Boolean = refreshLock.withLock {
        val refresh = refreshToken
        val clientId = prefs.getString(KEY_CLIENT, "").orEmpty()
        if (refresh.isBlank() || clientId.isBlank()) return false
        GitHubLogin(clientId).refresh(refresh)
            .onSuccess { saveTokens(it, clientId) }
            .isSuccess
    }

    /** Renews ahead of time when the access token is about to expire. */
    private suspend fun renewIfDue() {
        val at = expiresAt
        if (at > 0 && System.currentTimeMillis() > at - 10 * 60_000) renew()
    }

    val lastUpload: String get() = prefs.getString(KEY_LAST, "").orEmpty()

    val configured: Boolean get() = token.isNotBlank()

    /**
     * Uploads [progressFile] to [remotePath] in the repo, for example progress/nodejs-backend.json. An
     * expired access token is renewed on the way, so signing in once is enough.
     */
    suspend fun upload(progressFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        renewIfDue()
        val first = put(progressFile, remotePath)
        if (first.exceptionOrNull() is Unauthorized && renew()) put(progressFile, remotePath) else first
    }

    private class Unauthorized(message: String) : Exception(message)

    private fun put(progressFile: File, remotePath: String): Result<Unit> =
        runCatching {
            check(configured) { "אין טוקן של GitHub" }
            check(progressFile.exists()) { "אין עדיין התקדמות לשלוח" }
            val url = API_BASE + remotePath
            val sha = currentSha(url)
            val body = JSONObject()
                .put("message", "progress: " + stamp())
                .put("content", Base64.encodeToString(progressFile.readBytes(), Base64.NO_WRAP))
                .apply { if (sha != null) put("sha", sha) }
            val conn = open("PUT", url)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val error = if (code >= 400) conn.errorStream?.bufferedReader()?.use { it.readText() } else null
            conn.disconnect()
            if (code == 401) throw Unauthorized(explain(code, error))
            check(code in 200..299) { explain(code, error) }
            prefs.edit().putString(KEY_LAST, stamp()).apply()
        }

    /** What a failed upload means, in words the learner can act on. */
    private fun explain(code: Int, body: String?): String = when (code) {
        401 -> "החיבור ל-GitHub פג (401). צריך להתחבר פעם אחת מחדש."
        403 -> "אין הרשאת כתיבה (403). כדאי להתחבר מחדש ל-GitHub."
        404 -> "אין גישה ל-repo (404). כדאי להתחבר מחדש ל-GitHub."
        409, 422 -> "הקובץ השתנה בינתיים ($code). ננסה שוב בשליחה הבאה."
        else -> "GitHub החזיר $code ${body?.take(100).orEmpty()}"
    }

    private fun currentSha(url: String): String? {
        val conn = open("GET", url)
        return try {
            if (conn.responseCode == 200) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("sha").ifBlank { null }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LAST = "last_upload"
        private const val KEY_REFRESH = "github_refresh_token"
        private const val KEY_EXPIRES = "github_token_expires_at"
        private const val KEY_CLIENT = "github_client_id"
        private const val API_BASE = "https://api.github.com/repos/or3005/drive-learning/contents/"
    }
}
