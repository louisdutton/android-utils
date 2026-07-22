#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop .#suite --no-write-lock-file --command "$0" "$@"
fi

signing_base="${XDG_DATA_HOME:-${HOME}/.local/share}"
signing_dir="${ESSENTIALS_SIGNING_DIR:-$signing_base/grapheneos-essentials/signing}"
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

for required_file in "$keystore" "$password_file" "$init_script" "$dependency_init_script" "$dependency_policy"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing release input: $required_file" >&2
    exit 1
  fi
done

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
known_apps=(assistant calendar documents keyboard learn maps messages notes piano recorder scores vault wallet weather)
if [[ -n "$target_app" ]] && [[ ! " ${known_apps[*]} " =~ " $target_app " ]]; then
  echo "Unknown ESSENTIALS_TARGET_APP: $target_app" >&2
  exit 2
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

root_tasks=()
is_selected assistant && root_tasks+=(:apps:assistant:assembleRelease)
is_selected calendar && root_tasks+=(:apps:calendar:assembleRelease)
is_selected documents && root_tasks+=(:apps:documents:assembleRelease)
is_selected learn && root_tasks+=(:apps:trainer:assembleRelease)
is_selected notes && root_tasks+=(:apps:notes:assembleRelease)
is_selected piano && root_tasks+=(:apps:pitch:assembleRelease)
is_selected recorder && root_tasks+=(:apps:recorder:assembleRelease)
is_selected scores && root_tasks+=(:apps:scores:assembleRelease)
is_selected weather && root_tasks+=(:apps:weather:assembleRelease)
if [[ "${ESSENTIALS_SKIP_ROOT_BUILD:-0}" != 1 ]] && ((${#root_tasks[@]} > 0)); then
  gradle -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" "${root_tasks[@]}"
  is_selected assistant && stage_package digital.dutton.essentials.assistant "$repo_root/apps/assistant"
  is_selected calendar && stage_package digital.dutton.essentials.calendar "$repo_root/apps/calendar"
  is_selected documents && stage_package digital.dutton.essentials.documents "$repo_root/apps/documents"
  is_selected learn && stage_package digital.dutton.essentials.learn "$repo_root/apps/trainer"
  is_selected notes && stage_package digital.dutton.essentials.notes "$repo_root/apps/notes"
  is_selected piano && stage_package digital.dutton.essentials.piano "$repo_root/apps/pitch"
  is_selected recorder && stage_package digital.dutton.essentials.recorder "$repo_root/apps/recorder"
  is_selected scores && stage_package digital.dutton.essentials.scores "$repo_root/apps/scores"
  is_selected weather && stage_package digital.dutton.essentials.weather "$repo_root/apps/weather"
fi

if is_selected keyboard && [[ "${ESSENTIALS_SKIP_KEYBOARD_BUILD:-0}" != 1 ]]; then
  "$repo_root/scripts/build-keyboard-android.sh" -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" :assembleStableRelease
  stage_package digital.dutton.essentials.keyboard "$repo_root/apps/keyboard"
  reclaim_build_space "$repo_root/apps/keyboard/build" "$repo_root/apps/keyboard/.cxx"
fi

if is_selected messages && [[ "${ESSENTIALS_SKIP_MESSAGES_BUILD:-0}" != 1 ]]; then
  "$repo_root/scripts/build-messages-android.sh" -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" :presentation:assembleRelease -x :presentation:lintVitalRelease
  stage_package digital.dutton.essentials.messages "$repo_root/apps/messages"
  reclaim_build_space \
    "$repo_root/apps/messages/android-smsmms/build" \
    "$repo_root/apps/messages/common/build" \
    "$repo_root/apps/messages/data/build" \
    "$repo_root/apps/messages/domain/build" \
    "$repo_root/apps/messages/presentation/build"
fi

if is_selected vault && [[ "${ESSENTIALS_SKIP_VAULT_BUILD:-0}" != 1 ]]; then
  "$repo_root/scripts/build-vault-android.sh" -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" :app:assembleLibreRelease
  stage_package digital.dutton.essentials.vault "$repo_root/apps/vault"
  reclaim_build_space "$repo_root/apps/vault/app/build"
fi

if is_selected wallet && [[ "${ESSENTIALS_SKIP_WALLET_BUILD:-0}" != 1 ]]; then
  "$repo_root/scripts/build-wallet-android.sh" -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" :app:assembleRelease
  stage_package digital.dutton.essentials.wallet "$repo_root/apps/wallet"
  reclaim_build_space "$repo_root/apps/wallet/app/build"
fi

if is_selected maps && [[ "${ESSENTIALS_SKIP_MAPS_BUILD:-0}" != 1 ]]; then
  "$repo_root/scripts/build-comaps-android.sh" -I "$init_script" -I "$dependency_init_script" "${gradle_limits[@]}" :app:assembleRelease -x :app:lintVitalRelease -Parm64 -Pnjobs="${COMAPS_NJOBS:-4}"
  stage_package digital.dutton.essentials.maps "$repo_root/apps/maps"
  reclaim_build_space "$repo_root/apps/maps/android/app/build"
fi

if [[ "${ESSENTIALS_SKIP_DEPENDENCY_VERIFY:-0}" != 1 ]]; then
  "$repo_root/scripts/verify-suite-dependency-versions.py" \
    --policy "$dependency_policy" \
    "$apk_output"
fi

echo "Signed APKs are ready in $apk_output"
