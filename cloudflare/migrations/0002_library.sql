-- Personal cloud library.
--
-- Separate from Share Sets and deliberately opaque. Share Sets publishes
-- derived clips for someone else to watch; this stores the owner's own
-- recordings so they can reach them from any paired device.
--
-- Everything of substance is encrypted client-side before upload, so this
-- schema holds no titles, no durations, no transcript text and no filenames --
-- only sizes, nonces, versions and object keys. The nonces are not secret;
-- AES-GCM requires them to decrypt and they reveal nothing on their own.
--
-- Items are scoped to the deployment rather than to a device. That is the
-- point: a recording uploaded from the desktop browser has to be readable by
-- the phone, and both are separate rows in owner_devices.

PRAGMA foreign_keys = ON;

CREATE TABLE library_items (
    -- The client's own recording id, so sync is idempotent across devices.
    id TEXT PRIMARY KEY,
    version INTEGER NOT NULL DEFAULT 1,
    updated_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    -- Audit only; never used to filter reads.
    created_by_device TEXT,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETE')),

    audio_key TEXT NOT NULL,
    audio_nonce TEXT NOT NULL,
    -- Plain size drives player ranges; stored size includes one GCM tag/chunk.
    audio_plain_bytes INTEGER NOT NULL DEFAULT 0,
    audio_bytes INTEGER NOT NULL DEFAULT 0,
    crypto_version INTEGER NOT NULL DEFAULT 2,
    chunk_bytes INTEGER NOT NULL DEFAULT 8388608,
    audio_upload_id TEXT,
    audio_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (audio_status IN ('PENDING', 'COMPLETE')),

    meta_key TEXT NOT NULL,
    meta_nonce TEXT NOT NULL,
    meta_bytes INTEGER NOT NULL DEFAULT 0,
    meta_upload_id TEXT,
    meta_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (meta_status IN ('PENDING', 'COMPLETE')),

    FOREIGN KEY (created_by_device) REFERENCES owner_devices(id)
);

CREATE INDEX library_items_updated ON library_items(updated_at);
CREATE INDEX library_items_status ON library_items(status);

-- The library data key, wrapped with a key derived from the user's passphrase.
--
-- Stored server-side on purpose: it is what lets a brand new device read the
-- library after typing the passphrase, with nothing to transfer by hand. The
-- server only ever holds ciphertext plus a public salt, so it cannot unwrap it.
--
-- Singleton: one deployment is one person's library.
CREATE TABLE library_keys (
    id TEXT PRIMARY KEY CHECK (id = 'default'),
    wrapped_key TEXT NOT NULL,
    salt TEXT NOT NULL,
    iterations INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
