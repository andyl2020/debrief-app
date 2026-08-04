export interface Env {
  DB: D1Database;
  AUDIO: R2Bucket;
  PUBLIC_BASE_URL: string;
  FREE_STORAGE_BYTES: string;
  PIN_KDF_ITERATIONS: string;
  STAGING_TTL_HOURS: string;
  BOOTSTRAP_SECRET: string;
  TOKEN_PEPPER: string;
  PIN_PEPPER: string;
}

export interface OwnerDevice {
  id: string;
  label: string;
}

export interface ShareRow {
  id: string;
  owner_device_id: string;
  status: "DRAFT" | "ACTIVE" | "REVOKED" | "EXPIRED" | "FAILED";
  title: string;
  expiry_days: number;
  bearer_hash: string | null;
  pin_hash: string | null;
  pin_salt: string | null;
  set_count: number;
  total_duration_ms: number;
  total_size_bytes: number;
  created_at: number;
  updated_at: number;
  published_at: number | null;
  expires_at: number | null;
  revoked_at: number | null;
}

export interface ShareSetRow {
  id: string;
  share_id: string;
  client_set_id: string;
  position: number;
  title: string;
  duration_ms: number;
}

export interface ShareObjectRow {
  id: string;
  share_id: string;
  share_set_id: string;
  kind: "AUDIO" | "METADATA";
  object_key: string;
  mime_type: string;
  upload_id: string;
  status: "UPLOADING" | "COMPLETE" | "DELETED" | "FAILED";
  expected_size_bytes: number | null;
  actual_size_bytes: number | null;
  sha256: string | null;
  parts_json: string;
}

export interface ShareMetadata {
  schemaVersion: 1;
  title: string;
  durationMs: number;
  segments: Array<{
    speaker: string;
    startMs: number;
    endMs: number;
    text: string;
  }>;
  comments: Array<{
    timestampMs: number;
    text: string;
  }>;
}
