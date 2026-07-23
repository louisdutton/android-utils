#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop .#suite --no-write-lock-file --command "$0" "$@"
fi

signing_base="${XDG_DATA_HOME:-${HOME}/.local/share}"
signing_dir="${ESSENTIALS_SIGNING_DIR:-$signing_base/grapheneos-essentials/signing}"
repository_key_dir="$signing_dir/repository"
repository_output="${ESSENTIALS_REPO_OUTPUT:-/tmp/grapheneos-essentials-repository}"
repository_cache="${ESSENTIALS_REPO_CACHE:-${XDG_CACHE_HOME:-${HOME}/.cache}/grapheneos-essentials/repository}"
release_cache="${ESSENTIALS_RELEASE_CACHE:-$signing_base/grapheneos-essentials/releases}"
repository_url="${ESSENTIALS_REPO_BASE_URL:-http://10.70.0.1:8080}"
apk_output="${ESSENTIALS_APK_OUTPUT:-/tmp/grapheneos-essentials-suite-apks}"
dependency_policy="$repo_root/gradle/essentials-dependency-versions.json"
manifest="$repo_root/apps/manifest.json"
store_app="$(jq -r '.apps[] | select(.build.type == "store") | .id' "$manifest")"

required_files=(
  "$manifest"
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

build_args=(--all)
if [[ "${ESSENTIALS_SKIP_SUITE_BUILD:-0}" == 1 ]]; then
  build_args=("$store_app")
elif [[ "${ESSENTIALS_REBUILD_SUITE:-0}" == 1 ]]; then
  build_args+=(--force)
fi

ESSENTIALS_REPO_BASE_URL="$repository_url" \
ESSENTIALS_APK_OUTPUT="$apk_output" \
  "$repo_root/scripts/build-essentials-apps-android.sh" "${build_args[@]}"

suite_apk_count="$(find "$apk_output" -maxdepth 1 -type f -name '*.apk' | wc -l | tr -d ' ')"
expected_suite_apk_count="$(jq '[.apps[] | select(.build.type != "store")] | length' "$manifest")"
if [[ "$suite_apk_count" != "$expected_suite_apk_count" ]]; then
  echo "Expected $expected_suite_apk_count suite APKs in $apk_output; found $suite_apk_count." >&2
  exit 1
fi

store_package="$(jq -r '.apps[] | select(.build.type == "store") | .package' "$manifest")"
store_apk="$release_cache/apks/$store_package.apk"
"$repo_root/scripts/verify-suite-dependency-versions.sh" \
  --policy "$dependency_policy" \
  --reports-dir "$release_cache/dependencies" \
  "$apk_output" \
  "$store_apk"

apk_paths=("$store_apk")
while IFS= read -r apk_path; do
  apk_paths+=("$apk_path")
done < <(find "$apk_output" -maxdepth 1 -type f -name '*.apk' | sort)

"$repo_root/scripts/build-store-repository.sh" \
  --private-key "$repository_key_dir/apps.0.sec" \
  --public-key "$repository_key_dir/apps.0.pub" \
  --config "$manifest" \
  --artifact-cache "$repository_cache" \
  --output "$repository_output" \
  "${apk_paths[@]}"
cp "$repo_root/apps/store/pages/index.html" "$repository_output/index.html"

echo "Repository ready at $repository_output for $repository_url"
