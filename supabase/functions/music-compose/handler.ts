import { composeMusic, Deps, errorResponse, misconfigured, unauthorized } from "../_shared/elevenlabs.ts";

export async function handler(req: Request, deps: Deps): Promise<Response> {
  const denied = unauthorized(req, deps);
  if (denied) return denied;
  if (req.method !== "POST") return errorResponse(405, "method not allowed");
  if (!deps.env("ELEVENLABS_API_KEY")) return misconfigured();

  let input: { composition_plan?: unknown };
  try {
    input = await req.json();
  } catch {
    return errorResponse(400, "invalid JSON body");
  }
  if (!input.composition_plan || typeof input.composition_plan !== "object") {
    return errorResponse(400, "composition_plan (object) is required");
  }
  return composeMusic(input.composition_plan, deps);
}

