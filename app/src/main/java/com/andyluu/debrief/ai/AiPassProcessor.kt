package com.andyluu.debrief.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.SearchRepository
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.SettingsStore
import com.andyluu.debrief.data.SidecarStore
import com.andyluu.debrief.data.UsageStore
import com.andyluu.debrief.data.SpeakerAliasEntity
import com.andyluu.debrief.data.SpeakerSuggestionEntity
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiPassProcessor(
    private val context: Context,
    private val database: DebriefDatabase,
    private val settingsStore: SettingsStore,
    private val secrets: SecureSecretStore,
    private val search: SearchRepository,
    private val sidecars: SidecarStore,
    private val renamer: RecordingRenamer,
    private val usage: UsageStore,
) {
    private val dao = database.dao()

    suspend fun run(recordingId: String, force: Boolean = false) {
        val recording = dao.getRecording(recordingId) ?: throw AiPassException("Recording not found")
        if (recording.status != RecordingStatus.READY) throw AiPassException("Finish transcription before running the AI pass")
        val settings = settingsStore.settings.first()
        val existing = dao.getAiRecording(recordingId) ?: AiRecordingEntity(recordingId)
        if (existing.skipAiPass && !force) {
            dao.upsertAiRecording(existing.copy(status = AiPassStatus.SKIPPED, errorMessage = null))
            return
        }
        val segments = dao.getSegments(recordingId)
        val words = dao.getWords(recordingId)
        if (segments.isEmpty()) throw AiPassException("No transcript is available for the AI pass")
        val heuristicSets = SetDetector.detect(
            recordingId = recordingId,
            durationMs = recording.durationMs,
            segments = segments,
            words = words,
            gapMinutes = settings.aiGapMinutes,
        )
        val previousSets = dao.getConversationSets(recordingId)
        val previousSuggestions = dao.getSpeakerSuggestions(recordingId)
        dao.replaceAiAnalysis(
            existing.copy(status = AiPassStatus.RUNNING, errorMessage = null, provider = settings.aiProvider),
            previousSets.ifEmpty { heuristicSets },
            previousSuggestions,
        )
        var latestAi = existing
        try {
            val secretName = when (settings.aiProvider) {
                "openai" -> "openai_compatible"
                "anthropic" -> "anthropic"
                else -> "gemini"
            }
            val key = secrets.get(secretName)
                ?: throw AiPassException("Add the " + providerLabel(settings.aiProvider) + " key in Settings")
            val provider: AiPassProvider = when (settings.aiProvider) {
                "openai" -> OpenAiCompatibleProvider(settings.openAiBaseUrl, settings.openAiModel)
                "anthropic" -> AnthropicAiProvider(settings.anthropicModel)
                else -> GeminiAiProvider()
            }
            val response = provider.analyze(
                AiPassRequest(buildPrompt(recording.displayName, recording.lastModified, segments, heuristicSets)),
                key,
            )
            usage.recordSuccess("ai_" + settings.aiProvider, key, recording.durationMs)
            val normalizedSets = normalizeSets(recordingId, recording.durationMs, response, segments, heuristicSets)
            val aliases = dao.getAliases(recordingId).associate { it.speakerId to it.displayName }
            val knownSpeakerIds = segments.mapTo(mutableSetOf()) { it.speakerId }
            val explicitSpeakers = response.speakers.filter {
                it.confidence.equals("explicit", true) &&
                    it.speakerId in knownSpeakerIds &&
                    it.name.isNotBlank()
            }
            explicitSpeakers.forEach {
                dao.upsertAlias(SpeakerAliasEntity(recordingId, it.speakerId, it.name.trim()))
            }
            val explicitNames = explicitSpeakers.associate { it.speakerId to it.name.trim() }
            val andyCandidate = response.andySpeakerId
                ?.takeIf { it in knownSpeakerIds }
                ?: normalizedSets.map { it.speakerIds.split('|').filter(String::isNotBlank).toSet() }
                    .takeIf { it.size > 1 }
                    ?.reduceOrNull(Set<String>::intersect)
                    ?.singleOrNull()
            val andySpeaker = andyCandidate?.takeIf {
                explicitNames[it].isNullOrBlank() || explicitNames[it].equals("Andy", ignoreCase = true)
            }
            if (andySpeaker != null && aliases[andySpeaker].isNullOrBlank()) {
                dao.upsertAlias(SpeakerAliasEntity(recordingId, andySpeaker, "Andy"))
            }
            val suggestions = response.speakers.filter {
                it.confidence.equals("inferred", true) &&
                    it.speakerId in knownSpeakerIds &&
                    it.name.isNotBlank() &&
                    it.speakerId != andySpeaker
            }.map {
                SpeakerSuggestionEntity(recordingId, it.speakerId, it.name.trim(), "inferred", it.evidence.trim())
            }
            val completed = existing.copy(
                summary = response.recordingSummary.trim(),
                originalDisplayName = existing.originalDisplayName ?: recording.displayName,
                suggestedFilename = response.suggestedFilename.trim().ifBlank { null },
                status = AiPassStatus.READY,
                errorMessage = null,
                provider = settings.aiProvider,
                lastRunAt = System.currentTimeMillis(),
            )
            latestAi = completed
            dao.replaceAiAnalysis(completed, normalizedSets, suggestions)
            search.rebuild(recordingId)
            if (response.suggestedFilename.isNotBlank()) {
                renamer.rename(recordingId, response.suggestedFilename)
                search.rebuild(recordingId)
            }
            settings.folderUri?.let { folderUri ->
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.let { sidecars.write(it, recordingId) }
            }
        } catch (error: Throwable) {
            dao.upsertAiRecording(
                latestAi.copy(
                    status = AiPassStatus.FAILED,
                    errorMessage = error.message?.take(300) ?: "AI pass failed",
                    provider = settings.aiProvider,
                    lastRunAt = System.currentTimeMillis(),
                )
            )
            throw error
        }
    }

    suspend fun undoRename(recordingId: String) {
        val ai = dao.getAiRecording(recordingId) ?: return
        val original = ai.originalDisplayName ?: return
        renamer.rename(recordingId, original)
        search.rebuild(recordingId)
        settingsStore.settings.first().folderUri?.let { folderUri ->
            DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.let { sidecars.write(it, recordingId) }
        }
    }

    private fun buildPrompt(
        filename: String,
        lastModified: Long,
        segments: List<com.andyluu.debrief.data.TranscriptSegmentEntity>,
        candidates: List<ConversationSetEntity>,
    ) = buildString {
        appendLine("Analyze this transcript only. Never claim to inspect audio.")
        appendLine("Return one structured result that detects conversation sets, identifies speakers, summarizes, and proposes a filename.")
        appendLine("Use explicit names with confidence=explicit. Use confidence=inferred when uncertain; use none with an empty name when unsupported.")
        appendLine("Identify Andy only when evidence or cross-set speaker continuity supports it; otherwise return andySpeakerId=null.")
        appendLine("Keep every transcript moment covered by exactly one chronological set. Summaries must be one or two lines.")
        appendLine("Filename must be filesystem-safe, concise, and follow: YYYY-MM-DD - N sets - key people or moments. Do not include an extension.")
        appendLine("Current filename: " + filename)
        appendLine("Recording date: " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastModified)))
        appendLine("Deterministic silence-gap candidates:")
        candidates.forEach { appendLine("- " + it.startMs + ".." + it.endMs) }
        appendLine("Transcript:")
        segments.forEach {
            appendLine("[" + it.startMs + "-" + it.endMs + "] " + it.speakerId + ": " + it.text)
        }
    }

    private fun normalizeSets(
        recordingId: String,
        durationMs: Long,
        response: AiPassResponse,
        segments: List<com.andyluu.debrief.data.TranscriptSegmentEntity>,
        fallback: List<ConversationSetEntity>,
    ): List<ConversationSetEntity> {
        if (response.sets.isEmpty()) return fallback
        val endOfRecording = maxOf(durationMs, segments.maxOfOrNull { it.endMs } ?: 1L)
        val sorted = response.sets
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs.coerceIn(0, endOfRecording - 1) }
        if (sorted.isEmpty()) return fallback
        return sorted.mapIndexed { index, result ->
            val start = if (index == 0) 0 else result.startMs.coerceIn(0, endOfRecording - 1)
            val end = (sorted.getOrNull(index + 1)?.startMs?.minus(1) ?: endOfRecording)
                .coerceIn(start + 1, endOfRecording)
            val speakerIds = result.speakerIds.filter(String::isNotBlank).ifEmpty {
                segments.filter { it.endMs >= start && it.startMs <= end }.map { it.speakerId }.distinct()
            }
            ConversationSetEntity(
                id = recordingId + "-set-" + (index + 1) + "-" + start,
                recordingId = recordingId,
                orderIndex = index,
                startMs = start,
                endMs = end,
                title = result.title.trim().ifBlank { "Set " + (index + 1) },
                summary = result.summary.trim(),
                speakerIds = speakerIds.joinToString("|"),
            )
        }
    }

    private fun providerLabel(provider: String) = when (provider) {
        "openai" -> "OpenAI-compatible"
        "anthropic" -> "Anthropic"
        else -> "Gemini"
    }
}
