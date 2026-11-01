package com.or3005.drivelearning.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** content/pronunciation.json: how the Hebrew voice should say English terms and tricky Hebrew words. */
@Serializable
data class PronunciationData(
    /** English term to Hebrew spelling, e.g. React to ריאקט. Matched case-insensitively. */
    val terms: Map<String, String> = emptyMap(),
    /** Hebrew word to a vocalized spelling that the voice reads correctly, e.g. לפרק with niqqud. */
    val hebrew: Map<String, String> = emptyMap(),
    /** Letter names, used to spell acronyms that aren't in [terms]. */
    val letters: Map<String, String> = emptyMap(),
)

/**
 * Rewrites a sentence for the Hebrew text-to-speech voice:
 * 1. Known English terms become Hebrew spelling (React reads as ריאקט, API as איי פי איי).
 * 2. Other all-caps acronyms are spelled letter by letter.
 * 3. Ambiguous Hebrew words get niqqud so the voice picks the right reading.
 * 4. A Hebrew prefix with a hyphen (ה-ריאקט) is joined (הריאקט) so the voice doesn't pause on it.
 */
class Pronunciation(data: PronunciationData) {

    private val termMap = data.terms.mapKeys { it.key.lowercase() }
    private val termRegex: Regex? = data.terms.keys
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let { Regex("(?<![A-Za-z0-9])(?:$it)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) }

    private val hebrewRegex: Regex? = data.hebrew.keys
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let { Regex("(?<![\\u0590-\\u05FF])(?:$it)(?![\\u0590-\\u05FF])") }
    private val hebrewMap = data.hebrew

    private val letters = data.letters
    private val acronym = Regex("(?<![A-Za-z])[A-Z]{2,6}(?![A-Za-z])")
    private val hebrewPrefixHyphen = Regex("(?<![\\u0590-\\u05FF])([והבכלמש]{1,3})-(?=[\\u0590-\\u05FF])")

    fun apply(text: String): String {
        var out = text
        termRegex?.let { r -> out = r.replace(out) { m -> termMap[m.value.lowercase()] ?: m.value } }
        if (letters.isNotEmpty()) {
            out = acronym.replace(out) { m -> m.value.map { c -> letters[c.toString()] ?: c.toString() }.joinToString(" ") }
        }
        hebrewRegex?.let { r -> out = r.replace(out) { m -> hebrewMap[m.value] ?: m.value } }
        out = hebrewPrefixHyphen.replace(out, "$1")
        return out
    }

    companion object {
        val NONE = Pronunciation(PronunciationData())
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Pronunciation = Pronunciation(json.decodeFromString<PronunciationData>(text))
    }
}
