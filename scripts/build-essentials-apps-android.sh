#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  shell=android
  for argument in "$@"; do
    if [[ "$argument" == "--all" || "$argument" == "maps" || "$argument" == "scores" ]]; then
      shell=suite
      break
    fi
  done
  exec nix develop ".#$shell" --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
exec python3 "$repo_root/scripts/build-essentials-apps.py" "$@"
