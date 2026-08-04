package com.andyluu.debrief.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SHARE_MAX_SETS = 10
const val SHARE_MAX_DURATION_MS = 3L * 60L * 60L * 1_000L
const val SHARE_STORAGE_REFERENCE_BYTES = 10_000_000_000L
const val SHARE_UPLOAD_PART_BYTES = 8 * 1024 * 1024

@Serializable
data class ShareMetadataPayload(
    val schemaVersion: Int = 1,
    val title: String,
    val durationMs: Long,
    val segments: List<ShareTranscriptSegment>,
    val comments: List<ShareComment>,
)

@Serializable
data class ShareTranscriptSegment(
    val speaker: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class ShareComment(
    val timestampMs: Long,
    val text: String,
)

@Serializable
data class ShareMuteRange(val startMs: Long, val endMs: Long)

@Serializable
data class PairDeviceRequest(val code: String, val label: String)

@Serializable
data class PairDeviceResponse(val ownerToken: String, val deviceId: String)

@Serializable
data class CreateCloudDraftRequest(
    val title: String,
    val expiryDays: Int,
    val pin: String? = null,
    val sets: List<CreateCloudSetRequest>,
)

@Serializable
data class CreateCloudSetRequest(
    val clientSetId: String,
    val title: String,
    val durationMs: Long,
    val audioMimeType: String = "audio/mp4",
    val expectedAudioBytes: Long? = null,
    val expectedMetadataBytes: Long? = null,
)

@Serializable
data class CloudDraftResponse(
    val draftId: String,
    val expiresAfterHours: Int,
    val sets: List<CloudDraftSet>,
)

@Serializable
data class CloudDraftSet(
    val clientSetId: String,
    val objects: List<CloudDraftObject>,
)

@Serializable
data class CloudDraftObject(
    val objectId: String,
    val kind: String,
    val partUrl: String,
    val completeUrl: String,
    val minimumPartBytes: Int,
    val maximumPartBytes: Int,
)

@Serializable
data class UploadedPart(val partNumber: Int, val etag: String)

@Serializable
data class CompleteObjectRequest(
    val parts: List<UploadedPart>,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class CompleteObjectResponse(
    val objectId: String,
    val sizeBytes: Long,
    val sha256: String,
    val complete: Boolean,
)

@Serializable
data class PublishShareRequest(val publicToken: String)

@Serializable
data class PublishShareResponse(
    val shareId: String,
    val url: String,
    val expiresAt: Long,
    val sizeBytes: Long,
)

@Serializable
data class ExtendShareRequest(val expiryDays: Int)

@Serializable
data class ExtendShareResponse(val shareId: String, val expiryDays: Int, val expiresAt: Long)

@Serializable
data class CloudUsageResponse(
    val currentBytes: Long,
    val referenceBytes: Long,
    val percentUsed: Double,
    val activeLinks: Int,
    val trackedObjects: Int,
    val measuredAt: Long,
    val source: String,
    val providerMetric: CloudProviderMetric? = null,
    val billingNote: String,
)

@Serializable
data class CloudProviderMetric(
    val currentBytes: Long,
    val objectCount: Int,
    val measuredAt: Long,
    val source: String,
)

@Serializable
data class CloudSharesResponse(val shares: List<CloudShareSummary>)

@Serializable
data class CloudShareSummary(
    val id: String,
    val status: String,
    val title: String,
    val expiryDays: Int,
    val setCount: Int,
    val totalDurationMs: Long,
    val totalSizeBytes: Long,
    val createdAt: Long,
    val publishedAt: Long? = null,
    val expiresAt: Long? = null,
    val revokedAt: Long? = null,
    val sets: List<CloudSetSummary> = emptyList(),
)

@Serializable
data class CloudSetSummary(val id: String, val title: String, val durationMs: Long)

@Serializable
internal data class CloudErrorEnvelope(val error: CloudErrorBody? = null)

@Serializable
internal data class CloudErrorBody(val code: String = "UNKNOWN", val message: String = "Cloud sharing failed.")

class CloudShareException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : Exception(message)
