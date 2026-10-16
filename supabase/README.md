# Supabase proxy

Edge Functions that keep the ElevenLabs API key off the device. The app sends an access code in `x-access-code` and never sees the key.

| Function | Request | Response |
|---|---|---|
| `music-plan` | `POST {prompt, music_length_ms}` | plan JSON (`{chunks: [...]}`) |
| `music-compose` | `POST {composition_plan}` | binary audio; headers `song-id`, `x-audio-format`, `x-audio-channels` |
| `agent-token` | `GET` | `{"token": "..."}` (after the agent sync, below) |
| `access` | `GET` | `{"access": "code"}`: the code is valid; nothing is called upstream |
| `agent-conversation` | `GET ?id=conv_...` | why the agent's conversation failed: `{"status": "...", "reason": "..." \| null}`, or `402 {"error": "quota_exceeded"}` for exhausted credits |

All require a known `x-access-code`, otherwise `401`. Upstream errors come back as `{error, status}` with the same HTTP status (429 included).

## Secrets

```sh
supabase secrets set --project-ref <ref> \
  ELEVENLABS_API_KEY=... \
  ELEVENLABS_AGENTS_KEY=... \
  AGENT_ID=... \
  ACCESS_CODES=label:code,label:code \
  PREFERRED_FORMATS=pcm_44100,pcm_48000,pcm_24000,pcm_22050,mp3_44100_128 \
  PCM_CHANNELS=2
```

- `ELEVENLABS_API_KEY`: the Music API key, used by `music-plan` and `music-compose`.
- `ELEVENLABS_AGENTS_KEY`: a key scoped to ElevenLabs Agents only, used by `agent-token` and `agent-conversation`. Kept separate so the Agents surface never gets the Music key. If unset, `agent-token` returns `500`.
- `AGENT_ID`: the ElevenLabs agent that `agent-token` issues conversation tokens for.
- `ACCESS_CODES`: comma-separated `label:code` pairs; a request with one of the codes in `x-access-code` is let in and
  logs `{"evt":"access","code":"<label>"}`. Removing a pair (`supabase secrets set ACCESS_CODES=...`) revokes that code.

`PREFERRED_FORMATS` and `PCM_CHANNELS` are optional (the values above are the defaults).
- `AGENT_SYNC=off` (optional): skips the agent sync below.

## Agent sync

Before minting a token, `agent-token` brings the live agent to the config in `_shared/agent/` (`prompt.ts`, `agent.ts`, `tools.ts`), once per function instance (the next cold start syncs again; a failed sync is retried on the next request). It creates/patches the four client tools and patches only the fields the repo owns. Everything `agent.ts` declares is owned: prompt, tool list (foreign tools stay attached), `skip_turn`, first message, language, `plan` placeholder (merged with other stored placeholders), turn timeouts/eagerness, `conversation.client_events` (compared as a set, full list sent), each declared `tts` field (model, stability, speed, expressive mode), auth on and the `first_message` client override. The LLM and the voice (`tts.voice_id`) are set only when the agent has none, so a choice made in the ElevenLabs dashboard stays; the repo's values are the defaults for a new agent (the voice is the premade "Roger", available in every account). Not owned and left as they are: ASR, language presets, widget, other overrides and anything not declared in `agent.ts`. To own another field, add it to `agent.ts` and to `syncAgent`.

- `ELEVENLABS_AGENTS_KEY` needs **ElevenLabs Agents write access** (`convai_write`) for the sync. Without it the sync logs `outcome: "failed"` with the upstream status and the token is still returned, on the agent's previous config.
- Each attempt logs one JSON line `{"evt":"agent_sync","outcome":"unchanged|patched|failed","changed":[...],"error":null|"..."}`; no keys, tokens or prompt text.
- Kill switch: `supabase secrets set AGENT_SYNC=off` skips the sync entirely.
- The wanted config is the TS modules in `_shared/agent/` (`prompt.ts`, `agent.ts`, `tools.ts`), imported statically, so the bundle is self-contained (no Docker or `static_files`). Edit them there; `agent/scripts/create-agent.sh` reads the same modules. An invalid config logs `config_invalid: ...`.

## Deploy

The app authenticates with an access code, not a Supabase JWT, so deploy with `--no-verify-jwt` (also set in `config.toml`):

```sh
supabase functions deploy music-plan --no-verify-jwt --project-ref <ref>
supabase functions deploy music-compose --no-verify-jwt --project-ref <ref>
supabase functions deploy agent-token --no-verify-jwt --project-ref <ref>
supabase functions deploy agent-conversation --no-verify-jwt --project-ref <ref>
supabase functions deploy access --no-verify-jwt --project-ref <ref>
```

Check: `curl -H "x-access-code: <code>" https://<ref>.supabase.co/functions/v1/access` returns `{"access":"code"}`; it calls nothing upstream and spends no credits.

## Tests

From the repo root: `deno test supabase/functions` (no flags or permissions needed; `fetch` and env are injected).
