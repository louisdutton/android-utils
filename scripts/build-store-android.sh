#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
store_root="$repo_root/apps/store"

if [[ ! -f "$store_root/app/build.gradle.kts" ]]; then
  echo "Expected Essentials Android app at apps/store." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cd "$store_root"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleDebug --no-daemon
fi

exec ./gradlew "$@"
