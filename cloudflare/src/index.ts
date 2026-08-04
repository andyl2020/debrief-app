import { derivePinHash, keyedHash, randomId, randomPairingCode, randomToken, safeEqual } from "./crypto";
import { bearer, errorResponse, HttpError, json, readJson, textResponse, unavailable } from "./http";
import type { Env, OwnerDevice, ShareMetadata, ShareObjectRow, ShareRow, ShareSetRow } from "./types";
import {
  MAX_METADATA_BYTES,
  MAX_OBJECT_BYTES,
  MULTIPART_MAX_PARTS,
  MULTIPART_MIN_BYTES,
  type CreateDraftBody,
  validateDraft,
  validateMetadata,
  validateParts,
} from "./validation";
import { VIEWER_CSS, VIEWER_HTML, VIEWER_JS } from "./viewer";

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const MAX_PART_BYTES = 10 * 1024 * 1024;
const PIN_WINDOW_MS = 15 * 60 * 1000;
const PIN_MAX_FAILURES = 5;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      return errorResponse(error);
    }
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runMaintenance(env));
  },
};

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method === "GET" && path === "/health") {
    return json({ ok: true, service: "debrief-share", version: 1 });
  }
  if (request.method === "GET" && path === "/assets/viewer.css") {
    return textResponse(VIEWER_CSS, 200, "text/css; charset=utf-8", { "Cache-Control": "public, max-age=3600" });
  }
  if (request.method === "GET" && path === "/assets/viewer.js") {
    return textResponse(VIEWER_JS, 200, "text/javascript; charset=utf-8", { "Cache-Control": "public, max-age=3600" });
  }
  if (request.method === "POST" && path === "/v1/admin/pairing-codes") {
    return createPairingCode(request, env);
  }
  if (request.method === "POST" && path === "/v1/pair") {
    return pairDevice(request, env);
  }

  const publicMatch = path.match(/^\/v1\/public\/([^/]+)$/);
  if (request.method === "GET" && publicMatch?.[1]) {
    return publicSnapshot(request, env, publicMatch[1]);
  }
  const unlockMatch = path.match(/^\/v1\/public\/([^/]+)\/unlock$/);
  if (request.method === "POST" && unlockMatch?.[1]) {
    return unlockShare(request, env, unlockMatch[1]);
  }
  const audioMatch = path.match(/^\/v1\/public\/([^/]+)\/sets\/([^/]+)\/audio$/);
  if ((request.method === "GET" || request.method === "HEAD") && audioMatch?.[1] && audioMatch[2]) {
    return publicAudio(request, env, audioMatch[1], audioMatch[2]);
  }
  const viewerMatch = path.match(/^\/s\/([^/]+)$/);
  if (request.method === "GET" && viewerMatch?.[1]) {
    const resolved = await resolvePublicShare(env, viewerMatch[1]);
    if (!resolved) return unavailable();
    return textResponse(VIEWER_HTML, 200, "text/html; charset=utf-8", {
      "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; media-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    });
  }

  if (path.startsWith("/v1/owner/")) {
    const owner = await requireOwner(request, env, ctx);
    if (request.method === "GET" && path === "/v1/owner/usage") return ownerUsage(env);
    if (request.method === "GET" && path === "/v1/owner/shares") return listShares(env, owner);
    if (request.method === "POST" && path === "/v1/owner/share-drafts") return createDraft(request, env, owner);

    const uploadPartMatch = path.match(/^\/v1\/owner\/share-drafts\/([^/]+)\/objects\/([^/]+)\/parts\/(\d+)$/);
    if (request.method === "PUT" && uploadPartMatch?.[1] && uploadPartMatch[2] && uploadPartMatch[3]) {
      return uploadPart(request, env, owner, uploadPartMatch[1], uploadPartMatch[2], Number(uploadPartMatch[3]));
    }
    const completeObjectMatch = path.match(/^\/v1\/owner\/share-drafts\/([^/]+)\/objects\/([^/]+)\/complete$/);
    if (request.method === "POST" && completeObjectMatch?.[1] && completeObjectMatch[2]) {
      return completeObject(request, env, owner, completeObjectMatch[1], completeObjectMatch[2]);
    }
    const publishMatch = path.match(/^\/v1\/owner\/share-drafts\/([^/]+)\/publish$/);
    if (request.method === "POST" && publishMatch?.[1]) return publishShare(request, env, owner, publishMatch[1]);
    const draftMatch = path.match(/^\/v1\/owner\/share-drafts\/([^/]+)$/);
    if (request.method === "DELETE" && draftMatch?.[1]) return cancelDraft(env, owner, draftMatch[1], ctx);
    const extendMatch = path.match(/^\/v1\/owner\/shares\/([^/]+)\/extend$/);
    if (request.method === "POST" && extendMatch?.[1]) return extendShare(request, env, owner, extendMatch[1]);
    const shareMatch = path.match(/^\/v1\/owner\/shares\/([^/]+)$/);
    if (request.method === "DELETE" && shareMatch?.[1]) return revokeShare(env, owner, shareMatch[1], ctx);
  }

  throw new HttpError(404, "NOT_FOUND", "The requested endpoint does not exist.");
}

async function createPairingCode(request: Request, env: Env): Promise<Response> {
  const supplied = bearer(request);
  if (!supplied || !safeEqual(supplied, env.BOOTSTRAP_SECRET)) {
    throw new HttpError(401, "UNAUTHORIZED", "A deployment bootstrap credential is required.");
  }
  const code = randomPairingCode();
  const now = Date.now();
  const expiresAt = now + 15 * 60 * 1000;
  await env.DB.prepare("INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)")
    .bind(await keyedHash(normalizePairingCode(code), env.TOKEN_PEPPER), now, expiresAt)
    .run();
  return json({ code, expiresAt }, 201);
}

async function pairDevice(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ code?: string; label?: string }>(request, 8_000);
  const normalized = normalizePairingCode(body.code ?? "");
  const label = (body.label ?? "Android device").trim();
  if (!/^[A-Z0-9]{12,32}$/.test(normalized) || label.length < 1 || label.length > 80) {
    throw new HttpError(400, "INVALID_PAIRING", "The pairing code or device label is invalid.");
  }
  const codeHash = await keyedHash(normalized, env.TOKEN_PEPPER);
  const code = await env.DB.prepare(
    "SELECT code_hash, expires_at, used_at FROM pairing_codes WHERE code_hash = ? LIMIT 1",
  ).bind(codeHash).first<{ code_hash: string; expires_at: number; used_at: number | null }>();
  const now = Date.now();
  if (!code || code.used_at != null || code.expires_at <= now) {
    throw new HttpError(401, "PAIRING_EXPIRED", "That pairing code is invalid, expired, or already used.");
  }
  const token = randomToken();
  const deviceId = randomId("device");
  await env.DB.batch([
    env.DB.prepare("UPDATE pairing_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL").bind(now, codeHash),
    env.DB.prepare(
      "INSERT INTO owner_devices (id, token_hash, label, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
    ).bind(deviceId, await keyedHash(token, env.TOKEN_PEPPER), label, now, now),
  ]);
  return json({ ownerToken: token, deviceId }, 201);
}

