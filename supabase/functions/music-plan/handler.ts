import { API_BASE, Deps, errorResponse, misconfigured, MODEL_ID, unauthorized, upstreamError, upstreamUnreachable } from "../_shared/elevenlabs.ts";

export async function handler(req: Request, deps: Deps): Promise<Response> {
  const denied = unauthorized(req, deps);
  if (denied) return denied;
  if (req.method !== "POST") return errorResponse(405, "method not allowed");
  const apiKey = deps.env("ELEVENLABS_API_KEY");
  if (!apiKey) return misconfigured();

  let input: { prompt?: unknown; music_length_ms?: unknown };
  try {
    input = await req.json();
  } catch {
    return errorResponse(400, "invalid JSON body");
  }
  if (typeof input.prompt !== "string" || typeof input.music_length_ms !== "number") {
    return errorResponse(400, "prompt (string) and music_length_ms (number) are required");
  }

  let upstream: Response;
  try {
    upstream = await deps.fetch(`${API_BASE}/v1/music/plan`, {
      method: "POST",
      headers: { "xi-api-key": apiKey, "content-type": "application/json" },
      body: JSON.stringify({ prompt: input.prompt, music_length_ms: input.music_length_ms, model_id: MODEL_ID }),
    });
  } catch (err) {
    return upstreamUnreachable(err);
  }
  if (!upstream.ok) return upstreamError(upstream, undefined, apiKey);
  return new Response(upstream.body, { status: 200, headers: { "content-type": "application/json" } });
}

