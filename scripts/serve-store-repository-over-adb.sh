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

signing_base="${XDG_DATA_HOME:-${HOME}/.local/share}"
signing_dir="${ESSENTIALS_SIGNING_DIR:-$signing_base/grapheneos-essentials/signing}"
repository_key_dir="$signing_dir/repository"
repository_output="${ESSENTIALS_REPO_OUTPUT:-/tmp/grapheneos-essentials-repository}"
repository_cache="${ESSENTIALS_REPO_CACHE:-${XDG_CACHE_HOME:-${HOME}/.cache}/grapheneos-essentials/repository}"
release_cache="${ESSENTIALS_RELEASE_CACHE:-$signing_base/grapheneos-essentials/releases}"
dependency_policy="$repo_root/gradle/essentials-dependency-versions.json"

required_files=(
  "$signing_dir/essentials-store.keystore"
  "$signing_dir/store-password.txt"
  "$repository_key_dir/apps.0.sec"
  "$repository_key_dir/apps.0.pub"
)
for required_file in "${required_files[@]}"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing signing input: $required_file" >&2
    exit 1
  fi
done

repository_url="http://127.0.0.1:$port"
apk_output="${ESSENTIALS_APK_OUTPUT:-/tmp/grapheneos-essentials-suite-apks}"

build_args=(--all)
if [[ "${ESSENTIALS_SKIP_SUITE_BUILD:-0}" == 1 ]]; then
  build_args=(store)
elif [[ "${ESSENTIALS_REBUILD_SUITE:-0}" == 1 ]]; then
  build_args+=(--force)
fi
ESSENTIALS_REPO_BASE_URL="$repository_url" \
ESSENTIALS_APK_OUTPUT="$apk_output" \
  "$repo_root/scripts/build-essentials-apps-android.sh" "${build_args[@]}"

if [[ "${ESSENTIALS_SKIP_SUITE_BUILD:-0}" == 1 ]]; then
  echo "Reusing cached suite APKs from $apk_output"
else
  echo "Suite cache is current in $apk_output"
fi

suite_apk_count="$(find "$apk_output" -maxdepth 1 -type f -name '*.apk' | wc -l | tr -d ' ')"
if [[ "$suite_apk_count" != 14 ]]; then
  echo "Expected 14 suite APKs in $apk_output; found $suite_apk_count." >&2
  exit 1
fi

"$repo_root/scripts/verify-suite-dependency-versions.py" \
  --policy "$dependency_policy" \
  --reports-dir "$release_cache/dependencies" \
  "$apk_output" \
  "$release_cache/apks/digital.dutton.essentials.store.apk"

apk_paths=(
  "$repo_root/apps/store/app/build/outputs/apk/release/app-release.apk"
)
while IFS= read -r apk_path; do
  apk_paths+=("$apk_path")
done < <(find "$apk_output" -maxdepth 1 -type f -name '*.apk' | sort)

"$repo_root/scripts/build-store-repository.py" \
  --private-key "$repository_key_dir/apps.0.sec" \
  --public-key "$repository_key_dir/apps.0.pub" \
  --config "$repo_root/apps/store/repository-packages.toml" \
  --artifact-cache "$repository_cache" \
  --output "$repository_output" \
  "${apk_paths[@]}"
cp "$repo_root/apps/store/pages/index.html" "$repository_output/index.html"

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
exec python3 -m http.server "$port" --bind 127.0.0.1 --directory "$repository_output"
