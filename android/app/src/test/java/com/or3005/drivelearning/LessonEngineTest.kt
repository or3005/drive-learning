package com.or3005.drivelearning

import com.or3005.drivelearning.content.ContentJson
import com.or3005.drivelearning.content.Course
import com.or3005.drivelearning.content.LearningUnit
import com.or3005.drivelearning.engine.LessonEngine
import com.or3005.drivelearning.engine.ConceptProgress
import com.or3005.drivelearning.engine.MemoryProgressStore
import com.or3005.drivelearning.engine.ProgressState
import com.or3005.drivelearning.engine.Scheduler
import com.or3005.drivelearning.engine.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** Runs the engine against the real course content in the repo. */
class LessonEngineTest {

    private val contentDir = File("../../content/courses/nodejs-backend")
    private val today = LocalDate.of(2026, 11, 1)

    private fun units(): List<LearningUnit> {
        val course = ContentJson.decodeFromString<Course>(File(contentDir, "course.json").readText())
        return course.units.filter { it.status != "planned" }.map {
            ContentJson.decodeFromString<LearningUnit>(File(contentDir, "units/${it.id}.json").readText())
        }
    }

    /** The number the option at [original] index was read out as. */
    private fun Step.Ask.said(original: Int) = order.indexOf(original) + 1
    private fun Step.Ask.saidCorrect() = said(question.options.indexOfFirst { it.correct })
    private fun Step.Ask.saidWrong() = said(question.options.indexOfFirst { !it.correct })

    /** Advances through Say steps until the engine asks something. */
    private fun LessonEngine.untilQuestion(): Step {
        var step = current
        var guard = 0
        while (step is Step.Say && guard++ < 500) step = advance()
        return step
    }

    @Test
    fun allContentParses() {
        val all = units()
        assertTrue("expected at least 6 units", all.size >= 6)
        all.forEach { u ->
            assertTrue(u.id, u.explanations.main.isNotEmpty())
            u.questions.filter { !it.isExplain }.forEach { q ->
                assertEquals("${u.id}/${q.id} needs one correct option", 1, q.options.count { it.correct })
            }
        }
    }

