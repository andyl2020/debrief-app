PRAGMA foreign_keys = ON;

CREATE TABLE owner_devices (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE TABLE pairing_codes (
    code_hash TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER
);

CREATE TABLE shares (
    id TEXT PRIMARY KEY,
    owner_device_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'REVOKED', 'EXPIRED', 'FAILED')),
    title TEXT NOT NULL,
    expiry_days INTEGER NOT NULL CHECK (expiry_days IN (30, 60, 90)),
    bearer_hash TEXT UNIQUE,
    pin_hash TEXT,
    pin_salt TEXT,
    set_count INTEGER NOT NULL,
    total_duration_ms INTEGER NOT NULL,
    total_size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    published_at INTEGER,
    expires_at INTEGER,
    revoked_at INTEGER,
    error_message TEXT,
    FOREIGN KEY (owner_device_id) REFERENCES owner_devices(id)
);

CREATE INDEX shares_owner_status_expiry ON shares(owner_device_id, status, expires_at);
CREATE INDEX shares_bearer_hash ON shares(bearer_hash);

CREATE TABLE share_sets (
    id TEXT PRIMARY KEY,
    share_id TEXT NOT NULL,
    client_set_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    title TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (share_id) REFERENCES shares(id) ON DELETE CASCADE,
    UNIQUE (share_id, client_set_id),
    UNIQUE (share_id, position)
);

CREATE INDEX share_sets_share_position ON share_sets(share_id, position);

CREATE TABLE share_objects (
    id TEXT PRIMARY KEY,
    share_id TEXT NOT NULL,
    share_set_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('AUDIO', 'METADATA')),
    object_key TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    upload_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('UPLOADING', 'COMPLETE', 'DELETED', 'FAILED')),
    expected_size_bytes INTEGER,
    actual_size_bytes INTEGER,
    sha256 TEXT,
    parts_json TEXT NOT NULL DEFAULT '[]',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (share_id) REFERENCES shares(id) ON DELETE CASCADE,
    FOREIGN KEY (share_set_id) REFERENCES share_sets(id) ON DELETE CASCADE,
    UNIQUE (share_set_id, kind)
);

CREATE INDEX share_objects_share_status ON share_objects(share_id, status);

CREATE TABLE pin_attempts (
    share_id TEXT NOT NULL,
    client_hash TEXT NOT NULL,
    window_started_at INTEGER NOT NULL,
    failures INTEGER NOT NULL,
    PRIMARY KEY (share_id, client_hash),
    FOREIGN KEY (share_id) REFERENCES shares(id) ON DELETE CASCADE
);

CREATE TABLE usage_snapshots (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    payload_bytes INTEGER NOT NULL,
    metadata_bytes INTEGER NOT NULL,
    object_count INTEGER NOT NULL,
    measured_at INTEGER NOT NULL,
    source TEXT NOT NULL
);
