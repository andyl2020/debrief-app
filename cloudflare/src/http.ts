const BASE_HEADERS: Record<string, string> = {
  "Cache-Control": "no-store",
  "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

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
    headers: {
      ...BASE_HEADERS,
      "Content-Type": "application/json; charset=utf-8",
      ...Object.fromEntries(new Headers(headers)),
    },
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
    headers: {
      ...BASE_HEADERS,
      "Content-Type": contentType,
      ...Object.fromEntries(new Headers(headers)),
    },
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

export function noLogTokenPath(pathname: string): string {
  return pathname
    .replace(/\/s\/[^/]+/, "/s/[secret]")
    .replace(/\/v1\/public\/[^/]+/, "/v1/public/[secret]");
}
