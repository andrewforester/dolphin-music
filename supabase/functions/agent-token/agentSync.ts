import { API_BASE, type Deps } from "../_shared/elevenlabs.ts";
import { AGENT } from "../_shared/agent/agent.ts";
import { PROMPT } from "../_shared/agent/prompt.ts";
import { TOOLS } from "../_shared/agent/tools.ts";

// deno-lint-ignore no-explicit-any
type Json = Record<string, any>;

export type SyncOutcome = "unchanged" | "patched" | "failed";
export interface SyncLog {
  evt: "agent_sync";
  outcome: SyncOutcome;
  changed: string[];
  error: string | null;
}
/** Resolves (never rejects) once the agent matches the repo's config in `_shared/agent/`, or with `failed`. */
export type AgentSync = () => Promise<SyncOutcome>;

export interface WantedAgent {
  /** The canonical agent config (`_shared/agent/agent.ts`, prompt included). */
  agent: typeof AGENT;
  prompt: string;
  /** The `tool_config` of each client tool we own. */
  tools: (Json & { name: string })[];
}

class SyncError extends Error {}

/** The config comes from imported TS modules (nothing read at runtime, so the bundle is self-contained). */
export function loadWantedAgent(): WantedAgent {
  const tools = TOOLS as (Json & { name: string })[];
  if (!PROMPT.trim()) throw new SyncError("config_invalid: empty prompt");
  if (AGENT.conversation_config.agent.prompt.prompt !== PROMPT) throw new SyncError("config_invalid: agent prompt mismatch");
  if (tools.length === 0 || tools.some((t) => !t.name)) throw new SyncError("config_invalid: tools");
  return { agent: AGENT, prompt: PROMPT, tools };
}

const MAX_TOOL_PAGES = 5;
const PAGE_SIZE = "100";
/** ElevenLabs' defaults for tool fields it may leave out of a stored config (= ours). */
const TOOL_DEFAULTS: Json = { execution_mode: "immediate", pre_tool_speech: "auto", interruption_mode: "allow" };

const isRecord = (v: unknown): v is Json => typeof v === "object" && v !== null && !Array.isArray(v);

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (!isRecord(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().map((k) => [k, canonical(value[k])]));
}
const same = (a: unknown, b: unknown) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));

function projectParam(p: Json): Json {
  return { type: p.type, description: p.description, enum: p.enum ?? undefined, items: isRecord(p.items) ? { type: p.items.type } : undefined };
}

/** The stored tool already says what we want: compares only the fields we set (ElevenLabs defaults ignored). */
export function sameTool(stored: Json, wanted: Json): boolean {
  const projected: Json = {};
  const target: Json = {};
  for (const key of Object.keys(wanted)) {
    if (key === "parameters") {
      const project = (p: Json) => ({
        type: p.type,
        required: [...(p.required ?? [])].sort(),
        properties: Object.fromEntries(Object.entries(p.properties ?? {}).map(([n, v]) => [n, projectParam(v as Json)])),
      });
      projected[key] = project(stored[key] ?? {});
      target[key] = project(wanted[key]);
    } else {
      projected[key] = stored[key] ?? TOOL_DEFAULTS[key];
      target[key] = wanted[key];
    }
  }
  return same(projected, target);
}

/**
 * The field paths a 422 validation error names (`detail[].loc`), e.g. " at body.tool_config.parameters";
 * only the paths, never messages or values, so nothing from the request is echoed into the logs.
 */
async function invalidFields(res: Response): Promise<string> {
  const body = await res.json().catch(() => undefined);
  const detail = isRecord(body) && Array.isArray(body.detail) ? body.detail : [];
  const paths = detail
    .map((d) => (isRecord(d) && Array.isArray(d.loc) ? d.loc.filter((p) => typeof p === "string" || typeof p === "number").join(".") : ""))
    .filter((p) => p !== "")
    .slice(0, 3);
  return paths.length > 0 ? ` at ${paths.join(", ")}` : "";
}

class Api {
  constructor(private readonly deps: Deps, private readonly key: string) {}

