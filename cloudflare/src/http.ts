const BASE_HEADERS: Record<string, string> = {
  "Cache-Control": "no-store",
  "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

function responseHeaders(contentType: string, overrides: HeadersInit): Headers {
  const headers = new Headers(BASE_HEADERS);
  headers.set("Content-Type", contentType);
  new Headers(overrides).forEach((value, name) => headers.set(name, value));
  return headers;
}

export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function json(data: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: responseHeaders("application/json; charset=utf-8", headers),
  });
}

export function textResponse(
  body: string,
  status = 200,
  contentType = "text/plain; charset=utf-8",
  headers: HeadersInit = {},
): Response {
  return new Response(body, {
    status,
    headers: responseHeaders(contentType, headers),
  });
}

export function unavailable(): Response {
  return textResponse(
    "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"robots\" content=\"noindex,nofollow\"><title>Share unavailable</title><body><main><h1>Share unavailable</h1><p>This Debrief share is invalid, expired, or has been revoked.</p></main></body></html>",
    404,
    "text/html; charset=utf-8",
    { "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'" },
  );
}

export async function readJson<T>(request: Request, maximumBytes = 2_000_000): Promise<T> {
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (Number.isFinite(length) && length > maximumBytes) {
    throw new HttpError(413, "REQUEST_TOO_LARGE", "The request is too large.");
  }
  const raw = await request.text();
  if (new TextEncoder().encode(raw).byteLength > maximumBytes) {
    throw new HttpError(413, "REQUEST_TOO_LARGE", "The request is too large.");
  }
  try {
    return JSON.parse(raw) as T;
  } catch {
    throw new HttpError(400, "INVALID_JSON", "The request body must be valid JSON.");
  }
}

export function errorResponse(error: unknown): Response {
  if (error instanceof HttpError) {
    return json({ error: { code: error.code, message: error.message } }, error.status);
  }
  return json(
    { error: { code: "INTERNAL_ERROR", message: "The share service could not complete the request." } },
    500,
  );
}

export function bearer(request: Request): string | null {
  const value = request.headers.get("Authorization");
  if (!value?.startsWith("Bearer ")) return null;
  const token = value.slice(7).trim();
  return token || null;
}

/**
 * CORS, for the owner API only.
 *
 * The Android app is a native client and never needed this; the web app is a
 * browser on a different origin, so without it every owner call is blocked
 * before it is sent. The public share viewer deliberately does NOT get these
 * headers - it is same-origin HTML served by this Worker, and opening it up
 * would let any site read a share token holder's content.
 *
 * `Access-Control-Expose-Headers` matters more than it looks: without
 * Content-Range and Accept-Ranges the client cannot see the response to a
 * range request, which is exactly what encrypted seeking depends on.
 */
export function allowedOrigin(request: Request, env: { ALLOWED_ORIGINS?: string }): string | null {
  const origin = request.headers.get("Origin");
  if (!origin) return null;
  const allowed = (env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  if (allowed.includes("*")) return origin;
  return allowed.includes(origin) ? origin : null;
}

export function corsHeaders(origin: string): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, HEAD, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "authorization, content-type, range",
    "Access-Control-Expose-Headers": "content-range, accept-ranges, content-length, etag",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

export function preflight(origin: string): Response {
  return new Response(null, { status: 204, headers: corsHeaders(origin) });
}

/** Copies CORS headers onto an already-built response. */
export function withCors(response: Response, origin: string | null): Response {
  if (!origin) return response;
  const headers = new Headers(response.headers);
  for (const [name, value] of Object.entries(corsHeaders(origin))) headers.set(name, value);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

export function noLogTokenPath(pathname: string): string {
  return pathname
    .replace(/\/s\/[^/]+/, "/s/[secret]")
    .replace(/\/v1\/public\/[^/]+/, "/v1/public/[secret]");
}
