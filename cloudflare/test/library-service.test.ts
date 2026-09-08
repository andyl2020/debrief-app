import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

const ORIGIN = "https://share.example.test";
const WEB_ORIGIN = "http://localhost:5173";

/**
 * Personal cloud library.
 *
 * The properties worth pinning are the ones that make "my recordings, anywhere"
 * safe: that a second device can read what a first uploaded, that the server
 * holds nothing readable, and that a truncated upload is refused rather than
 * silently serving corrupt audio.
 */
describe("Personal cloud library", () => {
  it("requires a paired owner credential", async () => {
    const response = await SELF.fetch(`${ORIGIN}/v1/owner/library`);
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "PAIRING_REQUIRED" } });
  });

  it("stores and returns the wrapped library key without being able to read it", async () => {
    const owner = await pairOwner("Key device");
    const put = await ownerFetch(owner, "/v1/owner/library/key", {
      method: "PUT",
      body: JSON.stringify({ wrappedKey: "d3JhcHBlZC1rZXktY2lwaGVydGV4dA==", salt: "c2FsdHktc2FsdA==", iterations: 310_000 }),
    });
    expect(put.status).toBe(200);

    const get = await ownerFetch(owner, "/v1/owner/library/key");
    await expect(get.json()).resolves.toMatchObject({
      wrappedKey: "d3JhcHBlZC1rZXktY2lwaGVydGV4dA==",
      iterations: 310_000,
    });
  });

  it("refuses to replace the library key once recordings exist", async () => {
    // A different passphrase derives a different key, which would leave every
    // uploaded recording permanently undecryptable. That must not be a
    // one-tap accident.
    const owner = await pairOwner("Replace device");
    await putKey(owner);
    await uploadItem(owner, "rec-replace", "encrypted-audio-bytes-here", "{\"schemaVersion\":4}");

    const replace = await ownerFetch(owner, "/v1/owner/library/key", {
      method: "PUT",
      body: JSON.stringify({ wrappedKey: "YW5vdGhlci13cmFwcGVkLWtleQ==", salt: "b3RoZXItc2FsdA==", iterations: 310_000 }),
    });
    expect(replace.status).toBe(409);
    await expect(replace.json()).resolves.toMatchObject({ error: { code: "LIBRARY_KEY_EXISTS" } });
  });

  it("lets a second paired device read what the first uploaded", async () => {
    // This is the whole feature: upload on the desktop, play on the phone.
    const desktop = await pairOwner("Desktop");
    await putKey(desktop);
    await uploadItem(desktop, "rec-1", "encrypted-audio-payload", "encrypted-metadata");

    const phone = await pairOwner("Phone");
    const list = await ownerFetch(phone, "/v1/owner/library");
    const body = await list.json<{ items: Array<{ id: string; status: string; audioBytes: number }> }>();
    // Scoped by id: the suite shares one D1 instance, so asserting on totals
    // would couple these tests to each other's fixtures.
    expect(body.items.find((item) => item.id === "rec-1")).toMatchObject({ status: "COMPLETE" });

    const audio = await ownerFetch(phone, "/v1/owner/library/items/rec-1/objects/audio");
    expect(audio.status).toBe(200);
    expect(await audio.text()).toBe("encrypted-audio-payload");

    const metadata = await ownerFetch(phone, "/v1/owner/library/items/rec-1/objects/metadata");
    expect(await metadata.text()).toBe("encrypted-metadata");
  });

  it("serves byte ranges so an encrypted recording can be seeked", async () => {
    const owner = await pairOwner("Range device");
    await putKey(owner);
    const payload = "0123456789abcdefghijklmnopqrstuvwxyz";
    await uploadItem(owner, "rec-range", payload, "meta");

    const ranged = await ownerFetch(owner, "/v1/owner/library/items/rec-range/objects/audio", {
      headers: { Range: "bytes=10-19" },
    });
    expect(ranged.status).toBe(206);
    expect(ranged.headers.get("Content-Range")).toBe(`bytes 10-19/${payload.length}`);
    expect(ranged.headers.get("Accept-Ranges")).toBe("bytes");
    expect(await ranged.text()).toBe("abcdefghij");

    const head = await ownerFetch(owner, "/v1/owner/library/items/rec-range/objects/audio", { method: "HEAD" });
    expect(head.status).toBe(200);
    expect(head.headers.get("Content-Length")).toBe(String(payload.length));
  });

  it("rejects an unsatisfiable range instead of returning wrong bytes", async () => {
    const owner = await pairOwner("Bad range device");
    await putKey(owner);
    await uploadItem(owner, "rec-bad-range", "ciphertext-long-enough", "meta");

    const response = await ownerFetch(owner, "/v1/owner/library/items/rec-bad-range/objects/audio", {
      headers: { Range: "bytes=900-999" },
    });
    expect(response.status).toBe(416);
  });

  it("labels stored audio as opaque bytes, never as playable media", async () => {
    // The stored object is ciphertext. Advertising audio/mp4 would invite a
    // browser to try to decode it and would leak what the object is.
    const owner = await pairOwner("Content type device");
    await putKey(owner);
    await uploadItem(owner, "rec-type", "ciphertext-long-enough", "meta");

    const response = await ownerFetch(owner, "/v1/owner/library/items/rec-type/objects/audio");
    expect(response.headers.get("Content-Type")).toBe("application/octet-stream");
  });

  it("refuses a truncated upload rather than serving corrupt audio", async () => {
    // Truncated ciphertext decrypts to garbage rather than failing, so a size
    // mismatch has to be caught here.
    const owner = await pairOwner("Truncated device");
    await putKey(owner);
    const begin = await ownerFetch(owner, "/v1/owner/library/items", {
      method: "POST",
      body: JSON.stringify({
        id: "rec-truncated",
        audioBytes: 1_000,
        audioCipherBytes: 1_016,
        metadataBytes: 4,
        audioNonce: "AAAAAAAAAAA=",
        metadataNonce: "BBBBBBBBBBB=",
        cryptoVersion: 2,
        chunkBytes: 8 * 1024 * 1024,
      }),
    });
    expect(begin.status).toBe(201);

    const part = await ownerFetch(owner, "/v1/owner/library/items/rec-truncated/objects/audio/parts/1", {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: "far too short",
    });
    const { etag } = await part.json<{ etag: string }>();

    const complete = await ownerFetch(owner, "/v1/owner/library/items/rec-truncated/objects/audio/complete", {
      method: "POST",
      body: JSON.stringify({ parts: [{ partNumber: 1, etag }] }),
    });
    expect(complete.status).toBe(409);
    await expect(complete.json()).resolves.toMatchObject({ error: { code: "SIZE_MISMATCH" } });
  });

  it("will not serve an object whose upload never finished", async () => {
    const owner = await pairOwner("Incomplete device");
    await putKey(owner);
    await ownerFetch(owner, "/v1/owner/library/items", {
      method: "POST",
      body: JSON.stringify({
        id: "rec-incomplete",
        audioBytes: 10,
        audioCipherBytes: 26,
        metadataBytes: 4,
        audioNonce: "AAAAAAAAAAA=",
        metadataNonce: "BBBBBBBBBBB=",
        cryptoVersion: 2,
        chunkBytes: 8 * 1024 * 1024,
      }),
    });

    const response = await ownerFetch(owner, "/v1/owner/library/items/rec-incomplete/objects/audio");
    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "OBJECT_INCOMPLETE" } });
  });

  it("rejects malformed ids, sizes and nonces before allocating storage", async () => {
    const owner = await pairOwner("Validation device");
    const bad = async (body: Record<string, unknown>) =>
      (await ownerFetch(owner, "/v1/owner/library/items", { method: "POST", body: JSON.stringify(body) })).status;

    const base = {
      id: "ok", audioBytes: 10, audioCipherBytes: 26, metadataBytes: 4,
      audioNonce: "AAAAAAAAAAA=", metadataNonce: "BBBBBBBBBBB=",
      cryptoVersion: 2, chunkBytes: 8 * 1024 * 1024,
    };
    expect(await bad({ ...base, id: "../escape" })).toBe(400);
    expect(await bad({ ...base, audioBytes: 0 })).toBe(400);
    expect(await bad({ ...base, audioBytes: 5_000_000_000 })).toBe(400);
    expect(await bad({ ...base, audioNonce: "not base64!!" })).toBe(400);
  });

  it("re-uploading an item supersedes the previous attempt and bumps its version", async () => {
    const owner = await pairOwner("Resync device");
    await putKey(owner);
    await uploadItem(owner, "rec-resync", "first-payload-long-enough", "meta-1");
    await uploadItem(owner, "rec-resync", "second-payload-longer", "meta-2");

    const list = await ownerFetch(owner, "/v1/owner/library");
    const body = await list.json<{ items: Array<{ id: string; version: number }> }>();
    const matching = body.items.filter((item) => item.id === "rec-resync");
    // Re-uploading replaces in place rather than adding a second row.
    expect(matching).toHaveLength(1);
    expect(matching[0]!.version).toBe(2);

    const audio = await ownerFetch(owner, "/v1/owner/library/items/rec-resync/objects/audio");
    expect(await audio.text()).toBe("second-payload-longer");
  });

  it("deletes an item and reports usage", async () => {
    const owner = await pairOwner("Delete device");
    await putKey(owner);
    await uploadItem(owner, "rec-delete", "payload-long-enough", "meta");

    const before = await (await ownerFetch(owner, "/v1/owner/library/usage")).json<{ bytes: number; items: number }>();

    const removed = await ownerFetch(owner, "/v1/owner/library/items/rec-delete", { method: "DELETE" });
    expect(removed.status).toBe(200);

    const after = await (await ownerFetch(owner, "/v1/owner/library/usage")).json<{ bytes: number; items: number }>();
    expect(after.items).toBe(before.items - 1);
    expect(before.bytes - after.bytes).toBe("payload-long-enough".length + "meta".length);

    const list = await (await ownerFetch(owner, "/v1/owner/library")).json<{ items: Array<{ id: string }> }>();
    expect(list.items.some((item) => item.id === "rec-delete")).toBe(false);
    // Deleting something already gone is not an error.
    expect((await ownerFetch(owner, "/v1/owner/library/items/rec-delete", { method: "DELETE" })).status).toBe(200);
  });
});

