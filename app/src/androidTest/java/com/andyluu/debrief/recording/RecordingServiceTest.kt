package com.andyluu.debrief.recording

import android.Manifest
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.andyluu.debrief.DebriefApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun recordsPausesResumesAndPreservesAudioWhenFolderSaveFails() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<DebriefApplication>()
        val repository = application.services.recorder
        repository.update(RecordingState())

        repository.start("content://com.andyluu.debrief.invalid/tree/missing")
        awaitPhase(repository, RecordingPhase.RECORDING)
        delay(800)

        repository.pause()
        awaitPhase(repository, RecordingPhase.PAUSED)
        assertEquals(RecordingPauseReason.USER, repository.state.value.pauseReason)
        delay(250)

        repository.resume()
        awaitPhase(repository, RecordingPhase.RECORDING)
        delay(800)

        repository.stop()
        awaitPhase(repository, RecordingPhase.SAVE_FAILED, timeoutMs = 20_000)
        val sessionId = requireNotNull(repository.state.value.sessionId)
        val output = RecordingOutput(application)
        val playablePart = output.sessionParts(sessionId).firstOrNull(M4aConcatenator::isReadableAudio)
        assertTrue(
            "At least one playable local part should survive a folder-save failure.",
            playablePart != null,
        )

        val duplicatePart = output.partFile(sessionId, 99)
        requireNotNull(playablePart).copyTo(duplicatePart, overwrite = true)
        val joined = playablePart.parentFile!!.resolve("$sessionId-test-joined.m4a")
        M4aConcatenator.concatenate(listOf(playablePart, duplicatePart), joined)
        assertTrue("The losslessly joined long recording should be playable.", M4aConcatenator.isReadableAudio(joined))
        assertTrue(
            "Joining two parts should extend the recording timeline.",
            durationMs(joined) >= durationMs(playablePart) * 1.7,
        )

        output.cleanup(sessionId)
        repository.update(RecordingState())
    }

    private suspend fun awaitPhase(
        repository: RecordingRepository,
        expected: RecordingPhase,
        timeoutMs: Long = 10_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && repository.state.value.phase != expected) {
            delay(100)
        }
        assertEquals(
            "Expected $expected but state was ${repository.state.value.phase}: ${repository.state.value.statusMessage}",
            expected,
            repository.state.value.phase,
        )
    }

    private fun durationMs(file: java.io.File): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
    }
}
