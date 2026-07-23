package com.andyluu.debrief.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
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
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    application.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }

        repository.start(
            "content://com.andyluu.debrief.invalid/tree/missing",
            "Run club.m4a",
        )
        awaitPhase(repository, RecordingPhase.RECORDING)
        assertEquals("Run club.m4a", repository.state.value.displayName)
        delay(800)

        repository.updateDisplayName("Networking follow-up.m4a")
        assertEquals("Networking follow-up.m4a", repository.state.value.displayName)

        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val exerciseSystemSwipe = Build.VERSION.SDK_INT >= 33 &&
            InstrumentationRegistry.getArguments().getString("debriefTestNotificationSwipe") == "1"
        if (exerciseSystemSwipe) {
            swipeAwayRecordingNotification(repository)
        } else {
            application.sendBroadcast(
                Intent(application, RecordingNotificationDismissReceiver::class.java)
                    .setAction(RecordingNotificationDismissReceiver.ACTION_DISMISSED)
            )
            awaitNotificationDismissed(repository)
        }
        if (exerciseSystemSwipe) assertRecordingNotificationAbsent()

        repository.pause()
        awaitPhase(repository, RecordingPhase.PAUSED)
        assertEquals(RecordingPauseReason.USER, repository.state.value.pauseReason)
        delay(250)

        repository.resume()
        awaitPhase(repository, RecordingPhase.RECORDING)
        delay(800)
        if (exerciseSystemSwipe) assertRecordingNotificationAbsent()

        repository.stop()
        awaitPhase(repository, RecordingPhase.SAVE_FAILED, timeoutMs = 20_000)
        assertEquals("Networking follow-up.m4a", repository.state.value.displayName)
        assertTrue(repository.state.value.notificationDismissed)
        notificationManager.cancel(RecordingService.NOTIFICATION_ID)
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

    @Test
    fun discardStopsCaptureAndDeletesEveryPrivateSessionPart() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<DebriefApplication>()
        val repository = application.services.recorder
        repository.update(RecordingState())

        repository.start(
            "content://com.andyluu.debrief.invalid/tree/missing",
            "Delete this session.m4a",
        )
        awaitPhase(repository, RecordingPhase.RECORDING)
        val sessionId = requireNotNull(repository.state.value.sessionId)
        val output = RecordingOutput(application)
        delay(800)
        assertTrue(
            "Active capture should have a private session part before discard.",
            output.sessionParts(sessionId).isNotEmpty(),
        )

        repository.discard()
        awaitPhase(repository, RecordingPhase.IDLE)

        assertEquals(null, repository.state.value.sessionId)
        assertEquals("Recording deleted.", repository.state.value.statusMessage)
        assertTrue(
            "Discard must delete every private session part instead of exporting it.",
            output.sessionParts(sessionId).isEmpty(),
        )
    }

    private suspend fun awaitNotificationDismissed(repository: RecordingRepository) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !repository.state.value.notificationDismissed) {
            delay(50)
        }
        assertTrue(repository.state.value.notificationDismissed)
    }

    private suspend fun swipeAwayRecordingNotification(repository: RecordingRepository) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(3) {
            device.pressHome()
            assertTrue("Could not open the notification shade.", device.openNotification())
            device.waitForIdle()
            val notification = device.findObject(UiSelector().text("Debrief is recording"))
            assertTrue("The active recording notification was not visible.", notification.waitForExists(5_000))
            val centerY = notification.bounds.centerY()
            assertTrue(
                "The recording notification could not be swiped away.",
                device.swipe(device.displayWidth * 9 / 10, centerY, device.displayWidth / 10, centerY, 24),
            )
            device.pressBack()
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && !repository.state.value.notificationDismissed) {
                delay(50)
            }
            if (repository.state.value.notificationDismissed) return
        }
        assertTrue("Android did not deliver the notification dismissal.", repository.state.value.notificationDismissed)
    }

    private fun assertRecordingNotificationAbsent() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("Could not reopen the notification shade.", device.openNotification())
        val notification = device.findObject(UiSelector().text("Debrief is recording"))
        assertTrue(
            "The dismissed recording notification was visible again.",
            notification.waitUntilGone(2_000),
        )
        device.pressBack()
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
