#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  selected_apps=","
  build_all=0
  for argument in "$@"; do
    case "$argument" in
      --all) build_all=1 ;;
      --*) ;;
      *) selected_apps+="$argument," ;;
    esac
  done
  shell="$(
    ESSENTIALS_SELECTED_APPS="$selected_apps" \
    ESSENTIALS_BUILD_ALL="$build_all" \
      nix eval --raw --impure --expr "
        let
          manifest = builtins.fromJSON (builtins.readFile \"$repo_root/apps/manifest.json\");
          selected = builtins.getEnv \"ESSENTIALS_SELECTED_APPS\";
          all = builtins.getEnv \"ESSENTIALS_BUILD_ALL\" == \"1\";
          needsSuite = builtins.any (
            app:
              (app.shell or \"android\") == \"suite\" &&
              (all || builtins.match \".*,\${app.id},.*\" selected != null)
          ) manifest.apps;
        in if needsSuite then \"suite\" else \"android\"
      "
  )"
  exec nix develop ".#$shell" --no-write-lock-file --command "$0" "$@"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

manifest="$repo_root/apps/manifest.json"
signing_base="${XDG_DATA_HOME:-${HOME}/.local/share}"
signing_dir="${ESSENTIALS_SIGNING_DIR:-$signing_base/grapheneos-essentials/signing}"
signing_key="$signing_dir/essentials-store.keystore"
release_root="${ESSENTIALS_RELEASE_CACHE:-$signing_base/grapheneos-essentials/releases}"
staging="${ESSENTIALS_APK_OUTPUT:-/tmp/grapheneos-essentials-suite-apks}"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"

common_inputs="flake.nix flake.lock gradle/essentials-dependency-versions.json scripts/build-essentials-suite-android.sh scripts/essentials-dependency-policy.init.gradle scripts/essentials-release-version.init.gradle"
fingerprint_format="manifest-v1"

for command in jq sha256sum sort find; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing build command: $command" >&2
    exit 1
  fi
done
for required_file in "$manifest" "$signing_key" "$aapt"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing build input: $required_file" >&2
    exit 1
  fi
done

jq -e '
  (.apps | type == "array" and length > 0) and
  ([.apps[].id] | length == (unique | length)) and
  ([.apps[].package] | length == (unique | length))
' "$manifest" >/dev/null || { echo "Invalid app manifest: $manifest" >&2; exit 1; }

mapfile -t app_order < <(jq -r '.apps[].id' "$manifest")
store_app="$(jq -r '.apps[] | select(.build.type == "store") | .id' "$manifest")"

app_exists() {
  jq -e --arg app "$1" '.apps[] | select(.id == $app)' "$manifest" >/dev/null
}

app_package() {
  jq -r --arg app "$1" '.apps[] | select(.id == $app) | .package' "$manifest"
}

app_source_paths() {
  jq -r --arg app "$1" '
    . as $manifest
    | .apps[]
    | select(.id == $app)
    | (.sources[]),
      (.sourceGroups[]? as $group | $manifest.sourceGroups[$group][])
  ' "$manifest"
}

link_or_copy() {
  local source="$1"
  local destination="$2"
  mkdir -p "$(dirname "$destination")"
  if [[ -e "$destination" || -L "$destination" ]]; then
    rm -f "$destination"
  fi
  ln "$source" "$destination" 2>/dev/null || cp -p "$source" "$destination"
}

source_fingerprint() {
  local app="$1"
  local signing_files="$2"
  local configuration="$3"
  local -a paths
  local -a shared_paths
  mapfile -t paths < <(app_source_paths "$app")
  read -r -a shared_paths <<< "$common_inputs"
  paths+=("${shared_paths[@]}")
  {
    git -C "$repo_root" ls-tree -r HEAD -- "${paths[@]}"
    git -C "$repo_root" diff --binary --no-ext-diff HEAD -- "${paths[@]}"
    while IFS= read -r path; do
      case "/$path/" in
        */.cxx/*|*/.gradle/*|*/.idea/*|*/build/*|*/nativeOutputs/*|*/local.properties/)
          continue
          ;;
      esac
      printf '%s\0' "$path"
      if [[ -L "$repo_root/$path" ]]; then
        readlink "$repo_root/$path"
      else
        cat "$repo_root/$path"
      fi
      printf '\0'
    done < <(git -C "$repo_root" ls-files --others --exclude-standard -- "${paths[@]}" | sort -u)
    jq -cS --arg app "$app" '
      .apps[]
      | select(.id == $app)
      | {
          id,
          package,
          shell,
          sources,
          sourceGroups,
          build
        }
    ' "$manifest"
    local file
    for file in $signing_files; do
      cat "$file"
    done
    if [[ -n "$configuration" ]]; then
      while IFS=$'\t' read -r key value; do
        [[ -n "$key" ]] || continue
        printf '%s\0%s\0' "$key" "$value"
      done < <(printf '%s\n' "$configuration" | sort)
    fi
  } | sha256sum | awk '{print $1}'
}

apk_version() {
  local apk="$1"
  [[ -f "$apk" ]] || return 0
  "$aapt" dump badging "$apk" |
    sed -n "1s/.*versionCode='\\([^']*\\)'.*/\\1/p"
}

write_metadata() {
  local path="$1"
  local app="$2"
  local package="$3"
  local fingerprint="$4"
  local version_code="$5"
  local version_name="$6"
  local temporary="$path.tmp"
  jq -n \
    --arg app "$app" \
    --arg package "$package" \
    --arg fingerprint "$fingerprint" \
    --arg fingerprintFormat "$fingerprint_format" \
    --argjson versionCode "$version_code" \
    --arg versionName "$version_name" \
    '{
      app: $app,
      package: $package,
      sourceFingerprint: $fingerprint,
      fingerprintFormat: $fingerprintFormat,
      versionCode: $versionCode,
      versionName: $versionName
    }' > "$temporary"
  mv "$temporary" "$path"
}

