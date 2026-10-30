package com.or3005.drivelearning.content

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads course content. Content synced from GitHub (in filesDir) wins over the copy bundled
 * in the APK assets, so new units arrive without installing a new APK.
 */
class ContentRepository(private val context: Context) {

    data class Loaded(val course: Course, val units: List<LearningUnit>)

    private val syncedRoot = File(context.filesDir, "content")

    fun load(courseId: String = DEFAULT_COURSE): Loaded {
        val course = ContentJson.decodeFromString<Course>(read("courses/$courseId/course.json"))
        val units = course.units
            .filter { it.status != "planned" }
            .mapNotNull { ref ->
                runCatching {
                    ContentJson.decodeFromString<LearningUnit>(read("courses/$courseId/units/${ref.id}.json"))
                }.getOrNull()
            }
        return Loaded(course, units)
    }

    private fun read(path: String): String {
        val synced = File(syncedRoot, path)
        if (synced.exists()) return synced.readText()
        return context.assets.open("content/$path").bufferedReader().use { it.readText() }
    }

    /** Downloads the course map and every written unit. Returns how many units were saved. */
    suspend fun sync(courseId: String = DEFAULT_COURSE): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val courseText = download("content/courses/$courseId/course.json")
            val course = ContentJson.decodeFromString<Course>(courseText)
            val texts = course.units.filter { it.status != "planned" }.associate { ref ->
                val text = download("content/courses/$courseId/units/${ref.id}.json")
                ContentJson.decodeFromString<LearningUnit>(text) // fail before writing anything broken
                ref.id to text
            }
            val unitsDir = File(syncedRoot, "courses/$courseId/units").apply { mkdirs() }
            texts.forEach { (id, text) -> File(unitsDir, "$id.json").writeText(text) }
            File(syncedRoot, "courses/$courseId/course.json").writeText(courseText)
            texts.size
        }
    }

    private fun download(path: String): String {
        val connection = URL("$RAW_BASE/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = connection.responseCode
            if (code != 200) error("HTTP $code for $path")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_COURSE = "nodejs-backend"
        const val RAW_BASE = "https://raw.githubusercontent.com/or3005/drive-learning/main"
    }
}
