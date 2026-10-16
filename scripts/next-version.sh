#!/usr/bin/env bash
set -euo pipefail

bump="${1:?usage: next-version.sh patch|minor|major}"

if [ "$bump" = patch ]; then
  last=$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || echo v0.0.0)
else
  last=$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n1)
  last="${last:-v0.0.0}"
fi

IFS=. read -r major minor patch <<< "${last#v}"
case "$bump" in
  major) echo "$((major + 1)).0.0" ;;
  minor) echo "$major.$((minor + 1)).0" ;;
  patch) echo "$major.$minor.$((patch + 1))" ;;
  *) echo "unknown bump: $bump" >&2; exit 1 ;;
esac
