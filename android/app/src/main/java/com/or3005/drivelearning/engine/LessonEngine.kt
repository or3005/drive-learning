package com.or3005.drivelearning.engine

import com.or3005.drivelearning.content.LearningUnit
import com.or3005.drivelearning.content.Question
import com.or3005.drivelearning.content.Term
import java.time.LocalDate

/** One thing the player does next. The controller speaks Say steps and listens on Ask steps. */
sealed interface Step {
    val unitId: String?

    data class Say(
        val text: String,
        override val unitId: String? = null,
        val segmentIndex: Int? = null,
        val label: String = "",
    ) : Step

    data class Ask(override val unitId: String, val question: Question, val repeatOk: Boolean = false) : Step
    data class AskConfidence(override val unitId: String, val question: Question, val choice: Int) : Step
    data class AskReplay(override val unitId: String, val question: Question) : Step
    data class UnitDone(override val unitId: String) : Step
    data object End : Step {
        override val unitId: String? = null
    }
}

/**
 * The teaching logic, with no Android code so it can be unit tested.
 *
 * A drive is a queue of steps: a short review of concepts that are due, then the next units in
 * order. Answers insert follow-up steps at the front of the queue: the confidence question,
 * feedback, the remedial for a misconception, an offer to replay the lesson segment, and a
 * different question on the same concept. Wrong or unsure questions come back after the unit.
 */
