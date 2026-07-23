#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
private_key=""
public_key=""
key_version=0
config=""
artifact_cache=""
output=""
apks=()

while (($# > 0)); do
  case "$1" in
    --private-key) private_key="${2:-}"; shift 2 ;;
    --public-key) public_key="${2:-}"; shift 2 ;;
    --key-version) key_version="${2:-}"; shift 2 ;;
    --config) config="${2:-}"; shift 2 ;;
    --artifact-cache) artifact_cache="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) apks+=("$1"); shift ;;
  esac
done

for required in "$private_key" "$public_key" "$artifact_cache" "$output"; do
  [[ -n "$required" ]] || { echo "Missing repository argument." >&2; exit 2; }
done
[[ "$key_version" =~ ^[0-9]+$ ]] || { echo "Invalid key version." >&2; exit 2; }
((${#apks[@]} > 0)) || { echo "No APKs supplied." >&2; exit 2; }
for path in "$private_key" "$public_key" "${apks[@]}"; do
  [[ -f "$path" ]] || { echo "File not found: $path" >&2; exit 1; }
done
command -v jq >/dev/null || { echo "Missing repository command: jq" >&2; exit 1; }
if [[ -n "$config" ]]; then
  [[ -f "$config" ]] || { echo "Repository configuration not found: $config" >&2; exit 1; }
  jq -e '.apps | type == "array"' "$config" >/dev/null ||
    { echo "Invalid repository configuration: $config" >&2; exit 1; }
fi

for command in aapt2 apksigner gzip jq minisign sha256sum; do
  if [[ "$command" == aapt2 || "$command" == apksigner ]]; then
    continue
  fi
  command -v "$command" >/dev/null || { echo "Missing repository command: $command" >&2; exit 1; }
done
aapt2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
for tool in "$aapt2" "$apksigner"; do
  [[ -x "$tool" ]] || { echo "Missing Android build tool: $tool" >&2; exit 1; }
done

case "$output" in
  /|"${HOME}"|"${HOME}/")
    echo "Refusing unsafe repository output: $output" >&2
    exit 1
    ;;
esac
if [[ -L "$output" ]]; then
  echo "Refusing to replace repository symlink: $output" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")" "$artifact_cache/gzip"
output_parent="$(cd "$(dirname "$output")" && pwd)"
output_name="$(basename "$output")"
output="$output_parent/$output_name"
staging="$(mktemp -d "$output_parent/.${output_name}.tmp.XXXXXX")"
cleanup() {
  if [[ -n "${staging:-}" && -d "$staging" ]]; then
    rm -rf "$staging"
  fi
}
trap cleanup EXIT

link_or_copy() {
  local source="$1"
  local destination="$2"
  ln "$source" "$destination" 2>/dev/null || cp -p "$source" "$destination"
}

file_size() {
  stat -c '%s' "$1"
}

metadata="$staging/metadata.1.json"
jq -cn --argjson time "$(date -u +%s)" \
  '{time: $time, packages: {}, fsVerityCerts: {}}' > "$metadata"

for apk in "${apks[@]}"; do
  badging="$("$aapt2" dump badging "$apk")"
  package_line="$(sed -n '1p' <<< "$badging")"
  package_name="$(sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" <<< "$package_line")"
  version_code="$(sed -n "s/^package: name='[^']*' versionCode='\\([^']*\\)'.*/\\1/p" <<< "$package_line")"
  version_name="$(sed -n "s/^package: name='[^']*' versionCode='[^']*' versionName='\\([^']*\\)'.*/\\1/p" <<< "$package_line")"
  label="$(sed -n "s/^application-label:'\\(.*\\)'$/\\1/p" <<< "$badging" | head -1)"
  min_sdk="$(sed -n "s/^\\(minSdkVersion\\|sdkVersion\\):'\\([0-9]*\\)'$/\\2/p" <<< "$badging" | head -1)"
  abis="$(sed -n "s/^native-code:\\(.*\\)$/\\1/p" <<< "$badging" | tr -d "'")"
  if [[ -z "$package_name" || -z "$version_code" || -z "$label" || -z "$min_sdk" ]]; then
    echo "Unable to inspect APK: $apk" >&2
    exit 1
  fi
  [[ "$(jq -r --arg package "$package_name" '.packages[$package] == null' "$metadata")" == true ]] ||
    { echo "Duplicate package: $package_name" >&2; exit 1; }

  certs="$(
    "$apksigner" verify --print-certs --verbose "$apk" |
      sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' |
      tr '[:upper:]' '[:lower:]' |
      sort -u
  )"
  cert_count="$(printf '%s\n' "$certs" | sed '/^$/d' | wc -l | tr -d ' ')"
  ((cert_count == 1)) || { echo "Expected one APK signer: $apk" >&2; exit 1; }
  certificate="$certs"

  if [[ -n "$config" ]]; then
    channel="$(jq -r --arg package "$package_name" '[.apps[] | select(.package == $package)][0].repository.channel // "stable"' "$config")"
    description="$(jq -r --arg package "$package_name" '[.apps[] | select(.package == $package)][0].repository.description // ""' "$config")"
    icon="$(jq -r --arg package "$package_name" '[.apps[] | select(.package == $package)][0].repository.icon // ""' "$config")"
  else
    channel=stable
    description=""
    icon=""
  fi

  package_dir="$staging/packages/$package_name"
  version_dir="$package_dir/$version_code"
  mkdir -p "$version_dir"
  apk_hash="$(sha256sum "$apk" | awk '{print $1}')"
  copied_apk="$version_dir/base.apk"
  compressed_apk="$version_dir/base.apk.gz"
  cached_gzip="$artifact_cache/gzip/$apk_hash.apk.gz"
  link_or_copy "$apk" "$copied_apk"
  if [[ ! -f "$cached_gzip" ]]; then
    gzip -n -9 -c "$copied_apk" > "$cached_gzip.tmp"
    mv "$cached_gzip.tmp" "$cached_gzip"
  fi
  link_or_copy "$cached_gzip" "$compressed_apk"

  abis_json="$(
    for abi in $abis; do
      echo "$abi"
    done | jq -Rsc 'split("\n") | map(select(length > 0))'
  )"
  variant="$(
    jq -cn \
      --argjson versionCode "$version_code" \
      --arg versionName "${version_name:-$version_code}" \
      --arg label "$label" \
      --arg channel "$channel" \
      --argjson minSdk "$min_sdk" \
      --arg apkHash "$apk_hash" \
      --argjson apkSize "$(file_size "$copied_apk")" \
      --argjson apkGzSize "$(file_size "$compressed_apk")" \
      --argjson abis "$abis_json" \
      --arg description "$description" \
      '{
        versionCode: $versionCode,
        versionName: $versionName,
        label: $label,
        channel: $channel,
        minSdk: $minSdk,
        apks: ["base.apk"],
        apkHashes: [$apkHash],
        apkSizes: [$apkSize],
        apkGzSizes: [$apkGzSize]
      }
      + (if ($abis | length) > 0 then {abis: $abis} else {} end)
      + (if $description != "" then {description: $description} else {} end)'
  )"
  common="$(
    jq -cn \
      --arg certificate "$certificate" \
      --arg description "$description" \
      '{
        source: "GrapheneOS_build",
        signatures: [$certificate],
        requestUpdateOwnership: true,
        variants: {}
      }
      + (if $description != "" then {description: $description} else {} end)'
  )"

  if [[ -n "$icon" ]]; then
    [[ "$icon" == /* ]] || icon="$repo_root/$icon"
    [[ -f "$icon" && "$icon" == *.webp ]] ||
      { echo "Repository icon must be WebP: $icon" >&2; exit 1; }
    cp -p "$icon" "$package_dir/icon.webp"
    common="$(jq -c '. + {iconType: "webp"}' <<< "$common")"
  fi

  jq -cS \
    --arg package "$package_name" \
    --arg version "$version_code" \
    --argjson common "$common" \
    --argjson variant "$variant" \
    '.packages[$package] = ($common | .variants[$version] = $variant)' \
    "$metadata" > "$metadata.next"
  mv "$metadata.next" "$metadata"
done

jq -cS . "$metadata" > "$metadata.final"
mv "$metadata.final" "$metadata"
signature_file="$staging/metadata.1.json.$key_version.sig"
minisign -S -l -s "$private_key" -m "$metadata" -x "$signature_file"
signature="$(sed -n '2p' "$signature_file")"
[[ -n "$signature" ]] || { echo "Repository signature was not generated." >&2; exit 1; }
{
  cat "$metadata"
  printf '\n%s\n' "$signature"
} > "$staging/metadata.1.$key_version.sjson"

backup="$output_parent/.${output_name}.previous"
if [[ -e "$backup" ]]; then
  rm -rf "$backup"
fi
if [[ -e "$output" ]]; then
  mv "$output" "$backup"
fi
if ! mv "$staging" "$output"; then
  [[ -e "$output" || ! -e "$backup" ]] || mv "$backup" "$output"
  exit 1
fi
staging=""
[[ ! -e "$backup" ]] || rm -rf "$backup"

package_count="$(jq '.packages | length' "$output/metadata.1.json")"
echo "Repository: $output"
echo "Packages: $package_count"
echo "ESSENTIALS_REPO_PUBLIC_KEY=$(sed -n '2p' "$public_key")"
echo "ESSENTIALS_REPO_KEY_VERSION=$key_version"
