#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vault_dir="$repo_root/apps/vault"

if [[ ! -f "$vault_dir/settings.gradle" ]]; then
  echo "Expected hard-forked Vault Android project at apps/vault." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cat > "$vault_dir/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

cd "$vault_dir"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleLibreDebug --no-daemon
fi

./gradlew "$@"
