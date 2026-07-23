#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop .#suite --no-write-lock-file --command "$0" "$@"
fi

port="${ESSENTIALS_REPO_PORT:-8080}"
if [[ ! "$port" =~ ^[0-9]+$ ]] || ((port < 1024 || port > 65535)); then
  echo "ESSENTIALS_REPO_PORT must be an unprivileged TCP port." >&2
  exit 2
fi

repository_output="${ESSENTIALS_REPO_OUTPUT:-/tmp/grapheneos-essentials-repository}"

repository_url="http://127.0.0.1:$port"
ESSENTIALS_REPO_BASE_URL="$repository_url" \
ESSENTIALS_REPO_OUTPUT="$repository_output" \
  "$repo_root/scripts/build-store-repository-local.sh"

devices=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  devices+=("$ANDROID_SERIAL")
else
  while IFS= read -r serial; do
    devices+=("$serial")
  done < <(adb devices | awk '$2 == "device" { print $1 }')
fi

if ((${#devices[@]} == 0)); then
  echo "No authorized Android device is connected." >&2
  exit 1
fi

for serial in "${devices[@]}"; do
  adb -s "$serial" reverse "tcp:$port" "tcp:$port"
  echo "ADB repository tunnel active for $serial: $repository_url"
done

echo "Serving $repository_output; press Ctrl-C to stop."
exec darkhttpd "$repository_output" --addr 127.0.0.1 --port "$port"