next_version_code() {
  local metadata="$1"
  local apk="$2"
  local previous=0
  local staged=0
  local now
  [[ -f "$metadata" ]] && previous="$(jq -r '.versionCode // 0' "$metadata")"
  staged="$(apk_version "$apk")"
  staged="${staged:-0}"
  now="$(date -u +%s)"
  ((previous > staged)) || previous="$staged"
  ((previous > now)) || previous="$now"
  echo $((previous + 1))
}

cache_build_outputs() {
  local staged_apk="$1"
  local cached_apk="$2"
  local temporary_report="$3"
  local dependency_report="$4"
  [[ -f "$staged_apk" ]] || { echo "Build did not produce $staged_apk" >&2; exit 1; }
  [[ -f "$temporary_report" ]] || { echo "Build did not produce $temporary_report" >&2; exit 1; }
  mkdir -p "$(dirname "$cached_apk")" "$(dirname "$dependency_report")"
  cp -p "$staged_apk" "$cached_apk.tmp"
  mv "$cached_apk.tmp" "$cached_apk"
  sort -u "$temporary_report" > "$dependency_report.tmp"
  mv "$dependency_report.tmp" "$dependency_report"
  rm -f "$temporary_report"
}

build_regular_app() {
  local app="$1"
  local package
  package="$(app_package "$app")"
  local cached_apk="$release_root/apks/$package.apk"
  local dependency_report="$release_root/dependencies/$package.tsv"
  local metadata="$release_root/metadata/$package.json"
  local staged_apk="$staging/$package.apk"
  local fingerprint
  fingerprint="$(source_fingerprint "$app" "$signing_key" "")"

  if [[ "$force" == 0 && -f "$cached_apk" && -f "$dependency_report" && -f "$metadata" ]] &&
    {
      cached_fingerprint="$(jq -r '.sourceFingerprint // ""' "$metadata")"
      cached_format="$(jq -r '.fingerprintFormat // ""' "$metadata")"
      [[ "$cached_fingerprint" == "$fingerprint" || -z "$cached_format" || "$cached_format" == git-shell-v1 || "$cached_format" == git-shell-v2 ]]
    }; then
    if [[ "$cached_fingerprint" != "$fingerprint" || "$cached_format" != "$fingerprint_format" ]]; then
      write_metadata \
        "$metadata" "$app" "$package" "$fingerprint" \
        "$(jq -r '.versionCode' "$metadata")" \
        "$(jq -r '.versionName' "$metadata")"
    fi
    link_or_copy "$cached_apk" "$staged_apk"
    echo "Reused $app $(jq -r '.versionName // ""' "$metadata")"
    return 1
  fi

  local version_code
  local version_name
  local temporary_report="$dependency_report.tmp"
  version_code="$(next_version_code "$metadata" "$staged_apk")"
  version_name="$(date -u +%Y.%m.%d.%H%M%S)"
  mkdir -p "$(dirname "$dependency_report")"
  rm -f "$temporary_report"

  ESSENTIALS_APK_OUTPUT="$staging" \
  ESSENTIALS_KEEP_STAGED=1 \
  ESSENTIALS_RECLAIM_BUILD_SPACE=0 \
  ESSENTIALS_SKIP_DEPENDENCY_VERIFY=1 \
  ESSENTIALS_TARGET_APP="$app" \
  ESSENTIALS_VERSION_CODE="$version_code" \
  ESSENTIALS_VERSION_NAME="$version_name" \
  ESSENTIALS_DEPENDENCY_REPORT="$temporary_report" \
    "$repo_root/scripts/build-essentials-suite-android.sh"

  cache_build_outputs "$staged_apk" "$cached_apk" "$temporary_report" "$dependency_report"
  fingerprint="$(source_fingerprint "$app" "$signing_key" "")"
  mkdir -p "$(dirname "$metadata")"
  write_metadata "$metadata" "$app" "$package" "$fingerprint" "$version_code" "$version_name"
  echo "Built $app $version_name ($version_code)"
  return 0
}

