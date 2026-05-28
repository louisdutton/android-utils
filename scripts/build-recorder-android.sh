#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -f "$repo_root/apps/recorder/build.gradle.kts" ]]; then
  echo "Expected Recorder Android app at apps/recorder." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cd "$repo_root"

if [[ "$#" -eq 0 ]]; then
  set -- :apps:recorder:assembleDebug --no-daemon
fi

gradle "$@"
