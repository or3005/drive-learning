package com.or3005.drivelearning.audio

import android.content.Context
import com.or3005.drivelearning.engine.Sentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Recordings of a course's lessons, made by tools/tts_render.py with a good voice: one MP3 per
 * sentence, named by a hash of the sentence, packed in one zip per unit on the release
 * audio-<course>. The app downloads the packs of the next lessons ahead of the drive. When the
 * speaker reaches a sentence that has a file, it plays the file; anything else (a new sentence,
 * a pack not downloaded yet) falls back to the phone's voice.
 */
class AudioLibrary(context: Context) {

    private val root = File(context.filesDir, "audio")

    @Volatile private var index: Map<String, File> = emptyMap()
    @Volatile private var loadedCourse = ""

    /** How far the downloads got: packs ready out of the ones wanted, and their size. */
    data class Status(val ready: Int, val wanted: Int, val megabytes: Double, val error: String = "")

    /** The recording of [sentence], if there is one for the loaded course. */
    fun fileFor(sentence: String): File? = index[hash(sentence)]

    val hasRecordings: Boolean get() = index.isNotEmpty()

    /** Loads what is already on the phone for [courseId] (fast, no network). */
    fun load(courseId: String) {
        val dir = File(root, courseId)
        index = dir.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }
            ?.flatMap { pack -> pack.listFiles()?.filter { it.name.endsWith(".mp3") }.orEmpty() }
            ?.associateBy { it.name.removeSuffix(".mp3") }
            .orEmpty()
        loadedCourse = courseId
    }

    fun clear() {
        index = emptyMap()
        loadedCourse = ""
    }

    /**
     * Downloads the packs in [wanted] (unit ids, plus "common" for the app's own phrases) that are new
     * or changed, and deletes packs that are no longer wanted. Returns where it got to.
     */
    suspend fun sync(courseId: String, wanted: List<String>): Status = withContext(Dispatchers.IO) {
        val dir = File(root, courseId).apply { mkdirs() }
        val stateFile = File(dir, "state.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        var error = ""
        val manifest = runCatching { JSONObject(get("audio-$courseId/manifest.json").decodeToString()) }
            .onFailure { error = it.message ?: "no manifest" }
            .getOrNull()
        val units = manifest?.optJSONObject("units")
        if (units != null) {
            for (name in wanted) {
                val entry = units.optJSONObject(name) ?: continue
                val sha = entry.optString("sha")
                val target = File(dir, name)
                if (state.optString(name) == sha && target.isDirectory) continue
                runCatching {
                    val tmp = File(dir, "$name.tmp").apply { deleteRecursively(); mkdirs() }
                    ZipInputStream(get("audio-$courseId/${entry.getString("file")}").inputStream()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val file = File(tmp, File(e.name).name) // flat: no paths from the zip
                            if (!e.isDirectory && file.name.endsWith(".mp3")) file.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    target.deleteRecursively()
                    check(tmp.renameTo(target)) { "rename failed" }
                    state.put(name, sha)
                }.onFailure { error = it.message ?: "download failed" }
            }
        }
        // Packs no longer wanted: lessons long finished. Their sentences fall back to the phone's voice.
        if (manifest != null) {
            dir.listFiles()?.filter { it.isDirectory && it.name !in wanted }?.forEach {
                it.deleteRecursively()
                state.remove(it.name)
            }
        }
        runCatching { stateFile.writeText(state.toString()) }
        if (loadedCourse == courseId) load(courseId)
        val ready = wanted.count { File(dir, it).isDirectory }
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Status(ready, wanted.count { units?.has(it) ?: true }, bytes / 1e6, error)
    }

    private fun get(path: String): ByteArray {
        val conn = URL("$RELEASES/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val RELEASES = "https://github.com/or3005/drive-learning/releases/download"
        private val whitespace = Regex("[ \\t\\n\\x0B\\f\\r]+")

        /** Must match tools/tts_render.py: collapse whitespace, SHA-1 of the UTF-8 text, first 16 hex digits. */
        fun hash(sentence: String): String {
            val normal = sentence.replace(whitespace, " ").trim()
            val digest = MessageDigest.getInstance("SHA-1").digest(normal.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** The sentences [text] is spoken as, the same way the speaker splits it. */
        fun sentences(text: String): List<String> = Sentences.split(text)
    }
}
