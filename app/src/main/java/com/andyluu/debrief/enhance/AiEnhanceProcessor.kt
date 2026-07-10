package com.andyluu.debrief.enhance

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.RepairEntity
import com.andyluu.debrief.data.RepairRunEntity
import com.andyluu.debrief.data.RepairRunMode
import com.andyluu.debrief.data.RepairRunStatus
import com.andyluu.debrief.data.SearchRepository
import com.andyluu.debrief.data.SettingsStore
import com.andyluu.debrief.data.SidecarStore
import com.andyluu.debrief.data.SuspectSpanEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.UsageStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.UUID

class AiEnhanceProcessor(
    private val context: Context,
    private val database: DebriefDatabase,
    private val settingsStore: SettingsStore,
    private val secrets: SecureSecretStore,
    private val search: SearchRepository,
    private val sidecars: SidecarStore,
    private val usage: UsageStore,
    private val provider: GeminiEnhanceProvider = GeminiEnhanceProvider(),
) {
    private val dao = database.dao()

    suspend fun runAuto(recordingId: String) = run(recordingId, RepairRunMode.AUTO, null, null)

    suspend fun runSelection(recordingId: String, startMs: Long, endMs: Long) {
        if (endMs - startMs > EnhanceConstants.SELECTION_SOFT_CAP_MS) {
            throw AiEnhanceException("Enhance Selection is capped at 15 minutes. Split this range into smaller parts.")
        }
        run(recordingId, RepairRunMode.SELECTION, startMs.coerceAtLeast(0L), endMs.coerceAtLeast(startMs + 1))
    }

    private suspend fun run(
        recordingId: String,
        mode: RepairRunMode,
        selectionStartMs: Long?,
        selectionEndMs: Long?,
    ) {
        val recording = dao.getRecording(recordingId) ?: throw AiEnhanceException("Recording not found")
        if (recording.status != RecordingStatus.READY) throw AiEnhanceException("Finish transcription before running AI Enhance")
        val ai = dao.getAiRecording(recordingId) ?: AiRecordingEntity(recordingId)
        if (ai.skipAiPass) throw AiEnhanceException("Turn off Skip AI for this recording before enhancing")
        val segments = dao.getSegments(recordingId)
        val words = dao.getWords(recordingId)
        if (segments.isEmpty()) throw AiEnhanceException("No transcript is available to enhance")
        val settings = settingsStore.settings.first()
        val key = secrets.get("gemini") ?: throw AiEnhanceException("Add your Gemini API key in Settings before running AI Enhance")
        val keyterms = settings.keyterms.lines().flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank)
        var suspects = dao.getSuspectSpans(recordingId)
        if (suspects.isEmpty()) {
            suspects = SuspectSpanDetector.detect(recordingId, words, recording.durationMs)
            dao.replaceSuspectSpans(recordingId, suspects)
        }

        val textChunks = when (mode) {
            RepairRunMode.AUTO -> buildAutoTextChunks(segments, suspects.filterNot { it.resolved })
            RepairRunMode.SELECTION -> listOf(
                TextChunk(
                    id = "selection-text",
                    startMs = selectionStartMs ?: 0L,
                    endMs = selectionEndMs ?: recording.durationMs,
                    segments = segments.overlapping(selectionStartMs ?: 0L, selectionEndMs ?: recording.durationMs),
                suspects = suspects.overlappingSpans(selectionStartMs ?: 0L, selectionEndMs ?: recording.durationMs),
                )
            )
        }.filter { it.segments.isNotEmpty() }

        val audioTargets = if (settings.aiAudioRelisten) {
            when (mode) {
                RepairRunMode.AUTO -> suspects.filterNot { it.resolved }.take(EnhanceConstants.AUTO_CLIP_CAP).map {
                    AudioTarget("sp_" + it.startMs + "_" + it.endMs, it.startMs, it.endMs, it.originalText)
                }
                RepairRunMode.SELECTION -> buildSelectionAudioTargets(
                    selectionStartMs ?: 0L,
                    selectionEndMs ?: recording.durationMs,
                    segments,
                )
            }
        } else {
            emptyList()
        }
        val audioBatchSize = if (mode == RepairRunMode.AUTO) 4 else 2
        val totalSteps = textChunks.size + audioTargets.chunked(audioBatchSize).size
        var run = RepairRunEntity(
            id = UUID.randomUUID().toString(),
            recordingId = recordingId,
            mode = mode,
            status = RepairRunStatus.RUNNING,
            provider = "gemini",
            stageLabel = if (totalSteps == 0) "Nothing to enhance" else "Starting AI Enhance",
            totalSteps = totalSteps,
            selectionStartMs = selectionStartMs,
            selectionEndMs = selectionEndMs,
        )
        dao.upsertRepairRun(run)

        var fixedCount = 0
        var inaudibleCount = 0
        val clipFiles = mutableListOf<java.io.File>()
        try {
            if (totalSteps == 0) {
                dao.upsertRepairRun(run.copy(status = RepairRunStatus.READY, stageLabel = "No rough spots found", updatedAt = now()))
                return
            }

            textChunks.forEachIndexed { index, chunk ->
                run = run.progress("Fixing from context ${index + 1} of ${textChunks.size}")
                dao.upsertRepairRun(run)
                val response = provider.repairText(buildTextPrompt(chunk, keyterms), key)
                response.edits.forEach { edit ->
                    if (RepairValidator.validateTextEdit(edit, chunk.fullText(), keyterms)) {
                        val repair = edit.toRepair(run.id, recordingId, source = "context")
                        dao.upsertRepair(repair)
                        if (repair.applied) {
                            fixedCount += if (repair.type == "inaudible") 0 else 1
                            inaudibleCount += if (repair.type == "inaudible") 1 else 0
                            dao.markSuspectSpansResolved(recordingId, repair.startMs, repair.endMs)
                        }
                    }
                }
                run = run.copy(completedSteps = run.completedSteps + 1, fixedCount = fixedCount, inaudibleCount = inaudibleCount, updatedAt = now())
                dao.upsertRepairRun(run)
                delay(EnhanceConstants.GEMINI_CALL_SPACING_MS)
            }

            audioTargets.chunked(audioBatchSize).forEachIndexed { index, batch ->
                run = run.progress("Re-listening ${index + 1} of ${audioTargets.chunked(audioBatchSize).size} clips")
                dao.upsertRepairRun(run)
                val clips = batch.mapNotNull { target ->
                    runCatching {
                        AudioClipper.clip(
                            context = context,
                            source = Uri.parse(recording.documentUri),
                            recordingId = recordingId,
                            spanId = target.id,
                            startMs = target.startMs,
                            endMs = target.endMs,
                        ).also { clipFiles += it }.let { EnhanceClip(target.id, it) }
                    }.getOrNull()
                }
                if (clips.isNotEmpty()) {
                    val response = provider.relisten(buildAudioPrompt(batch, segments, keyterms), clips, key)
                    response.results.filter(RepairValidator::validateAudioResult).forEach { result ->
                        val target = batch.firstOrNull { it.id == result.spanId } ?: return@forEach
                        val repair = result.toRepair(run.id, recordingId, target, clips.firstOrNull { it.spanId == target.id }?.file)
                        dao.upsertRepair(repair)
                        if (repair.applied) {
                            fixedCount += if (repair.type == "inaudible") 0 else 1
                            inaudibleCount += if (repair.type == "inaudible") 1 else 0
                            dao.markSuspectSpansResolved(recordingId, repair.startMs, repair.endMs)
                        }
                    }
                }
                run = run.copy(completedSteps = run.completedSteps + 1, fixedCount = fixedCount, inaudibleCount = inaudibleCount, updatedAt = now())
                dao.upsertRepairRun(run)
                delay(EnhanceConstants.GEMINI_CALL_SPACING_MS)
            }

            run = run.copy(
                status = RepairRunStatus.READY,
                stageLabel = "Done: $fixedCount fixed, $inaudibleCount still inaudible",
                fixedCount = fixedCount,
                inaudibleCount = inaudibleCount,
                errorMessage = null,
                updatedAt = now(),
            )
            dao.upsertRepairRun(run)
            usage.recordSuccess("ai_gemini", key, recording.durationMs)
            search.rebuild(recordingId)
            settings.folderUri?.let { uri ->
                DocumentFile.fromTreeUri(context, Uri.parse(uri))?.let { sidecars.write(it, recordingId) }
            }
        } catch (error: Throwable) {
            val partial = fixedCount > 0 || inaudibleCount > 0
            dao.upsertRepairRun(
                run.copy(
                    status = if (partial) RepairRunStatus.PARTIAL else RepairRunStatus.FAILED,
                    stageLabel = if (partial) "Partial failure; resume available" else "Enhance failed",
                    fixedCount = fixedCount,
                    inaudibleCount = inaudibleCount,
                    errorMessage = error.message?.take(300) ?: "AI Enhance failed",
                    updatedAt = now(),
                )
            )
            throw error
        }
    }

    suspend fun setRepairReverted(repairId: String, reverted: Boolean) {
        dao.setRepairReverted(repairId, reverted)
    }

    suspend fun setRepairApplied(repairId: String, applied: Boolean) {
        dao.setRepairApplied(repairId, applied)
    }

    private fun buildAutoTextChunks(
        segments: List<TranscriptSegmentEntity>,
        suspects: List<SuspectSpanEntity>,
    ): List<TextChunk> {
        if (suspects.isEmpty()) return emptyList()
        val chunks = mutableListOf<TextChunk>()
        var chunkStart = suspects.minOf { it.startMs }
        var chunkEnd = chunkStart
        suspects.sortedBy { it.startMs }.forEach { span ->
            val split = span.startMs - chunkStart > 15 * 60_000L || span.startMs - chunkEnd > 3 * 60_000L
            if (split) {
                chunks += textChunk("auto-${chunks.size}", chunkStart, chunkEnd, segments, suspects)
                chunkStart = span.startMs
            }
            chunkEnd = maxOf(chunkEnd, span.endMs)
        }
        chunks += textChunk("auto-${chunks.size}", chunkStart, chunkEnd, segments, suspects)
        return chunks
    }

    private fun textChunk(
        id: String,
        startMs: Long,
        endMs: Long,
        segments: List<TranscriptSegmentEntity>,
        suspects: List<SuspectSpanEntity>,
    ) = TextChunk(
        id = id,
        startMs = (startMs - 30_000L).coerceAtLeast(0L),
        endMs = endMs + 30_000L,
        segments = segments.overlapping((startMs - 30_000L).coerceAtLeast(0L), endMs + 30_000L),
        suspects = suspects.overlappingSpans(startMs, endMs),
    )

    private fun buildSelectionAudioTargets(
        startMs: Long,
        endMs: Long,
        segments: List<TranscriptSegmentEntity>,
    ): List<AudioTarget> {
        val targets = mutableListOf<AudioTarget>()
        var cursor = startMs
        while (cursor < endMs) {
            val next = minOf(cursor + EnhanceConstants.SELECTION_CHUNK_MS, endMs)
            targets += AudioTarget(
                id = "sel_${cursor}_$next",
                startMs = cursor,
                endMs = next,
                originalText = segments.overlapping(cursor, next).joinToString(" ") { it.text }.trim(),
            )
            cursor = next
        }
        return targets
    }

    private fun buildTextPrompt(chunk: TextChunk, keyterms: List<String>) = buildString {
        appendLine("SYSTEM")
        appendLine("You repair automatic speech recognition transcripts. Be conservative. Only correct words inside marked spans when surrounding context clearly justifies it. Never paraphrase, reorder, or invent proper nouns absent from keyterms or nearby raw text. If text alone is insufficient, set needsAudio true. Return JSON only.")
        appendLine()
        appendLine("KEYTERMS: " + keyterms.joinToString(", "))
        appendLine("MARKED SPANS:")
        chunk.suspects.forEachIndexed { index, span ->
            appendLine("sp_$index [${seconds(span.startMs)}-${seconds(span.endMs)}] confidence min=${"%.2f".format(span.minConfidence)} text=${span.originalText}")
        }
        appendLine("TRANSCRIPT:")
        chunk.segments.forEachIndexed { index, segment ->
            appendLine("u_$index [${seconds(segment.startMs)}] ${segment.speakerId}: ${segment.text}")
        }
    }

    private fun buildAudioPrompt(targets: List<AudioTarget>, segments: List<TranscriptSegmentEntity>, keyterms: List<String>) = buildString {
        appendLine("SYSTEM")
        appendLine("You transcribe short audio clips exactly. For each clip, transcribe only the target span using context and keyterms. If genuinely unintelligible, return verdict inaudible instead of guessing. Return JSON only.")
        appendLine("KEYTERMS: " + keyterms.joinToString(", "))
        targets.forEach { target ->
            appendLine("CLIP ${target.id}:")
            appendLine("target [${seconds(target.startMs)}-${seconds(target.endMs)}]")
            appendLine("context_before: ${segments.before(target.startMs)}")
            appendLine("current_guess: ${target.originalText}")
            appendLine("context_after: ${segments.after(target.endMs)}")
        }
    }

    private fun EnhanceEdit.toRepair(runId: String, recordingId: String, source: String): RepairEntity {
        val confidence = confidence.lowercase()
        val type = type.lowercase()
        return RepairEntity(
            id = runId + "-" + source + "-" + (span.start * 1000).toLong() + "-" + UUID.randomUUID(),
            runId = runId,
            recordingId = recordingId,
            startMs = (span.start * 1000).toLong().coerceAtLeast(0L),
            endMs = (span.end * 1000).toLong().coerceAtLeast((span.start * 1000).toLong() + 1),
            original = original.trim(),
            repaired = if (type == "inaudible") "[inaudible]" else repaired?.trim(),
            type = type,
            source = source,
            confidence = confidence,
            reason = reason.take(300),
            applied = confidence == "high" || type == "inaudible",
        )
    }

    private fun EnhanceAudioResult.toRepair(
        runId: String,
        recordingId: String,
        target: AudioTarget,
        clipFile: java.io.File?,
    ): RepairEntity {
        val inaudible = verdict == "inaudible" || heard.isNullOrBlank()
        return RepairEntity(
            id = runId + "-audio-" + target.id,
            runId = runId,
            recordingId = recordingId,
            startMs = target.startMs,
            endMs = target.endMs,
            original = target.originalText,
            repaired = if (inaudible) "[inaudible]" else heard?.trim(),
            type = if (inaudible) "inaudible" else "fix",
            source = "audio",
            confidence = confidence.lowercase(),
            reason = if (inaudible) "Gemini could not hear this clip clearly." else "Gemini re-listened to the short audio clip.",
            applied = confidence.equals("high", ignoreCase = true) || inaudible,
            clipUri = clipFile?.let { Uri.fromFile(it).toString() },
        )
    }

    private fun RepairRunEntity.progress(label: String) = copy(stageLabel = label, updatedAt = now())

    private fun List<TranscriptSegmentEntity>.overlapping(startMs: Long, endMs: Long) =
        filter { it.startMs <= endMs && it.endMs >= startMs }

    private fun List<SuspectSpanEntity>.overlappingSpans(startMs: Long, endMs: Long) =
        filter { it.startMs <= endMs && it.endMs >= startMs }

    private fun List<TranscriptSegmentEntity>.before(timestampMs: Long) =
        filter { it.endMs <= timestampMs }.takeLast(2).joinToString(" ") { it.text }.take(500)

    private fun List<TranscriptSegmentEntity>.after(timestampMs: Long) =
        filter { it.startMs >= timestampMs }.take(2).joinToString(" ") { it.text }.take(500)

    private fun TextChunk.fullText() = segments.joinToString(" ") { it.text }
    private fun seconds(ms: Long) = "%.1f".format(ms / 1000.0)
    private fun now() = System.currentTimeMillis()

    private data class TextChunk(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val segments: List<TranscriptSegmentEntity>,
        val suspects: List<SuspectSpanEntity>,
    )

    private data class AudioTarget(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val originalText: String,
    )
}