class LessonEngine(
    private val units: List<LearningUnit>,
    private val store: ProgressStore,
    private val today: LocalDate,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    var progress: ProgressState = store.load()
        private set

    var current: Step = Step.End
        private set

    private val queue = ArrayDeque<Step>()
    private val asked = mutableSetOf<String>()
    private val retryLater = ArrayDeque<Pair<String, Question>>()
    private val unitById = units.associateBy { it.id }

    val totalUnits: Int get() = units.size
    val masteredCount: Int get() = progress.concepts.count { it.value.mastered }

    fun unitNumber(unitId: String?): Int = units.indexOfFirst { it.id == unitId } + 1
    fun unit(unitId: String?): LearningUnit? = unitId?.let { unitById[it] }

    fun start(): Step {
        queue.clear()
        asked.clear()
        retryLater.clear()
        planDrive()
        return advance()
    }

    /** Moves to the next step, doing bookkeeping steps on the way. */
    fun advance(): Step {
        while (true) {
            val next = queue.removeFirstOrNull() ?: Step.End
            when {
                next is Step.UnitDone -> completeUnit(next.unitId)
                next is Step.Ask && !next.repeatOk && key(next.unitId, next.question) in asked -> Unit // asked earlier as a follow-up
                else -> {
                    if (next is Step.Ask) asked += key(next.unitId, next.question)
                    current = next
                    return next
                }
            }
        }
    }

    // ---- answers ----------------------------------------------------------------------------

    /** A multiple-choice answer, 1-based. */
    fun answer(choice: Int): Boolean {
        val ask = current as? Step.Ask ?: return false
        val q = ask.question
        if (q.isExplain || choice !in 1..q.options.size) return false
        queue.addFirst(Step.AskConfidence(ask.unitId, q, choice))
        advance()
        return true
    }

    fun confidence(sure: Boolean): Boolean {
        val step = current as? Step.AskConfidence ?: return false
        val q = step.question
        val option = q.options[step.choice - 1]
        record(step.unitId, q, step.choice, option.correct, sure, option.misconception)

        val follow = mutableListOf<Step>()
        if (option.correct) {
            if (sure) {
                follow += Step.Say(q.explanation, step.unitId, label = "משוב")
            } else {
                follow += Step.Say(
                    q.explanation + " בגלל שלא היית בטוח, נחזור לזה עוד מעט.",
                    step.unitId, label = "משוב",
                )
                retryLater.addLast(step.unitId to q)
            }
        } else {
            val unit = unitById[step.unitId]
            val remedial = option.misconception
                ?.let { id -> unit?.misconceptions?.firstOrNull { it.id == id }?.remedial }
                ?: option.feedback
                ?: ""
            follow += Step.Say("לא בדיוק. $remedial", step.unitId, label = "הסבר על הטעות")
            val right = q.options.indexOfFirst { it.correct }
            if (right >= 0) {
                follow += Step.Say(
                    "התשובה הנכונה היא ${HebrewNumbers.word(right + 1)}: ${q.options[right].text}",
                    step.unitId, label = "התשובה הנכונה",
                )
            }
            follow += Step.AskReplay(step.unitId, q)
            retryLater.addLast(step.unitId to q)
        }
        queue.addAll(0, follow)
        advance()
        return true
    }

    /** Answer to ״רוצה לשמוע שוב את החלק הזה?״ */
    fun replayAnswer(yes: Boolean): Boolean {
        val step = current as? Step.AskReplay ?: return false
        val follow = mutableListOf<Step>()
        if (yes) follow += segmentSteps(step.unitId, step.question.segments)
        alternativeQuestion(step.unitId, step.question)?.let { alt ->
            follow += Step.Say("עכשיו שאלה נוספת על אותו נושא.", step.unitId)
            follow += Step.Ask(step.unitId, alt)
        }
        queue.addAll(0, follow)
        advance()
        return true
    }

    /** A spoken explain-back answer. Graded later (Gemini after the drive, Claude at night). */
    fun explain(transcript: String): Boolean {
        val ask = current as? Step.Ask ?: return false
        val q = ask.question
        if (!q.isExplain) return false
        if (transcript.isNotBlank()) {
            progress = progress.copy(
                explainAnswers = progress.explainAnswers +
                    ExplainAnswer(q.id, ask.unitId, transcript, today.toString(), now()),
            )
            store.save(progress)
        }
        val intro = if (transcript.isBlank()) {
            "לא שמעתי הסבר, אז נעבור על הנקודות ביחד."
        } else {
            "שמרתי את ההסבר שלך. הוא ייבדק בהפסקה, ותקבל עליו משוב בנסיעה הבאה."
        }
        val points = q.rubric.joinToString(". ")
        queue.addFirst(Step.Say("$intro אלה הנקודות שהיה חשוב להזכיר: $points.", ask.unitId, label = "נקודות חשובות"))
        advance()
        return true
    }

    fun saveLiveQuestion(text: String) {
        progress = progress.copy(
            liveQuestions = progress.liveQuestions + LiveQuestion(text, current.unitId, today.toString(), now()),
        )
        store.save(progress)
    }

    // ---- navigation commands --------------------------------------------------------------

    /** Plays the previous segment of the current unit, then the current one again. */
    fun back(): Step {
        val say = current as? Step.Say
        val unit = unit(say?.unitId)
        val index = say?.segmentIndex
        if (say != null && unit != null && index != null && index > 0) {
            queue.addFirst(say)
            queue.addFirst(segmentStep(unit, index - 1))
            return advance()
        }
        return current
    }

    fun skip(): Step = advance()

    /** Plays [steps] now. Afterwards, a question is asked again; a lesson segment continues. */
    fun insertNow(steps: List<Step>, resumeCurrent: Boolean) {
        if (steps.isEmpty()) return
        if (resumeCurrent || current !is Step.Say) queue.addFirst(current)
        queue.addAll(0, steps)
        advance()
    }

    fun simplerSteps(): List<Step> {
        val unit = unit(current.unitId) ?: return emptyList()
        return unit.explanations.simple.map { Step.Say(it.text, unit.id, label = "הסבר אחר") }
    }

    fun deeperSteps(): List<Step> {
        val unit = unit(current.unitId) ?: return emptyList()
        return unit.explanations.deep.map { Step.Say(it.text, unit.id, label = "העמקה") }
    }

    fun hintSteps(): List<Step> {
        val q = currentQuestion() ?: return emptyList()
        if (q.hint.isBlank()) return emptyList()
        return listOf(Step.Say("רמז: ${q.hint}", current.unitId, label = "רמז"))
    }

    fun recapSteps(): List<Step> {
        val unit = unit(current.unitId) ?: units.lastOrNull { it.id in progress.completedUnits } ?: return emptyList()
        return listOf(Step.Say(unit.recap, unit.id, label = "סיכום"))
    }

    fun defineSteps(query: String): List<Step> {
        val term = findTerm(query)
            ?: return listOf(Step.Say("לא מצאתי את המונח הזה במילון.", current.unitId, label = "מילון"))
        return listOf(Step.Say("${term.term}: ${term.definition}", current.unitId, label = "מילון"))
    }

    fun currentQuestion(): Question? = when (val s = current) {
        is Step.Ask -> s.question
        is Step.AskConfidence -> s.question
        is Step.AskReplay -> s.question
        else -> null
    }

    /** Terms from the current unit first, then from earlier units. Longest match wins. */
    fun findTerm(query: String): Term? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        val currentIndex = units.indexOfFirst { it.id == current.unitId }.let { if (it < 0) units.lastIndex else it }
        val ordered = (currentIndex downTo 0).flatMap { units[it].terms }
        return ordered
            .filter { t -> q.contains(t.term.lowercase()) || t.term.lowercase().contains(q) || q.contains(gloss(t)) }
            .maxByOrNull { it.term.length }
    }

    /** The Hebrew word before the first period in a definition, e.g. ״לקוח״ for Client. */
    private fun gloss(t: Term): String {
        val first = t.definition.substringBefore('.').trim()
        return if (first.isNotEmpty() && first.split(' ').size <= 2) first else "\u0000"
    }

    // ---- planning -----------------------------------------------------------------------------

    private fun planDrive() {
        val review = reviewQuestions(max = 6)
        if (review.isNotEmpty()) {
            queue += Step.Say("לפני שממשיכים, חזרה קצרה על דברים מנסיעות קודמות.")
            review.forEach { (unitId, q) -> queue += Step.Ask(unitId, q, repeatOk = true) }
        }
        val remaining = units.filter { it.id !in progress.completedUnits }
        if (remaining.isEmpty()) {
            queue += Step.Say(
                "סיימת את כל היחידות שמוכנות כרגע. יחידות חדשות יגיעו אחרי העדכון הבא. " +
                    "אפשר להגיד חזרה על יחידה, או סיכום.",
            )
            return
        }
        remaining.forEach { addUnit(it) }
        queue += Step.Say("זה הכול לבינתיים. כל הכבוד על ההתמדה.")
    }

    private fun addUnit(unit: LearningUnit) {
        queue += Step.Say("יחידה ${unitNumber(unit.id)}: ${unit.title}.", unit.id, label = "פתיחה")
        unit.explanations.main.indices.forEach { queue += segmentStep(unit, it) }
        unit.questions.forEach { queue += Step.Ask(unit.id, it) }
        if (unit.recap.isNotBlank()) queue += Step.Say(unit.recap, unit.id, label = "סיכום")
        queue += Step.UnitDone(unit.id)
    }

    /** Replays a finished unit from the start. */
    fun replayUnit(number: Int): Boolean {
        val unit = units.getOrNull(number - 1) ?: return false
        val steps = mutableListOf<Step>(Step.Say("חוזרים ליחידה $number: ${unit.title}.", unit.id, label = "פתיחה"))
        unit.explanations.main.indices.forEach { steps += segmentStep(unit, it) }
        unit.questions.filter { !it.isExplain }.forEach { steps += Step.Ask(unit.id, it, repeatOk = true) }
        if (unit.recap.isNotBlank()) steps += Step.Say(unit.recap, unit.id, label = "סיכום")
        queue.addAll(0, steps)
        advance()
        return true
    }

    private fun reviewQuestions(max: Int): List<Pair<String, Question>> {
        val due = Scheduler.dueConcepts(progress, today)
        return due.mapNotNull { concept ->
            val unit = units.firstOrNull { concept in it.concepts && it.id in progress.completedUnits }
                ?: return@mapNotNull null
            val candidates = unit.questions.filter { it.concept == concept && !it.isExplain }
            // Prefer the question answered correctly the fewest times.
            val q = candidates.minByOrNull { c ->
                progress.attempts.count { it.questionId == c.id && it.unitId == unit.id && it.correct && it.sure }
            } ?: return@mapNotNull null
            unit.id to q
        }.take(max)
    }

    private fun completeUnit(unitId: String) {
        progress = progress.copy(completedUnits = progress.completedUnits + unitId)
        store.save(progress)
        val retries = mutableListOf<Step>()
        repeat(2) {
            val (uid, q) = retryLater.removeFirstOrNull() ?: return@repeat
            if (retries.isEmpty()) retries += Step.Say("בוא נחזור לשאלה שהייתה קשה קודם.", uid, label = "חזרה")
            retries += Step.Ask(uid, q, repeatOk = true)
        }
        queue.addAll(0, retries)
    }

    private fun record(unitId: String, q: Question, choice: Int, correct: Boolean, sure: Boolean, misconception: String?) {
        val attempt = Attempt(q.id, unitId, q.concept, q.level, choice, correct, sure, today.toString(), now(), misconception)
        val attempts = progress.attempts + attempt
        val updated = Scheduler.update(progress.concepts[q.concept] ?: ConceptProgress(), correct, sure, today)
            .let { it.copy(mastered = Scheduler.isMastered(attempts, q.concept)) }
        progress = progress.copy(attempts = attempts, concepts = progress.concepts + (q.concept to updated))
        store.save(progress)
    }

    private fun alternativeQuestion(unitId: String, q: Question): Question? {
        val unit = unitById[unitId] ?: return null
        return unit.questions.firstOrNull {
            it.id != q.id && it.concept == q.concept && !it.isExplain && key(unitId, it) !in asked
        }
    }

    private fun segmentSteps(unitId: String, segmentIds: List<String>): List<Step> {
        val unit = unitById[unitId] ?: return emptyList()
        return segmentIds.mapNotNull { id ->
            unit.explanations.main.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { segmentStep(unit, it) }
        }
    }

    private fun segmentStep(unit: LearningUnit, index: Int): Step.Say {
        val seg = unit.explanations.main[index]
        return Step.Say(seg.text, unit.id, segmentIndex = index, label = seg.title)
    }

    private fun key(unitId: String, q: Question) = "$unitId/${q.id}"
}
