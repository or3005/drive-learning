package com.or3005.drivelearning.content

import android.content.Context
import com.or3005.drivelearning.engine.Pronunciation
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

    data class Loaded(val course: Course, val units: List<LearningUnit>, val feedback: List<FeedbackItem> = emptyList())

    private val syncedRoot = File(context.filesDir, "content")

    init {
        // Content downloaded by an older version of the app would hide the newer content bundled in
        // this APK, so a new install starts from the bundled content again.
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (prefs.getLong(KEY_CONTENT_APP_VERSION, -1L) != version) {
            syncedRoot.deleteRecursively()
            prefs.edit().putLong(KEY_CONTENT_APP_VERSION, version).apply()
        }
    }

    /** The courses to choose from. Falls back to the first course if the index is missing. */
    fun courses(): List<CourseEntry> =
        runCatching { ContentJson.decodeFromString<CourseIndex>(read("courses/index.json")).courses }
            .getOrDefault(emptyList())
            .filter { it.status != "hidden" }
            .ifEmpty { listOf(CourseEntry(DEFAULT_COURSE, "Node.js ו-Backend")) }

    fun load(courseId: String = DEFAULT_COURSE): Loaded {
        val course = ContentJson.decodeFromString<Course>(read("courses/$courseId/course.json"))
        val units = course.units
            .filter { it.status != "planned" }
            .mapNotNull { ref ->
                runCatching {
                    ContentJson.decodeFromString<LearningUnit>(read("courses/$courseId/units/${ref.id}.json"))
                }.getOrNull()
            }
        val feedback = runCatching {
            ContentJson.decodeFromString<FeedbackFile>(read("courses/$courseId/feedback.json")).items
        }.getOrDefault(emptyList())
        return Loaded(course, units, feedback)
    }

    /** Settings that can change without a new APK, like the GitHub sign-in client id. */
    fun loadConfig(): AppConfig =
        runCatching { ContentJson.decodeFromString<AppConfig>(read("app-config.json")) }.getOrDefault(AppConfig())

    /**
     * How the voice should say names, English terms and tricky Hebrew words: the course's own list
     * if it has one (a book's words differ from a technical course's: שרת, לקוח), the shared one otherwise.
     */
    fun loadPronunciation(course: Course? = null): Pronunciation {
        val lexicon = course?.voice?.lexicon.orEmpty()
        val path = if (lexicon.isNotBlank()) "courses/${course!!.id}/$lexicon" else "pronunciation.json"
        return runCatching { Pronunciation.parse(read(path)) }.getOrDefault(Pronunciation.NONE)
    }

    private fun read(path: String): String {
        val synced = File(syncedRoot, path)
        if (synced.exists()) return synced.readText()
        return context.assets.open("content/$path").bufferedReader().use { it.readText() }
    }

    /** Downloads every course, its written units and feedback, and the shared files. Returns how many units were saved. */
    suspend fun sync(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val indexText = runCatching { download("content/courses/index.json") }.getOrNull()
            val ids = indexText?.let { ContentJson.decodeFromString<CourseIndex>(it).courses.map { c -> c.id } }
                ?: listOf(DEFAULT_COURSE)
            var count = 0
            ids.forEach { courseId -> count += syncCourse(courseId) }
            indexText?.let { File(syncedRoot, "courses").mkdirs(); File(syncedRoot, "courses/index.json").writeText(it) }
            runCatching {
                val config = download("content/app-config.json")
                ContentJson.decodeFromString<AppConfig>(config)
                File(syncedRoot, "app-config.json").writeText(config)
            }
            runCatching {
                val pronunciation = download("content/pronunciation.json")
                Pronunciation.parse(pronunciation) // only save it if it parses
                File(syncedRoot, "pronunciation.json").writeText(pronunciation)
            }
            count
        }
    }

    private fun syncCourse(courseId: String): Int {
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
        course.voice?.lexicon?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching {
                val text = download("content/courses/$courseId/$name")
                Pronunciation.parse(text) // only save it if it parses
                File(syncedRoot, "courses/$courseId/$name").writeText(text)
            }
        }
        runCatching {
            val feedback = download("content/courses/$courseId/feedback.json")
            ContentJson.decodeFromString<FeedbackFile>(feedback)
            File(syncedRoot, "courses/$courseId/feedback.json").writeText(feedback)
        }
        return texts.size
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
        private const val KEY_CONTENT_APP_VERSION = "content_app_version"
        const val RAW_BASE = "https://raw.githubusercontent.com/or3005/drive-learning/main"
    }
}
