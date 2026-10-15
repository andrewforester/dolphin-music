import { API_BASE, Deps, errorResponse, jsonResponse, misconfigured, unauthorized, upstreamError, upstreamUnreachable } from "../_shared/elevenlabs.ts";

import type { AgentSync } from "./agentSync.ts";

export async function handler(req: Request, deps: Deps & { agentSync?: AgentSync }): Promise<Response> {
  const denied = unauthorized(req, deps);
  if (denied) return denied;
  if (req.method !== "GET") return errorResponse(405, "method not allowed");

  const agentId = deps.env("AGENT_ID");
  if (!agentId) return misconfigured();

  const agentsKey = deps.env("ELEVENLABS_AGENTS_KEY");
  if (!agentsKey) return misconfigured();

  await deps.agentSync?.(); // never rejects; a failed sync must not block the token

  let upstream: Response;
  try {
    upstream = await deps.fetch(
      `${API_BASE}/v1/convai/conversation/token?agent_id=${encodeURIComponent(agentId)}`,
      { headers: { "xi-api-key": agentsKey } },
    );
  } catch (err) {
    return upstreamUnreachable(err);
  }
  if (!upstream.ok) return upstreamError(upstream, undefined, agentsKey);

  const body = await upstream.json().catch(() => undefined);
  const token = (body as { token?: unknown } | undefined)?.token;
  if (typeof token !== "string") return errorResponse(502, "upstream response invalid");
  return jsonResponse({ token });
}
