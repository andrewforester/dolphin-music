import { Deps, errorResponse, jsonResponse, resolveAccess } from "../_shared/elevenlabs.ts";

export function handler(req: Request, deps: Deps): Promise<Response> {
  const access = resolveAccess(req, deps);
  if (access === null) return Promise.resolve(errorResponse(401, "unauthorized"));
  if (req.method !== "GET") return Promise.resolve(errorResponse(405, "method not allowed"));
  return Promise.resolve(jsonResponse({ access: access.kind }));
}
