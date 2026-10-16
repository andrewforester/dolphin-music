import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { createAgentSync, loadWantedAgent, type SyncLog } from "./agentSync.ts";
import { handler } from "./handler.ts";

const KEY = "sk-agents-secret-456";
const env: Record<string, string> = {
  ELEVENLABS_AGENTS_KEY: KEY,
  ACCESS_CODES: "test:s3cret",
  AGENT_ID: "agent_123",
};

// deno-lint-ignore no-explicit-any
type Json = Record<string, any>;
interface Call { method: string; path: string; body?: Json; keyOk: boolean }

/** A stateful fake of the ElevenLabs Agents API: stored agent and tools, every call recorded. */
function fakeApi(opts: { failWith?: number; toolPages?: number } = {}) {
  const wanted = loadWantedAgent();
  const agent: Json = {
    conversation_config: {
      agent: {
        first_message: wanted.agent.conversation_config.agent.first_message,
        language: "en",
        dynamic_variables: { dynamic_variable_placeholders: { ...wanted.agent.conversation_config.agent.dynamic_variables.dynamic_variable_placeholders } },
        prompt: {
          prompt: wanted.prompt,
          llm: wanted.agent.conversation_config.agent.prompt.llm,
          tool_ids: [] as string[],
          built_in_tools: { skip_turn: { ...wanted.agent.conversation_config.agent.prompt.built_in_tools.skip_turn, extra_default: 1 } },
        },
      },
      turn: { ...wanted.agent.conversation_config.turn, soft_timeout_config: { timeout_seconds: -1 } },
      conversation: { client_events: [...wanted.agent.conversation_config.conversation.client_events].reverse(), max_duration_seconds: 600 },
      tts: { ...wanted.agent.conversation_config.tts, optimize_streaming_latency: 3 },
    },
    platform_settings: {
      auth: { enable_auth: true },
      overrides: { conversation_config_override: { conversation: { text_only: true }, agent: { first_message: true, language: false } } },
    },
  };
  const tools: { id: string; tool_config: Json }[] = wanted.tools.map((t, i) => ({
    id: `tool_${i}`,
    // ElevenLabs adds defaults to what it stores
    tool_config: JSON.parse(JSON.stringify({ ...t, execution_mode: "immediate", assignments: [] })),
  }));
  agent.conversation_config.agent.prompt.tool_ids = tools.map((t) => t.id);
  const calls: Call[] = [];
  let nextId = 100;
  const fetch = (u: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(u));
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, path: url.pathname + url.search, body, keyOk: (init?.headers as Record<string, string>)?.["xi-api-key"] === KEY });
    const reply = (b: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(b), { status }));
    if (url.pathname === "/v1/convai/conversation/token") return reply({ token: "tok" });
    if (opts.failWith && url.pathname.startsWith("/v1/convai/agents/")) {
      return Promise.resolve(new Response(`denied for ${KEY}`, { status: opts.failWith }));
    }
    if (method === "GET" && url.pathname === "/v1/convai/agents/agent_123") return reply(agent);
    if (method === "GET" && url.pathname === "/v1/convai/tools") {
      if (opts.toolPages) return reply({ tools: [], has_more: true, next_cursor: "c" });
      return reply({ tools, has_more: false });
    }
    if (method === "POST" && url.pathname === "/v1/convai/tools") {
      const id = `tool_${nextId++}`;
      tools.push({ id, tool_config: body!.tool_config });
      return reply({ id });
    }
    if (method === "PATCH" && url.pathname.startsWith("/v1/convai/tools/")) {
      const t = tools.find((x) => url.pathname.endsWith(x.id))!;
      t.tool_config = body!.tool_config;
      return reply({ id: t.id });
    }
    if (method === "PATCH" && url.pathname === "/v1/convai/agents/agent_123") return reply({ agent_id: "agent_123" });
    return reply({}, 404);
  };
  const writes = () => calls.filter((c) => c.method !== "GET");
  const agentGets = () => calls.filter((c) => c.method === "GET" && c.path === "/v1/convai/agents/agent_123").length;
  return { wanted, agent, tools, calls, fetch: fetch as typeof globalThis.fetch, writes, agentGets };
}

