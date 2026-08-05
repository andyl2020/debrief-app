# Cloud library setup

Your recordings, reachable from any device, stored in **your** Cloudflare account and encrypted so
Cloudflare cannot read them.

This is a web-app feature. It is **not** the Android app's Share Sets, which does something
different: publishing selected clips to somebody else on an expiring link. Both use the same Worker
and the same R2 bucket, and neither interferes with the other.

Nothing here works until the Worker is deployed. That is the single reason the Cloudflare feature
appears "broken" out of the box — `cloudflare/` is source code, not a running service.

---

## 0. Enable R2 (dashboard only)

R2 has to be switched on once, in the Cloudflare dashboard, before the CLI can see it. There is no
`wrangler` command for this — until you do it, `wrangler r2 bucket list` fails with
`Please enable R2 through the Cloudflare Dashboard [code: 10042]`.

Dashboard → **R2** → enable. The free tier still asks for a payment method on file.

## 1. Deploy the Worker

From `cloudflare/`, with Node 22+ and a Cloudflare account:

```bash
npm install
npx wrangler login
```

Create the two resources:

```bash
npx wrangler d1 create debrief-share
npx wrangler r2 bucket create debrief-share-private
```

Put the D1 id `wrangler d1 create` printed into `wrangler.jsonc` (`d1_databases[0].database_id`).

Set the three secrets to independent high-entropy values:

```bash
npx wrangler secret put BOOTSTRAP_SECRET
npx wrangler secret put TOKEN_PEPPER
npx wrangler secret put PIN_PEPPER
```

Apply migrations — `0002_library.sql` is the cloud library:

```bash
npx wrangler d1 migrations apply DB --remote
```

Set `ALLOWED_ORIGINS` in `wrangler.jsonc` to wherever you serve the web app. Without this the
browser blocks every call before it is sent:

```jsonc
"ALLOWED_ORIGINS": "http://localhost:5173,https://your-web-app.example"
```

Also set `PUBLIC_BASE_URL` to your own Worker origin — it ships pointing at the upstream author's
hostname, which would put somebody else's domain into your share links.

Check the config before deploying:

```bash
npm run preflight
```

That fails with a named fix for anything still unconfigured, rather than letting `wrangler` return a
raw API error about a database in an account you do not own.

Deploy and check:

```bash
npm run deploy          # runs preflight first
curl https://<your-worker>.workers.dev/health
```

`{"ok":true,...}` means the service is up.

---

## 2. Pair a device

The bootstrap secret stays on the deployment. Each device gets a short-lived one-use code instead:

```bash
curl -X POST https://<your-worker>.workers.dev/v1/admin/pairing-codes \
  -H "Authorization: Bearer <BOOTSTRAP_SECRET>"
```

In the web app: **Settings → Cloud library**, enter the Worker URL and the code, then **Pair this
device**. Codes last 15 minutes and work once.

---

## 3. Set the cloud passphrase

Still in **Settings → Cloud library**, enter a passphrase and **Unlock**.

The first device to do this creates the library key. Every other device needs the **same
passphrase** — it is the only thing you carry between them, and it is what makes "from anywhere"
work without transferring key files by hand.

> **If you lose the passphrase, the cloud copy is gone.** The key is stored only in wrapped form, so
> nobody — not Cloudflare, not this app — can recover it. Your local copies are unaffected.

---

## 4. Upload and read

- **Upload**: in the Library, each recording gets an **Upload** button once the cloud is unlocked.
  Nothing uploads unless you press it.
- **Read elsewhere**: pair the second device, unlock with the same passphrase, then **Sync now**.
  Transcripts, comments, chapters, redactions and speaker names come down immediately; audio streams
  on demand rather than downloading.
- **Remove**: **In cloud ✓** removes the cloud copy. The local file is untouched.

---

## What Cloudflare can and cannot see

Audio and transcripts are encrypted in your browser with AES-CTR before upload. D1 stores only
sizes, nonces, versions and object keys — no titles, durations, filenames or transcript text.

Two limits worth being precise about:

- **Confidentiality, not integrity.** AES-CTR means your provider cannot *read* your recordings. It
  does not detect tampering: someone who could write to your bucket could corrupt audio without it
  failing loudly. The Worker's size check catches truncation, not substitution.
- **The owner token is a device credential** and is stored in that browser's IndexedDB. Anyone with
  access to an unlocked browser profile can reach the cloud library from it. Use **Disconnect this
  device** on a machine you no longer control.

## Costs

R2's free tier is about 10 GB. A six-hour recording is roughly 350 MB, so around 25 long recordings
before it costs anything. Settings shows current usage against that reference. It is a reference,
not a limit the app enforces — Cloudflare bills on GB-month plus request counts.

## Troubleshooting

| Symptom | Cause |
|---|---|
| "Could not reach the cloud service" | Worker not deployed, wrong URL, or this origin is missing from `ALLOWED_ORIGINS` |
| "That pairing code was not accepted" | Code expired (15 min), already used, or the wrong Worker |
| "That passphrase does not match" | Wrong passphrase — the library key is unrecoverable without it |
| Upload starts then fails | Check R2 bucket exists and migrations were applied with `--remote` |
| Playback says it downloaded the whole file | No service worker — needs HTTPS or localhost; over plain HTTP the browser disables it |

**Settings → Diagnostics** reports secure context, service worker status and storage support, and
has a copy button for bug reports.
