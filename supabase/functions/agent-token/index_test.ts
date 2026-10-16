import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";

const env: Record<string, string> = {
  ELEVENLABS_API_KEY: "music-key",
  ELEVENLABS_AGENTS_KEY: "agents-key",
  ACCESS_CODES: "test:s3cret",
  AGENT_ID: "agent_123",
};
const goodRequest = () => new Request("http://x", { headers: { "x-access-code": "s3cret" } });

Deno.test("agentTokenCallsConvaiEndpoint", async () => {
  let url = "";
  let headers: Record<string, string> = {};
  const fetch = (u: string | URL | Request, init?: RequestInit) => {
    url = String(u);
    headers = init!.headers as Record<string, string>;
    return Promise.resolve(new Response('{"token":"tok"}', { status: 200 }));
  };
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => env[k] });
  assertEquals(url, "https://api.elevenlabs.io/v1/convai/conversation/token?agent_id=agent_123");
  assertEquals(headers["xi-api-key"], "agents-key");
  assertEquals(await res.json(), { token: "tok" });
});

Deno.test("agent-token rejects bad secret", async () => {
  const deps = { fetch: (() => Promise.reject(new Error("no"))) as typeof globalThis.fetch, env: (k: string) => env[k] };
  assertEquals((await handler(new Request("http://x", { headers: { "x-access-code": "bad" } }), deps)).status, 401);
});

Deno.test("agent-token returns 500 without calling upstream when ELEVENLABS_AGENTS_KEY is unset", async () => {
  let calls = 0;
  const fetch = () => {
    calls++;
    return Promise.resolve(new Response('{"token":"tok"}', { status: 200 }));
  };
  const { ELEVENLABS_AGENTS_KEY: _unused, ...withoutAgentsKey } = env;
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => withoutAgentsKey[k] });
  assertEquals(res.status, 500);
  assertEquals(await res.json(), { error: "server misconfigured", status: 500 });
  assertEquals(calls, 0);
});

Deno.test("agent-token returns 502 when the upstream fetch throws", async () => {
  const fetch = () => Promise.reject(new TypeError("network down"));
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => env[k] });
  assertEquals(res.status, 502);
  assertEquals((await res.json()).status, 502);
});

Deno.test("agent-token returns 502 when the upstream body has no string token", async () => {
  const fetch = () => Promise.resolve(new Response('{"conversation_token":"nope"}', { status: 200 }));
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => env[k] });
  assertEquals(res.status, 502);
  assertEquals((await res.json()).status, 502);
});

Deno.test("agent-token returns 502 when a 200 upstream body is not JSON", async () => {
  const fetch = () => Promise.resolve(new Response("<html>oops</html>", { status: 200 }));
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => env[k] });
  assertEquals(res.status, 502);
  assertEquals((await res.json()).status, 502);
});

Deno.test("agent-token never forwards upstream error text or the agents key", async () => {
  const leakyEnv: Record<string, string> = { ...env, ELEVENLABS_AGENTS_KEY: "sk-agents-secret-456" };
  const fetch = () => Promise.resolve(new Response("denied for key sk-agents-secret-456", { status: 403 }));
  const res = await handler(goodRequest(), { fetch: fetch as typeof globalThis.fetch, env: (k) => leakyEnv[k] });
  const text = await res.text();
  assertEquals(res.status, 403);
  assertEquals(text.includes("sk-agents-secret-456"), false);
  assertEquals(text.includes("denied"), false);
});