function setup(api: Awaited<ReturnType<typeof fakeApi>>, extraEnv: Record<string, string> = {}) {
  const e = { ...env, ...extraEnv };
  const deps = { fetch: api.fetch, env: (k: string) => e[k] };
  const logs: SyncLog[] = [];
  const sync = createAgentSync(deps, { log: (l) => logs.push(l) });
  return { deps: { ...deps, agentSync: sync }, sync, logs };
}

const agentPatches = (api: Awaited<ReturnType<typeof fakeApi>>) =>
  api.writes().filter((c) => c.method === "PATCH" && c.path === "/v1/convai/agents/agent_123");

Deno.test("sync is a no-op when the agent already matches", async () => {
  const api = fakeApi();
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "unchanged");
  assertEquals(api.writes(), []);
  assertEquals(logs, [{ evt: "agent_sync", outcome: "unchanged", changed: [], error: null }]);
});

Deno.test("sync patches only the prompt when it differs", async () => {
  const api = fakeApi();
  api.agent.conversation_config.agent.prompt.prompt = "old prompt";
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const patches = agentPatches(api);
  assertEquals(patches.length, 1);
  assertEquals(patches[0].body, { conversation_config: { agent: { prompt: { prompt: api.wanted.prompt } } } });
  assertEquals(logs[0].changed, ["prompt"]);
});

Deno.test("sync patches first_message, dynamic variables and turn fields and keeps an llm chosen in the dashboard", async () => {
  const api = fakeApi();
  const cfg = api.agent.conversation_config;
  cfg.agent.prompt.llm = "other-llm";
  cfg.agent.first_message = "hi";
  cfg.agent.dynamic_variables.dynamic_variable_placeholders.plan = "{}";
  cfg.turn.turn_timeout = 7;
  cfg.turn.silence_end_call_timeout = 5;
  cfg.turn.turn_eagerness = "eager";
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const w = api.wanted.agent.conversation_config;
  assertEquals(agentPatches(api)[0].body, {
    conversation_config: {
      agent: {
        first_message: w.agent.first_message,
        dynamic_variables: { dynamic_variable_placeholders: { plan: w.agent.dynamic_variables.dynamic_variable_placeholders.plan } },
      },
      turn: { turn_timeout: -1, silence_end_call_timeout: 30, turn_eagerness: "patient" },
    },
  });
  assertEquals(logs[0].changed, ["first_message", "dynamic_variables", "turn_timeout", "silence_end_call_timeout", "turn_eagerness"]);
  assertEquals(cfg.agent.prompt.llm, "other-llm");
});

Deno.test("sync sets llm and voice only when the agent has none", async () => {
  const api = fakeApi();
  delete api.agent.conversation_config.agent.prompt.llm;
  api.agent.conversation_config.tts.voice_id = "";
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const w = api.wanted.agent.conversation_config;
  assertEquals(agentPatches(api)[0].body, {
    conversation_config: { agent: { prompt: { llm: w.agent.prompt.llm } }, tts: { voice_id: w.tts.voice_id } },
  });
  assertEquals(logs[0].changed, ["llm", "tts.voice_id"]);
});

Deno.test("sync patches client_events when tentative_user_transcript and vad_score are missing", async () => {
  const api = fakeApi();
  api.agent.conversation_config.conversation.client_events = ["audio", "interruption", "agent_response", "user_transcript", "agent_response_correction", "agent_tool_response"];
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  assertEquals(logs[0].changed, ["client_events"]);
  assertEquals(agentPatches(api)[0].body, {
    conversation_config: { conversation: { client_events: api.wanted.agent.conversation_config.conversation.client_events } },
  });
});

