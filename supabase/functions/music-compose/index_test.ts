import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";
import { resetFormatCache } from "../_shared/elevenlabs.ts";

const env = {
  ELEVENLABS_API_KEY: "key",
  APP_SHARED_SECRET: "s3cret",
  PREFERRED_FORMATS: "pcm_44100,pcm_24000",
  PCM_CHANNELS: "2",
};

type Call = { url: string; init: RequestInit };
function setup(responses: Response[]) {
  const calls: Call[] = [];
  const fetch = (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return Promise.resolve(responses.shift()!);
  };
  return { calls, deps: { fetch: fetch as typeof globalThis.fetch, env: (k: string) => (env as Record<string, string>)[k] } };
}
function req(secret: string | null = "s3cret") {
  return new Request("http://x/music-compose", {
    method: "POST",
    headers: secret ? { "x-app-secret": secret } : {},
    body: JSON.stringify({ composition_plan: { chunks: [] } }),
  });
}
const ok = () => new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { "song-id": "song-1", "content-type": "audio/pcm" } });

Deno.test("rejectsMissingSecret", async () => {
  resetFormatCache();
  const { deps, calls } = setup([]);
  const res = await handler(req(null), deps);
  assertEquals(res.status, 401);
  assertEquals((await res.json()).status, 401);
  assertEquals(calls.length, 0);
});

Deno.test("composeAddsModelAndInpaintingFlags", async () => {
  resetFormatCache();
  const { deps, calls } = setup([ok()]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 200);
  assertEquals(calls[0].url, "https://api.elevenlabs.io/v1/music?output_format=pcm_44100");
  assertEquals((calls[0].init.headers as Record<string, string>)["xi-api-key"], "key");
  const body = JSON.parse(calls[0].init.body as string);
  assertEquals(body.model_id, "music_v2_5");
  assertEquals(body.store_for_inpainting, true);
  assertEquals(body.composition_plan, { chunks: [] });
  assertEquals(res.headers.get("x-audio-format"), "pcm_44100");
  assertEquals(res.headers.get("x-audio-channels"), "2");
  assertEquals(new Uint8Array(await res.arrayBuffer()), new Uint8Array([1, 2, 3]));
});

Deno.test("composeFallsBackToNextFormat on 403 format error", async () => {
  resetFormatCache();
  const { deps, calls } = setup([
    new Response('{"detail":{"status":"output_format_not_allowed","message":"no"}}', { status: 403 }),
    ok(),
  ]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("x-audio-format"), "pcm_24000");
  assertEquals(calls.length, 2);
  // remembered: next request starts at the working format
  const next = setup([ok()]);
  await handler(req(), next.deps);
  assertEquals(next.calls[0].url.endsWith("output_format=pcm_24000"), true);
});

Deno.test("composeFallsBackToNextFormat on 422 mentioning output_format", async () => {
  resetFormatCache();
  const { deps } = setup([
    new Response('{"detail":[{"loc":["query","output_format"],"msg":"bad"}]}', { status: 422 }),
    ok(),
  ]);
  const res = await handler(req(), deps);
  assertEquals(res.headers.get("x-audio-format"), "pcm_24000");
});

Deno.test("compose returns last error when every format is rejected", async () => {
  resetFormatCache();
  const bad = () => new Response('{"detail":{"status":"output_format_not_allowed"}}', { status: 403 });
  const { deps, calls } = setup([bad(), bad()]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 403);
  assertEquals((await res.json()).status, 403);
  assertEquals(calls.length, 2);
});

Deno.test("composePassesSongIdHeader", async () => {
  resetFormatCache();
  const { deps } = setup([ok()]);
  const res = await handler(req(), deps);
  assertEquals(res.headers.get("song-id"), "song-1");
});

Deno.test("composePropagates429", async () => {
  resetFormatCache();
  const { deps, calls } = setup([new Response('{"detail":"too many"}', { status: 429 })]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 429);
  const body = await res.json();
  assertEquals(body.status, 429);
  assertEquals(typeof body.error, "string");
  assertEquals(calls.length, 1);
});

