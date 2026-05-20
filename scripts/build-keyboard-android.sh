#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keyboard_dir="$repo_root/apps/keyboard"

if [[ ! -f "$keyboard_dir/settings.gradle" ]]; then
  echo "Expected hard-forked Keyboard Android project at apps/keyboard." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export VERSION_CODE="${VERSION_CODE:-1}"
export VERSION_NAME="${VERSION_NAME:-0.1.0-grapheneos}"
export BRANCH_NAME="${BRANCH_NAME:-grapheneos-essentials}"

cat > "$keyboard_dir/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

cd "$keyboard_dir"

if [[ "$#" -eq 0 ]]; then
  set -- :assembleStableDebug --no-daemon
fi

./gradlew "$@"