describe("CORS", () => {
  it("answers preflight for an allowed web origin and exposes range headers", async () => {
    // Without Content-Range exposed the client cannot read a ranged response,
    // which is exactly what encrypted seeking depends on.
    const response = await SELF.fetch(`${ORIGIN}/v1/owner/library`, {
      method: "OPTIONS",
      headers: { Origin: WEB_ORIGIN, "Access-Control-Request-Method": "GET" },
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("Access-Control-Allow-Origin")).toBe(WEB_ORIGIN);
    expect(response.headers.get("Access-Control-Allow-Headers")).toContain("range");
    expect(response.headers.get("Access-Control-Expose-Headers")).toContain("content-range");
  });

  it("refuses preflight from an origin that is not allowed", async () => {
    const response = await SELF.fetch(`${ORIGIN}/v1/owner/library`, {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example", "Access-Control-Request-Method": "GET" },
    });
    expect(response.status).toBe(403);
    expect(response.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });

  it("does not put CORS headers on the public share viewer", async () => {
    // The viewer is same-origin HTML holding a bearer token's content; opening
    // it cross-origin would let any site read it.
    const response = await SELF.fetch(`${ORIGIN}/s/${"A".repeat(43)}`, {
      headers: { Origin: WEB_ORIGIN },
    });
    expect(response.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });
});

// --- helpers --------------------------------------------------------------

async function pairOwner(label: string): Promise<string> {
  const create = await SELF.fetch(`${ORIGIN}/v1/admin/pairing-codes`, {
    method: "POST",
    headers: { Authorization: `Bearer ${env.BOOTSTRAP_SECRET}` },
  });
  const { code } = await create.json<{ code: string }>();
  const pair = await SELF.fetch(`${ORIGIN}/v1/pair`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, label }),
  });
  return (await pair.json<{ ownerToken: string }>()).ownerToken;
}

