# Self-hosting

Dolphin needs a backend of your own: five Supabase Edge Functions that hold your ElevenLabs keys and an
ElevenLabs agent in your account. This guide sets both up and builds the app against them.

## Hand it to a coding agent

Paste this into Claude Code (or another coding agent) at the repository root:

```text
Read docs/self-hosting.md and set up my own Dolphin backend and app build, step by step.
Ask me for each key or account action you cannot do yourself. Never print, log or commit secret values;
write them only to Supabase secrets and to local.properties. Do not generate music.
Stop and tell me if a check fails.
```

## What you need

- An ElevenLabs account on a plan with Music API access, and credits.
- A Supabase account and the [Supabase CLI](https://supabase.com/docs/guides/cli) (`supabase login` done).
- `deno`, `jq`, `curl`, `openssl`.
- Android: Android Studio (its JBR as `JAVA_HOME`, the SDK path as `sdk.dir`). iOS: Xcode and `xcodegen`.

## 1. ElevenLabs keys

In ElevenLabs, Developers → API keys, create two restricted keys, each with a credit limit:

| Key | Permissions | Used for |
|---|---|---|
| Music key | Music generation | `ELEVENLABS_API_KEY`: `music-plan`, `music-compose` |
| Agents key | ElevenLabs Agents: write | `ELEVENLABS_AGENTS_KEY`: `agent-token`, `agent-conversation`, agent sync |

## 2. The agent

```sh
ELEVENLABS_API_KEY=<agents key> agent/scripts/create-agent.sh
```

It creates the four client tools and the "Composer" agent from `supabase/functions/_shared/agent/` and prints
`agent_id: agent_...`. Keep that id for step 4. The agent's prompt, tools and turn settings live in that
directory; `agent-token` re-applies them to the live agent (see `supabase/README.md`, "Agent sync").

The agent starts with the premade voice "Roger" and the LLM set in `agent.ts`. Change either in the ElevenLabs
dashboard (Agents → Composer); the sync fills them in only when they are empty, so your choice stays.

## 3. Supabase project

Create a project (dashboard or `supabase projects create`) and note its reference id, the `<ref>` in
`https://<ref>.supabase.co`.

## 4. Secrets

Make an access code for each person or group you let in, for example four random words or `openssl rand -hex 16`.
The app asks for the code on first start; `label` is what the function log shows when that code is used.

Set the function secrets:

```sh
supabase secrets set --project-ref <ref> \
  ELEVENLABS_API_KEY=<music key> \
  ELEVENLABS_AGENTS_KEY=<agents key> \
  AGENT_ID=<agent id from step 2> \
  ACCESS_CODES=me:<code>,friends:<another code>
```

To revoke a code, set `ACCESS_CODES` again without it.

## 5. Deploy

```sh
supabase functions deploy --no-verify-jwt --project-ref <ref>
```

To deploy on every push to `main` instead, add the repository secret `SUPABASE_ACCESS_TOKEN` (a Supabase personal
access token) and the variable `SUPABASE_PROJECT_REF`; `.github/workflows/supabase-deploy.yml` then deploys every
function when `supabase/` changes.

## 6. Check

```sh
curl -s -H "x-access-code: <code>" https://<ref>.supabase.co/functions/v1/access
curl -s -H "x-access-code: <code>" https://<ref>.supabase.co/functions/v1/agent-token
```

The answers are `{"access":"code"}` and `{"token":"..."}`. Neither call spends music credits; the second it also syncs the agent, and the function log
(Supabase dashboard → Edge Functions → `agent-token` → Logs) shows one `agent_sync` line with `"outcome":"patched"`
or `"unchanged"`.

| Answer | Meaning |
|---|---|
| `401 unauthorized` | the code is not in `ACCESS_CODES` |
| `500 server misconfigured` | a secret from step 4 is missing |
| `401`/`403 upstream rejected the request` | the Agents key lacks the permission, or `AGENT_ID` belongs to another account |
| `402 quota_exceeded` | the account or the key's credit limit is out of credits |
| `agent_sync` with `"outcome":"failed"` | the Agents key lacks write access; the token still works on the agent's current config |

## 7. Build the app

`local.properties` at the repository root (git-ignored):

```properties
sdk.dir=<Android SDK path>
PROXY_BASE_URL=https://<ref>.supabase.co/functions/v1
ACCESS_CODE=<code>
```

Android:

```sh
./gradlew :androidApp:installDebug
```

iOS:

```sh
scripts/gen-ios-secrets.sh && cd iosApp && xcodegen generate && open iosApp.xcodeproj
```

`ACCESS_CODE` is optional and used by debug builds only, so you are not asked for the code; release builds always
ask. No secret is compiled into the app: anyone with your build still needs a code, and removing a code from
`ACCESS_CODES` cuts it off at once. The ElevenLabs credit limits from step 1 cap what any code can spend.

## Updating

- Agent prompt and tools: edit `supabase/functions/_shared/agent/`, then deploy `agent-token`. Voice and LLM: the
  ElevenLabs dashboard.
- Proxy code: deploy the changed function.
