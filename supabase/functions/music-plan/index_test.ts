import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";

const env: Record<string, string> = { ELEVENLABS_API_KEY: "key", APP_SHARED_SECRET: "s3cret" };

Deno.test("music-plan forwards prompt, length and model", async () => {
  let seen: { url: string; init: RequestInit } | undefined;
  const fetch = (url: string | URL | Request, init?: RequestInit) => {
    seen = { url: String(url), init: init! };
    return Promise.resolve(new Response('{"chunks":[]}', { status: 200, headers: { "content-type": "application/json" } }));
  };
  const res = await handler(
    new Request("http://x", { method: "POST", headers: { "x-app-secret": "s3cret" }, body: '{"prompt":"p","music_length_ms":10000}' }),
    { fetch: fetch as typeof globalThis.fetch, env: (k) => env[k] },
  );
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { chunks: [] });
  assertEquals(seen!.url, "https://api.elevenlabs.io/v1/music/plan");
  assertEquals(JSON.parse(seen!.init.body as string), { prompt: "p", music_length_ms: 10000, model_id: "music_v2_5" });
});

Deno.test("music-plan requires secret and propagates errors", async () => {
  const deps = { fetch: (() => Promise.resolve(new Response("nope", { status: 429 }))) as typeof globalThis.fetch, env: (k: string) => env[k] };
  assertEquals((await handler(new Request("http://x", { method: "POST", body: "{}" }), deps)).status, 401);
  const res = await handler(
    new Request("http://x", { method: "POST", headers: { "x-app-secret": "s3cret" }, body: '{"prompt":"p","music_length_ms":1}' }),
    deps,
  );
  assertEquals(res.status, 429);
  assertEquals((await res.json()).status, 429);
});

const planReq = () =>
  new Request("http://x", { method: "POST", headers: { "x-app-secret": "s3cret" }, body: '{"prompt":"p","music_length_ms":1}' });

Deno.test("music-plan returns 502 when the upstream fetch throws", async () => {
  const deps = { fetch: (() => Promise.reject(new TypeError("dns failure"))) as typeof globalThis.fetch, env: (k: string) => env[k] };
  const res = await handler(planReq(), deps);
  assertEquals(res.status, 502);
  const body = await res.json();
  assertEquals(body.status, 502);
  assertEquals(typeof body.error, "string");
});

Deno.test("music-plan returns generic 500 without calling upstream when ELEVENLABS_API_KEY is unset", async () => {
  let calls = 0;
  const fetch = () => {
    calls++;
    return Promise.resolve(new Response("{}"));
  };
  const res = await handler(planReq(), { fetch: fetch as typeof globalThis.fetch, env: (k) => (k === "ELEVENLABS_API_KEY" ? undefined : env[k]) });
  assertEquals(res.status, 500);
  assertEquals(await res.json(), { error: "server misconfigured", status: 500 });
  assertEquals(calls, 0);
});

Deno.test("music-plan never forwards upstream error text or the API key", async () => {
  const leakyEnv: Record<string, string> = { ...env, ELEVENLABS_API_KEY: "sk-very-secret-key-123" };
  const fetch = () => Promise.resolve(new Response("bad key sk-very-secret-key-123 for user x", { status: 401 }));
  const res = await handler(planReq(), { fetch: fetch as typeof globalThis.fetch, env: (k) => leakyEnv[k] });
  const text = await res.text();
  assertEquals(res.status, 401);
  assertEquals(text.includes("sk-very-secret-key-123"), false);
  assertEquals(text.includes("bad key"), false);
});

const bad = (body: string) => ({
  fetch: (() => Promise.resolve(new Response(body, { status: 400 }))) as typeof globalThis.fetch,
  env: (k: string) => env[k],
});

Deno.test("music-plan maps upstream bad_prompt 400 to 422 with the suggestion", async () => {
  const upstream = JSON.stringify({
    detail: {
      type: "invalid_request",
      status: "bad_prompt",
      message: "Your prompt appears to have violated our Terms of Service",
      request_id: "r1",
      data: { reason: "copyrighted_material_detected", prompt_suggestion: "An instrumental track with lush orchestration" },
    },
  });
  const res = await handler(planReq(), bad(upstream));
  assertEquals(res.status, 422);
  assertEquals(await res.json(), {
    error: "bad_prompt",
    status: 422,
    reason: "copyrighted_material_detected",
    prompt_suggestion: "An instrumental track with lush orchestration",
  });
});

Deno.test("music-plan bad_prompt without data gives null reason and suggestion", async () => {
  const res = await handler(planReq(), bad('{"detail":{"status":"bad_prompt"}}'));
  assertEquals(res.status, 422);
  assertEquals(await res.json(), { error: "bad_prompt", status: 422, reason: null, prompt_suggestion: null });
});

Deno.test("music-plan keeps a non-bad_prompt 400 generic", async () => {
  const res = await handler(planReq(), bad('{"detail":{"status":"other","message":"leaky text"}}'));
  assertEquals(res.status, 400);
  assertEquals(await res.json(), { error: "upstream request failed", status: 400 });
});

Deno.test("music-plan keeps a malformed 400 body generic", async () => {
  const res = await handler(planReq(), bad("not json {"));
  assertEquals(res.status, 400);
  assertEquals(await res.json(), { error: "upstream request failed", status: 400 });
});