Deno.test("sync patches only the differing tts and language fields and keeps a voice chosen in the dashboard", async () => {
  const api = fakeApi();
  api.agent.conversation_config.tts.voice_id = "other";
  api.agent.conversation_config.tts.stability = 0.1;
  api.agent.conversation_config.agent.language = "de";
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const t = api.wanted.agent.conversation_config;
  assertEquals(agentPatches(api)[0].body, {
    conversation_config: { agent: { language: "en" }, tts: { stability: t.tts.stability } },
  });
  assertEquals(logs[0].changed, ["language", "tts.stability"]);
  assertEquals(api.agent.conversation_config.tts.voice_id, "other");
});

Deno.test("sync turns auth on and the first_message override on, leaving other overrides", async () => {
  const api = fakeApi();
  api.agent.platform_settings.auth.enable_auth = false;
  api.agent.platform_settings.overrides.conversation_config_override.agent.first_message = false;
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  assertEquals(agentPatches(api)[0].body, {
    platform_settings: {
      auth: { enable_auth: true },
      overrides: { conversation_config_override: { agent: { first_message: true } } },
    },
  });
  assertEquals(logs[0].changed, ["auth", "override:first_message"]);
});

Deno.test("sync re-enables skip_turn when it is missing", async () => {
  const api = fakeApi();
  api.agent.conversation_config.agent.prompt.built_in_tools.skip_turn = null;
  const { sync } = setup(api);
  assertEquals(await sync(), "patched");
  assertEquals(
    agentPatches(api)[0].body!.conversation_config.agent.prompt.built_in_tools.skip_turn.description,
    api.wanted.agent.conversation_config.agent.prompt.built_in_tools.skip_turn.description,
  );
});

Deno.test("sync creates a missing tool and attaches it", async () => {
  const api = fakeApi();
  api.tools.splice(api.tools.findIndex((t) => t.tool_config.name === "undo_edit"), 1);
  api.agent.conversation_config.agent.prompt.tool_ids = api.tools.map((t) => t.id);
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const posts = api.writes().filter((c) => c.method === "POST");
  assertEquals(posts.length, 1);
  assertEquals(posts[0].body!.tool_config.name, "undo_edit");
  const ids = agentPatches(api)[0].body!.conversation_config.agent.prompt.tool_ids as string[];
  assertEquals(ids.includes("tool_100"), true);
  assertEquals(ids.length, 4);
  assertEquals(logs[0].changed, ["tool:undo_edit:created", "tool_ids"]);
});

Deno.test("sync patches a tool whose owned fields differ and ignores ElevenLabs defaults", async () => {
  const api = fakeApi();
  api.tools.find((t) => t.tool_config.name === "set_global")!.tool_config.description = "stale";
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  const patches = api.writes().filter((c) => c.path.startsWith("/v1/convai/tools/"));
  assertEquals(patches.length, 1);
  assertEquals(patches[0].body!.tool_config.name, "set_global");
  assertEquals(agentPatches(api), []);
  assertEquals(logs[0].changed, ["tool:set_global"]);
});

Deno.test("sync keeps foreign tools attached", async () => {
  const api = fakeApi();
  api.tools.push({ id: "tool_foreign", tool_config: { type: "client", name: "other", description: "x" } });
  api.tools.splice(api.tools.findIndex((t) => t.tool_config.name === "edit_section"), 1);
  api.agent.conversation_config.agent.prompt.tool_ids = ["tool_foreign", "tool_0", "tool_2", "tool_3"];
  const { sync } = setup(api);
  assertEquals(await sync(), "patched");
  const ids = agentPatches(api)[0].body!.conversation_config.agent.prompt.tool_ids as string[];
  assertEquals(ids.includes("tool_foreign"), true);
  assertEquals(ids.length, 5);
});

Deno.test("sync failure logs failed with the status, never the key, and the token still works", async () => {
  const api = fakeApi({ failWith: 403 });
  const { deps, logs } = setup(api);
  const res = await handler(new Request("http://x", { headers: { "x-access-code": "s3cret" } }), deps);
  assertEquals(await res.json(), { token: "tok" });
  assertEquals(logs.length, 1);
  assertEquals(logs[0].outcome, "failed");
  assertStringIncludes(logs[0].error!, "403");
  assertEquals(JSON.stringify(logs).includes(KEY), false);
});

