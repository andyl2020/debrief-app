import { HttpError, json, readJson } from "./http";
import type { Env, LibraryItemRow, OwnerDevice } from "./types";
import { MULTIPART_MAX_PARTS, validateParts } from "./validation";

/**
 * Personal cloud library.
 *
 * Share Sets exists to publish derived clips for someone else. This exists so
 * the owner can reach their own recordings from any paired device, which is a
 * different problem and gets its own surface rather than a bent version of the
 * sharing one.
 *
 * The server is deliberately incapable of reading anything it stores. Audio and
 * the sidecar-v4 metadata are encrypted in the browser with AES-GCM before they
 * are uploaded; this module moves opaque bytes and records their sizes. Audio
 * uses independently authenticated chunks, preserving range seeking without
 * accepting mutable or truncated ciphertext.
 */

export const LIBRARY_MAX_AUDIO_BYTES = 2_000_000_000;
export const LIBRARY_MAX_METADATA_BYTES = 50_000_000;
export const LIBRARY_PART_BYTES = 10 * 1024 * 1024;
export const LIBRARY_CRYPTO_VERSION = 2;
export const LIBRARY_CHUNK_BYTES = 8 * 1024 * 1024;
const GCM_TAG_BYTES = 16;

/** Nonces are base64 of 8 bytes; they are not secret but must be well-formed. */
const NONCE_PATTERN = /^[A-Za-z0-9+/]{10,16}={0,2}$/;
const ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;

type ObjectKind = "audio" | "metadata";

export async function listLibrary(env: Env): Promise<Response> {
  const { results = [] } = await env.DB.prepare(
    "SELECT * FROM library_items ORDER BY updated_at DESC",
  ).all<LibraryItemRow>();
  return json({
    items: results.map((row) => ({
      id: row.id,
      version: row.version,
      updatedAt: row.updated_at,
      status: row.status,
      audioBytes: row.audio_plain_bytes,
      audioCipherBytes: row.audio_bytes,
      cryptoVersion: row.crypto_version,
      chunkBytes: row.chunk_bytes,
      audioNonce: row.audio_nonce,
      audioReady: row.audio_status === "COMPLETE",
      metadataBytes: row.meta_bytes,
      metadataNonce: row.meta_nonce,
      metadataReady: row.meta_status === "COMPLETE",
    })),
  });
}

export async function libraryUsage(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    "SELECT COALESCE(SUM(audio_bytes + meta_bytes), 0) AS bytes, COUNT(*) AS items FROM library_items",
  ).first<{ bytes: number; items: number }>();
  return json({
    bytes: row?.bytes ?? 0,
    items: row?.items ?? 0,
    freeReferenceBytes: Number(env.FREE_STORAGE_BYTES ?? "10000000000"),
  });
}

export async function getLibraryKey(env: Env): Promise<Response> {
  const row = await env.DB.prepare("SELECT * FROM library_keys WHERE id = 'default'").first<{
    wrapped_key: string;
    salt: string;
    iterations: number;
  }>();
  if (!row) throw new HttpError(404, "NO_LIBRARY_KEY", "This library has not been set up yet.");
  return json({ wrappedKey: row.wrapped_key, salt: row.salt, iterations: row.iterations });
}

/**
 * Stores the wrapped library key.
 *
 * Replacing it is refused once items exist: a different passphrase produces a
 * different data key, which would leave every uploaded recording permanently
 * undecryptable. That is a silent, total data loss, so it takes an explicit
 * `replace` acknowledgement.
 */
