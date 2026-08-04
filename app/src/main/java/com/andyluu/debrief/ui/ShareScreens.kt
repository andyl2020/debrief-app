@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andyluu.debrief.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.andyluu.debrief.data.ShareDraftEntity
import com.andyluu.debrief.data.ShareDraftStatus
import com.andyluu.debrief.data.SharedLinkEntity
import com.andyluu.debrief.data.SharedLinkStatus
import com.andyluu.debrief.share.SHARE_STORAGE_REFERENCE_BYTES
import com.andyluu.debrief.share.CloudStorageWarningLevel
import com.andyluu.debrief.share.cloudStorageWarning
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

@Composable
fun ShareReviewScreen(
    viewModel: ShareReviewViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var expiryDays by rememberSaveable { mutableIntStateOf(30) }
    var pinEnabled by rememberSaveable { mutableStateOf(false) }
    var pin by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.preview?.recordingName) {
        if (title.isBlank()) title = state.preview?.recordingName?.substringBeforeLast('.') ?: ""
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Share sets") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text("Building private preview…", Modifier.padding(top = 12.dp))
            }
            state.preview == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("These sets cannot be shared", style = MaterialTheme.typography.titleLarge)
                Text(state.error ?: "Return to Chapters and select completed sets from one recording.")
                OutlinedButton(onClick = onBack) { Text("Back to recording") }
            }
            else -> {
                val preview = state.preview!!
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "Review exactly what will be shared",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "${preview.sets.size} sets · ${formatShareDuration(preview.totalDurationMs)} · ${preview.commentCount} comments",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(160) },
                            label = { Text("Link title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text("Expires after", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 60, 90).forEach { days ->
                                FilterChip(
                                    selected = expiryDays == days,
                                    onClick = { expiryDays = days },
                                    label = { Text("$days days") },
                                )
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, null)
                                    Text("Privacy snapshot", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                                }
                                Text("Only these sets, their transcript, audio, and every comment inside their boundaries are included.")
                                Text("${preview.redactionCount} stored redactions will be permanently applied to the shared text and audio. Your original recording stays unchanged.")
                                Text("The page has no download button, but recipients can still screen-record or capture content.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(preview.sets, key = { it.id }) { set ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(set.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatShareDuration(set.durationMs)} · ${set.transcriptSegmentCount} transcript parts · ${set.commentCount} comments",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (set.redactionCount > 0) {
                                    Text("${set.redactionCount} redactions permanently rendered", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Require a PIN", fontWeight = FontWeight.SemiBold)
                                Text("Optional 6–12 digit code, shared separately.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = pinEnabled, onCheckedChange = { pinEnabled = it; if (!it) pin = "" })
                        }
                    }
                    if (pinEnabled) {
                        item {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                                label = { Text("6–12 digit PIN") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    state.error?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }
                    if (!state.paired) {
                        item {
                            Text(
                                "Connect Cloud sharing from Settings > Cloud sharing before creating this link.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { viewModel.create(title, expiryDays, pin.takeIf { pinEnabled }, onCreated) },
                            enabled = state.paired && !state.creating && title.isNotBlank() && (!pinEnabled || pin.length in 6..12),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Share, null)
                            Text(if (state.creating) "Securing snapshot…" else "Create private link", Modifier.padding(start = 8.dp))
                        }
                        Text(
                            "Preparation continues safely in the background. Partial uploads are checkpointed and resumable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SharedLinksScreen(viewModel: CloudSharingViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val usageWarning = state.usage?.let { cloudStorageWarning(it.currentBytes, it.referenceBytes) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var baseUrl by rememberSaveable(state.settings.cloudShareBaseUrl) { mutableStateOf(state.settings.cloudShareBaseUrl) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var revokeTarget by remember { mutableStateOf<SharedLinkEntity?>(null) }
    var cancelTarget by remember { mutableStateOf<ShareDraftEntity?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(usageWarning?.level) {
        if (usageWarning != null && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Shared links") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = state.paired && !state.refreshing) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh cloud sharing")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.paired) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cloud, null)
                                Text("Connect private sharing", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text("Enter the deployed Debrief share-service URL and its one-use pairing code. Cloudflare account secrets never go into the app.")
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("HTTPS share-service URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = pairingCode,
                                onValueChange = { pairingCode = it.trim().take(80) },
                                label = { Text("One-use pairing code") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { viewModel.pair(baseUrl, pairingCode) },
                                enabled = !state.pairing && baseUrl.isNotBlank() && pairingCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.pairing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(if (state.pairing) "Connecting…" else "Connect", Modifier.padding(start = if (state.pairing) 8.dp else 0.dp))
                            }
                        }
                    }
                }
            } else {
                item { CloudUsageCard(state.usage) }
                val activeDrafts = state.drafts.filter { it.status !in setOf(ShareDraftStatus.READY, ShareDraftStatus.CANCELLED) }
                if (activeDrafts.isNotEmpty()) {
                    item { SectionTitle("Preparing") }
                    items(activeDrafts, key = { it.id }) { draft ->
                        ShareDraftCard(
                            draft = draft,
                            onResume = { viewModel.resume(draft.id) },
                            onCancel = { cancelTarget = draft },
                        )
                    }
                }
                item { SectionTitle("Active links") }
                val activeLinks = state.links.filter { it.status == SharedLinkStatus.ACTIVE && it.expiresAt > System.currentTimeMillis() }
                if (activeLinks.isEmpty()) {
                    item {
                        Text(
                            "No active links. Open a recording, open Chapters, select completed sets, and tap Share.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(activeLinks, key = { it.id }) { link ->
                        SharedLinkCard(
                            link = link,
                            context = context,
                            onExtend = { viewModel.extend(link.id, it) },
                            onRevoke = { revokeTarget = link },
                        )
                    }
                }
                val history = state.links.filter { it !in activeLinks }
                if (history.isNotEmpty()) {
                    item { SectionTitle("Expired and revoked") }
                    items(history, key = { it.id }) { link ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(link.title, fontWeight = FontWeight.SemiBold)
                                Text("${link.status.name.lowercase().replaceFirstChar(Char::uppercase)} · ${formatBytes(link.totalSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    revokeTarget?.let { link ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke this link?") },
            text = { Text("Access stops immediately. Expired or revoked cloud data cannot be restored.") },
            confirmButton = { TextButton(onClick = { viewModel.revoke(link.id); revokeTarget = null }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Keep link") } },
        )
    }
    cancelTarget?.let { draft ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel share preparation?") },
            text = { Text("Local temporary files and any remote staging objects for this draft will be removed.") },
            confirmButton = { TextButton(onClick = { viewModel.cancel(draft.id); cancelTarget = null }) { Text("Cancel draft") } },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("Keep preparing") } },
        )
    }
}

@Composable
internal fun CloudUsageCard(usage: com.andyluu.debrief.data.CloudUsageEntity?) {
    val reference = usage?.referenceBytes?.takeIf { it > 0 } ?: SHARE_STORAGE_REFERENCE_BYTES
    val current = usage?.currentBytes?.coerceAtLeast(0) ?: 0L
    val ratio = (current.toDouble() / reference.toDouble()).coerceIn(0.0, 1.0).toFloat()
    val warning = cloudStorageWarning(current, reference)
    var showExplanation by remember { mutableStateOf(false) }
    val warningColor = when {
        ratio >= 1f -> MaterialTheme.colorScheme.errorContainer
        ratio >= .9f -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = warningColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cloudflare storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${usage?.activeLinks ?: 0} active")
                Box {
                    IconButton(onClick = { showExplanation = true }) {
                        Icon(Icons.Default.Info, "Explain Cloudflare storage billing")
                    }
                    DropdownMenu(expanded = showExplanation, onDismissRequest = { showExplanation = false }) {
                        Text(
                            "Warnings begin at 9 GB. Cloudflare's free 10 GB-month allowance uses average daily peak storage across the calendar month; it is not a hard capacity that resets instantly. Deleting earlier lowers the monthly average, but usage already accrued remains until the next month.",
                            modifier = Modifier.width(300.dp).padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Text("${formatBytes(current)} of ${formatBytes(reference)}")
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            Text(
                "10 GB is the R2 Standard free-tier monthly GB-month reference. This bar shows current tracked bytes; Cloudflare bills GB-month from average daily peak storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            warning?.let {
                val deadline = formatDate(it.deadlineMillis)
                Text(
                    if (it.level == CloudStorageWarningLevel.EXCEEDED) {
                        "Free-tier reference exceeded. Reduce storage before $deadline to lower this month's average; Cloudflare may charge if monthly usage remains above its free allowance."
                    } else {
                        "Near the free allowance. Keep storage below 10 GB before $deadline to reduce the risk of R2 storage charges."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (it.level == CloudStorageWarningLevel.EXCEEDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            usage?.billingNote?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ShareDraftCard(draft: ShareDraftEntity, onResume: () -> Unit, onCancel: () -> Unit) {
    val progress = if (draft.totalSteps > 0) draft.completedSteps.toFloat() / draft.totalSteps else 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(draft.title, fontWeight = FontWeight.SemiBold)
            Text(draft.stageLabel, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            draft.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.errorMessage != null || draft.status == ShareDraftStatus.FAILED) {
                    OutlinedButton(onClick = onResume) { Text("Resume") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SharedLinkCard(
    link: SharedLinkEntity,
    context: Context,
    onExtend: (Int) -> Unit,
    onRevoke: () -> Unit,
) {
    val remaining = ceil((link.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) / 86_400_000.0).toInt()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(link.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${link.setCount} sets · ${formatShareDuration(link.totalDurationMs)} · ${formatBytes(link.totalSizeBytes)}")
            Text("Expires ${formatDate(link.expiresAt)} · $remaining days remaining", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { copyLink(context, link.url) }, enabled = link.url.isNotBlank()) { Icon(Icons.Default.ContentCopy, "Copy link") }
                IconButton(onClick = { shareLink(context, link) }, enabled = link.url.isNotBlank()) { Icon(Icons.Default.Share, "Share link") }
                IconButton(onClick = { openLink(context, link.url) }, enabled = link.url.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Launch, "Open link") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRevoke) { Icon(Icons.Default.Delete, "Revoke link") }
            }
            HorizontalDivider()
            Text("Extend from today", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90).forEach { days ->
                    FilterChip(selected = false, onClick = { onExtend(days) }, label = { Text("$days days") })
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Debrief share link", url))
}

private fun shareLink(context: Context, link: SharedLinkEntity) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, link.title)
        putExtra(Intent.EXTRA_TEXT, link.url)
    }, "Share private Debrief link"))
}

private fun openLink(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun formatDate(timestamp: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))

internal fun formatShareDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
