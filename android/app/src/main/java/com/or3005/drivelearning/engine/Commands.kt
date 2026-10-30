package com.or3005.drivelearning.engine

/** What the learner said or tapped, after parsing. */
sealed interface Command {
    data class Answer(val n: Int) : Command
    data class Sure(val sure: Boolean) : Command
    data class YesNo(val yes: Boolean) : Command
    data class Explain(val transcript: String) : Command
    data class Define(val query: String) : Command
    data class LiveQuestion(val text: String) : Command
    data class ReplayUnit(val number: Int) : Command
    data object Replay : Command
    data object RestartSegment : Command
    data object Back : Command
    data object Forward : Command
    data object Simpler : Command
    data object Deeper : Command
    data object Hint : Command
    data object RepeatQuestion : Command
    data object Pause : Command
    data object Resume : Command
    data object Recap : Command
}

/** What the app is waiting for when it listens. */
enum class ListenContext { ANSWER, CONFIDENCE, YES_NO, EXPLAIN, COMMAND }

/** Turns Hebrew speech-recognition text into a [Command]. */
object CommandParser {

    private val numberWords = mapOf(
        1 to setOf("1", "אחת", "אחד", "ראשון", "ראשונה", "הראשונה", "הראשון", "אלף"),
        2 to setOf("2", "שתיים", "שתים", "שניים", "שנים", "שני", "שנייה", "שניה", "השנייה", "השניה", "בית"),
        3 to setOf("3", "שלוש", "שלושה", "שלישי", "שלישית", "השלישית", "השלישי", "גימל"),
        4 to setOf("4", "ארבע", "ארבעה", "רביעי", "רביעית", "הרביעית", "הרביעי", "דלת"),
    )
    private val yesWords = setOf("כן", "בטח", "אוקיי", "אוקי", "בסדר", "יאללה", "רוצה", "בבקשה", "נכון")
    private val noWords = setOf("לא", "תמשיך", "המשך", "דלג")
    private val defineRegex = Regex("^(?:מה זה|מה זאת|מה זו|מה הם|מה הן|מה פירוש|מה המשמעות של)\\s+(.+)$")
    private val replayUnitRegex = Regex("חזרה על יחידה\\s+(\\S+)")

    fun parse(raw: String, ctx: ListenContext): Command? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        val tokens = text.split(' ').toSet()

        if (ctx == ListenContext.EXPLAIN) {
            // Only clear navigation phrases count as commands; anything else is the explanation.
            if (text == "רמז") return Command.Hint
            if (text.startsWith("תחזור על השאלה")) return Command.RepeatQuestion
            if (text == "דלג" || text == "תדלג") return Command.Forward
            return Command.Explain(raw.trim())
        }

        if (text.startsWith("יש לי שאלה") || text.startsWith("שאלה ")) {
            return Command.LiveQuestion(raw.trim().substringAfter("שאלה").trim())
        }
        defineRegex.find(text)?.let { return Command.Define(it.groupValues[1]) }
        replayUnitRegex.find(text)?.let { m -> number(m.groupValues[1])?.let { return Command.ReplayUnit(it) } }

        when (ctx) {
            ListenContext.CONFIDENCE -> {
                if (text.contains("לא בטוח") || text.contains("לא יודע")) return Command.Sure(false)
                if (tokens.contains("בטוח") || tokens.contains("בטוחה")) return Command.Sure(true)
            }
            ListenContext.YES_NO -> {
                if (tokens.contains("לא") || tokens.any { it in noWords }) return Command.YesNo(false)
                if (tokens.any { it in yesWords }) return Command.YesNo(true)
            }
            else -> Unit
        }

        generic(text, tokens)?.let { return it }

        if (ctx == ListenContext.ANSWER) {
            val words = text.split(' ')
            for (w in words) number(w)?.let { return Command.Answer(it) }
        }
        return null
    }

    private fun generic(text: String, tokens: Set<String>): Command? = when {
        text.contains("תחזור על השאלה") || text.contains("שוב את השאלה") || text.contains("מה השאלה") -> Command.RepeatQuestion
        text.contains("מההתחלה") -> Command.RestartSegment
        text.contains("תסביר אחרת") || text.contains("לא הבנתי") || text.contains("יותר פשוט") ||
            text.contains("תסביר שוב") -> Command.Simpler
        tokens.contains("תעמיק") || text.contains("לעומק") -> Command.Deeper
        tokens.contains("רמז") -> Command.Hint
        tokens.contains("אחורה") || tokens.contains("הקודם") -> Command.Back
        tokens.contains("קדימה") || tokens.contains("דלג") || tokens.contains("תדלג") || tokens.contains("הבא") -> Command.Forward
        tokens.contains("עצור") || tokens.contains("הפסק") || tokens.contains("חכה") || tokens.contains("תעצור") -> Command.Pause
        tokens.contains("סיכום") -> Command.Recap
        tokens.contains("תחזור") || tokens.contains("שוב") || text.contains("עוד פעם") -> Command.Replay
        tokens.contains("המשך") || tokens.contains("תמשיך") -> Command.Resume
        else -> null
    }

    private fun number(word: String): Int? {
        val w = word.removePrefix("ה").let { if (it.isEmpty()) word else it }
        return numberWords.entries.firstOrNull { (_, set) -> word in set || w in set }?.key
            ?: word.toIntOrNull()?.takeIf { it in 1..60 }
    }

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[\\u0591-\\u05C7]"), "") // niqqud
            .replace(Regex("[.,!?;:״\"'׳()\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