async function requireOwner(request: Request, env: Env, ctx: ExecutionContext): Promise<OwnerDevice> {
  const token = bearer(request);
  if (!token || token.length < 32 || token.length > 160) {
    throw new HttpError(401, "PAIRING_REQUIRED", "Pair Debrief with the share service first.");
  }
  const owner = await env.DB.prepare(
    "SELECT id, label FROM owner_devices WHERE token_hash = ? AND revoked_at IS NULL LIMIT 1",
  ).bind(await keyedHash(token, env.TOKEN_PEPPER)).first<OwnerDevice>();
  if (!owner) throw new HttpError(401, "PAIRING_REQUIRED", "The device pairing is invalid or revoked.");
  ctx.waitUntil(env.DB.prepare("UPDATE owner_devices SET last_seen_at = ? WHERE id = ?").bind(Date.now(), owner.id).run());
  return owner;
}

async function createDraft(request: Request, env: Env, owner: OwnerDevice): Promise<Response> {
  const body = validateDraft(await readJson<CreateDraftBody>(request));
  const now = Date.now();
  const shareId = randomId("share");
  const pinSalt = body.pin ? randomToken(18) : null;
  const pinHash = body.pin && pinSalt
    ? await derivePinHash(body.pin, pinSalt, env.PIN_PEPPER, pinIterations(env))
    : null;
  const sets: Array<{
    id: string;
    clientSetId: string;
    title: string;
    durationMs: number;
    objects: Array<{
      id: string;
      kind: "AUDIO" | "METADATA";
      key: string;
      mimeType: string;
      upload: R2MultipartUpload;
      expectedBytes: number | null;
    }>;
  }> = [];
  try {
    for (const [position, input] of body.sets.entries()) {
      const setId = randomId("set");
      const audioId = randomId("object");
      const metadataId = randomId("object");
      const audioKey = `shares/${shareId}/sets/${setId}/audio`;
      const metadataKey = `shares/${shareId}/sets/${setId}/metadata.json`;
      const audioUpload = await env.AUDIO.createMultipartUpload(audioKey, {
        httpMetadata: { contentType: input.audioMimeType },
        customMetadata: { shareId, setId, kind: "audio" },
      });
      const metadataUpload = await env.AUDIO.createMultipartUpload(metadataKey, {
        httpMetadata: { contentType: "application/json; charset=utf-8" },
        customMetadata: { shareId, setId, kind: "metadata" },
      });
      sets.push({
        id: setId,
        clientSetId: input.clientSetId,
        title: input.title,
        durationMs: input.durationMs,
        objects: [
          { id: audioId, kind: "AUDIO", key: audioKey, mimeType: input.audioMimeType, upload: audioUpload, expectedBytes: input.expectedAudioBytes ?? null },
          { id: metadataId, kind: "METADATA", key: metadataKey, mimeType: "application/json; charset=utf-8", upload: metadataUpload, expectedBytes: input.expectedMetadataBytes ?? null },
        ],
      });
      void position;
    }
    const statements: D1PreparedStatement[] = [
      env.DB.prepare(
        "INSERT INTO shares (id, owner_device_id, status, title, expiry_days, pin_hash, pin_salt, set_count, total_duration_ms, created_at, updated_at) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)",
      ).bind(shareId, owner.id, body.title, body.expiryDays, pinHash, pinSalt, sets.length, sets.reduce((sum, set) => sum + set.durationMs, 0), now, now),
    ];
    sets.forEach((set, position) => {
      statements.push(env.DB.prepare(
        "INSERT INTO share_sets (id, share_id, client_set_id, position, title, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
      ).bind(set.id, shareId, set.clientSetId, position, set.title, set.durationMs, now));
      set.objects.forEach((object) => {
        statements.push(env.DB.prepare(
          "INSERT INTO share_objects (id, share_id, share_set_id, kind, object_key, mime_type, upload_id, status, expected_size_bytes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADING', ?, ?, ?)",
        ).bind(object.id, shareId, set.id, object.kind, object.key, object.mimeType, object.upload.uploadId, object.expectedBytes, now, now));
      });
    });
    await env.DB.batch(statements);
  } catch (error) {
    await Promise.all(sets.flatMap((set) => set.objects.map((object) => object.upload.abort().catch(() => undefined))));
    throw error;
  }
  return json({
    draftId: shareId,
    expiresAfterHours: stagingTtlHours(env),
    sets: sets.map((set) => ({
      clientSetId: set.clientSetId,
      objects: set.objects.map((object) => ({
        objectId: object.id,
        kind: object.kind,
        partUrl: `/v1/owner/share-drafts/${shareId}/objects/${object.id}/parts/{partNumber}`,
        completeUrl: `/v1/owner/share-drafts/${shareId}/objects/${object.id}/complete`,
        minimumPartBytes: MULTIPART_MIN_BYTES,
        maximumPartBytes: MAX_PART_BYTES,
      })),
    })),
  }, 201);
}

