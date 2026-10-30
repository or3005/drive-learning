package com.or3005.drivelearning.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

@Serializable
data class Attempt(
    val questionId: String,
    val unitId: String,
    val concept: String,
    val level: String,
    val choice: Int,
    val correct: Boolean,
    val sure: Boolean,
    val day: String,
    val time: Long,
    val misconception: String? = null,
)

@Serializable
data class ExplainAnswer(
    val questionId: String,
    val unitId: String,
    val transcript: String,
    val day: String,
    val time: Long,
)

@Serializable
data class LiveQuestion(val text: String, val unitId: String?, val day: String, val time: Long)

/** Leitner box and next review date for one concept. */
@Serializable
data class ConceptProgress(val box: Int = 0, val nextReview: String? = null, val mastered: Boolean = false)

@Serializable
data class ProgressState(
    val attempts: List<Attempt> = emptyList(),
    val concepts: Map<String, ConceptProgress> = emptyMap(),
    val completedUnits: Set<String> = emptySet(),
    val explainAnswers: List<ExplainAnswer> = emptyList(),
    val liveQuestions: List<LiveQuestion> = emptyList(),
)

interface ProgressStore {
    fun load(): ProgressState
    fun save(state: ProgressState)
}

class MemoryProgressStore(private var state: ProgressState = ProgressState()) : ProgressStore {
    override fun load() = state
    override fun save(state: ProgressState) {
        this.state = state
    }
}

class FileProgressStore(private val file: File) : ProgressStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun load(): ProgressState =
        runCatching { json.decodeFromString<ProgressState>(file.readText()) }.getOrDefault(ProgressState())

    override fun save(state: ProgressState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ProgressState.serializer(), state))
        tmp.renameTo(file)
    }
}

/** Spaced repetition and mastery rules from content/schema.md. */
object Scheduler {
    /** Days until the next review, by Leitner box. Box 0 means: review on the next drive. */
    private val intervals = listOf(0, 1, 2, 4, 8, 16, 32)

    fun update(current: ConceptProgress, correct: Boolean, sure: Boolean, today: LocalDate): ConceptProgress {
        val box = when {
            !correct -> 0
            sure -> (current.box + 1).coerceAtMost(intervals.lastIndex)
            else -> current.box.coerceAtLeast(1) // right but unsure: don't move up
        }
        return current.copy(box = box, nextReview = today.plusDays(intervals[box].toLong()).toString())
    }

    /**
     * Mastered = correct and sure on 3 occasions, on at least 2 different days, including an apply
     * question. (The schema also asks for a passed explain answer; that comes in once grading exists.)
     */
    fun isMastered(attempts: List<Attempt>, concept: String): Boolean {
        val good = attempts.filter { it.concept == concept && it.correct && it.sure }
        return good.size >= 3 && good.map { it.day }.distinct().size >= 2 && good.any { it.level == "apply" }
    }

    fun dueConcepts(state: ProgressState, today: LocalDate): List<String> =
        state.concepts
            .filter { (_, cp) -> cp.nextReview?.let { !LocalDate.parse(it).isAfter(today) } ?: false }
            .keys
            .sorted()
}
