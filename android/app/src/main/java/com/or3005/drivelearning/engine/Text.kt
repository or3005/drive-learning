package com.or3005.drivelearning.engine

/** Text helpers shared by the engine and the speaker, so sentence numbers mean the same thing. */
object Sentences {
    private val boundary = Regex("(?<=[.!?])\\s+")

    fun split(text: String): List<String> =
        text.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
}

/** A piece of a sentence, marked as Latin script (English terms) or not. */
data class Run(val text: String, val latin: Boolean)

object Runs {
    private val latinRun = Regex("[A-Za-z][A-Za-z0-9.#+'’\\-]*(?:\\s+[A-Za-z0-9][A-Za-z0-9.#+'’\\-]*)*")

    /** Splits a sentence into Hebrew and Latin runs. Hebrew prefixes like ״ה-״ stay with the Hebrew run. */
    fun split(sentence: String): List<Run> {
        val runs = mutableListOf<Run>()
        var last = 0
        for (m in latinRun.findAll(sentence)) {
            if (m.range.first > last) runs += Run(sentence.substring(last, m.range.first), latin = false)
            runs += Run(m.value, latin = true)
            last = m.range.last + 1
        }
        if (last < sentence.length) runs += Run(sentence.substring(last), latin = false)
        return runs.filter { it.text.isNotBlank() }
    }
}

object HebrewNumbers {
    private val words = listOf("אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש")

    fun word(n: Int): String = words.getOrElse(n - 1) { n.toString() }
}