async function uploadPart(
  request: Request,
  env: Env,
  owner: OwnerDevice,
  draftId: string,
  objectId: string,
  partNumber: number,
): Promise<Response> {
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > MULTIPART_MAX_PARTS) {
    throw new HttpError(400, "INVALID_PART", "The upload part number is invalid.");
  }
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (!request.body || !Number.isFinite(length) || length <= 0 || length > MAX_PART_BYTES) {
    throw new HttpError(400, "INVALID_PART_SIZE", `Upload parts must be between 1 byte and ${MAX_PART_BYTES} bytes.`);
  }
  const object = await ownedDraftObject(env, owner.id, draftId, objectId);
  if (object.status === "COMPLETE") throw new HttpError(409, "OBJECT_COMPLETE", "That object is already complete.");
  const upload = env.AUDIO.resumeMultipartUpload(object.object_key, object.upload_id);
  const part = await upload.uploadPart(partNumber, request.body);
  return json({ partNumber, etag: part.etag });
}

async function completeObject(
  request: Request,
  env: Env,
  owner: OwnerDevice,
  draftId: string,
  objectId: string,
): Promise<Response> {
  const body = await readJson<{ parts?: Array<{ partNumber: number; etag: string }>; sizeBytes?: number; sha256?: string }>(request, 1_000_000);
  const object = await ownedDraftObject(env, owner.id, draftId, objectId);
  if (object.status === "COMPLETE") {
    return json({ objectId, sizeBytes: object.actual_size_bytes, sha256: object.sha256, complete: true });
  }
  const parts = body.parts ?? [];
  validateParts(parts);
  const claimedSize = body.sizeBytes;
  const sha256 = body.sha256?.toLowerCase();
  const maximum = object.kind === "METADATA" ? MAX_METADATA_BYTES : MAX_OBJECT_BYTES;
  if (!Number.isSafeInteger(claimedSize) || claimedSize! <= 0 || claimedSize! > maximum || !sha256 || !/^[a-f0-9]{64}$/.test(sha256)) {
    throw new HttpError(400, "INVALID_OBJECT_MANIFEST", "The completed object manifest is invalid.");
  }
  if (object.expected_size_bytes != null && object.expected_size_bytes !== claimedSize) {
    throw new HttpError(409, "SIZE_MISMATCH", "The uploaded object does not match its expected size.");
  }
  const upload = env.AUDIO.resumeMultipartUpload(object.object_key, object.upload_id);
  await upload.complete(parts);
  const head = await env.AUDIO.head(object.object_key);
  if (!head || head.size !== claimedSize) {
    await env.AUDIO.delete(object.object_key);
    throw new HttpError(409, "SIZE_MISMATCH", "Cloud storage did not retain the complete expected object.");
  }
  await env.DB.prepare(
    "UPDATE share_objects SET status = 'COMPLETE', actual_size_bytes = ?, sha256 = ?, parts_json = ?, updated_at = ? WHERE id = ? AND share_id = ?",
  ).bind(claimedSize, sha256, JSON.stringify(parts), Date.now(), objectId, draftId).run();
  return json({ objectId, sizeBytes: claimedSize, sha256, complete: true });
}

