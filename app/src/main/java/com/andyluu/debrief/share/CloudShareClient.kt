package com.andyluu.debrief.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudShareClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun pair(baseUrl: String, request: PairDeviceRequest): PairDeviceResponse =
        post(baseUrl, "/v1/pair", request)

    suspend fun createDraft(
        baseUrl: String,
        ownerToken: String,
        request: CreateCloudDraftRequest,
    ): CloudDraftResponse = post(baseUrl, "/v1/owner/share-drafts", request, ownerToken)

    suspend fun inspectDraft(baseUrl: String, ownerToken: String, draftId: String): CloudDraftResponse =
        get(baseUrl, "/v1/owner/share-drafts/$draftId", ownerToken)

    suspend fun uploadFile(
        baseUrl: String,
        ownerToken: String,
        partUrlTemplate: String,
        file: File,
        existingParts: List<UploadedPart> = emptyList(),
        onPartUploaded: suspend (parts: List<UploadedPart>, uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): List<UploadedPart> = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "The prepared share file is missing or empty." }
        val completed = existingParts.sortedBy(UploadedPart::partNumber).toMutableList()
        require(completed.map(UploadedPart::partNumber) == (1..completed.size).toList()) { "Saved upload checkpoint is invalid." }
        var offset = completed.size.toLong() * SHARE_UPLOAD_PART_BYTES
        var partNumber = completed.size + 1
        if (offset > file.length()) throw IllegalArgumentException("Saved upload checkpoint exceeds the prepared file.")
        while (offset < file.length()) {
            val length = minOf(SHARE_UPLOAD_PART_BYTES.toLong(), file.length() - offset)
            val path = partUrlTemplate.replace("{partNumber}", partNumber.toString())
            val request = Request.Builder()
                .url(resolve(baseUrl, path))
                .header("Authorization", "Bearer $ownerToken")
                .put(FileSliceRequestBody(file, offset, length))
                .build()
            val uploaded = execute<UploadedPart>(request)
            completed += uploaded
            offset += length
            partNumber += 1
            onPartUploaded(completed.toList(), offset, file.length())
        }
        completed
    }

    suspend fun completeObject(
        baseUrl: String,
        ownerToken: String,
        completeUrl: String,
        request: CompleteObjectRequest,
    ): CompleteObjectResponse = post(baseUrl, completeUrl, request, ownerToken)

    suspend fun publish(
        baseUrl: String,
        ownerToken: String,
        draftId: String,
        publicToken: String,
    ): PublishShareResponse = post(
        baseUrl,
        "/v1/owner/share-drafts/$draftId/publish",
        PublishShareRequest(publicToken),
        ownerToken,
    )

    suspend fun usage(baseUrl: String, ownerToken: String): CloudUsageResponse =
        get(baseUrl, "/v1/owner/usage", ownerToken)

    suspend fun shares(baseUrl: String, ownerToken: String): CloudSharesResponse =
        get(baseUrl, "/v1/owner/shares", ownerToken)

    suspend fun extend(baseUrl: String, ownerToken: String, shareId: String, expiryDays: Int): ExtendShareResponse =
        post(baseUrl, "/v1/owner/shares/$shareId/extend", ExtendShareRequest(expiryDays), ownerToken)

    suspend fun revoke(baseUrl: String, ownerToken: String, shareId: String) {
        val request = Request.Builder()
            .url(resolve(baseUrl, "/v1/owner/shares/$shareId"))
            .header("Authorization", "Bearer $ownerToken")
            .delete()
            .build()
        execute<UnitResponse>(request)
    }

    suspend fun cancelDraft(baseUrl: String, ownerToken: String, draftId: String) {
        val request = Request.Builder()
            .url(resolve(baseUrl, "/v1/owner/share-drafts/$draftId"))
            .header("Authorization", "Bearer $ownerToken")
            .delete()
            .build()
        execute<UnitResponse>(request)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        baseUrl: String,
        path: String,
        body: RequestType,
        ownerToken: String? = null,
    ): ResponseType = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(resolve(baseUrl, path))
            .post(json.encodeToString(body).toRequestBody(jsonMedia))
        ownerToken?.let { builder.header("Authorization", "Bearer $it") }
        execute(builder.build())
    }

    private suspend inline fun <reified ResponseType> get(
        baseUrl: String,
        path: String,
        ownerToken: String,
    ): ResponseType = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url(resolve(baseUrl, path))
                .header("Authorization", "Bearer $ownerToken")
                .get()
                .build()
        )
    }

    private inline fun <reified ResponseType> execute(request: Request): ResponseType {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching { json.decodeFromString<CloudErrorEnvelope>(text).error }.getOrNull()
                    throw CloudShareException(
                        code = error?.code ?: "HTTP_${response.code}",
                        message = error?.message ?: "Cloud sharing failed (${response.code}).",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    )
                }
                if (ResponseType::class == UnitResponse::class) return UnitResponse as ResponseType
                return json.decodeFromString(text)
            }
        } catch (error: CloudShareException) {
            throw error
        } catch (error: IOException) {
            throw CloudShareException("NETWORK", "Couldn't reach the share service. Check your connection and resume.", retryable = true)
        }
    }

    private fun resolve(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://10.0.2.2") || base.startsWith("http://localhost")) {
            "Cloud sharing requires an HTTPS service URL."
        }
        return if (path.startsWith("http://") || path.startsWith("https://")) path else base + "/" + path.trimStart('/')
    }

    private object UnitResponse

    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val amount = input.skip(offset - skipped)
                    if (amount <= 0L) throw IOException("Could not seek prepared share file")
                    skipped += amount
                }
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Prepared share file ended unexpectedly")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}
