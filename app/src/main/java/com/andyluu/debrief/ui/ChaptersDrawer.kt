package com.andyluu.debrief.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.SpeakerSuggestionEntity

internal enum class ChapterEntryType { SET, COMMENT }

internal data class ChapterEntry(
    val id: String,
    val timestampMs: Long,
    val type: ChapterEntryType,
    val title: String,
    val detail: String = "",
    val endMs: Long? = null,
    val colorIndex: Int? = null,
)

internal fun buildChapterEntries(
    sets: List<ConversationSetEntity>,
    comments: List<CommentEntity>,
): List<ChapterEntry> = (
    sets.map { set ->
        ChapterEntry(
            id = "set:${set.id}",
            timestampMs = set.startMs,
            type = ChapterEntryType.SET,
            title = set.title.ifBlank { "Set ${set.orderIndex + 1}" },
            detail = if (set.isOpenManualSet()) {
                "Open set. Mark an end point when this section finishes."
            } else {
                listOf(
                    "${formatTimestamp(set.startMs)} to ${formatTimestamp(set.endMs)}",
                    set.summary,
                ).filter(String::isNotBlank).joinToString(" · ")
            },
            endMs = set.endMs,
            colorIndex = set.orderIndex,
        )
    } + comments.map { comment ->
        ChapterEntry(
            id = "comment:${comment.id}",
            timestampMs = comment.timestampMs,
            type = ChapterEntryType.COMMENT,
            title = comment.text,
        )
    }
).sortedWith(compareBy<ChapterEntry> { it.timestampMs }.thenBy { it.type.ordinal })

private val manualSetContainerColors = listOf(
    Color(0xFFE8F5E9),
    Color(0xFFE3F2FD),
    Color(0xFFFFF3E0),
    Color(0xFFF3E5F5),
    Color(0xFFE0F2F1),
)

private val manualSetAccentColors = listOf(
    Color(0xFF2E7D32),
    Color(0xFF1565C0),
    Color(0xFFEF6C00),
    Color(0xFF6A1B9A),
    Color(0xFF00695C),
)

internal fun manualSetContainerColor(index: Int): Color =
    manualSetContainerColors[index.floorMod(manualSetContainerColors.size)]

internal fun manualSetAccentColor(index: Int): Color =
    manualSetAccentColors[index.floorMod(manualSetAccentColors.size)]

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

@Composable
internal fun ChaptersDrawerContent(
    recording: RecordingEntity?,
    ai: AiRecordingEntity?,
    sets: List<ConversationSetEntity>,
    comments: List<CommentEntity>,
    suggestions: List<SpeakerSuggestionEntity>,
    aliases: Map<String, String>,
    positionMs: Long,
    onClose: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onUndoRename: () -> Unit,
    onConfirmSuggestion: (SpeakerSuggestionEntity) -> Unit,
    onSeek: (Long) -> Unit,
    onEditSet: (ConversationSetEntity) -> Unit,
    onDeleteSet: (ConversationSetEntity) -> Unit,
    onMerge: (String) -> Unit,
    onSplit: (String, Long) -> Unit,
) {
    val skipped = ai?.skipAiPass == true
    val entries = buildChapterEntries(sets, comments)
    val activeSet = sets.lastOrNull { it.containsPosition(positionMs) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Chapters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${sets.size} sets · ${comments.size} comments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close chapters") }
            }
        }
        item {
            AiChapterOverview(
                recording = recording,
                ai = ai,
                suggestions = suggestions,
                aliases = aliases,
                skipped = skipped,
                onSkip = onSkip,
                onUndoRename = onUndoRename,
                onConfirmSuggestion = onConfirmSuggestion,
            )
        }
        if (sets.isEmpty()) {
            item {
                Text(
                    "No manual sets yet. Long-press Add Comment, then use Set start and Set end.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Add a comment or manual set marker to create chapter entries.",
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(entries, key = ChapterEntry::id) { entry ->
                val set = if (entry.type == ChapterEntryType.SET) {
                    sets.firstOrNull { "set:${it.id}" == entry.id }
                } else null
                val setIndex = set?.let(sets::indexOf) ?: -1
                ChapterEntryCard(
                    entry = entry,
                    active = set?.id == activeSet?.id,
                    canMerge = set != null && setIndex in 0 until sets.lastIndex,
                    onSeek = onSeek,
                    onEditSet = { set?.let(onEditSet) },
                    onDeleteSet = { set?.let(onDeleteSet) },
                    onMerge = { set?.let { onMerge(it.id) } },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { activeSet?.let { onSplit(it.id, positionMs) } },
                enabled = activeSet != null && positionMs > activeSet.startMs + 1_000 && positionMs < activeSet.endMs - 1_000,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { Text("Split active set at ${formatTimestamp(positionMs)}") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun AiChapterOverview(
    recording: RecordingEntity?,
    ai: AiRecordingEntity?,
    suggestions: List<SpeakerSuggestionEntity>,
    aliases: Map<String, String>,
    skipped: Boolean,
    onSkip: (Boolean) -> Unit,
    onUndoRename: () -> Unit,
    onConfirmSuggestion: (SpeakerSuggestionEntity) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI analysis", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (ai?.status) {
                            AiPassStatus.RUNNING -> "Analyzing transcript…"
                            AiPassStatus.READY -> "Analysis ready"
                            AiPassStatus.FAILED -> "Needs attention"
                            AiPassStatus.SKIPPED -> "Skipped for this recording"
                            else -> "Not run"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = skipped,
                    onCheckedChange = onSkip,
                    modifier = Modifier.semantics { contentDescription = "Skip AI pass" },
                )
            }
            ai?.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ai?.summary?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onConfirmSuggestion(suggestion) },
                    label = { Text("Confirm ${aliases[suggestion.speakerId] ?: suggestion.speakerId} as ${suggestion.suggestedName}") },
                )
                if (suggestion.evidence.isNotBlank()) {
                    Text(
                        suggestion.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ai?.originalDisplayName != null && recording?.displayName != ai.originalDisplayName) {
                OutlinedButton(onClick = onUndoRename) { Text("Undo recording rename") }
            }
        }
    }
}

@Composable
private fun ChapterEntryCard(
    entry: ChapterEntry,
    active: Boolean,
    canMerge: Boolean,
    onSeek: (Long) -> Unit,
    onEditSet: () -> Unit,
    onDeleteSet: () -> Unit,
    onMerge: () -> Unit,
) {
    val entryLabel = if (entry.type == ChapterEntryType.SET) "set" else "comment"
    val containerColor = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        entry.type == ChapterEntryType.SET && entry.colorIndex != null -> manualSetContainerColor(entry.colorIndex)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .semantics { contentDescription = "Go to $entryLabel at ${formatTimestamp(entry.timestampMs)}" }
            .clickable { onSeek(entry.timestampMs) },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (entry.type == ChapterEntryType.SET) "SET" else "COMMENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.type == ChapterEntryType.SET && entry.colorIndex != null) manualSetAccentColor(entry.colorIndex) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(formatTimestamp(entry.timestampMs), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                entry.title,
                Modifier.padding(top = 4.dp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.type == ChapterEntryType.SET) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onEditSet,
                        modifier = Modifier.semantics { contentDescription = "Edit set ${entry.title}" },
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                    TextButton(
                        onClick = onDeleteSet,
                        modifier = Modifier.semantics { contentDescription = "Delete set ${entry.title}" },
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                    if (canMerge) TextButton(onClick = onMerge) { Text("Merge next") }
                }
            }
        }
    }
}
