import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";

const env: Record<string, string> = { ACCESS_CODES: "elevenlabs:river-copper-violin-dawn", APP_SHARED_SECRET: "s3cret" };
let upstreamCalls = 0;
const deps = {
  fetch: (() => {
    upstreamCalls++;
    return Promise.resolve(new Response("{}"));
  }) as typeof globalThis.fetch,
  env: (k: string) => env[k],
};

Deno.test("access answers which kind of access a known code gives", async () => {
  const res = await handler(new Request("http://x", { headers: { "x-access-code": "river-copper-violin-dawn" } }), deps);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { access: "code" });
});

Deno.test("access answers 401 for an unknown code and calls nothing upstream", async () => {
  const res = await handler(new Request("http://x", { headers: { "x-access-code": "nope" } }), deps);
  assertEquals(res.status, 401);
  assertEquals(upstreamCalls, 0);
});

Deno.test("access only answers GET", async () => {
  const res = await handler(
    new Request("http://x", { method: "POST", headers: { "x-access-code": "river-copper-violin-dawn" } }),
    deps,
  );
  assertEquals(res.status, 405);
});