Deno.test("compose rejects missing composition_plan with 400", async () => {
  resetFormatCache();
  const { deps } = setup([]);
  const r = new Request("http://x", { method: "POST", headers: { "x-app-secret": "s3cret" }, body: "{}" });
  assertEquals((await handler(r, deps)).status, 400);
});

Deno.test("compose returns 502 when the upstream fetch throws", async () => {
  resetFormatCache();
  const { deps } = setup([]);
  const throwing = { ...deps, fetch: (() => Promise.reject(new TypeError("dns failure"))) as typeof globalThis.fetch };
  const res = await handler(req(), throwing);
  assertEquals(res.status, 502);
  assertEquals((await res.json()).status, 502);
});

Deno.test("compose returns generic 500 without calling upstream when ELEVENLABS_API_KEY is unset", async () => {
  resetFormatCache();
  const { deps, calls } = setup([ok()]);
  const res = await handler(req(), { ...deps, env: (k) => (k === "ELEVENLABS_API_KEY" ? undefined : (env as Record<string, string>)[k]) });
  assertEquals(res.status, 500);
  assertEquals(await res.json(), { error: "server misconfigured", status: 500 });
  assertEquals(calls.length, 0);
});

Deno.test("compose never forwards upstream error text or the API key", async () => {
  resetFormatCache();
  const { deps } = setup([new Response("bad key sk-very-secret-key-123 for user x", { status: 401 })]);
  const res = await handler(req(), { ...deps, env: (k) => (k === "ELEVENLABS_API_KEY" ? "sk-very-secret-key-123" : (env as Record<string, string>)[k]) });
  const text = await res.text();
  assertEquals(res.status, 401);
  assertEquals(text.includes("sk-very-secret-key-123"), false);
  assertEquals(text.includes("bad key"), false);
});

Deno.test("compose maps upstream bad_prompt 400 to 422 with the suggestion", async () => {
  resetFormatCache();
  const upstream = JSON.stringify({ detail: { status: "bad_prompt", data: { reason: "copyrighted_material_detected", prompt_suggestion: "lush strings" } } });
  const { deps, calls } = setup([new Response(upstream, { status: 400 })]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 422);
  assertEquals(await res.json(), { error: "bad_prompt", status: 422, reason: "copyrighted_material_detected", prompt_suggestion: "lush strings" });
  assertEquals(calls.length, 1); // not treated as a format error
});

Deno.test("compose maps upstream quota_exceeded to 402 with the credits", async () => {
  resetFormatCache();
  const upstream = JSON.stringify({
    detail: {
      type: "invalid_request",
      code: "quota_exceeded",
      status: "quota_exceeded",
      message: "This request exceeds your quota of 34128. You have 137 credits remaining, while 325 credits are required for this request. Upgrade your plan or add credits to continue using the API: https://elevenlabs.io/app/subscription",
    },
  });
  const { deps, calls } = setup([new Response(upstream, { status: 401 })]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 402);
  assertEquals(await res.json(), { error: "quota_exceeded", status: 402, credits_remaining: 137, credits_required: 325 });
  assertEquals(calls.length, 1); // not treated as a format error
});

Deno.test("compose maps quota_exceeded without readable numbers to a bare 402", async () => {
  resetFormatCache();
  const upstream = JSON.stringify({ detail: { status: "quota_exceeded", message: "no numbers here" } });
  const { deps } = setup([new Response(upstream, { status: 401 })]);
  const res = await handler(req(), deps);
  assertEquals(res.status, 402);
  assertEquals(await res.json(), { error: "quota_exceeded", status: 402, credits_remaining: null, credits_required: null });
});

Deno.test("compose keeps non-bad_prompt and malformed 400s generic", async () => {
  for (const body of ['{"detail":{"status":"x"}}', "garbage"]) {
    resetFormatCache();
    const { deps } = setup([new Response(body, { status: 400 })]);
    const res = await handler(req(), deps);
    assertEquals(res.status, 400);
    assertEquals(await res.json(), { error: "upstream request failed", status: 400 });
  }
});
