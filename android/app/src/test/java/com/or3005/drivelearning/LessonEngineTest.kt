package com.or3005.drivelearning

import com.or3005.drivelearning.content.ContentJson
import com.or3005.drivelearning.content.Course
import com.or3005.drivelearning.content.LearningUnit
import com.or3005.drivelearning.engine.LessonEngine
import com.or3005.drivelearning.engine.MemoryProgressStore
import com.or3005.drivelearning.engine.Scheduler
import com.or3005.drivelearning.engine.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

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
        val engine = LessonEngine(units(), MemoryProgressStore(), today)
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
        val engine = LessonEngine(units(), store, today)
        engine.start()
        val ask = engine.untilQuestion() as Step.Ask
        val right = ask.question.options.indexOfFirst { it.correct } + 1
        assertTrue(engine.answer(right))
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
        val engine = LessonEngine(units(), MemoryProgressStore(), today)
        engine.start()
        val ask = engine.untilQuestion() as Step.Ask
        val wrongIndex = ask.question.options.indexOfFirst { it.misconception != null }
        assertTrue("q1 should have a misconception option", wrongIndex >= 0)
        engine.answer(wrongIndex + 1)
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
        val engine = LessonEngine(units(), MemoryProgressStore(), today)
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
                    else engine.answer(step.question.options.indexOfFirst { !it.correct } + 1)
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
        val engine = LessonEngine(units(), store, today)
        engine.start()
        var guard = 0
        while (engine.current.unitId == "u01-backend-role" || engine.current.unitId == null) {
            if (guard++ > 300) break
            when (val step = engine.current) {
                is Step.Ask -> if (step.question.isExplain) engine.explain("") else
                    engine.answer(if (step.question.id == "q2") step.question.options.indexOfFirst { !it.correct } + 1
                        else step.question.options.indexOfFirst { it.correct } + 1)
                is Step.AskConfidence -> engine.confidence(true)
                is Step.AskReplay -> engine.replayAnswer(false)
                is Step.End -> break
                else -> engine.advance()
            }
        }
        assertTrue("u01 should be complete", "u01-backend-role" in store.load().completedUnits)

        val tomorrow = LessonEngine(units(), store, today.plusDays(1))
        val opening = tomorrow.start() as Step.Say
        assertTrue("next drive opens with a review", opening.text.startsWith("לפני שממשיכים"))
        assertTrue(tomorrow.advance() is Step.Ask)
    }

    @Test
    fun backPlaysPreviousSegmentThenCurrent() {
        val engine = LessonEngine(units(), MemoryProgressStore(), today)
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
        val engine = LessonEngine(units(), MemoryProgressStore(), today)
        engine.start()
        assertEquals("Middleware", engine.findTerm("middleware")?.term)
        assertEquals("Client", engine.findTerm("לקוח")?.term)
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
