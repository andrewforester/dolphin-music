import { matchAccessCode } from "./auth.ts";

export const API_BASE = "https://api.elevenlabs.io";
export const MODEL_ID = "music_v2_5";
export const DEFAULT_FORMATS = ["pcm_44100", "pcm_48000", "pcm_24000", "pcm_22050", "mp3_44100_128"];

export interface Deps {
  fetch: typeof globalThis.fetch;
  env: (key: string) => string | undefined;
}

export const defaultDeps: Deps = {
  fetch: (input, init) => fetch(input, init),
  env: (key) => Deno.env.get(key),
};

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export function errorResponse(status: number, error: string): Response {
  return jsonResponse({ error, status }, status);
}

export type Access = { kind: "code"; label: string };

export function resolveAccess(req: Request, deps: Deps): Access | null {
  const label = matchAccessCode(req.headers.get("x-access-code"), deps.env("ACCESS_CODES"));
  return label === null ? null : { kind: "code", label };
}

export function unauthorized(req: Request, deps: Deps): Response | null {
  const access = resolveAccess(req, deps);
  if (access === null) return errorResponse(401, "unauthorized");
  console.log(JSON.stringify({ evt: "access", code: access.label }));
  return null;
}

export function parseFormats(csv: string | undefined): string[] {
  const list = (csv ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return list.length ? list : DEFAULT_FORMATS;
}

export function pcmChannels(raw: string | undefined): number {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : 2;
}

/** A 4xx that says the requested output format is not usable (so the next one should be tried). */
export function isFormatError(status: number, body: string): boolean {
  return (status === 403 || status === 422) && /format/i.test(body);
}

const LOG_LIMIT = 500;

/** 500 for a handler whose required secret is unset; never names the secret. */
export function misconfigured(): Response {
  return errorResponse(500, "server misconfigured");
}

/** 502 for an upstream call that threw (network, DNS, ...). The cause is logged, not returned. */
export function upstreamUnreachable(err: unknown): Response {
  console.error("upstream request failed:", String(err instanceof Error ? err.message : err).slice(0, LOG_LIMIT));
  return errorResponse(502, "upstream request failed");
}

function genericMessage(status: number): string {
  if (status === 401 || status === 403) return "upstream rejected the request";
  if (status === 429) return "upstream rate limit reached";
  return status >= 500 ? "upstream service error" : "upstream request failed";
}

/**
 * An upstream 400 with `detail.status == "bad_prompt"` becomes a 422 the app can act on:
 * only the reason and the suggested prompt are forwarded. Null for any other (or malformed) body.
 */
function badPrompt(body: string): Response | null {
  try {
    const detail = JSON.parse(body)?.detail;
    if (detail?.status !== "bad_prompt") return null;
    const str = (v: unknown) => (typeof v === "string" ? v : null);
    return jsonResponse({
      error: "bad_prompt",
      status: 422,
      reason: str(detail.data?.reason),
      prompt_suggestion: str(detail.data?.prompt_suggestion),
    }, 422);
  } catch {
    return null;
  }
}

/**
 * An upstream error with `detail.status == "quota_exceeded"` (ElevenLabs answers 401) becomes a 402 the app can
 * show: only the credits left and needed are forwarded, null where the message does not say. Null for any other body.
 */
function quotaExceeded(body: string): Response | null {
  try {
    const detail = JSON.parse(body)?.detail;
    if (detail?.status !== "quota_exceeded") return null;
    const message = typeof detail.message === "string" ? detail.message : "";
    const credits = /(\d+) credits remaining, while (\d+) credits are required/.exec(message);
    return quotaExceededResponse(credits ? Number(credits[1]) : null, credits ? Number(credits[2]) : null);
  } catch {
    return null;
  }
}

export function quotaExceededResponse(remaining: number | null, required: number | null): Response {
  return jsonResponse({ error: "quota_exceeded", status: 402, credits_remaining: remaining, credits_required: required }, 402);
}

/**
 * Turns a failed ElevenLabs response into `{error, status}` with the same status and a generic message (a rejected
 * prompt and an exceeded quota excepted).
 * The upstream text is only logged (truncated). Pass [text] when the body was already read.
 */
export async function upstreamError(res: Response, text?: string, apiKey?: string): Promise<Response> {
  let body = text ?? await res.text().catch(() => "");
  if (apiKey) body = body.split(apiKey).join("[redacted]"); // in case upstream echoes the key
  console.error(`upstream error ${res.status}:`, body.slice(0, LOG_LIMIT));
  if (res.status === 400) {
    const rejected = badPrompt(body);
    if (rejected) return rejected;
  }
  return quotaExceeded(body) ?? errorResponse(res.status, genericMessage(res.status));
}

let lastGoodFormat: string | undefined;
export function resetFormatCache() {
  lastGoodFormat = undefined;
}

/** Order the chain so the remembered working format is tried first. */
function orderedFormats(formats: string[]): string[] {
  return lastGoodFormat && formats.includes(lastGoodFormat)
    ? [lastGoodFormat, ...formats.filter((f) => f !== lastGoodFormat)]
    : formats;
}

/**
 * POST /v1/music with the first output format the account accepts.
 * Only format-related 403/422 errors advance the chain; anything else is returned as is.
 */
export async function composeMusic(compositionPlan: unknown, deps: Deps): Promise<Response> {
  const apiKey = deps.env("ELEVENLABS_API_KEY");
  if (!apiKey) return misconfigured();
  const formats = orderedFormats(parseFormats(deps.env("PREFERRED_FORMATS")));
  const channels = pcmChannels(deps.env("PCM_CHANNELS"));
  const body = JSON.stringify({ composition_plan: compositionPlan, model_id: MODEL_ID, store_for_inpainting: true });

  let failure: Response | undefined;
  for (const format of formats) {
    let upstream: Response;
    try {
      upstream = await deps.fetch(`${API_BASE}/v1/music?output_format=${encodeURIComponent(format)}`, {
        method: "POST",
        headers: { "xi-api-key": apiKey, "content-type": "application/json" },
        body,
      });
    } catch (err) {
      return upstreamUnreachable(err);
    }
    if (upstream.ok) {
      lastGoodFormat = format;
      const headers = new Headers({
        "content-type": upstream.headers.get("content-type") ?? "application/octet-stream",
        "x-audio-format": format,
        "x-audio-channels": String(format.startsWith("pcm_") ? channels : 2),
      });
      const songId = upstream.headers.get("song-id");
      if (songId) headers.set("song-id", songId);
      return new Response(upstream.body, { status: 200, headers }); // streamed, not buffered
    }
    const text = await upstream.text().catch(() => "");
    failure = await upstreamError(upstream, text, apiKey);
    if (!isFormatError(upstream.status, text)) return failure;
  }
  return failure ?? errorResponse(500, "no output formats configured");
}
