#!/usr/bin/env bash
set -euo pipefail

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
: "${ELEVENLABS_API_KEY:?set ELEVENLABS_API_KEY}"

command -v deno >/dev/null || { echo "deno is required" >&2; exit 1; }
SRC="$(cd "$(dirname "$0")/../../supabase/functions/_shared/agent" && pwd)"
DIR="$(mktemp -d)"; trap 'rm -rf "$DIR"' EXIT
deno eval --quiet "
import { AGENT } from 'file://$SRC/agent.ts';
import { TOOLS } from 'file://$SRC/tools.ts';
await Deno.mkdir('$DIR/tools');
await Deno.writeTextFile('$DIR/agent.json', JSON.stringify(AGENT));
for (const t of TOOLS) await Deno.writeTextFile('$DIR/tools/' + t.name + '.json', JSON.stringify({ tool_config: t }));
"
API="https://api.elevenlabs.io/v1/convai"

post() {
  local out code
  out="$(mktemp)"
  code="$(curl -sS -o "$out" -w '%{http_code}' -X POST "$API/$1" \
    -H "xi-api-key: $ELEVENLABS_API_KEY" -H 'Content-Type: application/json' -d @"$2")"
  if [ "$code" != 200 ]; then echo "POST $1 failed ($code):" >&2; cat "$out" >&2; rm -f "$out"; exit 1; fi
  cat "$out"; rm -f "$out"
}

TOOL_IDS=()
for t in compose_track edit_section set_global undo_edit; do
  id="$(post tools "$DIR/tools/$t.json" | jq -r .id)"
  echo "tool $t: $id" >&2
  TOOL_IDS+=("$id")
done

BODY="$(mktemp)"
jq --argjson ids "$(printf '%s\n' "${TOOL_IDS[@]}" | jq -R . | jq -s .)" \
   '.conversation_config.agent.prompt.tool_ids = $ids' \
   "$DIR/agent.json" > "$BODY"

AGENT_ID="$(post agents/create "$BODY" | jq -r .agent_id)"
rm -f "$BODY"
echo "agent_id: $AGENT_ID"
