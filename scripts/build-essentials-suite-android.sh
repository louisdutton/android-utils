#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop .#suite --no-write-lock-file --command "$0" "$@"
fi

signing_base="${XDG_DATA_HOME:-${HOME}/.local/share}"
signing_dir="${ESSENTIALS_SIGNING_DIR:-$signing_base/grapheneos-essentials/signing}"
manifest="$repo_root/apps/manifest.json"
keystore="$signing_dir/essentials-store.keystore"
password_file="$signing_dir/store-password.txt"
apk_output="${ESSENTIALS_APK_OUTPUT:-/tmp/grapheneos-essentials-suite-apks}"
version_code="${ESSENTIALS_VERSION_CODE:-$(date -u +%s)}"
version_name="${ESSENTIALS_VERSION_NAME:-$(date -u +%Y.%m.%d.%H%M%S)}"
init_script="$repo_root/scripts/essentials-release-version.init.gradle"
dependency_init_script="$repo_root/scripts/essentials-dependency-policy.init.gradle"
dependency_policy="$repo_root/gradle/essentials-dependency-versions.json"
gradle_limits=(
  -Dorg.gradle.jvmargs=-Xmx4096m\ -Dfile.encoding=UTF-8
  -Dorg.gradle.caching=true
  -Dorg.gradle.parallel=true
  --max-workers="${ESSENTIALS_GRADLE_WORKERS:-4}"
)

for required_file in "$manifest" "$keystore" "$password_file" "$init_script" "$dependency_init_script" "$dependency_policy"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing release input: $required_file" >&2
    exit 1
  fi
done
command -v jq >/dev/null || { echo "Missing build command: jq" >&2; exit 1; }

if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]] || ((version_code > 2147483647)); then
  echo "ESSENTIALS_VERSION_CODE must be a positive signed 32-bit integer." >&2
  exit 2
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ESSENTIALS_VERSION_CODE="$version_code"
export ESSENTIALS_VERSION_NAME="$version_name"
export ESSENTIALS_ABI="${ESSENTIALS_ABI:-arm64-v8a}"
export VERSION_CODE="$version_code"
export VERSION_NAME="$version_name"
export ESSENTIALS_DEPENDENCY_POLICY="$dependency_policy"

build_tools="$ANDROID_HOME/build-tools/36.0.0"
aapt="$build_tools/aapt"
zipalign="$build_tools/zipalign"
apksigner="$build_tools/apksigner"
for tool in "$aapt" "$zipalign" "$apksigner"; do
  if [[ ! -x "$tool" ]]; then
    echo "Missing Android build tool: $tool" >&2
    exit 1
  fi
done

mkdir -p "$apk_output"
if [[ "${ESSENTIALS_KEEP_STAGED:-0}" != 1 ]]; then
  find "$apk_output" -maxdepth 1 -type f -name '*.apk' -delete
fi
password="$(tr -d '\r\n' < "$password_file")"
target_app="${ESSENTIALS_TARGET_APP:-}"
mapfile -t known_apps < <(
  jq -r '.apps[] | select(.build.type != "store") | .id' "$manifest"
)
if [[ -n "$target_app" ]]; then
  target_known=0
  for app in "${known_apps[@]}"; do
    if [[ "$app" == "$target_app" ]]; then
      target_known=1
      break
    fi
  done
  if ((target_known == 0)); then
    echo "Unknown ESSENTIALS_TARGET_APP: $target_app" >&2
    exit 2
  fi
fi

is_selected() {
  [[ -z "$target_app" || "$target_app" == "$1" ]]
}

