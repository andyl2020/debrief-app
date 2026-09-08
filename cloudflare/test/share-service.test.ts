import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

const ORIGIN = "https://share.example.test";

describe("Debrief share service", () => {
  it("exposes a small health endpoint with defensive headers", async () => {
    const response = await SELF.fetch(`${ORIGIN}/health`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, service: "debrief-share", version: 1 });
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.get("X-Content-Type-Options")).toBe("nosniff");

    const favicon = await SELF.fetch(`${ORIGIN}/favicon.ico`);
    expect(favicon.status).toBe(200);
    expect(favicon.headers.get("Cache-Control")).toBe("public, max-age=86400");
  });

  it("requires a paired owner credential", async () => {
    const response = await SELF.fetch(`${ORIGIN}/v1/owner/usage`);
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "PAIRING_REQUIRED" } });
  });

  it("rejects invalid set counts and durations before allocating a share", async () => {
    const owner = await pairOwner("Validation device");
    const response = await ownerFetch(owner, "/v1/owner/share-drafts", {
      method: "POST",
      body: JSON.stringify({
        title: "Too long",
        expiryDays: 30,
        sets: [{ clientSetId: "set-1", title: "Set 1", durationMs: 3 * 60 * 60 * 1000 + 1, audioMimeType: "audio/mp4" }],
      }),
    });
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "DURATION_LIMIT" } });
  });

  it("publishes only complete validated set audio, transcript, and comments", async () => {
    const owner = await pairOwner("Happy path device");
    const fixture = await createUploadedDraft(owner, {
      title: "Run club debrief",
      setTitle: "Introductions",
      metadata: validMetadata("Introductions"),
    });
    const token = "A".repeat(43);
    const published = await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: token }),
    });
    expect(published.status).toBe(201);
    const publication = await published.json<{ url: string; expiresAt: number; sizeBytes: number }>();
    expect(publication.url).toBe(`${ORIGIN}/s/${token}`);
    expect(publication.expiresAt).toBeGreaterThan(Date.now() + 29 * 24 * 60 * 60 * 1000);
    expect(publication.sizeBytes).toBeGreaterThan(0);

    const recovered = await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}`);
    expect(recovered.status).toBe(200);
    const recoveredDraft = await recovered.json<any>();
    expect(recoveredDraft.status).toBe("ACTIVE");
    expect(recoveredDraft.sets[0].objects.every((object: any) => object.status === "COMPLETE")).toBe(true);
    expect(recoveredDraft.sets[0].objects.every((object: any) => object.sizeBytes > 0 && object.sha256?.length === 64)).toBe(true);

    const repeatedPublish = await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: token }),
    });
    expect(repeatedPublish.status).toBe(200);
    await expect(repeatedPublish.json()).resolves.toMatchObject({ url: `${ORIGIN}/s/${token}` });

    const publicResponse = await SELF.fetch(`${ORIGIN}/v1/public/${token}`);
    expect(publicResponse.status).toBe(200);
    const snapshot = await publicResponse.json<any>();
    expect(snapshot.title).toBe("Run club debrief");
    expect(snapshot.sets).toHaveLength(1);
    expect(snapshot.sets[0].segments.map((segment: any) => segment.text)).toEqual([
      "Hello from the selected set.",
      "[redacted]",
    ]);
    expect(snapshot.sets[0].comments).toEqual([{ timestampMs: 800, text: "Ask a better follow-up here." }]);
    expect(JSON.stringify(snapshot)).not.toContain("recordingId");
    expect(JSON.stringify(snapshot)).not.toContain("documentUri");

    const setId = snapshot.sets[0].id as string;
    const range = await SELF.fetch(`${ORIGIN}/v1/public/${token}/sets/${setId}/audio`, {
      headers: { Range: "bytes=2-5" },
    });
    expect(range.status).toBe(206);
    expect(range.headers.get("Content-Range")).toBe("bytes 2-5/18");
    expect(new TextDecoder().decode(await range.arrayBuffer())).toBe("dio-");

    const viewer = await SELF.fetch(`${ORIGIN}/s/${token}`);
    expect(viewer.status).toBe(200);
    expect(viewer.headers.get("Content-Security-Policy")).toBe(
      "default-src 'none'; script-src 'self'; style-src 'self'; media-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    );
    expect(await viewer.text()).not.toContain(">Download<");

    const viewerScript = await SELF.fetch(`${ORIGIN}/assets/viewer.js`);
    expect(viewerScript.status).toBe(200);
    expect(viewerScript.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(viewerScript.headers.get("Content-Type")).toBe("text/javascript; charset=utf-8");

    const usage = await ownerFetch(owner, "/v1/owner/usage");
    const usageBody = await usage.json<any>();
    expect(usageBody.referenceBytes).toBe(10_000_000_000);
    expect(usageBody.currentBytes).toBe(publication.sizeBytes);
    expect(usageBody.activeLinks).toBe(1);
  });

  it("fails closed when uploaded metadata contains source-private fields", async () => {
    const owner = await pairOwner("Privacy validation device");
    const metadata = { ...validMetadata("Private field"), recordingId: "local-recording-id" };
    const fixture = await createUploadedDraft(owner, { title: "Private metadata", setTitle: "Private field", metadata });
    const response = await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: "B".repeat(43) }),
    });
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "PRIVATE_METADATA" } });
    const stored = await env.DB.prepare("SELECT status FROM shares WHERE id = ?").bind(fixture.draftId).first<{ status: string }>();
    expect(stored?.status).toBe("DRAFT");
  });

  it("supports optional PIN unlock and throttled private media sessions", async () => {
    const owner = await pairOwner("PIN device");
    const fixture = await createUploadedDraft(owner, {
      title: "PIN share",
      setTitle: "Protected",
      metadata: validMetadata("Protected"),
      pin: "123456",
    });
    const token = "C".repeat(43);
    await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: token }),
    });
    expect((await SELF.fetch(`${ORIGIN}/v1/public/${token}`)).status).toBe(401);
    const wrong = await SELF.fetch(`${ORIGIN}/v1/public/${token}/unlock`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7" },
      body: JSON.stringify({ pin: "000000" }),
    });
    expect(wrong.status).toBe(401);
    const unlock = await SELF.fetch(`${ORIGIN}/v1/public/${token}/unlock`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7" },
      body: JSON.stringify({ pin: "123456" }),
    });
    expect(unlock.status).toBe(200);
    const cookie = unlock.headers.get("Set-Cookie");
    expect(cookie).toContain("HttpOnly");
    expect((await SELF.fetch(`${ORIGIN}/v1/public/${token}`, { headers: { Cookie: cookie! } })).status).toBe(200);
  });

  it("revokes access before deleting cloud objects and treats repeat revoke idempotently", async () => {
    const owner = await pairOwner("Revocation device");
    const fixture = await createUploadedDraft(owner, {
      title: "Revoke me",
      setTitle: "Only set",
      metadata: validMetadata("Only set"),
    });
    const token = "D".repeat(43);
    await ownerFetch(owner, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: token }),
    });
    expect((await SELF.fetch(`${ORIGIN}/v1/public/${token}`)).status).toBe(200);
    const revoked = await ownerFetch(owner, `/v1/owner/shares/${fixture.draftId}`, { method: "DELETE" });
    expect(revoked.status).toBe(202);
    expect((await SELF.fetch(`${ORIGIN}/v1/public/${token}`)).status).toBe(404);
    expect((await ownerFetch(owner, `/v1/owner/shares/${fixture.draftId}`, { method: "DELETE" })).status).toBe(202);
  });

  it("lets another paired device manage account-wide shared links", async () => {
    const creator = await pairOwner("Creator device");
    const manager = await pairOwner("Manager device");
    const fixture = await createUploadedDraft(creator, {
      title: "Cross-device link",
      setTitle: "Set one",
      metadata: validMetadata("Set one"),
    });
    await ownerFetch(creator, `/v1/owner/share-drafts/${fixture.draftId}/publish`, {
      method: "POST",
      body: JSON.stringify({ publicToken: "E".repeat(43) }),
    });
    const list = await (await ownerFetch(manager, "/v1/owner/shares")).json<any>();
    expect(list.shares.some((share: any) => share.id === fixture.draftId)).toBe(true);
    expect((await ownerFetch(manager, `/v1/owner/shares/${fixture.draftId}`, { method: "DELETE" })).status).toBe(202);
  });
});

async function pairOwner(label: string): Promise<string> {
  const create = await SELF.fetch(`${ORIGIN}/v1/admin/pairing-codes`, {
    method: "POST",
    headers: { Authorization: `Bearer ${env.BOOTSTRAP_SECRET}` },
  });
  expect(create.status).toBe(201);
  const { code } = await create.json<{ code: string }>();
  const pair = await SELF.fetch(`${ORIGIN}/v1/pair`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, label }),
  });
  expect(pair.status).toBe(201);
  return (await pair.json<{ ownerToken: string }>()).ownerToken;
}

async function ownerFetch(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  if (init.body) headers.set("Content-Type", "application/json");
  return SELF.fetch(`${ORIGIN}${path}`, { ...init, headers });
}

async function createUploadedDraft(
  owner: string,
  options: { title: string; setTitle: string; metadata: unknown; pin?: string },
): Promise<{ draftId: string }> {
  const audio = new TextEncoder().encode("audio-selected-set");
  const metadata = new TextEncoder().encode(JSON.stringify(options.metadata));
  const draft = await ownerFetch(owner, "/v1/owner/share-drafts", {
    method: "POST",
    body: JSON.stringify({
      title: options.title,
      expiryDays: 30,
      pin: options.pin,
      sets: [{
        clientSetId: "local-set-1",
        title: options.setTitle,
        durationMs: 2_000,
        audioMimeType: "audio/mp4",
        expectedAudioBytes: audio.byteLength,
        expectedMetadataBytes: metadata.byteLength,
      }],
    }),
  });
  expect(draft.status).toBe(201);
  const body = await draft.json<any>();
  for (const object of body.sets[0].objects) {
    const bytes = object.kind === "AUDIO" ? audio : metadata;
    const partUrl = String(object.partUrl).replace("{partNumber}", "1");
    const part = await ownerFetch(owner, partUrl, { method: "PUT", body: bytes });
    expect(part.status, await part.clone().text()).toBe(200);
    const partBody = await part.json<{ partNumber: number; etag: string }>();
    expect(typeof partBody.etag, JSON.stringify(partBody)).toBe("string");
    expect(partBody.partNumber, JSON.stringify(partBody)).toBe(1);
    expect(partBody.etag.length, JSON.stringify(partBody)).toBeGreaterThanOrEqual(3);
    const complete = await ownerFetch(owner, object.completeUrl, {
      method: "POST",
      body: JSON.stringify({
        parts: [partBody],
        sizeBytes: bytes.byteLength,
        sha256: await sha256(bytes),
      }),
    });
    expect(complete.status, await complete.clone().text()).toBe(200);
  }
  return { draftId: body.draftId };
}

function validMetadata(title: string): Record<string, unknown> {
  return {
    schemaVersion: 1,
    title,
    durationMs: 2_000,
    segments: [
      { speaker: "Andy", startMs: 0, endMs: 900, text: "Hello from the selected set." },
      { speaker: "Guest", startMs: 900, endMs: 1_800, text: "[redacted]" },
    ],
    comments: [{ timestampMs: 800, text: "Ask a better follow-up here." }],
  };
}

async function sha256(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new Uint8Array(bytes).buffer));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}
