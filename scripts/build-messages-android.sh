#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
messages_dir="$repo_root/apps/messages"

if [[ ! -f "$messages_dir/settings.gradle" ]]; then
  echo "Expected hard-forked Messages Android project at apps/messages." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cat > "$messages_dir/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

cd "$messages_dir"

if [[ "$#" -eq 0 ]]; then
  set -- :presentation:assembleDebug --no-daemon
fi

gradle "$@"