    @Test
    fun firstDrivePlaysUnitOneThenAsksFirstQuestion() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today, random = Random(7), askConfidence = true)
        val first = engine.start()
        assertTrue(first is Step.Say)
        assertEquals("u01-backend-role", first.unitId)
        val ask = engine.untilQuestion()
        assertTrue(ask is Step.Ask)
        assertEquals("q1", (ask as Step.Ask).question.id)
    }

    @Test
    fun correctAndSureRecordsAttemptAndMovesUpABox() {
        val store = MemoryProgressStore()
        val engine = LessonEngine(units(), store, today, random = Random(7), askConfidence = true)
        engine.start()
        val ask = engine.untilQuestion() as Step.Ask
        assertTrue(engine.answer(ask.saidCorrect()))
        assertTrue(engine.current is Step.AskConfidence)
        assertTrue(engine.confidence(sure = true))
        val feedback = engine.current as Step.Say
        assertEquals(ask.question.explanation, feedback.text)
        val attempt = store.load().attempts.single()
        assertTrue(attempt.correct && attempt.sure)
        assertEquals(1, store.load().concepts.getValue(ask.question.concept).box)
    }

    @Test
    fun wrongAnswerPlaysRemedialOffersReplayThenAsksAnotherQuestion() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today, random = Random(7), askConfidence = true)
        engine.start()
        val ask = engine.untilQuestion() as Step.Ask
        val wrongIndex = ask.question.options.indexOfFirst { it.misconception != null }
        assertTrue("q1 should have a misconception option", wrongIndex >= 0)
        engine.answer(ask.said(wrongIndex))
        engine.confidence(sure = true)

        val remedial = engine.current as Step.Say
        assertTrue(remedial.text.startsWith("לא בדיוק."))
        val correctAnswer = engine.advance() as Step.Say
        assertTrue(correctAnswer.text.startsWith("התשובה הנכונה היא"))
        assertTrue(engine.advance() is Step.AskReplay)

        assertTrue(engine.replayAnswer(yes = true))
        val replayed = engine.current as Step.Say
        assertNotNull("replay should play a lesson segment", replayed.segmentIndex)
        val next = engine.untilQuestion() as Step.Ask
        assertFalse("follow-up must be a different question", next.question.id == ask.question.id)
        assertEquals(ask.question.concept, next.question.concept)
        assertEquals(0, engine.progress.concepts.getValue(ask.question.concept).box)
    }

    @Test
    fun followUpQuestionIsNotAskedTwice() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today, random = Random(7), askConfidence = true)
        engine.start()
        val seen = mutableListOf<String>()
        var step = engine.untilQuestion()
        var guard = 0
        // Answer everything in unit 1 wrong-then-no-replay, and collect which questions were asked.
        while (step !is Step.End && step.unitId == "u01-backend-role" && guard++ < 200) {
            when (step) {
                is Step.Ask -> {
                    seen += step.question.id + if (step.repeatOk) "*" else ""
                    if (step.question.isExplain) engine.explain("השרת הוא הצד שהמשתמש לא שולט בו")
                    else engine.answer(step.saidWrong())
                }
                is Step.AskConfidence -> engine.confidence(true)
                is Step.AskReplay -> engine.replayAnswer(false)
                else -> engine.advance()
            }
            step = engine.current
        }
        val firstTime = seen.filter { !it.endsWith("*") }
        assertEquals("no question asked twice outside retries: $seen", firstTime.distinct(), firstTime)
    }

    @Test
    fun finishingAUnitMarksItCompleteAndNextDriveStartsWithReview() {
        val store = MemoryProgressStore()
        val engine = LessonEngine(units(), store, today, random = Random(7), askConfidence = true)
        engine.start()
        var guard = 0
        while (engine.current.unitId == "u01-backend-role" || engine.current.unitId == null) {
            if (guard++ > 300) break
            when (val step = engine.current) {
                is Step.Ask -> if (step.question.isExplain) engine.explain("") else
                    engine.answer(if (step.question.id == "q2") step.saidWrong() else step.saidCorrect())
                is Step.AskConfidence -> engine.confidence(true)
                is Step.AskReplay -> engine.replayAnswer(false)
                is Step.End -> break
                else -> engine.advance()
            }
        }
        assertTrue("u01 should be complete", "u01-backend-role" in store.load().completedUnits)

        val tomorrow = LessonEngine(units(), store, today.plusDays(1), random = Random(7), askConfidence = true)
        val opening = tomorrow.start() as Step.Say
        assertTrue("next drive opens with a review", opening.text.startsWith("לפני שממשיכים"))
        assertTrue(tomorrow.advance() is Step.Ask)
    }

    @Test
    fun optionsAreShuffledAndTheRightAnswerIsStillRecognized() {
        val store = MemoryProgressStore()
        val engine = LessonEngine(units(), store, today, random = Random(3), askConfidence = true)
        engine.start()
        val positions = mutableSetOf<Int>()
        var step = engine.untilQuestion()
        var guard = 0
        while (step.unitId == "u01-backend-role" && guard++ < 100) {
            when (step) {
                is Step.Ask -> if (step.question.isExplain) engine.explain("") else {
                    positions += step.saidCorrect()
                    assertEquals(step.question.options.map { it.text }.toSet(), step.displayedOptions.toSet())
                    engine.answer(step.saidCorrect())
                }
                is Step.AskConfidence -> engine.confidence(true)
                else -> engine.advance()
            }
            step = engine.current
        }
        assertTrue("the right answer should move around: $positions", positions.size > 1)
        assertTrue("every first-time answer was right", store.load().attempts.all { it.correct })
    }

    @Test
    fun halfFinishedUnitContinuesWithTheQuestionsThatAreLeft() {
        val store = MemoryProgressStore()
        val first = LessonEngine(units(), store, today, random = Random(7), askConfidence = true)
        first.start()
        // Answer unit 1's first two questions, then stop the drive.
        repeat(2) {
            val ask = first.untilQuestion() as Step.Ask
            first.answer(ask.saidCorrect())
            first.confidence(true)
            first.advance()
        }
        val next = LessonEngine(units(), store, today.plusDays(1), random = Random(7), askConfidence = true)
        val opening = next.start() as Step.Say
        assertTrue(opening.text.startsWith("ממשיכים מאיפה שעצרנו"))
        assertEquals("תזכורת", (next.advance() as Step.Say).label)
        val ask = next.advance() as Step.Ask
        assertEquals("q3", ask.question.id)
    }

    @Test
    fun withoutConfidenceAnAnswerGoesStraightToFeedback() {
        val store = MemoryProgressStore()
        val engine = LessonEngine(units(), store, today, random = Random(7))
        engine.start()
        val ask = engine.untilQuestion() as Step.Ask
        assertTrue(engine.answer(ask.saidCorrect()))
        assertEquals(ask.question.explanation, (engine.current as Step.Say).text)
        assertTrue(store.load().attempts.single().sure)
    }

    @Test
    fun backPlaysPreviousSegmentThenCurrent() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today, random = Random(7), askConfidence = true)
        engine.start()
        engine.advance() // segment 0
        val second = engine.advance() as Step.Say // segment 1
        assertEquals(1, second.segmentIndex)
        val back = engine.back() as Step.Say
        assertEquals(0, back.segmentIndex)
        assertEquals(second, engine.advance())
    }

    @Test
    fun termsAreFoundFromEarlierUnits() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today, random = Random(7), askConfidence = true)
        engine.start()
        assertEquals("Middleware", engine.findTerm("middleware")?.term)
        assertEquals("Client", engine.findTerm("לקוח")?.term)
    }

    private val chapterOne = listOf(
        "u01-backend-role", "u02-request-journey", "u03-http-anatomy", "r1-review-foundations",
        "u04-http-methods", "u05-status-codes", "u06-statelessness", "r2-review-http",
    )

    /** Answers everything right until [stop] is true, returning the steps that were seen. */
    private fun LessonEngine.answerRightUntil(stop: (Step) -> Boolean): List<Step> {
        val seen = mutableListOf<Step>()
        var guard = 0
        while (!stop(current) && current !is Step.End && guard++ < 600) {
            val step = current
            seen += step
            when (step) {
                is Step.Ask -> if (step.question.isExplain) explain("תשובה") else answer(step.saidCorrect())
                is Step.AskConfidence -> confidence(true)
                is Step.AskReplay -> replayAnswer(false)
                else -> advance()
            }
        }
        return seen
    }

    @Test
    fun reviewUnitTeachesFillInsThenAsksAboutEachCoveredUnitAndSummarizes() {
        val done = setOf("u01-backend-role", "u02-request-journey", "u03-http-anatomy")
        val store = MemoryProgressStore(ProgressState(completedUnits = done, schedulerVersion = Scheduler.VERSION))
        val engine = LessonEngine(units(), store, today, random = Random(5))
        val opening = engine.start() as Step.Say
        assertEquals("r1-review-foundations", opening.unitId)
        assertTrue(engine.unitName(opening.unitId).startsWith("חזרה"))
        val seen = engine.answerRightUntil { it.unitId == "u04-http-methods" }
        val checks = seen.filterIsInstance<Step.Ask>().filter { it.question.isCheck }.map { it.question.id }
        assertEquals(listOf("c-proxy", "c-cache"), checks.distinct())
        val asked = seen.filterIsInstance<Step.Ask>().filter { !it.question.isCheck }
        done.forEach { unit -> assertTrue("questions on $unit", asked.count { it.unitId == unit } >= 4) }
        assertTrue("one short explain part per covered unit", asked.count { it.question.isExplain } == 3)
        val summary = seen.filterIsInstance<Step.Say>().single { it.label == "סיכום החזרה" }
        assertTrue(summary.text.contains("אתה כבר זוכר טוב"))
        assertTrue("r1-review-foundations" in store.load().completedUnits)
    }

    @Test
    fun formatTwoLessonWarmsUpThenChecksAfterEverySegment() {
        val store = MemoryProgressStore(ProgressState(completedUnits = chapterOne.toSet(), schedulerVersion = Scheduler.VERSION))
        val engine = LessonEngine(units(), store, today, random = Random(5))
        engine.start()
        val seen = engine.answerRightUntil { it.unitId == "u08-event-loop" }
        val labels = seen.filterIsInstance<Step.Say>().map { it.label }
        assertTrue("warm-up before the lesson", labels.contains("חימום"))
        val u07 = units().single { it.id == "u07-node-runtime" }
        val checks = seen.filterIsInstance<Step.Ask>().filter { it.question.isCheck && it.unitId == u07.id }
        assertEquals(u07.explanations.main.size, checks.size)
        // Each check comes right after its segment.
        u07.explanations.main.forEach { seg ->
            val i = seen.indexOfFirst { it is Step.Say && it.unitId == u07.id && it.label == seg.title }
            val next = seen[i + 1] as Step.Ask
            assertEquals(seg.check?.id, next.question.id)
        }
        assertTrue("retell after the lesson", labels.contains("במילים אחרות"))
        val retell = labels.indexOf("במילים אחרות")
        val firstMain = seen.indexOfFirst { it is Step.Ask && it.unitId == u07.id && it.question.id == "q1" }
        assertTrue("retell comes before the final questions", seen.indexOfFirst { it is Step.Say && it.label == "במילים אחרות" } < firstMain && retell >= 0)
    }

    @Test
    fun wrongCheckOffersTheSegmentAgainAndAsksTheSameCheck() {
        val store = MemoryProgressStore(ProgressState(completedUnits = chapterOne.toSet(), schedulerVersion = Scheduler.VERSION))
        val engine = LessonEngine(units(), store, today, random = Random(5))
        engine.start()
        engine.answerRightUntil { it is Step.Ask && it.question.isCheck }
        val check = engine.current as Step.Ask
        engine.answer(check.saidWrong())
        while (engine.current is Step.Say) engine.advance()
        assertTrue(engine.current is Step.AskReplay)
        engine.replayAnswer(true)
        val replayed = engine.current as Step.Say
        assertEquals(check.question.segments.single(), units().single { it.id == check.unitId }
            .explanations.main[replayed.segmentIndex!!].id)
        while (engine.current is Step.Say) engine.advance()
        assertEquals(check.question.id, (engine.current as Step.Ask).question.id)
        // Checks don't move the concept's box.
        assertEquals(null, store.load().concepts["node-runtime"])
    }

    @Test
    fun aConceptMovesUpAtMostOneBoxADay() {
        var cp = ConceptProgress()
        repeat(5) { cp = Scheduler.update(cp, correct = true, sure = true, today = today) }
        assertEquals(1, cp.box)
        cp = Scheduler.update(cp, correct = true, sure = true, today = today.plusDays(1))
        assertEquals(2, cp.box)
        cp = Scheduler.update(cp, correct = false, sure = true, today = today.plusDays(1))
        assertEquals(0, cp.box)
    }

    @Test
    fun theLearnersRealProgressGoesToBothReviewsThenUnitSeven() {
        val file = File("../../progress/progress.json")
        val state = com.or3005.drivelearning.engine.FileProgressStore(file).load()
        val store = MemoryProgressStore(state)
        val engine = LessonEngine(units(), store, LocalDate.of(2026, 11, 4), random = Random(1))
        // Old boxes are rebuilt: eight right answers on one day are one step up, not eight.
        assertEquals(Scheduler.VERSION, engine.progress.schedulerVersion)
        assertTrue((engine.progress.concepts["statelessness"]?.box ?: 0) <= 1)
        engine.start()
        val seen = engine.answerRightUntil { it.unitId == "u08-event-loop" }
        val openings = seen.filterIsInstance<Step.Say>().filter { it.label == "פתיחה" }.map { it.unitId }
        assertEquals(listOf("r1-review-foundations", "r2-review-http", "u07-node-runtime"), openings)
    }

    @Test
    fun masteryNeedsThreeGoodAnswersOnTwoDaysIncludingApply() {
        val a = { day: String, level: String ->
            com.or3005.drivelearning.engine.Attempt("q", "u", "c", level, 1, true, true, day, 0L)
        }
        assertFalse(Scheduler.isMastered(listOf(a("d1", "recognize"), a("d1", "why"), a("d1", "apply")), "c"))
        assertFalse(Scheduler.isMastered(listOf(a("d1", "recognize"), a("d2", "why"), a("d2", "why")), "c"))
        assertTrue(Scheduler.isMastered(listOf(a("d1", "recognize"), a("d2", "why"), a("d2", "apply")), "c"))
    }
}