Deno.test("failure is not memoized: the next request retries", async () => {
  const api = fakeApi({ failWith: 403 });
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "failed");
  assertEquals(await sync(), "failed");
  assertEquals(api.agentGets(), 2);
  assertEquals(logs.length, 2);
});

Deno.test("success is memoized: the second request does not read the agent again", async () => {
  const api = fakeApi();
  const { deps, logs } = setup(api);
  const req = () => new Request("http://x", { headers: { "x-access-code": "s3cret" } });
  await handler(req(), deps);
  await handler(req(), deps);
  assertEquals(api.agentGets(), 1);
  assertEquals(logs.length, 1);
});

Deno.test("concurrent requests share one sync", async () => {
  const api = fakeApi();
  const { sync } = setup(api);
  await Promise.all([sync(), sync(), sync()]);
  assertEquals(api.agentGets(), 1);
});

Deno.test("AGENT_SYNC=off skips the sync and still returns the token", async () => {
  const api = fakeApi();
  const { deps, logs } = setup(api, { AGENT_SYNC: "off" });
  const res = await handler(new Request("http://x", { headers: { "x-access-code": "s3cret" } }), deps);
  assertEquals(await res.json(), { token: "tok" });
  assertEquals(api.calls.map((c) => c.path), ["/v1/convai/conversation/token?agent_id=agent_123"]);
  assertEquals(logs, []);
});

Deno.test("more than 5 pages of tools fails the sync", async () => {
  const api = fakeApi({ toolPages: 1 });
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "failed");
  assertStringIncludes(logs[0].error!, "pages");
});

Deno.test("sync sends the agents key on every call and logs hold no prompt text", async () => {
  const api = fakeApi();
  api.agent.conversation_config.agent.prompt.prompt = "old";
  const { sync, logs } = setup(api);
  await sync();
  assertEquals(api.calls.length > 0 && api.calls.every((c) => c.keyOk), true);
  assertEquals(JSON.stringify(logs).includes(api.wanted.prompt.slice(0, 40)), false);
});

Deno.test("the wanted config carries the repo values", () => {
  const w = loadWantedAgent();
  assertEquals(w.tools.map((t) => t.name).sort(), ["compose_track", "edit_section", "set_global", "undo_edit"]);
  assertEquals(w.prompt.includes("{{plan}}"), true);
  assertEquals(w.agent.platform_settings.auth.enable_auth, true);
  assertEquals(w.agent.conversation_config.tts.voice_id, "CwhRBWXzGAHq8TQ4Fs17");
});

Deno.test("a failure before the first await is not memoized (retry rule)", async () => {
  const api = fakeApi();
  const cfg: { key?: string } = {};
  const deps = { fetch: api.fetch, env: (k: string) => (k === "ELEVENLABS_AGENTS_KEY" ? cfg.key : env[k]) };
  const logs: SyncLog[] = [];
  const sync = createAgentSync(deps, { log: (l) => logs.push(l) });
  assertEquals(await sync(), "failed");
  cfg.key = KEY;
  assertEquals(await sync(), "unchanged");
});

Deno.test("an invalid wanted config fails with a safe config_invalid reason and retries", async () => {
  const api = fakeApi();
  const logs: SyncLog[] = [];
  const sync = createAgentSync({ fetch: api.fetch, env: (k) => env[k] }, {
    wanted: () => {
      throw new TypeError(`boom ${KEY}`);
    },
    log: (l) => logs.push(l),
  });
  assertEquals(await sync(), "failed");
  assertEquals(await sync(), "failed");
  assertEquals(logs.length, 2);
  assertEquals(logs[0].error, "config_invalid: TypeError");
  assertEquals(api.calls, []);
});

