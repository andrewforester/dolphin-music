import {
  API_BASE,
  Deps,
  errorResponse,
  jsonResponse,
  misconfigured,
  quotaExceededResponse,
  unauthorized,
  upstreamError,
  upstreamUnreachable,
} from "../_shared/elevenlabs.ts";

const CONVERSATION_ID = /^conv_[A-Za-z0-9]+$/;
const READS = 3;
const READ_INTERVAL_MS = 700;

interface Conversation {
  agent_id?: unknown;
  status?: unknown;
  metadata?: { error?: { reason?: unknown } };
}

export async function handler(
  req: Request,
  deps: Deps & { sleep?: (ms: number) => Promise<void> },
): Promise<Response> {
  const denied = unauthorized(req, deps);
  if (denied) return denied;
  if (req.method !== "GET") return errorResponse(405, "method not allowed");

  const id = new URL(req.url).searchParams.get("id") ?? "";
  if (!CONVERSATION_ID.test(id)) return errorResponse(400, "invalid conversation id");

  const agentId = deps.env("AGENT_ID");
  const agentsKey = deps.env("ELEVENLABS_AGENTS_KEY");
  if (!agentId || !agentsKey) return misconfigured();

  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  let conversation: Conversation = {};
  for (let read = 1; read <= READS; read++) {
    let upstream: Response;
    try {
      upstream = await deps.fetch(`${API_BASE}/v1/convai/conversations/${id}`, {
        headers: { "xi-api-key": agentsKey },
      });
    } catch (err) {
      return upstreamUnreachable(err);
    }
    if (!upstream.ok) return upstreamError(upstream, undefined, agentsKey);
    conversation = (await upstream.json().catch(() => ({}))) as Conversation;
    if (conversation.agent_id !== agentId) return errorResponse(404, "conversation not found");
    if (failureReason(conversation) !== null || read === READS) break;
    await sleep(READ_INTERVAL_MS);
  }

  const reason = failureReason(conversation);
  if (reason?.startsWith("[quota_exceeded]")) return quotaExceededResponse(null, null);
  return jsonResponse({
    status: typeof conversation.status === "string" ? conversation.status : null,
    reason: reason?.replace(/^\[[^\]]*\]\s*/, "") ?? null,
  });
}

function failureReason(conversation: Conversation): string | null {
  const reason = conversation.metadata?.error?.reason;
  return typeof reason === "string" && reason.length > 0 ? reason : null;
}
