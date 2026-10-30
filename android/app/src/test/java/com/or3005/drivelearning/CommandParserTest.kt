package com.or3005.drivelearning

import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.CommandParser
import com.or3005.drivelearning.engine.ListenContext
import com.or3005.drivelearning.engine.Runs
import com.or3005.drivelearning.engine.Sentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    private fun answer(text: String) = CommandParser.parse(text, ListenContext.ANSWER)

    @Test
    fun answersInWordsAndDigits() {
        assertEquals(Command.Answer(2), answer("תשובה שתיים"))
        assertEquals(Command.Answer(1), answer("אחת"))
        assertEquals(Command.Answer(3), answer("אני חושב שזה שלוש"))
        assertEquals(Command.Answer(4), answer("4"))
        assertEquals(Command.Answer(2), answer("התשובה השנייה"))
    }

    @Test
    fun confidence() {
        assertEquals(Command.Sure(true), CommandParser.parse("בטוח", ListenContext.CONFIDENCE))
        assertEquals(Command.Sure(false), CommandParser.parse("לא בטוח", ListenContext.CONFIDENCE))
        assertEquals(Command.Sure(false), CommandParser.parse("לא יודע", ListenContext.CONFIDENCE))
    }

    @Test
    fun yesNo() {
        assertEquals(Command.YesNo(true), CommandParser.parse("כן", ListenContext.YES_NO))
        assertEquals(Command.YesNo(false), CommandParser.parse("לא תודה", ListenContext.YES_NO))
        assertEquals(Command.YesNo(false), CommandParser.parse("תמשיך", ListenContext.YES_NO))
    }

    @Test
    fun navigationCommands() {
        assertEquals(Command.RepeatQuestion, answer("תחזור על השאלה"))
        assertEquals(Command.Replay, answer("תחזור"))
        assertEquals(Command.RestartSegment, CommandParser.parse("מההתחלה", ListenContext.COMMAND))
        assertEquals(Command.Simpler, CommandParser.parse("לא הבנתי", ListenContext.COMMAND))
        assertEquals(Command.Deeper, CommandParser.parse("תעמיק", ListenContext.COMMAND))
        assertEquals(Command.Hint, answer("רמז"))
        assertEquals(Command.Back, CommandParser.parse("אחורה", ListenContext.COMMAND))
        assertEquals(Command.Forward, CommandParser.parse("דלג", ListenContext.COMMAND))
        assertEquals(Command.Pause, CommandParser.parse("עצור", ListenContext.COMMAND))
        assertEquals(Command.Resume, CommandParser.parse("תמשיך", ListenContext.COMMAND))
        assertEquals(Command.Recap, CommandParser.parse("סיכום", ListenContext.COMMAND))
    }

    @Test
    fun defineLiveQuestionAndReplayUnit() {
        assertEquals(Command.Define("middleware"), CommandParser.parse("מה זה Middleware?", ListenContext.ANSWER))
        assertEquals(Command.ReplayUnit(3), CommandParser.parse("חזרה על יחידה שלוש", ListenContext.COMMAND))
        assertEquals(
            Command.LiveQuestion("למה צריך Reverse Proxy"),
            CommandParser.parse("יש לי שאלה למה צריך Reverse Proxy", ListenContext.COMMAND),
        )
    }

    @Test
    fun explainContextKeepsTheWholeAnswer() {
        val said = "השרת הוא הצד שהמשתמש לא שולט בו ולכן בודקים שם הרשאות"
        assertEquals(Command.Explain(said), CommandParser.parse(said, ListenContext.EXPLAIN))
        assertEquals(Command.Hint, CommandParser.parse("רמז", ListenContext.EXPLAIN))
    }

    @Test
    fun nonsenseIsNull() {
        assertNull(answer("בננה"))
        assertNull(answer(""))
    }

    @Test
    fun sentencesAndRuns() {
        assertEquals(listOf("שלום.", "מה שלומך?", "טוב!"), Sentences.split("שלום. מה שלומך? טוב!"))
        val runs = Runs.split("השרת עובר Middleware ו-Routing.")
        assertEquals(listOf("Middleware", "Routing."), runs.filter { it.latin }.map { it.text })
    }
}