async function publishShare(
  request: Request,
  env: Env,
  owner: OwnerDevice,
  draftId: string,
): Promise<Response> {
  const body = await readJson<{ publicToken?: string }>(request, 8_000);
  const publicToken = body.publicToken ?? "";
  if (!/^[A-Za-z0-9_-]{43,100}$/.test(publicToken)) {
    throw new HttpError(400, "INVALID_PUBLIC_TOKEN", "The share token is invalid.");
  }
  const tokenHash = await keyedHash(publicToken, env.TOKEN_PEPPER);
  const share = await ownedShare(env, owner.id, draftId);
  if (share.status === "ACTIVE") {
    if (share.bearer_hash !== tokenHash) throw new HttpError(409, "ALREADY_PUBLISHED", "The share is already published with a different token.");
    return json(publicationPayload(request, env, share, publicToken));
  }
  if (share.status !== "DRAFT") throw new HttpError(409, "DRAFT_NOT_ACTIVE", "That draft can no longer be published.");
  const { results: sets = [] } = await env.DB.prepare(
    "SELECT * FROM share_sets WHERE share_id = ? ORDER BY position",
  ).bind(draftId).all<ShareSetRow>();
  const { results: objects = [] } = await env.DB.prepare(
    "SELECT * FROM share_objects WHERE share_id = ? ORDER BY share_set_id, kind",
  ).bind(draftId).all<ShareObjectRow>();
  if (sets.length !== share.set_count || objects.length !== share.set_count * 2 || objects.some((object) => object.status !== "COMPLETE")) {
    throw new HttpError(409, "UPLOAD_INCOMPLETE", "Every set's audio and transcript package must finish uploading first.");
  }
  let totalSize = 0;
  for (const set of sets) {
    const audio = objects.find((object) => object.share_set_id === set.id && object.kind === "AUDIO");
    const metadataObject = objects.find((object) => object.share_set_id === set.id && object.kind === "METADATA");
    if (!audio || !metadataObject) throw new HttpError(409, "UPLOAD_INCOMPLETE", "A selected set is missing required objects.");
    const audioHead = await env.AUDIO.head(audio.object_key);
    const metadataStored = await env.AUDIO.get(metadataObject.object_key);
    if (!audioHead || !metadataStored || audioHead.size !== audio.actual_size_bytes || metadataStored.size !== metadataObject.actual_size_bytes) {
      throw new HttpError(409, "OBJECT_MISSING", "A cloud object is missing or incomplete.");
    }
    const raw = await metadataStored.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_METADATA_BYTES) {
      throw new HttpError(400, "METADATA_TOO_LARGE", "A set transcript package is too large.");
    }
    let parsed: unknown;
    try { parsed = JSON.parse(raw); } catch { throw new HttpError(400, "INVALID_METADATA", "A set transcript package is invalid JSON."); }
    validateMetadata(parsed, set.title, set.duration_ms);
    totalSize += (audio.actual_size_bytes ?? 0) + (metadataObject.actual_size_bytes ?? 0);
  }
  const now = Date.now();
  const expiresAt = now + share.expiry_days * DAY_MS;
  const result = await env.DB.prepare(
    "UPDATE shares SET status = 'ACTIVE', bearer_hash = ?, total_size_bytes = ?, published_at = ?, expires_at = ?, updated_at = ? WHERE id = ? AND owner_device_id = ? AND status = 'DRAFT'",
  ).bind(tokenHash, totalSize, now, expiresAt, now, draftId, owner.id).run();
  if (!result.meta.changes) throw new HttpError(409, "PUBLISH_CONFLICT", "The share changed while it was being published. Retry safely.");
  return json(publicationPayload(request, env, { ...share, status: "ACTIVE", bearer_hash: tokenHash, total_size_bytes: totalSize, published_at: now, expires_at: expiresAt, updated_at: now }, publicToken), 201);
}