export async function putLibraryKey(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{
    wrappedKey?: string;
    salt?: string;
    iterations?: number;
    replace?: boolean;
  }>(request, 20_000);

  const wrappedKey = body.wrappedKey;
  const salt = body.salt;
  const iterations = body.iterations;
  if (
    typeof wrappedKey !== "string" ||
    wrappedKey.length < 16 ||
    wrappedKey.length > 4_000 ||
    typeof salt !== "string" ||
    salt.length < 8 ||
    salt.length > 256 ||
    !Number.isSafeInteger(iterations) ||
    (iterations as number) < 100_000 ||
    (iterations as number) > 2_000_000
  ) {
    throw new HttpError(400, "INVALID_LIBRARY_KEY", "The wrapped library key is invalid.");
  }

  const existing = await env.DB.prepare("SELECT id FROM library_keys WHERE id = 'default'").first();
  if (existing && body.replace !== true) {
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM library_items").first<{ n: number }>();
    if ((count?.n ?? 0) > 0) {
      throw new HttpError(
        409,
        "LIBRARY_KEY_EXISTS",
        "A library key already exists. Replacing it would make every uploaded recording unreadable.",
      );
    }
  }

  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO library_keys (id, wrapped_key, salt, iterations, created_at, updated_at)
     VALUES ('default', ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET wrapped_key = excluded.wrapped_key, salt = excluded.salt,
       iterations = excluded.iterations, updated_at = excluded.updated_at`,
  ).bind(wrappedKey, salt, iterations, now, now).run();
  return json({ stored: true });
}

/**
 * Opens (or reopens) an item for upload.
 *
 * Re-calling for an existing id discards the previous staging upload and starts
 * again, so an interrupted sync retries cleanly instead of accumulating orphans.
 */
export async function beginItem(request: Request, env: Env, owner: OwnerDevice): Promise<Response> {
  const body = await readJson<{
    id?: string;
    audioBytes?: number;
    audioCipherBytes?: number;
    metadataBytes?: number;
    audioNonce?: string;
    metadataNonce?: string;
    cryptoVersion?: number;
    chunkBytes?: number;
  }>(request, 20_000);

  const id = body.id;
  if (typeof id !== "string" || !ID_PATTERN.test(id)) {
    throw new HttpError(400, "INVALID_ITEM_ID", "The recording id is invalid.");
  }
  validateSize(body.audioBytes, LIBRARY_MAX_AUDIO_BYTES, "audio");
  validateSize(body.audioCipherBytes, encryptedMaximum(LIBRARY_MAX_AUDIO_BYTES), "encrypted audio");
  validateSize(body.metadataBytes, LIBRARY_MAX_METADATA_BYTES + GCM_TAG_BYTES, "metadata");
  validateNonce(body.audioNonce, "audio");
  validateNonce(body.metadataNonce, "metadata");
  if (body.cryptoVersion !== LIBRARY_CRYPTO_VERSION || body.chunkBytes !== LIBRARY_CHUNK_BYTES) {
    throw new HttpError(400, "UNSUPPORTED_CRYPTO", "Use the current authenticated cloud format.");
  }
  const expectedCipherBytes =
    (body.audioBytes as number) +
    Math.ceil((body.audioBytes as number) / LIBRARY_CHUNK_BYTES) * GCM_TAG_BYTES;
  if (body.audioCipherBytes !== expectedCipherBytes) {
    throw new HttpError(400, "INVALID_CIPHER_SIZE", "The encrypted audio size is inconsistent.");
  }

  const previous = await env.DB.prepare("SELECT * FROM library_items WHERE id = ?")
    .bind(id).first<LibraryItemRow>();
  if (previous) await abortStaging(env, previous);

  const now = Date.now();
  const audioKey = `library/${id}/audio-${now}`;
  const metaKey = `library/${id}/metadata-${now}`;
  const audioUpload = await env.AUDIO.createMultipartUpload(audioKey, {
    httpMetadata: { contentType: "application/octet-stream" },
  });
  const metaUpload = await env.AUDIO.createMultipartUpload(metaKey, {
    httpMetadata: { contentType: "application/octet-stream" },
  });

  await env.DB.prepare(
    `INSERT INTO library_items (
       id, version, updated_at, created_at, created_by_device, status,
       audio_key, audio_nonce, audio_plain_bytes, audio_bytes, crypto_version, chunk_bytes,
       audio_upload_id, audio_status,
       meta_key, meta_nonce, meta_bytes, meta_upload_id, meta_status
     ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, 'PENDING')
     ON CONFLICT(id) DO UPDATE SET
       version = excluded.version,
       updated_at = excluded.updated_at, created_by_device = excluded.created_by_device,
       status = 'PENDING',
       audio_key = excluded.audio_key, audio_nonce = excluded.audio_nonce,
       audio_plain_bytes = excluded.audio_plain_bytes, audio_bytes = excluded.audio_bytes,
       crypto_version = excluded.crypto_version, chunk_bytes = excluded.chunk_bytes,
       audio_upload_id = excluded.audio_upload_id,
       audio_status = 'PENDING',
       meta_key = excluded.meta_key, meta_nonce = excluded.meta_nonce,
       meta_bytes = excluded.meta_bytes, meta_upload_id = excluded.meta_upload_id,
       meta_status = 'PENDING'`,
  ).bind(
    id,
    (previous?.version ?? 0) + 1,
    now,
    previous?.created_at ?? now,
    owner.id,
    audioKey,
    body.audioNonce,
    body.audioBytes,
    body.audioCipherBytes,
    body.cryptoVersion,
    body.chunkBytes,
    audioUpload.uploadId,
    metaKey,
    body.metadataNonce,
    body.metadataBytes,
    metaUpload.uploadId,
  ).run();

  return json(
    {
      id,
      partBytes: LIBRARY_PART_BYTES,
      objects: {
        audio: { partUrl: partUrl(id, "audio"), completeUrl: completeUrl(id, "audio") },
        metadata: { partUrl: partUrl(id, "metadata"), completeUrl: completeUrl(id, "metadata") },
      },
    },
    201,
  );
}

export async function uploadItemPart(
  request: Request,
  env: Env,
  id: string,
  kind: ObjectKind,
  partNumber: number,
): Promise<Response> {
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > MULTIPART_MAX_PARTS) {
    throw new HttpError(400, "INVALID_PART", "The upload part number is invalid.");
  }
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (!request.body || !Number.isFinite(length) || length <= 0 || length > LIBRARY_PART_BYTES) {
    throw new HttpError(400, "INVALID_PART_SIZE", `Upload parts must be 1 to ${LIBRARY_PART_BYTES} bytes.`);
  }
  const item = await requireItem(env, id);
  const { key, uploadId, status } = objectFields(item, kind);
  if (status === "COMPLETE") throw new HttpError(409, "OBJECT_COMPLETE", "That object is already complete.");
  if (!uploadId) throw new HttpError(409, "NO_UPLOAD", "Begin the item upload before sending parts.");

  const upload = env.AUDIO.resumeMultipartUpload(key, uploadId);
  const part = await upload.uploadPart(partNumber, request.body);
  return json({ partNumber, etag: part.etag });
}

/** Commits one object, refusing anything whose stored size does not match. */
export async function completeItemObject(
  request: Request,
  env: Env,
  id: string,
  kind: ObjectKind,
): Promise<Response> {
  const body = await readJson<{ parts?: Array<{ partNumber: number; etag: string }> }>(request, 1_000_000);
  const item = await requireItem(env, id);
  const { key, uploadId, status, expectedBytes } = objectFields(item, kind);
  if (status === "COMPLETE") return json({ id, kind, complete: true, sizeBytes: expectedBytes });
  if (!uploadId) throw new HttpError(409, "NO_UPLOAD", "Begin the item upload before completing it.");

  const parts = body.parts ?? [];
  validateParts(parts);
  const upload = env.AUDIO.resumeMultipartUpload(key, uploadId);
  await upload.complete(parts);

  const head = await env.AUDIO.head(key);
  if (!head || head.size !== expectedBytes) {
    // Refuse truncated objects before they can be presented as complete.
    await env.AUDIO.delete(key);
    throw new HttpError(409, "SIZE_MISMATCH", "Cloud storage did not retain the complete expected object.");
  }

  const column = kind === "audio" ? "audio_status" : "meta_status";
  await env.DB.prepare(`UPDATE library_items SET ${column} = 'COMPLETE', updated_at = ? WHERE id = ?`)
    .bind(Date.now(), id).run();

  const refreshed = await requireItem(env, id);
  if (refreshed.audio_status === "COMPLETE" && refreshed.meta_status === "COMPLETE") {
    await env.DB.prepare("UPDATE library_items SET status = 'COMPLETE', updated_at = ? WHERE id = ?")
      .bind(Date.now(), id).run();
  }
  return json({ id, kind, complete: true, sizeBytes: head.size });
}

/**
 * Serves ciphertext, honouring Range.
 *
 * The browser's audio element issues its own Range requests; a service worker
 * widens them to complete authenticated chunks and decrypts them locally.
 */
export async function getItemObject(
  request: Request,
  env: Env,
  id: string,
  kind: ObjectKind,
  parseRange: (value: string | null, size: number) => { start: number; end: number; length: number } | "invalid" | null,
): Promise<Response> {
  const item = await requireItem(env, id);
  const { key, status } = objectFields(item, kind);
  if (status !== "COMPLETE") throw new HttpError(409, "OBJECT_INCOMPLETE", "That object has not finished uploading.");

  const head = await env.AUDIO.head(key);
  if (!head) throw new HttpError(404, "OBJECT_MISSING", "That object is no longer stored.");

  const range = parseRange(request.headers.get("Range"), head.size);
  if (range === "invalid") {
    return new Response(null, {
      status: 416,
      headers: { "Content-Range": `bytes */${head.size}`, "Accept-Ranges": "bytes" },
    });
  }

  const headers = new Headers({
    "Accept-Ranges": "bytes",
    "Cache-Control": "private, no-store",
    // Always opaque: this is ciphertext, and labelling it audio/* would invite
    // a browser to try to decode it.
    "Content-Type": "application/octet-stream",
    "Content-Length": String(range?.length ?? head.size),
    "X-Content-Type-Options": "nosniff",
  });
  if (range) headers.set("Content-Range", `bytes ${range.start}-${range.end}/${head.size}`);
  if (request.method === "HEAD") {
    headers.set("Content-Length", String(head.size));
    return new Response(null, { status: 200, headers });
  }

  const stored = range
    ? await env.AUDIO.get(key, { range: { offset: range.start, length: range.length } })
    : await env.AUDIO.get(key);
  if (!stored) throw new HttpError(404, "OBJECT_MISSING", "That object is no longer stored.");
  return new Response(stored.body, { status: range ? 206 : 200, headers });
}

export async function deleteItem(env: Env, id: string, ctx: ExecutionContext): Promise<Response> {
  const item = await env.DB.prepare("SELECT * FROM library_items WHERE id = ?")
    .bind(id).first<LibraryItemRow>();
  if (!item) return json({ deleted: true });
  await env.DB.prepare("DELETE FROM library_items WHERE id = ?").bind(id).run();
  ctx.waitUntil(
    Promise.all([
      env.AUDIO.delete(item.audio_key).catch(() => undefined),
      env.AUDIO.delete(item.meta_key).catch(() => undefined),
    ]).then(() => undefined),
  );
  return json({ deleted: true });
}

// --- helpers --------------------------------------------------------------

async function requireItem(env: Env, id: string): Promise<LibraryItemRow> {
  const item = await env.DB.prepare("SELECT * FROM library_items WHERE id = ?")
    .bind(id).first<LibraryItemRow>();
  if (!item) throw new HttpError(404, "ITEM_NOT_FOUND", "That recording is not in the cloud library.");
  return item;
}

function objectFields(item: LibraryItemRow, kind: ObjectKind) {
  return kind === "audio"
    ? {
        key: item.audio_key,
        uploadId: item.audio_upload_id,
        status: item.audio_status,
        expectedBytes: item.audio_bytes,
      }
    : {
        key: item.meta_key,
        uploadId: item.meta_upload_id,
        status: item.meta_status,
        expectedBytes: item.meta_bytes,
      };
}

/** Cancels a superseded staging upload so retries do not leave orphans in R2. */
async function abortStaging(env: Env, item: LibraryItemRow): Promise<void> {
  const pending: Array<Promise<unknown>> = [];
  if (item.audio_upload_id && item.audio_status !== "COMPLETE") {
    pending.push(
      env.AUDIO.resumeMultipartUpload(item.audio_key, item.audio_upload_id).abort().catch(() => undefined),
    );
  }
  if (item.meta_upload_id && item.meta_status !== "COMPLETE") {
    pending.push(
      env.AUDIO.resumeMultipartUpload(item.meta_key, item.meta_upload_id).abort().catch(() => undefined),
    );
  }
  if (item.audio_status === "COMPLETE") pending.push(env.AUDIO.delete(item.audio_key).catch(() => undefined));
  if (item.meta_status === "COMPLETE") pending.push(env.AUDIO.delete(item.meta_key).catch(() => undefined));
  await Promise.all(pending);
}

function validateSize(value: unknown, maximum: number, label: string): void {
  if (!Number.isSafeInteger(value) || (value as number) <= 0 || (value as number) > maximum) {
    throw new HttpError(400, "INVALID_SIZE", `The ${label} size must be between 1 and ${maximum} bytes.`);
  }
}

function validateNonce(value: unknown, label: string): void {
  if (typeof value !== "string" || !NONCE_PATTERN.test(value)) {
    throw new HttpError(400, "INVALID_NONCE", `The ${label} nonce is invalid.`);
  }
}

function encryptedMaximum(plainMaximum: number): number {
  return plainMaximum + Math.ceil(plainMaximum / LIBRARY_CHUNK_BYTES) * GCM_TAG_BYTES;
}

function partUrl(id: string, kind: ObjectKind): string {
  return `/v1/owner/library/items/${encodeURIComponent(id)}/objects/${kind}/parts/{partNumber}`;
}

function completeUrl(id: string, kind: ObjectKind): string {
  return `/v1/owner/library/items/${encodeURIComponent(id)}/objects/${kind}/complete`;
}

export function isObjectKind(value: string): value is ObjectKind {
  return value === "audio" || value === "metadata";
}
