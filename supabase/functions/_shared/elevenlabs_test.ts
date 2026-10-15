import { assertEquals } from "jsr:@std/assert@1";
import { isFormatError, parseFormats, pcmChannels } from "./elevenlabs.ts";
import { secretMatches } from "./auth.ts";

Deno.test("parseFormats splits CSV and falls back to defaults", () => {
  assertEquals(parseFormats("a, b ,,c"), ["a", "b", "c"]);
  assertEquals(parseFormats(undefined)[0], "pcm_44100");
  assertEquals(parseFormats("")[0], "pcm_44100");
});

Deno.test("pcmChannels defaults to 2", () => {
  assertEquals(pcmChannels(undefined), 2);
  assertEquals(pcmChannels("1"), 1);
  assertEquals(pcmChannels("junk"), 2);
});

Deno.test("isFormatError detects format-related 403/422 only", () => {
  const body403 = '{"detail":{"status":"output_format_not_allowed","message":"x"}}';
  assertEquals(isFormatError(403, body403), true);
  assertEquals(isFormatError(422, '{"detail":[{"loc":["query","output_format"]}]}'), true);
  assertEquals(isFormatError(422, '{"detail":"bad prompt"}'), false);
  assertEquals(isFormatError(429, "output_format"), false);
  assertEquals(isFormatError(500, "output_format"), false);
});

Deno.test("secretMatches compares exactly", () => {
  assertEquals(secretMatches("abc", "abc"), true);
  assertEquals(secretMatches("abd", "abc"), false);
  assertEquals(secretMatches("ab", "abc"), false);
  assertEquals(secretMatches(null, "abc"), false);
  assertEquals(secretMatches("abc", undefined), false);
  assertEquals(secretMatches("", ""), false);
});
