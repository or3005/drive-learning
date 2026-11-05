package com.or3005.drivelearning.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors content/schema.md in the repo. Unknown fields are ignored so the schema can grow. */

val ContentJson = Json { ignoreUnknownKeys = true }

@Serializable
data class Course(
    val id: String,
    val title: String,
    val language: String = "he",
    val modules: List<Module> = emptyList(),
    val concepts: List<Concept> = emptyList(),
    val units: List<UnitRef> = emptyList(),
)

@Serializable
data class Module(val id: String, val title: String)

@Serializable
data class Concept(
    val id: String,
    val module: String,
    val title: String,
    val summary: String,
    val prerequisites: List<String> = emptyList(),
    val dotnetBridge: String? = null,
)

@Serializable
data class UnitRef(val id: String, val concepts: List<String>, val status: String)

@Serializable
data class LearningUnit(
    val id: String,
    val concepts: List<String> = emptyList(),
    val title: String,
    /** "lesson" teaches something new; "review" goes back over [covers] with reminders and many questions. */
    val type: String = "lesson",
    val estimatedMinutes: Int = 15,
    val explanations: Explanations = Explanations(),
    val terms: List<Term> = emptyList(),
    val misconceptions: List<Misconception> = emptyList(),
    val questions: List<Question> = emptyList(),
    val recap: String = "",
    /** Two or three sentences that bring the unit back to mind, read before review questions on it. */
    val reminder: String = "",
    /** The whole unit told again in a different way, after the lesson and before the final questions. */
    val retell: String = "",
    /** Short questions kept for later units' warm-ups and for review units. */
    val reviewQuestions: List<Question> = emptyList(),
    /** Review units: the lessons they go back over. */
    val covers: List<String> = emptyList(),
    /** Review units: segments from other units to play first (concepts that were used before they were taught). */
    val fillIns: List<SegmentRef> = emptyList(),
) {
    val isReview: Boolean get() = type == "review"
}

@Serializable
data class SegmentRef(val unit: String, val segment: String)

@Serializable
data class Explanations(
    val main: List<Segment> = emptyList(),
    val simple: List<Segment> = emptyList(),
    val deep: List<Segment> = emptyList(),
)

@Serializable
data class Segment(
    val id: String,
    val title: String = "",
    val text: String,
    /** A short question right after this segment, to check it was understood before moving on. */
    val check: Question? = null,
)

@Serializable
data class Term(val term: String, val definition: String)

@Serializable
data class Misconception(val id: String, val concept: String, val belief: String, val remedial: String)

@Serializable
data class Question(
    val id: String,
    val concept: String,
    val level: String,
    val segments: List<String> = emptyList(),
    val prompt: String,
    val hint: String = "",
    val options: List<Option> = emptyList(),
    val explanation: String = "",
    val rubric: List<String> = emptyList(),
    val minPoints: Int = 0,
) {
    val isExplain: Boolean get() = level == "explain"
    val isCheck: Boolean get() = level == "check"
}

@Serializable
data class Option(
    val text: String,
    val correct: Boolean = false,
    val misconception: String? = null,
    val feedback: String? = null,
)

/** content/app-config.json */
@Serializable
data class AppConfig(
    /** Client id of the GitHub OAuth app used to sign in with a code (device flow). Public, not a secret. */
    val githubClientId: String = "",
)
