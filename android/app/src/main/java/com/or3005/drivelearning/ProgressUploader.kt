package com.or3005.drivelearning

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uploads progress.json to progress/progress.json in the repo, so the after-drive and nightly
 * runs can read it. Uses a fine-grained GitHub token with Contents read and write on this repo only.
 */
class ProgressUploader(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    val lastUpload: String get() = prefs.getString(KEY_LAST, "").orEmpty()

    val configured: Boolean get() = token.isNotBlank()

    suspend fun upload(progressFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(configured) { "אין טוקן של GitHub" }
            check(progressFile.exists()) { "אין עדיין התקדמות לשלוח" }
            val sha = currentSha()
            val body = JSONObject()
                .put("message", "progress: " + stamp())
                .put("content", Base64.encodeToString(progressFile.readBytes(), Base64.NO_WRAP))
                .apply { if (sha != null) put("sha", sha) }
            val conn = open("PUT")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val error = if (code >= 400) conn.errorStream?.bufferedReader()?.use { it.readText() } else null
            conn.disconnect()
            check(code in 200..299) { "GitHub החזיר $code ${error?.take(120).orEmpty()}" }
            prefs.edit().putString(KEY_LAST, stamp()).apply()
        }
    }

    private fun currentSha(): String? {
        val conn = open("GET")
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

    private fun open(method: String): HttpURLConnection =
        (URL(API_URL).openConnection() as HttpURLConnection).apply {
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
        private const val API_URL = "https://api.github.com/repos/or3005/drive-learning/contents/progress/progress.json"
    }
}