build_store() {
  local app="$store_app"
  local package
  package="$(app_package "$app")"
  local password_file="$signing_dir/store-password.txt"
  local public_key_file="$signing_dir/repository/apps.0.pub"
  local cached_apk="$release_root/apks/$package.apk"
  local dependency_report="$release_root/dependencies/$package.tsv"
  local metadata="$release_root/metadata/$package.json"
  local build_script
  local output_apk
  build_script="$(jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.script' "$manifest")"
  output_apk="$repo_root/$(jq -r --arg app "$app" '.apps[] | select(.id == $app) | .build.output' "$manifest")"
  local repository_url="${ESSENTIALS_REPO_BASE_URL:-http://10.70.0.1:8080}"
  local repository_key_version="${ESSENTIALS_REPO_KEY_VERSION:-0}"
  local configuration
  configuration="$(printf 'repositoryKeyVersion\t%s\nrepositoryUrl\t%s' "$repository_key_version" "$repository_url")"
  for required_file in "$password_file" "$public_key_file"; do
    [[ -f "$required_file" ]] || { echo "Missing Store input: $required_file" >&2; exit 1; }
  done

  local fingerprint
  fingerprint="$(source_fingerprint "$app" "$signing_key $public_key_file" "$configuration")"
  if [[ "$force" == 0 && -f "$cached_apk" && -f "$dependency_report" && -f "$metadata" ]] &&
    {
      cached_fingerprint="$(jq -r '.sourceFingerprint // ""' "$metadata")"
      cached_format="$(jq -r '.fingerprintFormat // ""' "$metadata")"
      [[ "$cached_fingerprint" == "$fingerprint" || -z "$cached_format" || "$cached_format" == git-shell-v1 || "$cached_format" == git-shell-v2 ]]
    }; then
    if [[ "$cached_fingerprint" != "$fingerprint" || "$cached_format" != "$fingerprint_format" ]]; then
      write_metadata \
        "$metadata" "$app" "$package" "$fingerprint" \
        "$(jq -r '.versionCode' "$metadata")" \
        "$(jq -r '.versionName' "$metadata")"
    fi
    mkdir -p "$(dirname "$output_apk")"
    cp -p "$cached_apk" "$output_apk"
    echo "Reused $app $(jq -r '.versionName // ""' "$metadata")"
    return 1
  fi

  local version_code
  local version_name
  local password
  local public_key
  local temporary_report="$dependency_report.tmp"
  version_code="$(next_version_code "$metadata" "$output_apk")"
  version_name="$(date -u +%Y.%m.%d.%H%M%S)"
  password="$(tr -d '\r\n' < "$password_file")"
  public_key="$(sed -n '2p' "$public_key_file")"
  [[ -n "$public_key" ]] || { echo "Invalid repository public key." >&2; exit 1; }
  mkdir -p "$(dirname "$dependency_report")"
  rm -f "$temporary_report"

  gradle_args=(
    -I "$repo_root/scripts/essentials-dependency-policy.init.gradle"
    -Dorg.gradle.caching=true
  )
  if [[ "${ESSENTIALS_UPDATE_DEPENDENCY_VERIFICATION:-0}" == 1 ]]; then
    gradle_args+=(--write-verification-metadata sha256)
  fi
  gradle_args+=(:app:assembleRelease)

  ESSENTIALS_REPO_BASE_URL="$repository_url" \
  ESSENTIALS_REPO_PUBLIC_KEY="$public_key" \
  ESSENTIALS_REPO_KEY_VERSION="$repository_key_version" \
  ESSENTIALS_STORE_VERSION_CODE="$version_code" \
  ESSENTIALS_STORE_VERSION_NAME="$version_name" \
  ESSENTIALS_STORE_KEYSTORE_FILE="$signing_key" \
  ESSENTIALS_STORE_KEYSTORE_PASSWORD="$password" \
  ESSENTIALS_STORE_KEY_ALIAS=essentials-store \
  ESSENTIALS_STORE_KEY_PASSWORD="$password" \
  ESSENTIALS_DEPENDENCY_POLICY="$repo_root/gradle/essentials-dependency-versions.json" \
  ESSENTIALS_DEPENDENCY_REPORT="$temporary_report" \
    "$repo_root/$build_script" "${gradle_args[@]}"

  cache_build_outputs "$output_apk" "$cached_apk" "$temporary_report" "$dependency_report"
  fingerprint="$(source_fingerprint "$app" "$signing_key $public_key_file" "$configuration")"
  mkdir -p "$(dirname "$metadata")"
  write_metadata "$metadata" "$app" "$package" "$fingerprint" "$version_code" "$version_name"
  echo "Built $app $version_name ($version_code)"
  return 0
}