async function cancelDraft(env: Env, owner: OwnerDevice, draftId: string, ctx: ExecutionContext): Promise<Response> {
  const share = await ownedShare(env, owner.id, draftId);
  if (share.status !== "DRAFT" && share.status !== "FAILED") {
    throw new HttpError(409, "NOT_A_DRAFT", "Only an unpublished draft can be cancelled.");
  }
  await env.DB.prepare("UPDATE shares SET status = 'FAILED', updated_at = ?, error_message = 'Cancelled' WHERE id = ?").bind(Date.now(), draftId).run();
  ctx.waitUntil(deleteShareObjects(env, draftId));
  return json({ cancelled: true }, 202);
}

async function extendShare(request: Request, env: Env, owner: OwnerDevice, shareId: string): Promise<Response> {
  const body = await readJson<{ expiryDays?: number }>(request, 4_000);
  if (![30, 60, 90].includes(body.expiryDays ?? 0)) throw new HttpError(400, "INVALID_EXPIRY", "Expiry must be 30, 60, or 90 days.");
  const share = await ownedShare(env, owner.id, shareId);
  if (share.status !== "ACTIVE") throw new HttpError(409, "SHARE_NOT_ACTIVE", "Only an active share can be extended.");
  const now = Date.now();
  const expiresAt = now + body.expiryDays! * DAY_MS;
  await env.DB.prepare("UPDATE shares SET expiry_days = ?, expires_at = ?, updated_at = ? WHERE id = ?").bind(body.expiryDays, expiresAt, now, shareId).run();
  return json({ shareId, expiryDays: body.expiryDays, expiresAt });
}

async function revokeShare(env: Env, owner: OwnerDevice, shareId: string, ctx: ExecutionContext): Promise<Response> {
  const share = await ownedShare(env, owner.id, shareId);
  if (share.status === "REVOKED" || share.status === "EXPIRED") return json({ revoked: true }, 202);
  if (share.status !== "ACTIVE") throw new HttpError(409, "SHARE_NOT_ACTIVE", "Only an active share can be revoked.");
  const now = Date.now();
  await env.DB.prepare("UPDATE shares SET status = 'REVOKED', revoked_at = ?, updated_at = ? WHERE id = ?").bind(now, now, shareId).run();
  ctx.waitUntil(deleteShareObjects(env, shareId));
  return json({ revoked: true }, 202);
}

async function ownerUsage(env: Env): Promise<Response> {
  const tracked = await env.DB.prepare(
    "SELECT COALESCE(SUM(o.actual_size_bytes), 0) AS bytes, COUNT(o.id) AS objects FROM share_objects o JOIN shares s ON s.id = o.share_id WHERE o.status = 'COMPLETE' AND s.status IN ('DRAFT', 'ACTIVE')",
  ).first<{ bytes: number; objects: number }>();
  const active = await env.DB.prepare("SELECT COUNT(*) AS count FROM shares WHERE status = 'ACTIVE' AND expires_at > ?")
    .bind(Date.now()).first<{ count: number }>();
  const reconciled = await env.DB.prepare("SELECT * FROM usage_snapshots WHERE id = 1").first<{
    payload_bytes: number; metadata_bytes: number; object_count: number; measured_at: number; source: string;
  }>();
  const currentBytes = Number(tracked?.bytes ?? 0);
  const referenceBytes = freeStorageBytes(env);
  return json({
    currentBytes,
    referenceBytes,
    percentUsed: referenceBytes > 0 ? currentBytes / referenceBytes : 0,
    activeLinks: Number(active?.count ?? 0),
    trackedObjects: Number(tracked?.objects ?? 0),
    measuredAt: Date.now(),
    source: "manifest",
    providerMetric: reconciled ? {
      currentBytes: reconciled.payload_bytes + reconciled.metadata_bytes,
      objectCount: reconciled.object_count,
      measuredAt: reconciled.measured_at,
      source: reconciled.source,
    } : null,
    billingNote: "Cloudflare's 10 GB-month allowance is based on average daily peak storage, not a fixed disk capacity.",
  });
}

