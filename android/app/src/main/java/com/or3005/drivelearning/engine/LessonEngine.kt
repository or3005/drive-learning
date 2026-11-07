package com.or3005.drivelearning.engine

import com.or3005.drivelearning.content.FeedbackItem
import com.or3005.drivelearning.content.LearningUnit
import com.or3005.drivelearning.content.Question
import com.or3005.drivelearning.content.SegmentRef
import com.or3005.drivelearning.content.Term
import java.time.LocalDate
import kotlin.random.Random

/** One thing the player does next. The controller speaks Say steps and listens on Ask steps. */
sealed interface Step {
    val unitId: String?

    data class Say(
        val text: String,
        override val unitId: String? = null,
        val segmentIndex: Int? = null,
        val label: String = "",
    ) : Step

    /** [order] is the shuffled order of the options as read out: order[0] is the option read as ״one״. */
    data class Ask(
        override val unitId: String,
        val question: Question,
        val repeatOk: Boolean = false,
        val order: List<Int> = question.options.indices.toList(),
    ) : Step {
        val displayedOptions: List<String> get() = order.map { question.options[it].text }
    }

    /** [choice] is the 1-based index in the question's original option list. */
    data class AskConfidence(
        override val unitId: String,
        val question: Question,
        val choice: Int,
        val order: List<Int>,
    ) : Step
    data class AskReplay(override val unitId: String, val question: Question) : Step
    data class UnitDone(override val unitId: String) : Step

