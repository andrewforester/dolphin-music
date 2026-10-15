#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/iosApp/Secrets.xcconfig"

prop() { [ -f "$1" ] && grep -E "^$2=" "$1" | head -n1 | cut -d= -f2- | tr -d '\r' || true; }

proxy="$(prop "$root/local.properties" PROXY_BASE_URL)"
proxy="${proxy:-$(prop "$root/gradle.properties" voiceproducer.proxyBaseUrl)}"

[ -n "$proxy" ] || { echo "PROXY_BASE_URL is missing in local.properties and gradle.properties" >&2; exit 1; }

proxy="$(printf '%s' "$proxy" | sed 's#//#/$()/#g')"

echo "PROXY_BASE_URL = $proxy" > "$out"
echo "wrote $out"
