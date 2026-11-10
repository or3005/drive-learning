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
    /** Courses recorded with a good voice (tools/tts_render.py). Without it, the phone's voice reads everything. */
    val voice: CourseVoice? = null,
    /** How much going back over earlier units the course wants. Without it: the full practice of a technical course. */
    val practice: CoursePractice = CoursePractice(),
)

/**
 * A technical course wants spaced repetition; a course read for interest wants less. [warmUpQuestions]
 * before a lesson (0: just a short reminder of the previous lesson), [spacedReview] questions at the start
 * of a drive, and [reviewUnits] (for the content tools: whether to add review units every few lessons).
 */
@Serializable
data class CoursePractice(
    val warmUpQuestions: Int = 3,
    val spacedReview: Boolean = true,
    val reviewUnits: Boolean = true,
    /** How many spaced-review questions open a drive, at most (when [spacedReview] is on). */
    val reviewQuestions: Int = 4,
)

/** How a course is recorded. The app only needs to know that it is; the details are for the recording tool. */
@Serializable
data class CourseVoice(
    val engine: String = "edge",
    val reading: String = "",
    val explain: String = "",
    val rate: String = "",
    val style: String = "",
    /** The course's own pronunciation list, next to course.json. Without it the shared list is used. */
    val lexicon: String = "",
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
    /** A passage from the book this segment explains, read before [text] (book courses). */
    val reading: String = "",
    val text: String,
    /** A short question right after this segment, to check it was understood before moving on. */
    val check: Question? = null,
) {
    /** What is spoken for this segment: the book passage, if any, then the explanation. */
    val spoken: String get() = if (reading.isBlank()) text else reading.trim() + "\n" + text
}

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
    /** Gemini model for live help. Empty: the app picks the best Pro (or Flash) model the credential can use. */
    val geminiModel: String = "",
    /** Use Pro models while the month's estimated spend is below [geminiProCapUsd]; then Flash. */
    val geminiUsePro: Boolean = true,
    val geminiProCapUsd: Double = 7.0,
    /** Live help stops for the month at this estimated spend, below the $10 monthly credit. */
    val geminiHardCapUsd: Double = 9.0,
    /** Dollars per million tokens by model family: [input, output]. Empty: the app's built-in prices. */
    val geminiPrices: Map<String, List<Double>> = emptyMap(),
    /** Vertex AI model names to try, best first, by tier ("pro", "flash"). Empty: the app's built-in list. */
    val geminiVertexModels: Map<String, List<String>> = emptyMap(),
)

/** content/courses/index.json: the courses the learner can choose from. */
@Serializable
data class CourseIndex(val courses: List<CourseEntry> = emptyList())

@Serializable
data class CourseEntry(val id: String, val title: String, val status: String = "active")

/** content/courses/<id>/feedback.json: feedback on explain-back answers, written after the drive. */
@Serializable
data class FeedbackFile(val items: List<FeedbackItem> = emptyList())

@Serializable
data class FeedbackItem(
    val id: String,
    val unitId: String = "",
    val questionId: String = "",
    /** Spoken at the start of the next drive in this course. */
    val text: String,
)
