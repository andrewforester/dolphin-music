import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";

const env: Record<string, string> = {
  ELEVENLABS_AGENTS_KEY: "agents-key",
  APP_SHARED_SECRET: "s3cret",
  AGENT_ID: "agent_123",
};
const request = (id: string | null, secret = "s3cret") =>
  new Request(`http://x/agent-conversation${id === null ? "" : `?id=${encodeURIComponent(id)}`}`, {
    headers: { "x-app-secret": secret },
  });

const quotaRefused = {
  agent_id: "agent_123",
  status: "failed",
  metadata: {
    error: {
      code: 3000,
      reason: "[quota_exceeded] You've run out of credits. Add credits or upgrade your plan to start a new conversation.",
      error_type: "call_initialization_error",
    },
  },
};

function serving(...bodies: unknown[]) {
  const urls: string[] = [];
  const keys: string[] = [];
  const fetch = (u: string | URL | Request, init?: RequestInit) => {
    urls.push(String(u));
    keys.push((init!.headers as Record<string, string>)["xi-api-key"]);
    const body = bodies[Math.min(urls.length, bodies.length) - 1];
    return Promise.resolve(
      body instanceof Response ? body : new Response(JSON.stringify(body), { status: 200 }),
    );
  };
  return { urls, keys, fetch: fetch as typeof globalThis.fetch };
}

const noWait = () => Promise.resolve();

Deno.test("agent-conversation reads the conversation with the agents key", async () => {
  const upstream = serving(quotaRefused);
  await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(upstream.urls, ["https://api.elevenlabs.io/v1/convai/conversations/conv_abc"]);
  assertEquals(upstream.keys, ["agents-key"]);
});

Deno.test("agent-conversation turns an exhausted quota into the 402 the app shows", async () => {
  const upstream = serving(quotaRefused);
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 402);
  assertEquals(await res.json(), {
    error: "quota_exceeded",
    status: 402,
    credits_remaining: null,
    credits_required: null,
  });
});

Deno.test("agent-conversation returns any other recorded failure without its tag", async () => {
  const upstream = serving({
    agent_id: "agent_123",
    status: "failed",
    metadata: { error: { code: 3001, reason: "[agent_busy] Too many calls at once.", error_type: "x" } },
  });
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { status: "failed", reason: "Too many calls at once." });
});

Deno.test("agent-conversation waits while the failure is not recorded yet", async () => {
  const upstream = serving({ agent_id: "agent_123", status: "initiated", metadata: {} }, quotaRefused);
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 402);
  assertEquals(upstream.urls.length, 2);
});

Deno.test("agent-conversation gives up with no reason after a few reads", async () => {
  const upstream = serving({ agent_id: "agent_123", status: "in-progress", metadata: {} });
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(await res.json(), { status: "in-progress", reason: null });
  assertEquals(upstream.urls.length, 3);
});

Deno.test("agent-conversation hides a conversation of another agent", async () => {
  const upstream = serving({ ...quotaRefused, agent_id: "agent_other" });
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 404);
});

Deno.test("agent-conversation rejects a missing or malformed id without calling upstream", async () => {
  for (const id of [null, "", "conv_../../v1/user", "abc"]) {
    const upstream = serving(quotaRefused);
    const res = await handler(request(id), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
    assertEquals(res.status, 400);
    assertEquals(upstream.urls.length, 0);
  }
});

Deno.test("agent-conversation rejects bad secret", async () => {
  const upstream = serving(quotaRefused);
  const res = await handler(request("conv_abc", "bad"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 401);
});

Deno.test("agent-conversation returns 500 when the agents key is unset", async () => {
  const upstream = serving(quotaRefused);
  const { ELEVENLABS_AGENTS_KEY: _unused, ...withoutKey } = env;
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => withoutKey[k], sleep: noWait });
  assertEquals(res.status, 500);
  assertEquals(upstream.urls.length, 0);
});

Deno.test("agent-conversation passes an upstream error on", async () => {
  const upstream = serving(new Response('{"detail":"not found"}', { status: 404 }));
  const res = await handler(request("conv_abc"), { fetch: upstream.fetch, env: (k) => env[k], sleep: noWait });
  assertEquals(res.status, 404);
});

Deno.test("agent-conversation returns 502 when the upstream fetch throws", async () => {
  const fetch = () => Promise.reject(new TypeError("network down"));
  const res = await handler(request("conv_abc"), {
    fetch: fetch as typeof globalThis.fetch,
    env: (k) => env[k],
    sleep: noWait,
  });
  assertEquals(res.status, 502);
});
