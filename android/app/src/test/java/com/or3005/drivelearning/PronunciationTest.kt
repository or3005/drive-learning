package com.or3005.drivelearning

import com.or3005.drivelearning.engine.Pronunciation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PronunciationTest {

    private val p = Pronunciation.parse(File("../../content/pronunciation.json").readText())

    @Test
    fun englishTermsBecomeHebrewSpelling() {
        assertEquals("אפליקציית ריאקט היא לָקוֹחַ.", p.apply("אפליקציית React היא לקוח."))
        assertEquals("איי פי איי הוא חוזה.", p.apply("API הוא חוזה."))
        assertEquals("נוֹוד ג'יי אס רץ על הַשָׁרָת.", p.apply("Node.js רץ על השרת."))
    }

    @Test
    fun hebrewPrefixWithHyphenIsJoined() {
        assertEquals("הריאקט הוא הַלָקוֹחַ.", p.apply("ה-React הוא הלקוח."))
    }

    @Test
    fun longestTermWinsAndCaseIsIgnored() {
        assertEquals("ריוורס פרוקסי מטפל בזה.", p.apply("Reverse Proxy מטפל בזה."))
        assertEquals("מידלוור", p.apply("middleware"))
        assertTrue(p.apply("Stateless").startsWith("סטייטלס"))
    }

    @Test
    fun stemsGetNiqqudWithTheirPrefixes() {
        assertEquals("שָׁרָת", p.applyHebrewRun("שרת"))
        assertEquals("הַשָׁרָת מקבל בַּקָּשָׁה", p.applyHebrewRun("השרת מקבל בקשה"))
        assertEquals("תשובה מֵהַשָׁרָת", p.applyHebrewRun("תשובה מהשרת"))
        assertEquals("רץ בַּשָּׁרָת", p.applyHebrewRun("רץ בשרת"))
        // Words that only contain the letters of a stem are left alone
        assertEquals("בשרשרת של עשרת", p.applyHebrewRun("בשרשרת של עשרת"))
        // Already vocalized text is not touched again
        assertEquals("יכול לְשָׁרֵת", p.applyHebrewRun("יכול לְשָׁרֵת"))
    }

    @Test
    fun englishVoiceRuns() {
        assertEquals("C sharp", p.applyEnglishRun("C#"))
        assertEquals("A S P dot net", p.applyEnglishRun("ASP.NET"))
        assertEquals("Middleware", p.applyEnglishRun("Middleware"))
        // A Hebrew prefix before an English term becomes a vocalized syllable
        assertEquals("בפרויקט שלך הַ", p.applyHebrewRun("בפרויקט שלך ה-"))
        assertEquals("בַּקָּשָׁה עוברת ", p.applyHebrewRun("בקשה עוברת "))
    }

    @Test
    fun unknownAcronymsAreSpelled() {
        assertEquals("אקס וואי זד", p.apply("XYZ"))
    }

    @Test
    fun ambiguousHebrewGetsNiqqud() {
        val out = p.apply("בוא נלמד לפרק בקשה מורכבת.")
        assertFalse(out.contains("לפרק "))
        assertTrue(out.contains("לְפָרֵק"))
    }
}