async function listShares(env: Env, owner: OwnerDevice): Promise<Response> {
  const { results = [] } = await env.DB.prepare(
    `SELECT s.id, s.status, s.title, s.expiry_days, s.set_count, s.total_duration_ms, s.total_size_bytes,
            s.created_at, s.published_at, s.expires_at, s.revoked_at,
            COALESCE((SELECT json_group_array(json_object('id', ss.id, 'title', ss.title, 'durationMs', ss.duration_ms))
                      FROM share_sets ss WHERE ss.share_id = s.id ORDER BY ss.position), '[]') AS sets_json
       FROM shares s
      WHERE s.owner_device_id = ? AND s.status <> 'DRAFT'
      ORDER BY CASE WHEN s.status = 'ACTIVE' THEN 0 ELSE 1 END, s.expires_at ASC, s.created_at DESC`,
  ).bind(owner.id).all<Record<string, unknown>>();
  return json({ shares: results.map((row) => ({
    id: row.id,
    status: row.status,
    title: row.title,
    expiryDays: row.expiry_days,
    setCount: row.set_count,
    totalDurationMs: row.total_duration_ms,
    totalSizeBytes: row.total_size_bytes,
    createdAt: row.created_at,
    publishedAt: row.published_at,
    expiresAt: row.expires_at,
    revokedAt: row.revoked_at,
    sets: JSON.parse(String(row.sets_json)),
  })) });
}

async function publicSnapshot(request: Request, env: Env, token: string): Promise<Response> {
  const share = await resolvePublicShare(env, token);
  if (!share) throw new HttpError(404, "SHARE_UNAVAILABLE", "This share is unavailable.");
  if (share.pin_hash && !(await hasValidPinSession(request, env, share))) {
    throw new HttpError(401, "PIN_REQUIRED", "Enter the share PIN to continue.");
  }
  const sets = await publicSets(env, share, token);
  return json({ title: share.title, expiresAt: share.expires_at, sets });
}

async function publicSets(env: Env, share: ShareRow, token: string): Promise<Array<ShareMetadata & { id: string; audioUrl: string }>> {
  const { results: sets = [] } = await env.DB.prepare("SELECT * FROM share_sets WHERE share_id = ? ORDER BY position")
    .bind(share.id).all<ShareSetRow>();
  const { results: objects = [] } = await env.DB.prepare("SELECT * FROM share_objects WHERE share_id = ? AND status = 'COMPLETE'")
    .bind(share.id).all<ShareObjectRow>();
  const output: Array<ShareMetadata & { id: string; audioUrl: string }> = [];
  for (const set of sets) {
    const metadataObject = objects.find((object) => object.share_set_id === set.id && object.kind === "METADATA");
    const audio = objects.find((object) => object.share_set_id === set.id && object.kind === "AUDIO");
    if (!metadataObject || !audio) throw new HttpError(404, "SHARE_UNAVAILABLE", "This share is unavailable.");
    const stored = await env.AUDIO.get(metadataObject.object_key);
    if (!stored) throw new HttpError(404, "SHARE_UNAVAILABLE", "This share is unavailable.");
    const metadata = validateMetadata(await stored.json<unknown>(), set.title, set.duration_ms);
    output.push({ ...metadata, id: set.id, audioUrl: `/v1/public/${encodeURIComponent(token)}/sets/${set.id}/audio` });
  }
  return output;
}

