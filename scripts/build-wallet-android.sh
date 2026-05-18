#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wallet_dir="$repo_root/apps/wallet"

if [[ ! -f "$wallet_dir/settings.gradle.kts" ]]; then
  echo "Expected hard-forked Wallet Android project at apps/wallet." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cat > "$wallet_dir/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

cd "$wallet_dir"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleDebug --no-daemon
fi

gradle "$@"