async function ownerFetch(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return SELF.fetch(`${ORIGIN}${path}`, { ...init, headers });
}

async function putKey(owner: string): Promise<void> {
  await ownerFetch(owner, "/v1/owner/library/key", {
    method: "PUT",
    body: JSON.stringify({ wrappedKey: "d3JhcHBlZC1rZXktY2lwaGVydGV4dA==", salt: "c2FsdHktc2FsdA==", iterations: 310_000 }),
  });
}

/** Runs the full begin -> part -> complete flow for both objects. */
async function uploadItem(owner: string, id: string, audio: string, metadata: string): Promise<void> {
  if (audio.length <= 16) throw new Error("Test ciphertext fixtures must include room for a GCM tag.");
  const begin = await ownerFetch(owner, "/v1/owner/library/items", {
    method: "POST",
    body: JSON.stringify({
      id,
      audioBytes: audio.length - 16,
      audioCipherBytes: audio.length,
      metadataBytes: metadata.length,
      audioNonce: "AAAAAAAAAAA=",
      metadataNonce: "BBBBBBBBBBB=",
      cryptoVersion: 2,
      chunkBytes: 8 * 1024 * 1024,
    }),
  });
  expect(begin.status).toBe(201);

  for (const [kind, payload] of [["audio", audio], ["metadata", metadata]] as const) {
    const part = await ownerFetch(owner, `/v1/owner/library/items/${id}/objects/${kind}/parts/1`, {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: payload,
    });
    expect(part.status).toBe(200);
    const { etag } = await part.json<{ etag: string }>();
    const complete = await ownerFetch(owner, `/v1/owner/library/items/${id}/objects/${kind}/complete`, {
      method: "POST",
      body: JSON.stringify({ parts: [{ partNumber: 1, etag }] }),
    });
    expect(complete.status).toBe(200);
  }
}
