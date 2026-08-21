import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { chromium } from "playwright";

const baseUrl = (process.env.DEBRIEF_SHARE_BASE_URL ?? "").replace(/\/$/, "");
const bootstrapSecret = process.env.BOOTSTRAP_SECRET ?? "";

assert(baseUrl, "DEBRIEF_SHARE_BASE_URL is required");
assert(bootstrapSecret, "BOOTSTRAP_SECRET is required");

let browser;
let ownerToken;
let shareId;

try {
  const pairing = await api("/v1/admin/pairing-codes", {
    method: "POST",
    headers: { Authorization: `Bearer ${bootstrapSecret}` },
  });
  const paired = await api("/v1/pair", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: pairing.code, label: "Deployed viewer browser smoke" }),
  });
  ownerToken = paired.ownerToken;

  const audio = Buffer.from("debrief-browser-smoke-audio");
  const metadata = Buffer.from(JSON.stringify({
    schemaVersion: 1,
    title: "Browser smoke set",
    durationMs: 10_000,
    segments: [
      { speaker: "Andy", startMs: 0, endMs: 4_000, text: "The public share viewer loaded correctly." },
      { speaker: "Coach", startMs: 4_500, endMs: 9_000, text: "Transcript and comments are visible." },
    ],
    comments: [{ timestampMs: 5_000, text: "Browser end-to-end check." }],
  }));
  const draft = await ownerApi("/v1/owner/share-drafts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      title: "Debrief deployed viewer smoke",
      expiryDays: 30,
      sets: [{
        clientSetId: "browser-smoke",
        title: "Browser smoke set",
        durationMs: 10_000,
        audioMimeType: "audio/mp4",
        expectedAudioBytes: audio.byteLength,
        expectedMetadataBytes: metadata.byteLength,
      }],
    }),
  });
  shareId = draft.draftId;

  for (const object of draft.sets[0].objects) {
    const bytes = object.kind === "AUDIO" ? audio : metadata;
    const part = await ownerApi(object.partUrl.replace("{partNumber}", "1"), {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: bytes,
    });
    await ownerApi(object.completeUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        parts: [{ partNumber: 1, etag: part.etag }],
        sizeBytes: bytes.byteLength,
        sha256: createHash("sha256").update(bytes).digest("hex"),
      }),
    });
  }

  const publicToken = randomBytes(32).toString("base64url");
  const published = await ownerApi(`/v1/owner/share-drafts/${shareId}/publish`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ publicToken }),
  });

  browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
      : {}),
  });
  const context = await browser.newContext({
    viewport: { width: 412, height: 915 },
    userAgent: "Mozilla/5.0 (Linux; Android 15; CPH2655) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
  });
  const page = await context.newPage();
  const errors = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("requestfailed", (request) => errors.push(`requestfailed: ${request.url()} ${request.failure()?.errorText ?? ""}`));
  page.on("response", (response) => {
    if (response.status() >= 400) errors.push(`response: ${response.status()} ${response.url()}`);
  });

  const response = await page.goto(published.url, { waitUntil: "networkidle", timeout: 30_000 });
  assert.equal(response?.status(), 200);
  await page.getByRole("heading", { name: "Debrief deployed viewer smoke" }).waitFor({ state: "visible", timeout: 10_000 });
  assert.equal(await page.getByText("The public share viewer loaded correctly.").count(), 1);
  assert.equal(await page.getByText("Browser end-to-end check.").count(), 1);
  assert.equal(await page.getByText("Opening private share…").count(), 0);
  assert.equal(await page.locator("audio").count(), 1);
  assert.deepEqual(errors, []);
  console.log("Public viewer browser smoke passed at a OnePlus-sized Android viewport.");
} finally {
  await browser?.close();
  if (ownerToken && shareId) {
    await ownerApi(`/v1/owner/shares/${shareId}`, { method: "DELETE" }).catch(() => undefined);
  }
}

async function api(path, init = {}) {
  const response = await fetch(`${baseUrl}${path}`, init);
  const body = await response.json().catch(() => null);
  assert(response.ok, `${init.method ?? "GET"} ${path} failed (${response.status}): ${JSON.stringify(body)}`);
  return body;
}

async function ownerApi(path, init = {}) {
  return api(path, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${ownerToken}` },
  });
}