  async call(op: string, method: string, path: string, body?: Json): Promise<Json> {
    let res: Response;
    try {
      res = await this.deps.fetch(`${API_BASE}${path}`, {
        method,
        headers: { "xi-api-key": this.key, ...(body ? { "content-type": "application/json" } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch {
      throw new SyncError(`${op} network`);
    }
    if (!res.ok) {
      const fields = res.status === 422 ? await invalidFields(res) : "";
      if (res.status !== 422) await res.body?.cancel();
      throw new SyncError(`${op} http ${res.status}${fields}`);
    }
    const json = await res.json().catch(() => undefined);
    if (!isRecord(json)) throw new SyncError(`${op} bad_response`);
    return json;
  }

  async listTools(): Promise<{ id: string; config: Json }[]> {
    const tools: { id: string; config: Json }[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < MAX_TOOL_PAGES; page++) {
      const q = new URLSearchParams({ page_size: PAGE_SIZE, ...(cursor ? { cursor } : {}) });
      const body = await this.call("list_tools", "GET", `/v1/convai/tools?${q}`);
      for (const t of Array.isArray(body.tools) ? body.tools : []) {
        if (isRecord(t) && typeof t.id === "string" && isRecord(t.tool_config)) tools.push({ id: t.id, config: t.tool_config });
      }
      if (body.has_more !== true || typeof body.next_cursor !== "string") return tools;
      cursor = body.next_cursor;
    }
    throw new SyncError(`more than ${MAX_TOOL_PAGES} pages of tools`);
  }
}

/** Sets `value` at the dotted `path` inside `root`, creating objects on the way. */
function setPath(root: Json, path: string[], value: unknown) {
  let node = root;
  for (const key of path.slice(0, -1)) node = node[key] = isRecord(node[key]) ? node[key] : {};
  node[path.at(-1)!] = value;
}

const unset = (v: unknown) => v === undefined || v === null || v === "";

const INITIAL_ONLY_TTS = new Set(["voice_id"]);

const sameSet = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && [...a].sort().join("\n") === [...b].sort().join("\n");

/**
 * Brings the agent to `wanted`: creates missing tools, patches changed ones, then patches only the
 * owned agent fields that differ. Foreign tools stay attached. Returns what changed.
 */
export async function syncAgent(api: Api, agentId: string, wanted: WantedAgent): Promise<string[]> {
  const [agent, allTools] = await Promise.all([
    api.call("get_agent", "GET", `/v1/convai/agents/${encodeURIComponent(agentId)}`),
    api.listTools(),
  ]);
  const tools = allTools.filter((t) => t.config.type === "client");
  const changed: string[] = [];
  const attached: string[] = Array.isArray(agent.conversation_config?.agent?.prompt?.tool_ids)
    ? agent.conversation_config.agent.prompt.tool_ids
    : [];
  const ourNames = new Set(wanted.tools.map((t) => t.name));
  const ourIds: string[] = [];
  for (const tool of wanted.tools) {
    const candidates = tools.filter((t) => t.config.name === tool.name);
    const existing = candidates.find((t) => attached.includes(t.id)) ?? candidates[0];
    if (!existing) {
      const created = await api.call("create_tool", "POST", "/v1/convai/tools", { tool_config: tool });
      if (typeof created.id !== "string") throw new SyncError("create_tool bad_response");
      ourIds.push(created.id);
      changed.push(`tool:${tool.name}:created`);
      continue;
    }
    if (!sameTool(existing.config, tool)) {
      await api.call("patch_tool", "PATCH", `/v1/convai/tools/${encodeURIComponent(existing.id)}`, { tool_config: tool });
      changed.push(`tool:${tool.name}`);
    }
    ourIds.push(existing.id);
  }

  const nameById = new Map(tools.map((t) => [t.id, t.config.name]));
  const toolIds = [...attached.filter((id) => !ourNames.has(nameById.get(id) ?? "")), ...ourIds];

  const cc: Json = agent.conversation_config ?? {};
  const ps: Json = agent.platform_settings ?? {};
  const want = wanted.agent.conversation_config;
  const patch: Json = {};
  const set = (name: string, path: string[], value: unknown) => {
    setPath(patch, path, value);
    changed.push(name);
  };
  const A = ["conversation_config", "agent"];
  if (cc.agent?.prompt?.prompt !== wanted.prompt) set("prompt", [...A, "prompt", "prompt"], wanted.prompt);
  if (unset(cc.agent?.prompt?.llm)) set("llm", [...A, "prompt", "llm"], want.agent.prompt.llm);
  if (!sameSet(attached, toolIds)) set("tool_ids", [...A, "prompt", "tool_ids"], toolIds);
  const skip = want.agent.prompt.built_in_tools.skip_turn;
  const storedSkip = cc.agent?.prompt?.built_in_tools?.skip_turn;
  // only the fields we set; ElevenLabs may add defaults inside `params`
  const skipDrift = !isRecord(storedSkip) || storedSkip.description !== skip.description ||
    storedSkip.response_timeout_secs !== skip.response_timeout_secs ||
    storedSkip.params?.system_tool_type !== skip.params.system_tool_type;
  if (skipDrift) {
    set("skip_turn", [...A, "prompt", "built_in_tools", "skip_turn"], skip);
  }
  if (cc.agent?.first_message !== want.agent.first_message) set("first_message", [...A, "first_message"], want.agent.first_message);
  const wantedVars: Json = want.agent.dynamic_variables.dynamic_variable_placeholders;
  const storedVars: Json = cc.agent?.dynamic_variables?.dynamic_variable_placeholders ?? {};
  if (Object.keys(wantedVars).some((k) => storedVars[k] !== wantedVars[k])) {
    set("dynamic_variables", [...A, "dynamic_variables", "dynamic_variable_placeholders"], { ...storedVars, ...wantedVars });
  }
  for (const field of ["turn_timeout", "silence_end_call_timeout", "turn_eagerness"] as const) {
    if (cc.turn?.[field] !== want.turn[field]) set(field, ["conversation_config", "turn", field], want.turn[field]);
  }
  if (cc.agent?.language !== want.agent.language) set("language", [...A, "language"], want.agent.language);
  const storedEvents: string[] = Array.isArray(cc.conversation?.client_events) ? cc.conversation.client_events : [];
  if (!sameSet(storedEvents, want.conversation.client_events)) {
    set("client_events", ["conversation_config", "conversation", "client_events"], want.conversation.client_events);
  }
  for (const [field, value] of Object.entries(want.tts)) {
    const drift = INITIAL_ONLY_TTS.has(field) ? unset(cc.tts?.[field]) : cc.tts?.[field] !== value;
    if (drift) set(`tts.${field}`, ["conversation_config", "tts", field], value);
  }
  if (ps.auth?.enable_auth !== true) set("auth", ["platform_settings", "auth", "enable_auth"], true);
  if (ps.overrides?.conversation_config_override?.agent?.first_message !== true) {
    set("override:first_message", ["platform_settings", "overrides", "conversation_config_override", "agent", "first_message"], true);
  }
  if (Object.keys(patch).length > 0) {
    await api.call("patch_agent", "PATCH", `/v1/convai/agents/${encodeURIComponent(agentId)}`, patch);
  }
  return changed;
}

export interface SyncOptions {
  wanted?: () => WantedAgent | Promise<WantedAgent>;
  log?: (line: SyncLog) => void;
}

/**
 * The once-per-instance sync: a memoized promise, cleared on failure so the next request retries.
 * Logs one `agent_sync` line per attempt (no keys, tokens or prompts). A failure never blocks the
 * token: the call runs on the agent's previous config. `AGENT_SYNC=off` skips it.
 */
export function createAgentSync(deps: Deps, options: SyncOptions = {}): AgentSync {
  const wanted = options.wanted ?? loadWantedAgent;
  const log = options.log ?? ((line: SyncLog) => console.log(JSON.stringify(line)));
  let running: Promise<SyncOutcome> | undefined;
  return () => {
    if (deps.env("AGENT_SYNC") === "off") return Promise.resolve("unchanged");
    running ??= (async (): Promise<SyncOutcome> => {
      await Promise.resolve(); // so a clear in `catch` always lands after the assignment
      try {
        const agentId = deps.env("AGENT_ID");
        const key = deps.env("ELEVENLABS_AGENTS_KEY");
        if (!agentId || !key) throw new SyncError("not configured");
        let config: WantedAgent;
        try {
          config = await wanted();
        } catch (err) {
          throw err instanceof SyncError ? err : new SyncError(`config_invalid: ${err instanceof Error ? err.name : "unknown"}`);
        }
        const changed = await syncAgent(new Api(deps, key), agentId, config);
        const outcome = changed.length > 0 ? "patched" : "unchanged";
        log({ evt: "agent_sync", outcome, changed, error: null });
        return outcome;
      } catch (err) {
        running = undefined;
        log({ evt: "agent_sync", outcome: "failed", changed: [], error: err instanceof SyncError ? err.message : "unexpected error" });
        return "failed";
      }
    })();
    return running;
  };
}
