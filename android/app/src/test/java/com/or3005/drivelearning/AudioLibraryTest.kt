package com.or3005.drivelearning

import com.or3005.drivelearning.audio.AudioLibrary
import com.or3005.drivelearning.engine.Sentences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The recordings are found by sentence: the app must split and hash exactly like tools/tts_render.py. */
class AudioLibraryTest {

    @Test
    fun sentencesAndHashesMatchTheRecordingTool() {
        val text = File("../../tools/sentence_fixture.json").readText()
        val cases = parse(text)
        for ((input, expected) in cases) {
            val sentences = Sentences.split(input)
            assertEquals(expected.map { it.first }, sentences)
            assertEquals(expected.map { it.second }, sentences.map { AudioLibrary.hash(it) })
        }
    }

    /** A tiny reader for the fixture, so the test doesn't need org.json (a stub in unit tests). */
    private fun parse(json: String): List<Pair<String, List<Pair<String, String>>>> {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        return (element as kotlinx.serialization.json.JsonArray).map { case ->
            val o = case as kotlinx.serialization.json.JsonObject
            val input = (o["text"] as kotlinx.serialization.json.JsonPrimitive).content
            val sentences = (o["sentences"] as kotlinx.serialization.json.JsonArray).map { s ->
                val so = s as kotlinx.serialization.json.JsonObject
                (so["text"] as kotlinx.serialization.json.JsonPrimitive).content to
                    (so["hash"] as kotlinx.serialization.json.JsonPrimitive).content
            }
            input to sentences
        }
    }
}