stage_package() {
  local package_name="$1"
  local search_root="$2"
  local candidate=""
  local apk
  local badging
  while IFS= read -r apk; do
    badging="$("$aapt" dump badging "$apk" 2>/dev/null | sed -n '1p')"
    if grep -Fq "name='$package_name'" <<< "$badging"; then
      if [[ -z "$candidate" || "$apk" -nt "$candidate" ]]; then
        candidate="$apk"
      fi
    fi
  done < <(find "$search_root" -path '*/build/outputs/apk/*' -type f -name '*.apk')

  if [[ -z "$candidate" ]]; then
    echo "No built APK found for $package_name under $search_root" >&2
    exit 1
  fi

  local work_dir
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/essentials-sign.XXXXXX")"
  "$zipalign" -f -p 4 "$candidate" "$work_dir/aligned.apk"
  ESSENTIALS_APKSIGNER_PASSWORD="$password" "$apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias essentials-store \
    --ks-pass env:ESSENTIALS_APKSIGNER_PASSWORD \
    --key-pass env:ESSENTIALS_APKSIGNER_PASSWORD \
    --out "$apk_output/$package_name.apk" \
    "$work_dir/aligned.apk"
  "$apksigner" verify "$apk_output/$package_name.apk"
  rm -rf "$work_dir"
  echo "Staged $package_name"
}

reclaim_build_space() {
  [[ "${ESSENTIALS_RECLAIM_BUILD_SPACE:-0}" == 1 ]] || return 0
  local target
  for target in "$@"; do
    if [[ -d "$target" ]]; then
      find "$target" -depth -delete
    fi
  done
}

echo "Building Essentials suite $version_name ($version_code)"

root_apps=()
root_tasks=()
while IFS= read -r app; do
  if is_selected "$app"; then
    root_apps+=("$app")
    root_tasks+=("$(
      jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.task' "$manifest"
    )")
  fi
done < <(jq -r '.apps[] | select(.build.type == "root") | .id' "$manifest")

if [[ "${ESSENTIALS_SKIP_ROOT_BUILD:-0}" != 1 ]] && ((${#root_tasks[@]} > 0)); then
  gradle -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" "${root_tasks[@]}"
  for app in "${root_apps[@]}"; do
    package="$(
      jq -r --arg app "$app" '.apps[] | select(.id == $app) | .package' "$manifest"
    )"
    stage_root="$(
      jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.stageRoot' "$manifest"
    )"
    stage_package "$package" "$repo_root/$stage_root"
  done
fi

while IFS= read -r app; do
  is_selected "$app" || continue
  skip_variable="ESSENTIALS_SKIP_${app^^}_BUILD"
  [[ "${!skip_variable:-0}" != 1 ]] || continue

  build_script="$(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.script' "$manifest"
  )"
  mapfile -t build_arguments < <(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.arguments[]' "$manifest"
  )
  parallel_environment="$(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.parallelism.environment // empty' "$manifest"
  )"
  if [[ -n "$parallel_environment" ]]; then
    parallel_property="$(
      jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.parallelism.property' "$manifest"
    )"
    parallel_default="$(
      jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.parallelism.default' "$manifest"
    )"
    build_arguments+=("-P$parallel_property=${!parallel_environment:-$parallel_default}")
  fi

  "$repo_root/$build_script" \
    -I "$init_script" \
    -I "$dependency_init_script" \
    "${gradle_limits[@]}" \
    "${build_arguments[@]}"

  package="$(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .package' "$manifest"
  )"
  stage_root="$(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.stageRoot' "$manifest"
  )"
  stage_package "$package" "$repo_root/$stage_root"

  reclaim_paths=()
  while IFS= read -r path; do
    reclaim_paths+=("$repo_root/$path")
  done < <(
    jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.reclaim[]?' "$manifest"
  )
  reclaim_build_space "${reclaim_paths[@]}"
done < <(jq -r '.apps[] | select(.build.type == "standalone") | .id' "$manifest")

if [[ "${ESSENTIALS_SKIP_DEPENDENCY_VERIFY:-0}" != 1 ]]; then
  "$repo_root/scripts/verify-suite-dependency-versions.sh" \
    --policy "$dependency_policy" \
    "$apk_output"
fi

echo "Signed APKs are ready in $apk_output"
