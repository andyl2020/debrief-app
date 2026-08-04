# Debrief Share Service

Cloudflare Worker/D1/R2 backend and read-only web viewer for the approved Share Sets PRD addendum.

## Security model

- The R2 bucket is private.
- Android uploads only derived per-set audio and a filtered per-set metadata package.
- Raw recordings and Cloudflare credentials never enter the APK or public payload.
- Drafts remain private until every object is complete and metadata passes server validation.
- Public tokens and owner credentials are stored as keyed hashes.
- Optional PINs use PBKDF2 plus a server-only pepper and online rate limiting.
- Expiry and revoke checks run before HTML, JSON, and every audio range response.
- The viewer uses same-origin assets, no third-party scripts/fonts/analytics, `noindex`, CSP, and no Download action.

## Local verification

Node.js 22 or newer is required.

```text
npm install
npm run check
npx wrangler deploy --dry-run
```

Tests use Cloudflare's Workers Vitest runtime with local D1 and R2 implementations. `.dev.vars` is ignored; copy `.dev.vars.example` only for interactive local development.

## First deployment

1. Activate Cloudflare R2 and authenticate Wrangler with the intended account.
2. Create a D1 database named `debrief-share` and an R2 Standard bucket named `debrief-share-private`, or use Wrangler's supported resource-provisioning flow.
3. Replace the placeholder D1 database ID in `wrangler.jsonc` with the created database ID.
4. Change `PUBLIC_BASE_URL` to the final HTTPS Worker/custom-domain origin.
5. Generate three independent high-entropy secrets and configure them with `wrangler secret put`:

   - `BOOTSTRAP_SECRET`
   - `TOKEN_PEPPER`
   - `PIN_PEPPER`

6. Apply D1 migrations remotely:

```text
npx wrangler d1 migrations apply DB --remote
```

7. Deploy and confirm `GET /health`.
8. Set an R2 lifecycle backstop for abandoned `shares/` staging content. Application cleanup remains authoritative because active expiry varies by share.

Never commit `.dev.vars`, real resource credentials, API tokens, pairing codes, or owner credentials.

## Pair the Android owner device

The bootstrap secret is deployment-only. Use it to request a 15-minute one-use code:

```text
POST /v1/admin/pairing-codes
Authorization: Bearer <BOOTSTRAP_SECRET>
```

Enter the returned code in Debrief Settings. Android exchanges it through `POST /v1/pair`, stores the returned owner credential in Android Keystore, and never retains the bootstrap secret.

## Limits

- 10 completed sets per share.
- 3 hours of combined audio per share.
- 10 MiB upload parts; all non-final multipart parts must meet R2's 5 MiB minimum.
- 1 GB maximum derived audio object.
- 2 MB maximum per-set metadata package, matching D1/Worker safety constraints while metadata itself remains in R2.
- Expiry options: 30, 60, or 90 days.
- Storage meter reference defaults to 10,000,000,000 bytes and remains server-configurable.

Cloudflare's free storage allowance is currently 10 GB-month, calculated from average daily peak storage. The app's prominent bar is current bytes against the configured 10 GB reference and must retain the monthly-averaging explanation.
