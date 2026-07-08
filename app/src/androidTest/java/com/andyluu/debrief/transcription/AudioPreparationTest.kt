package com.andyluu.debrief.transcription

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyluu.debrief.data.TranscriptionAudioQuality
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPreparationTest {
    @Test
    fun originalQualityStreamsExactSourceBytesWithoutTranscoding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = "unchanged-original-audio".toByteArray()
        val source = File(context.cacheDir, "original-quality-test.m4a").apply { writeBytes(expected) }

        try {
            val prepared = AudioPreparer.prepare(
                context = context,
                source = Uri.fromFile(source),
                recordingId = "quality-test",
                sourceMimeType = "audio/mp4",
                sourceSizeBytes = expected.size.toLong(),
                quality = TranscriptionAudioQuality.ORIGINAL,
            )
            val sink = Buffer()
            prepared.body.writeTo(sink)

            assertArrayEquals(expected, sink.readByteArray())
            assertEquals(expected.size.toLong(), prepared.body.contentLength())
            assertEquals("audio/mp4", prepared.mimeType)
            prepared.cleanup()
            assertEquals(true, source.exists())
        } finally {
            source.delete()
        }
    }
}