Deno.test("dynamic placeholders are merged with the stored ones", async () => {
  const api = fakeApi();
  const vars = api.agent.conversation_config.agent.dynamic_variables.dynamic_variable_placeholders;
  vars.plan = "stale";
  vars.foreign = "keep";
  const { sync } = setup(api);
  assertEquals(await sync(), "patched");
  const sent = agentPatches(api)[0].body!.conversation_config.agent.dynamic_variables.dynamic_variable_placeholders;
  assertEquals(sent.foreign, "keep");
  assertEquals(sent.plan, api.wanted.agent.conversation_config.agent.dynamic_variables.dynamic_variable_placeholders.plan);
});

Deno.test("skip_turn drift in owned fields beyond description is patched", async () => {
  const api = fakeApi();
  api.agent.conversation_config.agent.prompt.built_in_tools.skip_turn.response_timeout_secs = 1;
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "patched");
  assertEquals(logs[0].changed, ["skip_turn"]);
});

Deno.test("every tool parameter, array items included, has a description (ElevenLabs rejects the tool otherwise)", () => {
  const missing: string[] = [];
  const walk = (node: Json, path: string) => {
    if (typeof node.description !== "string" || node.description === "") missing.push(path);
    for (const [name, child] of Object.entries((node.properties ?? {}) as Record<string, Json>)) walk(child, `${path}.${name}`);
    if (node.items) walk(node.items as Json, `${path}[]`);
  };
  for (const tool of loadWantedAgent().tools) {
    for (const [name, param] of Object.entries((tool.parameters?.properties ?? {}) as Record<string, Json>)) {
      walk(param, `${tool.name}.${name}`);
    }
  }
  assertEquals(missing, []);
});

Deno.test("a 422 logs the invalid field paths, never values", async () => {
  const api = fakeApi();
  api.tools.find((t) => t.tool_config.name === "set_global")!.tool_config.description = "stale";
  const base = api.fetch;
  api.fetch = ((u: string | URL | Request, init?: RequestInit) =>
    init?.method === "PATCH" && String(u).includes("/v1/convai/tools/")
      ? Promise.resolve(new Response(JSON.stringify({ detail: [{ loc: ["body", "tool_config", "parameters"], msg: `bad ${KEY}`, input: "secret value" }] }), { status: 422 }))
      : base(u, init)) as typeof globalThis.fetch;
  const { sync, logs } = setup(api);
  assertEquals(await sync(), "failed");
  assertEquals(logs[0].error, "patch_tool http 422 at body.tool_config.parameters");
  assertEquals(JSON.stringify(logs).includes(KEY), false);
  assertEquals(JSON.stringify(logs).includes("secret value"), false);
});

/** The client events ElevenLabs accepts (from its validation error, 2026-10-15). */
const KNOWN_CLIENT_EVENTS = new Set([
  "conversation_initiation_metadata", "asr_initiation_metadata", "ping", "audio", "interruption", "user_transcript",
  "tentative_user_transcript", "agent_response", "agent_response_correction", "client_tool_call", "rich_content",
  "mcp_tool_call", "mcp_connection_status", "agent_tool_request", "agent_tool_response",
  "agent_tool_response_full_payload", "agent_response_metadata", "vad_score", "agent_chat_response_part",
  "agent_reasoning_response_part", "client_error", "guardrail_triggered", "dtmf_request", "agent_response_complete",
  "context_usage",
]);

Deno.test("every wanted client event is one ElevenLabs accepts", () => {
  const events = loadWantedAgent().agent.conversation_config.conversation.client_events as string[];
  assertEquals(events.filter((e) => !KNOWN_CLIENT_EVENTS.has(e)), []);
});

Deno.test("a stored param with enum: null equals a wanted param without enum (no endless re-patching)", async () => {
  const api = fakeApi();
  for (const t of api.tools) {
    for (const p of Object.values((t.tool_config.parameters?.properties ?? {}) as Record<string, Json>)) {
      if (p.type !== "array" && p.enum === undefined) p.enum = null;
    }
  }
  const { sync } = setup(api);
  assertEquals(await sync(), "unchanged");
  assertEquals(api.writes(), []);
});
