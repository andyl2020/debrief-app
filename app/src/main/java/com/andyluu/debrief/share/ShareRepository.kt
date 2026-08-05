package com.andyluu.debrief.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.andyluu.debrief.data.CloudUsageEntity
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.SettingsStore
import com.andyluu.debrief.data.ShareDraftStatus
import com.andyluu.debrief.data.SharePartEntity
import com.andyluu.debrief.data.SharePartStatus
import com.andyluu.debrief.data.SharedLinkEntity
import com.andyluu.debrief.data.SharedLinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

class ShareRepository(
    private val context: Context,
    private val database: DebriefDatabase,
    private val settings: SettingsStore,
    private val secrets: SecureSecretStore,
    private val client: CloudShareClient = CloudShareClient(),
) {
    private val dao = database.dao()
    private val snapshots = ShareSnapshotBuilder(context, database)
    private val exporter = ShareAudioExporter(context)
    private val storageWarnings = CloudStorageWarningNotifier(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val sharedLinks: Flow<List<SharedLinkEntity>> = dao.observeSharedLinks()
    val cloudUsage: Flow<CloudUsageEntity?> = dao.observeCloudUsage()
    val drafts = dao.observeShareDrafts()

    fun isPaired(): Boolean = secrets.has(OWNER_TOKEN_SECRET)

    suspend fun pair(baseUrl: String, code: String) {
        val cleanBase = validateBaseUrl(baseUrl)
        val response = client.pair(
            cleanBase,
            PairDeviceRequest(code.trim(), "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
        )
        settings.setCloudShareBaseUrl(cleanBase)
        secrets.put(OWNER_TOKEN_SECRET, response.ownerToken)
        refresh()
    }

    suspend fun preview(recordingId: String, setIds: Collection<String>): ShareSelectionPreview =
        snapshots.preview(recordingId, setIds)

    suspend fun createAndEnqueue(
        recordingId: String,
        setIds: Collection<String>,
        title: String,
        expiryDays: Int,
        pin: String?,
    ): String {
        requireConfigured()
        val cleanPin = pin?.trim()?.takeIf(String::isNotBlank)
        if (cleanPin != null) require(cleanPin.matches(Regex("\\d{6,12}"))) { "A share PIN must contain 6 to 12 digits." }
        val draftId = snapshots.createDraft(recordingId, setIds, title, expiryDays, cleanPin != null)
        if (cleanPin != null) secrets.put(pinSecret(draftId), cleanPin)
        enqueue(draftId, replace = true)
        return draftId
    }

    fun resume(draftId: String) = enqueue(draftId, replace = true)

    suspend fun recoverPending() {
        if (configurationOrNull() == null) return
        drafts.first()
            .filter { it.status in setOf(ShareDraftStatus.READY_TO_UPLOAD, ShareDraftStatus.UPLOADING, ShareDraftStatus.PUBLISHING) }
            .forEach { enqueue(it.id, replace = false) }
    }

    suspend fun cancel(draftId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(draftId))
        val draft = dao.getShareDraft(draftId) ?: return
        val configuration = configurationOrNull()
        if (draft.serverDraftId != null && configuration != null) {
            runCatching { client.cancelDraft(configuration.first, configuration.second, draft.serverDraftId) }
        }
        cleanupDraftFiles(draftId)
        secrets.remove(pinSecret(draftId))
        dao.deleteShareDraft(draftId)
    }

    suspend fun refresh() {
        val (baseUrl, token) = configurationOrNull() ?: return
        val usage = client.usage(baseUrl, token)
        dao.upsertCloudUsage(
            CloudUsageEntity(
                currentBytes = usage.currentBytes,
                referenceBytes = usage.referenceBytes,
                activeLinks = usage.activeLinks,
                providerBytes = usage.providerMetric?.currentBytes,
                measuredAt = usage.measuredAt,
                source = usage.source,
                billingNote = usage.billingNote,
            )
        )
        storageWarnings.update(usage.currentBytes, usage.referenceBytes)
        val remote = client.shares(baseUrl, token)
        remote.shares.forEach { item ->
            val existing = dao.getSharedLink(item.id)
            val status = when (item.status) {
                "ACTIVE" -> SharedLinkStatus.ACTIVE
                "REVOKED" -> SharedLinkStatus.REVOKED
                "EXPIRED" -> SharedLinkStatus.EXPIRED
                else -> SharedLinkStatus.FAILED
            }
            dao.upsertSharedLink(
                SharedLinkEntity(
                    id = item.id,
                    recordingId = existing?.recordingId,
                    title = item.title,
                    url = existing?.url.orEmpty(),
                    status = status,
                    expiryDays = item.expiryDays,
                    setCount = item.setCount,
                    totalDurationMs = item.totalDurationMs,
                    totalSizeBytes = item.totalSizeBytes,
                    createdAt = item.createdAt,
                    publishedAt = item.publishedAt ?: existing?.publishedAt ?: item.createdAt,
                    expiresAt = item.expiresAt ?: existing?.expiresAt ?: item.createdAt,
                    revokedAt = item.revokedAt,
                    lastSyncedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun extend(shareId: String, expiryDays: Int) {
        require(expiryDays in setOf(30, 60, 90))
        val (baseUrl, token) = requireConfigured()
        val response = client.extend(baseUrl, token, shareId, expiryDays)
        val existing = dao.getSharedLink(shareId) ?: return
        dao.upsertSharedLink(
            existing.copy(
                expiryDays = response.expiryDays,
                expiresAt = response.expiresAt,
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
        refresh()
    }

    suspend fun revoke(shareId: String) {
        val (baseUrl, token) = requireConfigured()
        client.revoke(baseUrl, token, shareId)
        dao.getSharedLink(shareId)?.let { existing ->
            dao.upsertSharedLink(
                existing.copy(
                    status = SharedLinkStatus.REVOKED,
                    revokedAt = System.currentTimeMillis(),
                    lastSyncedAt = System.currentTimeMillis(),
                )
            )
        }
        refresh()
    }

    internal suspend fun processDraft(
        draftId: String,
        onProgress: suspend (stage: String, completed: Int, total: Int) -> Unit,
    ) {
        var draft = dao.getShareDraft(draftId) ?: return
        val (baseUrl, ownerToken) = requireConfigured()
        var parts = dao.getShareParts(draftId)
        require(parts.isNotEmpty()) { "The private share snapshot has no selected sets." }
        val recording = dao.getRecording(draft.recordingId) ?: throw IllegalStateException("The source recording is no longer available.")
        val source = Uri.parse(recording.documentUri)
        val totalSteps = parts.size * 3 + 2
        updateDraft(draft.copy(status = ShareDraftStatus.UPLOADING, stageLabel = "Starting secure upload", totalSteps = totalSteps, errorMessage = null))
        draft = dao.getShareDraft(draftId)!!

        val remote = if (draft.serverDraftId == null) {
            val response = client.createDraft(
                baseUrl,
                ownerToken,
                CreateCloudDraftRequest(
                    title = draft.title,
                    expiryDays = draft.expiryDays,
                    pin = secrets.get(pinSecret(draftId)),
                    sets = parts.map { part ->
                        CreateCloudSetRequest(
                            clientSetId = part.setId,
                            title = part.title,
                            durationMs = part.durationMs,
                            expectedMetadataBytes = part.metadataSizeBytes,
                        )
                    },
                ),
            )
            updateDraft(draft.copy(serverDraftId = response.draftId, stageLabel = "Cloud draft secured", updatedAt = System.currentTimeMillis()))
            response
        } else {
            client.inspectDraft(baseUrl, ownerToken, draft.serverDraftId)
        }
        draft = dao.getShareDraft(draftId)!!
        parts = assignRemoteObjects(parts, remote)
        parts = reconcileRemoteCompletion(parts, remote)
        var completed = parts.sumOf { (if (it.metadataUploaded) 1 else 0) + (if (it.audioUploaded) 2 else 0) }

        for (originalPart in parts) {
            var part = dao.getSharePart(originalPart.id) ?: continue
            val remoteSet = remote.sets.first { it.clientSetId == part.setId }
            val metadataObject = remoteSet.objects.first { it.kind == "METADATA" }
            val audioObject = remoteSet.objects.first { it.kind == "AUDIO" }
            if (!part.metadataUploaded) {
                val metadataFile = File(part.metadataPath)
                require(metadataFile.isFile && metadataFile.length() == part.metadataSizeBytes) { "The private transcript snapshot is missing or changed." }
                onProgress("Uploading transcript for ${part.title}", completed, totalSteps)
                val existing = decodeParts(part.metadataPartsJson)
                val uploaded = client.uploadFile(baseUrl, ownerToken, metadataObject.partUrl, metadataFile, existing) { checkpoint, _, _ ->
                    part = part.copy(metadataPartsJson = json.encodeToString(checkpoint), status = SharePartStatus.UPLOADING, updatedAt = System.currentTimeMillis())
                    dao.upsertSharePart(part)
                }
                client.completeObject(
                    baseUrl,
                    ownerToken,
                    metadataObject.completeUrl,
                    CompleteObjectRequest(uploaded, metadataFile.length(), part.metadataSha256 ?: sha256(metadataFile)),
                )
                part = part.copy(metadataUploaded = true, metadataPartsJson = json.encodeToString(uploaded), updatedAt = System.currentTimeMillis())
                dao.upsertSharePart(part)
                completed += 1
            }
            if (!part.audioUploaded) {
                val audioFile = part.audioPath?.let(::File)?.takeIf(File::isFile) ?: File(part.metadataPath).parentFile!!.resolve("set_${part.position.toString().padStart(2, '0')}_audio.m4a")
                if (!audioFile.isFile || audioFile.length() == 0L) {
                    onProgress(if (part.redactionRangesJson == "[]") "Preparing ${part.title}" else "Permanently applying redactions to ${part.title}", completed, totalSteps)
                    part = part.copy(status = SharePartStatus.PREPARING_AUDIO, updatedAt = System.currentTimeMillis())
                    dao.upsertSharePart(part)
                    exporter.export(source, audioFile, part.sourceStartMs, part.sourceEndMs, decodeMuteRanges(part.redactionRangesJson))
                    part = part.copy(
                        audioPath = audioFile.absolutePath,
                        audioSizeBytes = audioFile.length(),
                        audioSha256 = sha256(audioFile),
                        status = SharePartStatus.READY_TO_UPLOAD,
                        updatedAt = System.currentTimeMillis(),
                    )
                    dao.upsertSharePart(part)
                    completed += 1
                }
                onProgress("Uploading audio for ${part.title}", completed, totalSteps)
                val existing = decodeParts(part.audioPartsJson)
                val uploaded = client.uploadFile(baseUrl, ownerToken, audioObject.partUrl, audioFile, existing) { checkpoint, _, _ ->
                    part = part.copy(audioPartsJson = json.encodeToString(checkpoint), status = SharePartStatus.UPLOADING, updatedAt = System.currentTimeMillis())
                    dao.upsertSharePart(part)
                }
                client.completeObject(
                    baseUrl,
                    ownerToken,
                    audioObject.completeUrl,
                    CompleteObjectRequest(uploaded, audioFile.length(), part.audioSha256 ?: sha256(audioFile)),
                )
                audioFile.delete()
                part = part.copy(
                    audioPath = null,
                    audioUploaded = true,
                    audioPartsJson = json.encodeToString(uploaded),
                    status = SharePartStatus.COMPLETE,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.upsertSharePart(part)
                completed += 1
            }
            updateDraft(draft.copy(completedSteps = completed, stageLabel = "${part.title} secured", updatedAt = System.currentTimeMillis()))
            draft = dao.getShareDraft(draftId)!!
        }

        val publicToken = draft.publicToken ?: generatePublicToken().also { token ->
            updateDraft(draft.copy(publicToken = token, status = ShareDraftStatus.PUBLISHING, stageLabel = "Activating private link", completedSteps = completed, updatedAt = System.currentTimeMillis()))
            draft = dao.getShareDraft(draftId)!!
        }
        onProgress("Activating private link", completed, totalSteps)
        val publication = client.publish(baseUrl, ownerToken, remote.draftId, publicToken)
        val publishedAt = System.currentTimeMillis()
        dao.upsertSharedLink(
            SharedLinkEntity(
                id = publication.shareId,
                recordingId = draft.recordingId,
                title = draft.title,
                url = publication.url,
                expiryDays = draft.expiryDays,
                setCount = parts.size,
                totalDurationMs = parts.sumOf(SharePartEntity::durationMs),
                totalSizeBytes = publication.sizeBytes,
                createdAt = draft.createdAt,
                publishedAt = publishedAt,
                expiresAt = publication.expiresAt,
                lastSyncedAt = publishedAt,
            )
        )
        updateDraft(
            draft.copy(
                status = ShareDraftStatus.READY,
                stageLabel = "Share link ready",
                completedSteps = totalSteps,
                totalSteps = totalSteps,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        secrets.remove(pinSecret(draftId))
        cleanupDraftFiles(draftId)
        runCatching { refresh() }
        onProgress("Share link ready", totalSteps, totalSteps)
    }

    internal suspend fun recordFailure(draftId: String, message: String, resumable: Boolean) {
        dao.getShareDraft(draftId)?.let { draft ->
            updateDraft(
                draft.copy(
                    status = if (resumable) ShareDraftStatus.UPLOADING else ShareDraftStatus.FAILED,
                    stageLabel = if (resumable) "Paused - ready to resume" else "Share failed",
                    errorMessage = message.take(240),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun assignRemoteObjects(parts: List<SharePartEntity>, remote: CloudDraftResponse): List<SharePartEntity> =
        parts.map { part ->
            val remoteSet = remote.sets.firstOrNull { it.clientSetId == part.setId }
                ?: throw IllegalStateException("Cloud draft no longer matches the private snapshot.")
            val updated = part.copy(
                audioObjectId = remoteSet.objects.firstOrNull { it.kind == "AUDIO" }?.objectId,
                metadataObjectId = remoteSet.objects.firstOrNull { it.kind == "METADATA" }?.objectId,
                updatedAt = System.currentTimeMillis(),
            )
            require(updated.audioObjectId != null && updated.metadataObjectId != null) { "Cloud draft is missing upload objects." }
            dao.upsertSharePart(updated)
            updated
        }

    private suspend fun reconcileRemoteCompletion(
        parts: List<SharePartEntity>,
        remote: CloudDraftResponse,
    ): List<SharePartEntity> = parts.map { part ->
        val remoteSet = remote.sets.first { it.clientSetId == part.setId }
        val audio = remoteSet.objects.first { it.kind == "AUDIO" }
        val metadata = remoteSet.objects.first { it.kind == "METADATA" }
        val audioComplete = audio.status == "COMPLETE"
        val metadataComplete = metadata.status == "COMPLETE"
        if (part.audioUploaded && !audioComplete) {
            throw IllegalStateException("Cloud audio state no longer matches the resumable checkpoint.")
        }
        if (part.metadataUploaded && !metadataComplete) {
            throw IllegalStateException("Cloud transcript state no longer matches the resumable checkpoint.")
        }
        if (audioComplete) verifyRemoteObject("audio", part.audioSizeBytes, part.audioSha256, audio)
        if (metadataComplete) verifyRemoteObject("transcript", part.metadataSizeBytes, part.metadataSha256, metadata)
        if (audioComplete) part.audioPath?.let(::File)?.delete()
        val updated = part.copy(
            audioPath = if (audioComplete) null else part.audioPath,
            audioUploaded = audioComplete,
            metadataUploaded = metadataComplete,
            status = if (audioComplete && metadataComplete) SharePartStatus.COMPLETE else part.status,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertSharePart(updated)
        updated
    }

    private fun verifyRemoteObject(
        label: String,
        expectedSize: Long,
        expectedSha256: String?,
        remote: CloudDraftObject,
    ) {
        if (expectedSize > 0L && remote.sizeBytes != expectedSize) {
            throw IllegalStateException("The completed cloud $label does not match the private snapshot size.")
        }
        if (expectedSha256 != null && !remote.sha256.equals(expectedSha256, ignoreCase = true)) {
            throw IllegalStateException("The completed cloud $label does not match the private snapshot checksum.")
        }
    }

    private suspend fun updateDraft(draft: com.andyluu.debrief.data.ShareDraftEntity) = dao.upsertShareDraft(draft)

    private fun enqueue(draftId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<ShareUploadWorker>()
            .setInputData(workDataOf(ShareUploadWorker.KEY_DRAFT_ID to draftId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("share-upload")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(draftId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private suspend fun configurationOrNull(): Pair<String, String>? {
        val base = settings.settings.first().cloudShareBaseUrl.trim()
        val token = secrets.get(OWNER_TOKEN_SECRET)
        return if (base.isBlank() || token.isNullOrBlank()) null else validateBaseUrl(base) to token
    }

    private suspend fun requireConfigured(): Pair<String, String> = configurationOrNull()
        ?: throw IllegalStateException("Set up Cloud sharing in Settings before creating a link.")

    private fun validateBaseUrl(value: String): String {
        val clean = value.trim().trimEnd('/')
        require(clean.startsWith("https://") || clean.startsWith("http://10.0.2.2") || clean.startsWith("http://localhost")) {
            "Enter the HTTPS Debrief share-service URL."
        }
        return clean
    }

    private fun cleanupDraftFiles(draftId: String) {
        File(context.cacheDir, "share-drafts/$draftId").deleteRecursively()
    }

    private fun decodeParts(value: String): List<UploadedPart> =
        runCatching { json.decodeFromString<List<UploadedPart>>(value) }.getOrDefault(emptyList())

    private fun decodeMuteRanges(value: String): List<ShareMuteRange> =
        runCatching { json.decodeFromString<List<ShareMuteRange>>(value) }.getOrDefault(emptyList())

    private fun generatePublicToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE) }

    companion object {
        private const val OWNER_TOKEN_SECRET = "cloud_share_owner_token"
        private fun pinSecret(draftId: String) = "cloud_share_pin_$draftId"
        private fun workName(draftId: String) = "share-upload-$draftId"
    }
}
