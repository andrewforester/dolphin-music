import { assertEquals } from "jsr:@std/assert@1";
import { matchAccessCode } from "./auth.ts";
import { resolveAccess, unauthorized } from "./elevenlabs.ts";

const CODES = "elevenlabs:river-copper-violin-dawn, me:quiet-orbit-maple-seven";

Deno.test("matchAccessCode returns the label of the matching code", () => {
  assertEquals(
    matchAccessCode("river-copper-violin-dawn", CODES),
    "elevenlabs",
  );
  assertEquals(matchAccessCode("quiet-orbit-maple-seven", CODES), "me");
});

Deno.test("matchAccessCode rejects unknown, partial, missing and empty codes", () => {
  assertEquals(matchAccessCode("river-copper-violin", CODES), null);
  assertEquals(matchAccessCode("elevenlabs", CODES), null);
  assertEquals(matchAccessCode(null, CODES), null);
  assertEquals(matchAccessCode("", CODES), null);
  assertEquals(matchAccessCode("river-copper-violin-dawn", undefined), null);
  assertEquals(matchAccessCode("river-copper-violin-dawn", ""), null);
});

Deno.test("matchAccessCode ignores malformed entries and entries without a code", () => {
  assertEquals(matchAccessCode("x", "broken, empty:, :x"), null);
  assertEquals(matchAccessCode("a:b", "colon:a:b"), "colon");
});

const deps = (env: Record<string, string>) => ({
  fetch: globalThis.fetch,
  env: (k: string) => env[k],
});
const req = (headers: Record<string, string>) => new Request("http://x", { headers });

Deno.test("unauthorized lets an access code through", () => {
  assertEquals(
    unauthorized(
      req({ "x-access-code": "quiet-orbit-maple-seven" }),
      deps({ ACCESS_CODES: CODES }),
    ),
    null,
  );
});

Deno.test("unauthorized no longer accepts the app secret", () => {
  const res = unauthorized(req({ "x-app-secret": "s3cret" }), deps({ APP_SHARED_SECRET: "s3cret", ACCESS_CODES: CODES }));
  assertEquals(res?.status, 401);
});

Deno.test("unauthorized answers 401 for a wrong code, a code sent as the app secret, or nothing", async () => {
  const d = deps({ APP_SHARED_SECRET: "s3cret", ACCESS_CODES: CODES });
  const cases: Record<string, string>[] = [
    { "x-access-code": "nope" },
    { "x-app-secret": "quiet-orbit-maple-seven" },
    { "x-access-code": "s3cret" },
    {},
  ];
  for (const headers of cases) {
    const res = unauthorized(req(headers), d);
    assertEquals(res?.status, 401);
    assertEquals(await res?.json(), { error: "unauthorized", status: 401 });
  }
});

Deno.test("resolveAccess names how the request got in", () => {
  const d = deps({ APP_SHARED_SECRET: "s3cret", ACCESS_CODES: CODES });
  assertEquals(
    resolveAccess(req({ "x-access-code": "river-copper-violin-dawn" }), d),
    { kind: "code", label: "elevenlabs" },
  );
  assertEquals(resolveAccess(req({ "x-app-secret": "s3cret" }), d), null);
  assertEquals(resolveAccess(req({}), d), null);
});