async function publicAudio(request: Request, env: Env, token: string, setId: string): Promise<Response> {
  const share = await resolvePublicShare(env, token);
  if (!share || (share.pin_hash && !(await hasValidPinSession(request, env, share)))) return unavailable();
  const object = await env.DB.prepare(
    `SELECT o.* FROM share_objects o JOIN share_sets ss ON ss.id = o.share_set_id
      WHERE o.share_id = ? AND ss.id = ? AND o.kind = 'AUDIO' AND o.status = 'COMPLETE' LIMIT 1`,
  ).bind(share.id, setId).first<ShareObjectRow>();
  if (!object) return unavailable();
  const head = await env.AUDIO.head(object.object_key);
  if (!head) return unavailable();
  const range = parseRange(request.headers.get("Range"), head.size);
  if (range === "invalid") {
    return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${head.size}`, "Accept-Ranges": "bytes" } });
  }
  if (request.method === "HEAD") {
    return new Response(null, { status: 200, headers: audioHeaders(object.mime_type, head.size) });
  }
  const stored = range
    ? await env.AUDIO.get(object.object_key, { range: { offset: range.start, length: range.length } })
    : await env.AUDIO.get(object.object_key);
  if (!stored) return unavailable();
  const headers = audioHeaders(object.mime_type, range?.length ?? head.size);
  if (range) headers.set("Content-Range", `bytes ${range.start}-${range.end}/${head.size}`);
  return new Response(stored.body, { status: range ? 206 : 200, headers });
}

async function unlockShare(request: Request, env: Env, token: string): Promise<Response> {
  const share = await resolvePublicShare(env, token);
  if (!share || !share.pin_hash || !share.pin_salt) throw new HttpError(404, "SHARE_UNAVAILABLE", "This share is unavailable.");
  const body = await readJson<{ pin?: string }>(request, 4_000);
  const pin = body.pin ?? "";
  const client = await keyedHash(request.headers.get("CF-Connecting-IP") ?? "unknown", env.TOKEN_PEPPER);
  const now = Date.now();
  const attempt = await env.DB.prepare("SELECT window_started_at, failures FROM pin_attempts WHERE share_id = ? AND client_hash = ?")
    .bind(share.id, client).first<{ window_started_at: number; failures: number }>();
  if (attempt && now - attempt.window_started_at < PIN_WINDOW_MS && attempt.failures >= PIN_MAX_FAILURES) {
    throw new HttpError(429, "PIN_RATE_LIMIT", "Too many incorrect attempts. Try again in 15 minutes.");
  }
  const candidate = /^\d{6,12}$/.test(pin)
    ? await derivePinHash(pin, share.pin_salt, env.PIN_PEPPER, pinIterations(env))
    : "invalid";
  if (!safeEqual(candidate, share.pin_hash)) {
    const windowStart = attempt && now - attempt.window_started_at < PIN_WINDOW_MS ? attempt.window_started_at : now;
    const failures = attempt && windowStart === attempt.window_started_at ? attempt.failures + 1 : 1;
    await env.DB.prepare(
      "INSERT INTO pin_attempts (share_id, client_hash, window_started_at, failures) VALUES (?, ?, ?, ?) ON CONFLICT(share_id, client_hash) DO UPDATE SET window_started_at = excluded.window_started_at, failures = excluded.failures",
    ).bind(share.id, client, windowStart, failures).run();
    throw new HttpError(401, "PIN_INCORRECT", "That PIN did not work.");
  }
  await env.DB.prepare("DELETE FROM pin_attempts WHERE share_id = ? AND client_hash = ?").bind(share.id, client).run();
  const expires = Math.min(share.expires_at ?? now, now + DAY_MS);
  const session = `${expires}.${await keyedHash(`${share.id}:${expires}`, env.PIN_PEPPER)}`;
  return json({ unlocked: true }, 200, {
    "Set-Cookie": `debrief_share=${session}; Path=/; Max-Age=${Math.max(1, Math.floor((expires - now) / 1000))}; HttpOnly; Secure; SameSite=Strict`,
  });
}

async function hasValidPinSession(request: Request, env: Env, share: ShareRow): Promise<boolean> {
  const cookie = request.headers.get("Cookie")?.split(";").map((item) => item.trim()).find((item) => item.startsWith("debrief_share="));
  const value = cookie?.slice("debrief_share=".length);
  if (!value) return false;
  const [expiresText, signature] = value.split(".");
  const expires = Number(expiresText);
  if (!signature || !Number.isSafeInteger(expires) || expires <= Date.now() || expires > (share.expires_at ?? 0)) return false;
  const expected = await keyedHash(`${share.id}:${expires}`, env.PIN_PEPPER);
  return safeEqual(signature, expected);
}

async function resolvePublicShare(env: Env, token: string): Promise<ShareRow | null> {
  if (!/^[A-Za-z0-9_-]{43,100}$/.test(token)) return null;
  const share = await env.DB.prepare("SELECT * FROM shares WHERE bearer_hash = ? AND status = 'ACTIVE' LIMIT 1")
    .bind(await keyedHash(token, env.TOKEN_PEPPER)).first<ShareRow>();
  if (!share || !share.expires_at || share.expires_at <= Date.now()) {
    if (share?.status === "ACTIVE") {
      const now = Date.now();
      await env.DB.prepare("UPDATE shares SET status = 'EXPIRED', updated_at = ? WHERE id = ? AND status = 'ACTIVE'").bind(now, share.id).run();
    }
    return null;
  }
  return share;
}

async function ownedShare(env: Env, ownerId: string, shareId: string): Promise<ShareRow> {
  const share = await env.DB.prepare("SELECT * FROM shares WHERE id = ? AND owner_device_id = ? LIMIT 1")
    .bind(shareId, ownerId).first<ShareRow>();
  if (!share) throw new HttpError(404, "SHARE_NOT_FOUND", "That share was not found.");
  return share;
}

async function ownedDraftObject(env: Env, ownerId: string, draftId: string, objectId: string): Promise<ShareObjectRow> {
  const object = await env.DB.prepare(
    `SELECT o.* FROM share_objects o JOIN shares s ON s.id = o.share_id
      WHERE o.id = ? AND o.share_id = ? AND s.owner_device_id = ? AND s.status = 'DRAFT' LIMIT 1`,
  ).bind(objectId, draftId, ownerId).first<ShareObjectRow>();
  if (!object) throw new HttpError(404, "UPLOAD_NOT_FOUND", "That private upload was not found.");
  return object;
}

async function deleteShareObjects(env: Env, shareId: string): Promise<void> {
  const { results = [] } = await env.DB.prepare("SELECT * FROM share_objects WHERE share_id = ? AND status <> 'DELETED'")
    .bind(shareId).all<ShareObjectRow>();
  for (const object of results) {
    try {
      if (object.status === "UPLOADING") {
        await env.AUDIO.resumeMultipartUpload(object.object_key, object.upload_id).abort();
      } else {
        await env.AUDIO.delete(object.object_key);
      }
      await env.DB.prepare("UPDATE share_objects SET status = 'DELETED', updated_at = ? WHERE id = ?")
        .bind(Date.now(), object.id).run();
    } catch {
      // The next hourly maintenance pass retries deletion without exposing content.
    }
  }
}

async function runMaintenance(env: Env): Promise<void> {
  const now = Date.now();
  const draftCutoff = now - stagingTtlHours(env) * HOUR_MS;
  const { results: stale = [] } = await env.DB.prepare(
    "SELECT id, status FROM shares WHERE (status = 'ACTIVE' AND expires_at <= ?) OR (status IN ('DRAFT', 'FAILED') AND updated_at <= ?) OR status IN ('REVOKED', 'EXPIRED') LIMIT 100",
  ).bind(now, draftCutoff).all<{ id: string; status: ShareRow["status"] }>();
  for (const share of stale) {
    if (share.status === "ACTIVE") {
      await env.DB.prepare("UPDATE shares SET status = 'EXPIRED', updated_at = ? WHERE id = ? AND status = 'ACTIVE'").bind(now, share.id).run();
    } else if (share.status === "DRAFT") {
      await env.DB.prepare("UPDATE shares SET status = 'FAILED', error_message = 'Draft expired', updated_at = ? WHERE id = ?").bind(now, share.id).run();
    }
    await deleteShareObjects(env, share.id);
  }
  await reconcileUsage(env);
}

async function reconcileUsage(env: Env): Promise<void> {
  let cursor: string | undefined;
  let payload = 0;
  let metadata = 0;
  let count = 0;
  do {
    const page = await env.AUDIO.list({ cursor, limit: 1000 });
    for (const object of page.objects) {
      payload += object.size;
      metadata += JSON.stringify(object.customMetadata ?? {}).length + JSON.stringify(object.httpMetadata ?? {}).length;
      count += 1;
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  await env.DB.prepare(
    "INSERT INTO usage_snapshots (id, payload_bytes, metadata_bytes, object_count, measured_at, source) VALUES (1, ?, ?, ?, ?, 'r2-list') ON CONFLICT(id) DO UPDATE SET payload_bytes = excluded.payload_bytes, metadata_bytes = excluded.metadata_bytes, object_count = excluded.object_count, measured_at = excluded.measured_at, source = excluded.source",
  ).bind(payload, metadata, count, Date.now()).run();
}

function publicationPayload(request: Request, env: Env, share: ShareRow, token: string): Record<string, unknown> {
  const base = publicBaseUrl(request, env);
  return { shareId: share.id, url: `${base}/s/${token}`, expiresAt: share.expires_at, sizeBytes: share.total_size_bytes };
}

function publicBaseUrl(request: Request, env: Env): string {
  const configured = env.PUBLIC_BASE_URL.trim().replace(/\/$/, "");
  if (configured && !configured.includes("localhost")) return configured;
  return new URL(request.url).origin;
}

function normalizePairingCode(value: string): string {
  return value.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

function pinIterations(env: Env): number {
  const value = Number(env.PIN_KDF_ITERATIONS);
  return Number.isInteger(value) && value >= 10_000 && value <= 200_000 ? value : 25_000;
}

function stagingTtlHours(env: Env): number {
  const value = Number(env.STAGING_TTL_HOURS);
  return Number.isFinite(value) && value >= 1 && value <= 168 ? value : 24;
}

function freeStorageBytes(env: Env): number {
  const value = Number(env.FREE_STORAGE_BYTES);
  return Number.isSafeInteger(value) && value > 0 ? value : 10_000_000_000;
}

function parseRange(value: string | null, size: number): { start: number; end: number; length: number } | "invalid" | null {
  if (!value) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value.trim());
  if (!match) return "invalid";
  const startText = match[1] ?? "";
  const endText = match[2] ?? "";
  if (!startText && !endText) return "invalid";
  let start: number;
  let end: number;
  if (!startText) {
    const suffix = Number(endText);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return "invalid";
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(startText);
    end = endText ? Number(endText) : size - 1;
  }
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) return "invalid";
  end = Math.min(end, size - 1);
  return { start, end, length: end - start + 1 };
}

function audioHeaders(contentType: string, contentLength: number): Headers {
  return new Headers({
    "Accept-Ranges": "bytes",
    "Cache-Control": "private, no-store",
    "Content-Disposition": "inline",
    "Content-Length": String(contentLength),
    "Content-Type": contentType,
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  });
}
