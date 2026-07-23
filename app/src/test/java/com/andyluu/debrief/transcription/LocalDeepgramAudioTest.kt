package com.andyluu.debrief.transcription

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Opt-in, real-provider smoke test.
 *
 * It is intentionally skipped during normal CI/release tests because it consumes provider
 * quota and private audio must never be committed. Run scripts/run-local-audio-test.ps1.
 */
class LocalDeepgramAudioTest {
    @Test
    fun transcribesPrivateLocalAudioWithCompleteTimestamps() = runBlocking {
        assumeTrue(
            "Set DEBRIEF_RUN_LOCAL_AUDIO_TEST=1 to run the private fixture.",
            System.getenv("DEBRIEF_RUN_LOCAL_AUDIO_TEST") == "1",
        )
        val localRoot = findLocalTestingRoot()
        val key = loadDeepgramKey(localRoot)
        val requestedAudio = System.getenv("DEBRIEF_LOCAL_AUDIO_FILE")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.isFile && it.extension.lowercase() in supportedExtensions }
        val audio = requestedAudio ?: sequenceOf(localRoot, localRoot.resolve("audio"))
                .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
                .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                .sortedBy { it.name }
                .firstOrNull()
                ?: error("Put an MP3, M4A, WAV, AAC, FLAC, or OGG file in $localRoot or ${localRoot.resolve("audio")}.")

        val result = DeepgramProvider().transcribeAudio(
            recordingId = "local-${audio.nameWithoutExtension}",
            audioBody = audio.asRequestBody(mimeType(audio).toMediaType()),
            apiKey = key,
            keyterms = emptyList(),
        )

        assertTrue("The provider returned no transcript segments.", result.segments.isNotEmpty())
        assertTrue("The provider returned no word timestamps.", result.words.isNotEmpty())
        assertTrue("Transcript words must contain visible text.", result.words.all { it.text.isNotBlank() })
        assertTrue("Word timestamps must be non-negative.", result.words.all { it.startMs >= 0 && it.endMs >= it.startMs })
        assertTrue(
            "Word timestamps must be in chronological order.",
            result.words.zipWithNext().all { (first, second) -> second.startMs >= first.startMs },
        )

        val resultDirectory = localRoot.resolve("results").apply { mkdirs() }
        val resultFile = resultDirectory.resolve("${audio.nameWithoutExtension}-deepgram-transcript.txt")
        resultFile.writeText(
            buildString {
                appendLine("Fixture: ${audio.name}")
                appendLine("Segments: ${result.segments.size}")
                appendLine("Words: ${result.words.size}")
                appendLine()
                result.segments.forEach { segment ->
                    appendLine("[${formatTime(segment.startMs)}] ${segment.speakerId}: ${segment.text}")
                }
            }
        )
        assertTrue("The readable local transcript result was not written.", resultFile.length() > 0)
    }

    private fun findLocalTestingRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (candidate != null) {
            val local = candidate.resolve("local-testing")
            if (local.isDirectory) return local
            candidate = candidate.parentFile
        }
        error("local-testing was not found. Run this test from the Debrief repository.")
    }

    private fun loadDeepgramKey(localRoot: File): String {
        val secretsFile = localRoot.resolve("secrets.properties")
        check(secretsFile.isFile) { "Create $secretsFile and add DEEPGRAM_API_KEY=your_key." }
        val properties = Properties().apply { secretsFile.inputStream().use { input -> load(input) } }
        return properties.getProperty("DEEPGRAM_API_KEY").orEmpty().trim().also {
            check(it.isNotBlank() && it != "paste_key_here") {
                "Add the Deepgram key to $secretsFile. It is gitignored."
            }
        }
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        val supportedExtensions = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")
    }
}
