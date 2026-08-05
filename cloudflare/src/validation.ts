import { HttpError } from "./http";
import type { ShareMetadata } from "./types";

export const MAX_SETS = 10;
export const MAX_TOTAL_DURATION_MS = 3 * 60 * 60 * 1000;
export const MAX_METADATA_BYTES = 2_000_000;
export const MAX_OBJECT_BYTES = 1_000_000_000;
export const MULTIPART_MIN_BYTES = 5 * 1024 * 1024;
export const MULTIPART_MAX_PARTS = 10_000;

export interface CreateDraftBody {
  title: string;
  expiryDays: 30 | 60 | 90;
  pin?: string | null;
  sets: Array<{
    clientSetId: string;
    title: string;
    durationMs: number;
    audioMimeType: string;
    expectedAudioBytes?: number | null;
    expectedMetadataBytes?: number | null;
  }>;
}

export function validateDraft(body: CreateDraftBody): CreateDraftBody {
  body.title = cleanText(body.title, "Share title", 1, 160);
  if (![30, 60, 90].includes(body.expiryDays)) {
    throw new HttpError(400, "INVALID_EXPIRY", "Expiry must be 30, 60, or 90 days.");
  }
  if (!Array.isArray(body.sets) || body.sets.length === 0 || body.sets.length > MAX_SETS) {
    throw new HttpError(400, "INVALID_SET_COUNT", `Choose between 1 and ${MAX_SETS} completed sets.`);
  }
  const ids = new Set<string>();
  let totalDuration = 0;
  body.sets.forEach((set, position) => {
    set.clientSetId = cleanIdentifier(set.clientSetId, `Set ${position + 1} identifier`);
    if (ids.has(set.clientSetId)) throw new HttpError(400, "DUPLICATE_SET", "Selected sets must be unique.");
    ids.add(set.clientSetId);
    set.title = cleanText(set.title, `Set ${position + 1} title`, 1, 160);
    if (!Number.isSafeInteger(set.durationMs) || set.durationMs <= 0) {
      throw new HttpError(400, "INVALID_SET_DURATION", "Every selected set must have a valid end marker.");
    }
    totalDuration += set.durationMs;
    if (!/^audio\/(mp4|m4a|aac|mpeg|ogg|webm)$/i.test(set.audioMimeType)) {
      throw new HttpError(400, "INVALID_AUDIO_TYPE", "A selected set uses an unsupported audio type.");
    }
    validateExpectedBytes(set.expectedAudioBytes, MAX_OBJECT_BYTES, "audio");
    validateExpectedBytes(set.expectedMetadataBytes, MAX_METADATA_BYTES, "metadata");
  });
  if (totalDuration > MAX_TOTAL_DURATION_MS) {
    throw new HttpError(400, "DURATION_LIMIT", "One share can contain at most three hours of audio.");
  }
  if (body.pin != null) {
    if (!/^\d{6,12}$/.test(body.pin)) {
      throw new HttpError(400, "INVALID_PIN", "A share PIN must contain 6 to 12 digits.");
    }
  }
  return body;
}

export function validateMetadata(value: unknown, expectedTitle: string, expectedDurationMs: number): ShareMetadata {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "INVALID_METADATA", "Set metadata must be a JSON object.");
  }
  rejectPrivateKeys(value);
  const data = value as Partial<ShareMetadata>;
  if (data.schemaVersion !== 1 || data.title !== expectedTitle || data.durationMs !== expectedDurationMs) {
    throw new HttpError(400, "METADATA_MISMATCH", "Set metadata does not match its share manifest.");
  }
  if (!Array.isArray(data.segments) || !Array.isArray(data.comments)) {
    throw new HttpError(400, "INVALID_METADATA", "Set metadata must contain transcript segments and comments.");
  }
  let previousStart = -1;
  for (const segment of data.segments) {
    segment.speaker = cleanText(segment.speaker, "Speaker", 1, 80);
    segment.text = cleanText(segment.text, "Transcript text", 1, 20_000);
    validateRelativeRange(segment.startMs, segment.endMs, expectedDurationMs, "transcript segment");
    if (segment.startMs < previousStart) {
      throw new HttpError(400, "UNSORTED_METADATA", "Transcript segments must be chronological.");
    }
    previousStart = segment.startMs;
  }
  let previousComment = -1;
  for (const comment of data.comments) {
    comment.text = cleanText(comment.text, "Comment", 1, 20_000);
    if (!Number.isSafeInteger(comment.timestampMs) || comment.timestampMs < 0 || comment.timestampMs > expectedDurationMs) {
      throw new HttpError(400, "COMMENT_OUT_OF_RANGE", "A comment falls outside its selected set.");
    }
    if (comment.timestampMs < previousComment) {
      throw new HttpError(400, "UNSORTED_METADATA", "Comments must be chronological.");
    }
    previousComment = comment.timestampMs;
  }
  return data as ShareMetadata;
}

export function validateParts(parts: Array<{ partNumber: number; etag: string }>): void {
  if (!Array.isArray(parts) || parts.length === 0 || parts.length > MULTIPART_MAX_PARTS) {
    throw new HttpError(400, "INVALID_PARTS", "The upload must contain valid completed parts.");
  }
  let expected = 1;
  for (const part of parts) {
    if (part.partNumber !== expected || typeof part.etag !== "string" || part.etag.length < 3 || part.etag.length > 512) {
      throw new HttpError(400, "INVALID_PARTS", "Upload parts must be consecutive and include valid ETags.");
    }
    expected += 1;
  }
}

function validateExpectedBytes(value: number | null | undefined, maximum: number, label: string): void {
  if (value == null) return;
  if (!Number.isSafeInteger(value) || value <= 0 || value > maximum) {
    throw new HttpError(400, "INVALID_EXPECTED_SIZE", `The expected ${label} size is invalid.`);
  }
}

function validateRelativeRange(startMs: number, endMs: number, durationMs: number, label: string): void {
  if (!Number.isSafeInteger(startMs) || !Number.isSafeInteger(endMs) || startMs < 0 || endMs <= startMs || endMs > durationMs) {
    throw new HttpError(400, "SEGMENT_OUT_OF_RANGE", `A ${label} falls outside its selected set.`);
  }
}

function cleanIdentifier(value: unknown, label: string): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,100}$/.test(value)) {
    throw new HttpError(400, "INVALID_IDENTIFIER", `${label} is invalid.`);
  }
  return value;
}

function cleanText(value: unknown, label: string, minimum: number, maximum: number): string {
  if (typeof value !== "string") throw new HttpError(400, "INVALID_TEXT", `${label} is invalid.`);
  const cleaned = value.trim();
  if (cleaned.length < minimum || cleaned.length > maximum || /[\u0000-\u0008\u000B\u000C\u000E-\u001F]/.test(cleaned)) {
    throw new HttpError(400, "INVALID_TEXT", `${label} is invalid.`);
  }
  return cleaned;
}

function rejectPrivateKeys(value: object): void {
  const forbidden = new Set([
    "recordingId",
    "recordingName",
    "documentUri",
    "displayName",
    "fileName",
    "filename",
    "folder",
    "path",
    "sourceStartMs",
    "sourceEndMs",
    "redactions",
    "redactionText",
  ]);
  const stack: unknown[] = [value];
  while (stack.length > 0) {
    const current = stack.pop();
    if (!current || typeof current !== "object") continue;
    for (const [key, child] of Object.entries(current)) {
      if (forbidden.has(key)) {
        throw new HttpError(400, "PRIVATE_METADATA", "Set metadata contains a private source field.");
      }
      if (typeof child === "object" && child != null) stack.push(child);
    }
  }
}