force=0
verify=1
all=0
selected=()
for argument in "$@"; do
  case "$argument" in
    --all) all=1 ;;
    --force) force=1 ;;
    --skip-suite-verify) verify=0 ;;
    -h|--help)
      echo "Usage: $0 (--all | APP...) [--force] [--skip-suite-verify]"
      exit 0
      ;;
    --*)
      echo "Unknown option: $argument" >&2
      exit 2
      ;;
    *)
      app_exists "$argument" || { echo "Unknown app: $argument" >&2; exit 2; }
      selected+=("$argument")
      ;;
  esac
done
if ((all == 1 && ${#selected[@]} > 0)) || ((all == 0 && ${#selected[@]} == 0)); then
  echo "Specify app names or --all." >&2
  exit 2
fi
((all == 0)) || selected=("${app_order[@]}")

mkdir -p "$staging" "$release_root/apks" "$release_root/dependencies" "$release_root/metadata"
built=0
for app in "${selected[@]}"; do
  if [[ "$app" == "$store_app" ]]; then
    if build_store; then
      built=$((built + 1))
    fi
  elif build_regular_app "$app"; then
    built=$((built + 1))
  fi
done

if ((verify == 1)); then
  verification_paths=("$staging")
  store_apk="$release_root/apks/$(app_package "$store_app").apk"
  [[ ! -f "$store_apk" ]] || verification_paths+=("$store_apk")
  "$repo_root/scripts/verify-suite-dependency-versions.sh" \
    --policy "$repo_root/gradle/essentials-dependency-versions.json" \
    --reports-dir "$release_root/dependencies" \
    "${verification_paths[@]}"
fi

echo "$built built, $((${#selected[@]} - built)) reused"