    /**
     * Planned work that is worked out only when it is reached, from the progress at that moment:
     * a warm-up before a lesson, the questions on one unit in a review, the summary of a review.
     * The engine expands these itself; the player never gets one from [LessonEngine.advance].
     */
    data class Plan(override val unitId: String, val kind: Kind, val covered: String? = null) : Step {
        enum class Kind { WARM_UP, REVIEW_BLOCK, REVIEW_BLOCK_DONE, REVIEW_SUMMARY, FEEDBACK_HEARD }
    }
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
    private val random: Random = Random.Default,
    /** Ask ״בטוח או לא בטוח?״ after each answer. Off: one tap less while driving; answers count as sure. */
    private val askConfidence: Boolean = false,
    /** Feedback on explain-back answers, written after earlier drives. Unheard items open the drive. */
    private val feedback: List<FeedbackItem> = emptyList(),
) {
    var progress: ProgressState = store.load().let { loaded ->
        if (loaded.schedulerVersion < Scheduler.VERSION) Scheduler.rebuild(loaded).also { store.save(it) } else loaded
    }
        private set

    var current: Step = Step.End
        private set

    private val queue = ArrayDeque<Step>()
    private val asked = mutableSetOf<String>()
    private val retryLater = ArrayDeque<Pair<String, Question>>()
    private val unitById = units.associateBy { it.id }
    private val lessons = units.filter { !it.isReview }

    /** Concepts that already had a warm-up or review question on this drive. */
    private val reviewedThisDrive = mutableSetOf<String>()
    /** When each review unit started on this drive, to summarize only its own answers. */
    private val reviewStarted = mutableMapOf<String, Long>()

    val totalUnits: Int get() = units.size
    val masteredCount: Int get() = progress.concepts.count { it.value.mastered }

    /** Lessons are numbered 1, 2, 3...; review units have no number (0). */
    fun unitNumber(unitId: String?): Int = lessons.indexOfFirst { it.id == unitId } + 1
    fun unit(unitId: String?): LearningUnit? = unitId?.let { unitById[it] }

    /** How the unit is named on screen and out loud: ״יחידה 3: ...״ or ״חזרה: ...״. */
    fun unitName(unitId: String?): String {
        val unit = unit(unitId) ?: return ""
        return if (unit.isReview) "חזרה: ${unit.title}" else "יחידה ${unitNumber(unit.id)}: ${unit.title}"
    }

    fun start(): Step {
        queue.clear()
        asked.clear()
        retryLater.clear()
        reviewedThisDrive.clear()
        reviewStarted.clear()
        planDrive()
        return advance()
    }

    /** Moves to the next step, doing bookkeeping steps on the way. */
    fun advance(): Step {
        while (true) {
            val next = queue.removeFirstOrNull() ?: Step.End
            when {
                next is Step.UnitDone -> completeUnit(next.unitId)
                next is Step.Plan -> queue.addAll(0, expand(next))
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

    /** A multiple-choice answer: the 1-based number of the option as it was read out. */
    fun answer(choice: Int): Boolean {
        val ask = current as? Step.Ask ?: return false
        val q = ask.question
        if (q.isExplain || choice !in 1..q.options.size) return false
        queue.addFirst(Step.AskConfidence(ask.unitId, q, ask.order[choice - 1] + 1, ask.order))
        advance()
        if (!askConfidence) confidence(sure = true)
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
                    "התשובה הנכונה היא ${HebrewNumbers.word(step.order.indexOf(right) + 1)}: ${q.options[right].text}",
                    step.unitId, label = "התשובה הנכונה",
                )
            }
            follow += Step.AskReplay(step.unitId, q)
            if (!q.isCheck) retryLater.addLast(step.unitId to q)
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
        if (step.question.isCheck) {
            if (yes) {
                follow += Step.Say("ועכשיו שוב את אותה שאלה.", step.unitId)
                follow += ask(step.unitId, step.question, repeatOk = true)
            }
        } else {
            alternativeQuestion(step.unitId, step.question)?.let { alt ->
                follow += Step.Say("עכשיו שאלה נוספת על אותו נושא.", step.unitId)
                follow += ask(step.unitId, alt)
            }
        }
        queue.addAll(0, follow)
        advance()
        return true
    }

    /**
     * A spoken explain-back answer. With [feedback] (graded live by Gemini) the feedback is played
     * now; otherwise the answer is graded after the drive and the feedback comes on the next drive.
     */
    fun explain(transcript: String, feedback: String? = null, covered: List<Int>? = null): Boolean {
        val ask = current as? Step.Ask ?: return false
        val q = ask.question
        if (!q.isExplain) return false
        val passed = covered?.let { it.size >= q.minPoints.coerceAtLeast(1) }
        if (transcript.isNotBlank()) {
            progress = progress.copy(
                explainAnswers = progress.explainAnswers +
                    ExplainAnswer(q.id, ask.unitId, transcript, today.toString(), now(), feedback, covered, passed),
            )
            store.save(progress)
        }
        if (transcript.isNotBlank() && !feedback.isNullOrBlank()) {
            queue.addFirst(Step.Say(feedback, ask.unitId, label = "משוב על ההסבר שלך"))
        } else {
            val intro = if (transcript.isBlank()) {
                "לא שמעתי הסבר, אז נעבור על הנקודות ביחד."
            } else {
                "שמרתי את ההסבר שלך. הוא ייבדק אחרי הנסיעה, ותקבל עליו משוב בנסיעה הבאה."
            }
            val points = q.rubric.joinToString(". ")
            queue.addFirst(Step.Say("$intro אלה הנקודות שהיה חשוב להזכיר: $points.", ask.unitId, label = "נקודות חשובות"))
        }
        advance()
        return true
    }

    /** The lesson text a question is about, for live grading. */
    fun lessonTextFor(unitId: String, q: Question): String {
        val unit = unitById[unitId] ?: return ""
        val segs = unit.explanations.main.filter { it.id in q.segments }.ifEmpty { unit.explanations.main }
        return segs.joinToString("\n") { it.spoken }
    }

    /** What the learner is learning now, for answering a question asked out loud. */
    fun liveContext(): String {
        val unit = unit(current.unitId) ?: return ""
        val say = current as? Step.Say
        val segment = say?.segmentIndex?.let { unit.explanations.main.getOrNull(it)?.spoken }.orEmpty()
        return listOf("Unit: ${unit.title}", "Summary: ${unit.recap}", segment).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun saveLiveAnswer(question: String, answer: String) {
        progress = progress.copy(
            liveAnswers = progress.liveAnswers + LiveAnswer(question, answer, current.unitId, today.toString(), now()),
        )
        store.save(progress)
    }

    fun saveLiveQuestion(text: String) {
        progress = progress.copy(
            liveQuestions = progress.liveQuestions + LiveQuestion(text, current.unitId, today.toString(), now()),
        )
        store.save(progress)
    }

    fun logDiagnostic(text: String) {
        progress = progress.copy(diagnostics = (progress.diagnostics + "${today}: $text").takeLast(50))
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
        return unit.explanations.simple.map { Step.Say(it.spoken, unit.id, label = "הסבר אחר") }
    }

    fun deeperSteps(): List<Step> {
        val unit = unit(current.unitId) ?: return emptyList()
        return unit.explanations.deep.map { Step.Say(it.spoken, unit.id, label = "העמקה") }
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
        val unheard = feedback.filter { it.id !in progress.heardFeedback && it.text.isNotBlank() }
        if (unheard.isNotEmpty()) {
            queue += Step.Say("קודם, משוב על ההסברים שנתת בנסיעות הקודמות.", label = "משוב")
            unheard.forEach { item ->
                queue += Step.Say(item.text, item.unitId.ifBlank { null }, label = "משוב על ההסבר שלך")
                queue += Step.Plan("", Step.Plan.Kind.FEEDBACK_HEARD, covered = item.id)
            }
        }
        val review = reviewQuestions(max = 4)
        if (review.isNotEmpty()) {
            queue += Step.Say("לפני שממשיכים, חזרה קצרה על דברים מנסיעות קודמות.")
            review.forEach { (unitId, q) ->
                reviewedThisDrive += q.concept
                queue += ask(unitId, q, repeatOk = true)
            }
        }
        val remaining = units.filter { it.id !in progress.completedUnits }
        if (remaining.isEmpty()) {
            queue += Step.Say(
                "סיימת את כל היחידות שמוכנות כרגע. יחידות חדשות יגיעו אחרי העדכון הבא. " +
                    "אפשר להגיד חזרה על יחידה, או סיכום.",
            )
            return
        }
        remaining.forEach { if (it.isReview) addReview(it) else addUnit(it) }
        queue += Step.Say("זה הכול לבינתיים. כל הכבוד על ההתמדה.")
    }

    private fun addUnit(unit: LearningUnit) {
        val earlier = progress.attempts.filter { it.unitId == unit.id }
        val mainIds = unit.questions.map { it.id }.toSet()
        val answeredMain = earlier.any { it.questionId in mainIds }
        if (answeredMain) {
            // The lesson was heard on an earlier drive: a short reminder, then only the questions that are left.
            val solid = earlier.filter { it.correct && it.sure }.map { it.questionId }.toSet()
            queue += Step.Say(
                "ממשיכים מאיפה שעצרנו, ב${unitName(unit.id)}. קודם תזכורת קצרה. " +
                    "אם תרצה לשמוע את כל ההסבר מההתחלה, תגיד חזרה על יחידה ${unitNumber(unit.id)}.",
                unit.id, label = "המשך",
            )
            if (unit.recap.isNotBlank()) queue += Step.Say(unit.recap, unit.id, label = "תזכורת")
            unit.questions.filter { it.id !in solid }.forEach { queue += ask(unit.id, it) }
            queue += Step.UnitDone(unit.id)
            return
        }
        // Stopped in the middle of the lesson: continue after the last segment whose check was answered.
        val checkIds = earlier.map { it.questionId }.toSet()
        val lastChecked = unit.explanations.main.indexOfLast { seg -> seg.check?.id?.let { it in checkIds } == true }
        val from = lastChecked + 1
        if (from > 0) {
            queue += Step.Say(
                "ממשיכים מאיפה שעצרנו, ב${unitName(unit.id)}. בפעם הקודמת הגענו עד החלק: " +
                    "${unit.explanations.main[lastChecked].title}.",
                unit.id, label = "המשך",
            )
        } else {
            queue += Step.Say("${unitName(unit.id)}.", unit.id, label = "פתיחה")
            queue += Step.Plan(unit.id, Step.Plan.Kind.WARM_UP)
        }
        for (i in from until unit.explanations.main.size) addSegment(unit, i)
        if (unit.retell.isNotBlank()) {
            queue += Step.Say("עכשיו נעבור על כל מה שלמדנו ביחידה הזו שוב, בדרך אחרת.", unit.id, label = "במילים אחרות")
            queue += Step.Say(unit.retell, unit.id, label = "במילים אחרות")
        }
        unit.questions.forEach { queue += ask(unit.id, it) }
        if (unit.recap.isNotBlank()) queue += Step.Say(unit.recap, unit.id, label = "סיכום")
        queue += Step.UnitDone(unit.id)
    }

    /** A segment, then its check question if it has one. */
    private fun addSegment(unit: LearningUnit, index: Int) {
        queue += segmentStep(unit, index)
        checkFor(unit, index)?.let { queue += ask(unit.id, it) }
    }

    private fun checkFor(unit: LearningUnit, index: Int): Question? {
        val seg = unit.explanations.main[index]
        val check = seg.check ?: return null
        return if (check.segments.isEmpty()) check.copy(segments = listOf(seg.id)) else check
    }

    /**
     * A review unit: first the segments that teach concepts used earlier without being taught,
     * then for each covered lesson a reminder and several questions, and a summary of what to
     * strengthen. The questions are chosen when each block is reached, weakest first.
     */
    private fun addReview(unit: LearningUnit) {
        // Stopped in the middle on an earlier drive: skip what was already done.
        val fillIns = unit.fillIns.filter { !fillInDone(it) }
        val blocks = unit.covers.filter { !reviewBlockDone(unit, it) }
        val resuming = fillIns.size < unit.fillIns.size || blocks.size < unit.covers.size
        if (resuming) {
            queue += Step.Say(
                "ממשיכים את ה${unitName(unit.id)}, מאיפה שעצרנו בפעם הקודמת.",
                unit.id, label = "פתיחה",
            )
        } else {
            queue += Step.Say("${unitName(unit.id)}.", unit.id, label = "פתיחה")
            unit.explanations.main.indices.forEach { addSegment(unit, it) }
        }
        if (fillIns.isNotEmpty()) {
            queue += Step.Say(
                "לפני השאלות, נשלים כמה מושגים שהשתמשנו בהם ביחידות הקודמות, ולא הסברנו כמו שצריך.",
                unit.id, label = "השלמה",
            )
            fillIns.forEach { ref ->
                val source = unitById[ref.unit] ?: return@forEach
                val index = source.explanations.main.indexOfFirst { it.id == ref.segment }
                if (index >= 0) addSegment(source, index)
            }
        }
        blocks.forEach {
            queue += Step.Plan(unit.id, Step.Plan.Kind.REVIEW_BLOCK, covered = it)
            queue += Step.Plan(unit.id, Step.Plan.Kind.REVIEW_BLOCK_DONE, covered = it)
        }
        queue += Step.Plan(unit.id, Step.Plan.Kind.REVIEW_SUMMARY)
        if (unit.recap.isNotBlank()) queue += Step.Say(unit.recap, unit.id, label = "סיכום")
        queue += Step.UnitDone(unit.id)
    }

    private fun expand(plan: Step.Plan): List<Step> = when (plan.kind) {
        Step.Plan.Kind.WARM_UP -> warmUp(plan.unitId)
        Step.Plan.Kind.REVIEW_BLOCK -> reviewBlock(plan.unitId, plan.covered)
        Step.Plan.Kind.FEEDBACK_HEARD -> {
            progress = progress.copy(heardFeedback = progress.heardFeedback + plan.covered.orEmpty())
            store.save(progress)
            emptyList()
        }
        Step.Plan.Kind.REVIEW_BLOCK_DONE -> {
            progress = progress.copy(reviewBlocksDone = progress.reviewBlocksDone + "${plan.unitId}/${plan.covered}")
            store.save(progress)
            emptyList()
        }
        Step.Plan.Kind.REVIEW_SUMMARY -> reviewSummary(plan.unitId)
    }

    /**
     * Before a new lesson: three short questions on earlier concepts, weakest first. A weak
     * concept gets its reminder before the question.
     */
    private fun warmUp(unitId: String): List<Step> {
        val index = units.indexOfFirst { it.id == unitId }
        val earlier = units.take(index.coerceAtLeast(0)).filter { !it.isReview && it.id in progress.completedUnits }
        val candidates = earlier.flatMap { u -> u.concepts.map { it to u } }
            .filter { (concept, _) -> concept !in reviewedThisDrive }
            .distinctBy { it.first }
            .sortedBy { (concept, _) -> strength(concept) }
            .take(WARM_UP_QUESTIONS)
        if (candidates.isEmpty()) return emptyList()
        val steps = mutableListOf<Step>(
            Step.Say("לפני שמתחילים, כמה שאלות קצרות על דברים שכבר למדנו.", unitId, label = "חימום"),
        )
        candidates.forEach { (concept, source) ->
            val q = pickReviewQuestion(source, concept) ?: return@forEach
            reviewedThisDrive += concept
            if (isWeak(concept)) reminderFor(source)?.let { steps += Step.Say(it, source.id, label = "תזכורת") }
            steps += ask(source.id, q, repeatOk = true)
        }
        return if (steps.size > 1) steps else emptyList()
    }

    /** One covered lesson inside a review unit: a reminder, then several questions. */
    private fun reviewBlock(reviewId: String, coveredId: String?): List<Step> {
        reviewStarted.getOrPut(reviewId) { now() }
        val covered = unitById[coveredId] ?: return emptyList()
        val steps = mutableListOf<Step>()
        reminderFor(covered)?.let { steps += Step.Say("${covered.title}. $it", covered.id, label = "תזכורת") }
        val chosen = mutableListOf<Question>()
        chosen += covered.reviewQuestions.sortedBy { timesRight(covered.id, it) }.take(REVIEW_SHORT_QUESTIONS)
        chosen += covered.questions
            .filter { !it.isExplain && !it.isCheck }
            .sortedWith(compareByDescending<Question> { timesWrong(covered.id, it) }.thenBy { timesRight(covered.id, it) })
            .take(REVIEW_MAIN_QUESTIONS)
        chosen.shuffled(random).forEach { steps += ask(covered.id, it, repeatOk = true) }
        covered.questions.filter { it.isExplain }.shuffled(random).firstOrNull()?.let { steps += ask(covered.id, it, repeatOk = true) }
        covered.concepts.forEach { reviewedThisDrive += it }
        return steps
    }

    /** A fill-in segment counts as heard once its check was answered right. */
    private fun fillInDone(ref: SegmentRef): Boolean {
        val check = unitById[ref.unit]?.explanations?.main?.firstOrNull { it.id == ref.segment }?.check ?: return false
        return progress.attempts.any { it.unitId == ref.unit && it.questionId == check.id && it.correct }
    }

    /**
     * A covered unit's block is done if it was marked done, or (for drives before blocks were
     * marked) if its explain part was answered after the review's fill-ins were first heard.
     */
    private fun reviewBlockDone(review: LearningUnit, covered: String): Boolean {
        if ("${review.id}/$covered" in progress.reviewBlocksDone) return true
        val checkIds = review.fillIns.mapNotNull { ref ->
            unitById[ref.unit]?.explanations?.main?.firstOrNull { it.id == ref.segment }?.check?.id?.let { ref.unit to it }
        }.toSet()
        val started = progress.attempts.filter { (it.unitId to it.questionId) in checkIds }.minOfOrNull { it.time } ?: return false
        return progress.explainAnswers.any { it.unitId == covered && it.time > started }
    }

    /** What went well in this review and what to strengthen. Wrong answers already sent those concepts back to box 0. */
    private fun reviewSummary(reviewId: String): List<Step> {
        val review = unitById[reviewId] ?: return emptyList()
        val since = reviewStarted[reviewId] ?: return emptyList()
        val attempts = progress.attempts.filter { it.time >= since && it.level != "check" }
        if (attempts.isEmpty()) return emptyList()
        val titleOf = { concept: String -> lessons.firstOrNull { concept in it.concepts }?.title ?: concept }
        val byConcept = attempts.groupBy { it.concept }
        val weak = byConcept.filter { (_, list) -> list.any { !it.correct } }.keys.map(titleOf)
        val strong = byConcept.filter { (_, list) -> list.all { it.correct } }.keys.map(titleOf)
        val right = attempts.count { it.correct }
        val text = buildString {
            append("סיכום החזרה: ענית נכון על $right מתוך ${attempts.size} שאלות. ")
            if (strong.isNotEmpty()) append("את הנושאים האלה אתה כבר זוכר טוב: ${strong.joinToString(", ")}. ")
            if (weak.isNotEmpty()) {
                append("כדאי לחזק את: ${weak.joinToString(", ")}. ")
                append("הם יחזרו בנסיעות הקרובות, עם תזכורת קצרה לפני השאלות.")
            } else {
                append("אין נושא שצריך לחזק כרגע. מצוין.")
            }
        }
        return listOf(Step.Say(text, review.id, label = "סיכום החזרה"))
    }

    /** Lower is weaker: the Leitner box, minus recent mistakes. */
    private fun strength(concept: String): Int {
        val box = progress.concepts[concept]?.box ?: 0
        val recent = progress.attempts.filter { it.concept == concept && it.level != "check" }.takeLast(4)
        return box * 2 - recent.count { !it.correct } * 3
    }

    private fun isWeak(concept: String): Boolean {
        val box = progress.concepts[concept]?.box ?: 0
        val recent = progress.attempts.filter { it.concept == concept && it.level != "check" }.takeLast(3)
        return box <= 1 || recent.any { !it.correct }
    }

    private fun reminderFor(unit: LearningUnit): String? =
        unit.reminder.ifBlank { unit.recap.split(". ").take(2).joinToString(". ").trim() }.takeIf { it.isNotBlank() }

    /** A short review question on [concept] from [unit], the one answered right the fewest times. */
    private fun pickReviewQuestion(unit: LearningUnit, concept: String): Question? {
        val pool = unit.reviewQuestions.filter { it.concept == concept }
            .ifEmpty { unit.questions.filter { it.concept == concept && !it.isExplain && !it.isCheck } }
        return pool.shuffled(random).minByOrNull { timesRight(unit.id, it) }
    }

    private fun timesRight(unitId: String, q: Question) =
        progress.attempts.count { it.unitId == unitId && it.questionId == q.id && it.correct }

    private fun timesWrong(unitId: String, q: Question) =
        progress.attempts.count { it.unitId == unitId && it.questionId == q.id && !it.correct }

    /** A question step with the options in a fresh random order, so the right answer's position means nothing. */
    private fun ask(unitId: String, q: Question, repeatOk: Boolean = false): Step.Ask =
        Step.Ask(unitId, q, repeatOk, q.options.indices.shuffled(random))

    /** Replays a finished unit from the start. */
    fun replayUnit(number: Int): Boolean {
        val unit = lessons.getOrNull(number - 1) ?: return false
        val steps = mutableListOf<Step>(Step.Say("חוזרים ליחידה $number: ${unit.title}.", unit.id, label = "פתיחה"))
        unit.explanations.main.indices.forEach { i ->
            steps += segmentStep(unit, i)
            checkFor(unit, i)?.let { steps += ask(unit.id, it, repeatOk = true) }
        }
        unit.questions.filter { !it.isExplain }.forEach { steps += ask(unit.id, it, repeatOk = true) }
        if (unit.recap.isNotBlank()) steps += Step.Say(unit.recap, unit.id, label = "סיכום")
        queue.addAll(0, steps)
        advance()
        return true
    }

    private fun reviewQuestions(max: Int): List<Pair<String, Question>> {
        val due = Scheduler.dueConcepts(progress, today)
        return due.sortedBy { strength(it) }.mapNotNull { concept ->
            val unit = lessons.firstOrNull { concept in it.concepts && it.id in progress.completedUnits }
                ?: return@mapNotNull null
            val candidates = (unit.reviewQuestions + unit.questions).filter { it.concept == concept && !it.isExplain && !it.isCheck }
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
        // After a review, every question that went wrong comes back once more.
        repeat(if (unitById[unitId]?.isReview == true) 6 else 2) {
            val (uid, q) = retryLater.removeFirstOrNull() ?: return@repeat
            if (retries.isEmpty()) retries += Step.Say("בוא נחזור לשאלה שהייתה קשה קודם.", uid, label = "חזרה")
            retries += ask(uid, q, repeatOk = true)
        }
        queue.addAll(0, retries)
    }

    private fun record(unitId: String, q: Question, choice: Int, correct: Boolean, sure: Boolean, misconception: String?) {
        val attempt = Attempt(q.id, unitId, q.concept, q.level, choice, correct, sure, today.toString(), now(), misconception)
        val attempts = progress.attempts + attempt
        if (q.isCheck) {
            // A check right after the explanation shows it was heard, not that it was learned.
            progress = progress.copy(attempts = attempts)
        } else {
            val updated = Scheduler.update(progress.concepts[q.concept] ?: ConceptProgress(), correct, sure, today)
                .let { it.copy(mastered = Scheduler.isMastered(attempts, q.concept)) }
            progress = progress.copy(attempts = attempts, concepts = progress.concepts + (q.concept to updated))
        }
        store.save(progress)
    }

    private fun alternativeQuestion(unitId: String, q: Question): Question? {
        val unit = unitById[unitId] ?: return null
        return (unit.questions + unit.reviewQuestions).firstOrNull {
            it.id != q.id && it.concept == q.concept && !it.isExplain && !it.isCheck && key(unitId, it) !in asked
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
        return Step.Say(seg.spoken, unit.id, segmentIndex = index, label = seg.title)
    }

    private fun key(unitId: String, q: Question) = "$unitId/${q.id}"

    private companion object {
        const val WARM_UP_QUESTIONS = 3
        const val REVIEW_SHORT_QUESTIONS = 3
        const val REVIEW_MAIN_QUESTIONS = 2
    }
}
