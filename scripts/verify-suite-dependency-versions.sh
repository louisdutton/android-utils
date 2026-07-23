#!/usr/bin/env bash
set -euo pipefail

policy=""
reports_dir=""
paths=()
while (($# > 0)); do
  case "$1" in
    --policy)
      policy="${2:-}"
      shift 2
      ;;
    --reports-dir)
      reports_dir="${2:-}"
      shift 2
      ;;
    --*)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    *)
      paths+=("$1")
      shift
      ;;
  esac
done

[[ -f "$policy" ]] || { echo "Dependency policy not found: $policy" >&2; exit 1; }
((${#paths[@]} > 0)) || { echo "No APK paths supplied." >&2; exit 2; }
for command in jq unzip; do
  command -v "$command" >/dev/null || { echo "Missing verification command: $command" >&2; exit 1; }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/essentials-dependencies.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
apk_list="$work_dir/apks"
apk_versions="$work_dir/apk-versions.tsv"
resolved_versions="$work_dir/resolved-versions.tsv"
errors="$work_dir/errors"
: > "$apk_list"
: > "$apk_versions"
: > "$resolved_versions"
: > "$errors"

for path in "${paths[@]}"; do
  if [[ -d "$path" ]]; then
    find "$path" -maxdepth 1 -type f -name '*.apk' -print >> "$apk_list"
  elif [[ -f "$path" ]]; then
    echo "$path" >> "$apk_list"
  fi
done
sort -u "$apk_list" -o "$apk_list"
apk_count="$(wc -l < "$apk_list" | tr -d ' ')"
((apk_count > 0)) || { echo "No APKs found." >&2; exit 1; }

while IFS= read -r apk; do
  app="$(basename "$apk" .apk)"
  entries="$work_dir/entries"
  if ! unzip -Z1 "$apk" > "$entries"; then
    echo "invalid APK: $apk" >> "$errors"
    continue
  fi
  while IFS= read -r entry; do
    case "$entry" in
      META-INF/*.version)
        marker="${entry#META-INF/}"
        marker="${marker%.version}"
        version="$(unzip -p "$apk" "$entry" | tr -d '\r\n')"
        printf '%s\t%s\t%s\n' "$marker" "$version" "$app" >> "$apk_versions"
        ;;
    esac
  done < "$entries"
done < "$apk_list"

if [[ -n "$reports_dir" ]]; then
  while IFS= read -r apk; do
    app="$(basename "$apk" .apk)"
    case "$app" in
      digital.dutton.*)
        [[ -f "$reports_dir/$app.tsv" ]] ||
          echo "missing resolved dependency report for $app" >> "$errors"
        ;;
    esac
  done < "$apk_list"

  if [[ -d "$reports_dir" ]]; then
    while IFS= read -r report; do
      app="$(basename "$report" .tsv)"
      while IFS=$'\t' read -r coordinate version extra; do
        [[ -n "$coordinate" ]] || continue
        if [[ "$coordinate" == :* && -z "$version" ]]; then
          continue
        fi
        if [[ -z "$version" || -n "${extra:-}" ]]; then
          echo "invalid dependency report line in $report" >> "$errors"
          continue
        fi
        printf '%s\t%s\t%s\n' "$coordinate" "$version" "$app" >> "$resolved_versions"
      done < "$report"
    done < <(find "$reports_dir" -maxdepth 1 -type f -name '*.tsv' | sort)
  fi

  while IFS= read -r coordinate; do
    [[ -n "$coordinate" ]] ||
      continue
    versions="$(
      awk -F '\t' -v coordinate="$coordinate" '
        $1 == coordinate &&
        !(coordinate == "com.google.guava:listenablefuture" &&
          $2 == "9999.0-empty-to-avoid-conflict-with-guava") {
          print $2
        }
      ' "$resolved_versions" | sort -u
    )"
    version_count="$(printf '%s\n' "$versions" | sed '/^$/d' | wc -l | tr -d ' ')"
    if ((version_count > 1)); then
      echo "resolved $coordinate has multiple versions: $(tr '\n' ' ' <<< "$versions")" >> "$errors"
    fi
  done < <(cut -f1 "$resolved_versions" | sort -u)
fi

while IFS= read -r marker; do
  [[ -n "$marker" ]] || continue
  versions="$(awk -F '\t' -v marker="$marker" '$1 == marker {print $2}' "$apk_versions" | sort -u)"
  version_count="$(printf '%s\n' "$versions" | sed '/^$/d' | wc -l | tr -d ' ')"
  if ((version_count > 1)); then
    echo "$marker has multiple APK versions: $(tr '\n' ' ' <<< "$versions")" >> "$errors"
  fi
done < <(cut -f1 "$apk_versions" | sort -u)

while IFS=$'\t' read -r coordinate required; do
  if [[ -n "$reports_dir" ]]; then
    while IFS=$'\t' read -r actual app; do
      [[ -n "$actual" ]] || continue
      if [[ "$actual" != "$required" ]]; then
        echo "resolved $coordinate must be $required, found $actual in $app" >> "$errors"
      fi
    done < <(
      awk -F '\t' -v coordinate="$coordinate" '$1 == coordinate {print $2 "\t" $3}' \
        "$resolved_versions"
    )
  fi

  group="${coordinate%%:*}"
  module="${coordinate#*:}"
  if [[ "$group" == org.jetbrains.kotlinx && "$module" == kotlinx-coroutines-* ]]; then
    marker="kotlinx_coroutines_${module#kotlinx-coroutines-}"
  else
    marker="${group}_${module}"
  fi
  while IFS=$'\t' read -r actual app; do
    [[ -n "$actual" ]] || continue
    if [[ "$actual" != "$required" ]]; then
      echo "$coordinate must be $required, found $actual in $app" >> "$errors"
    fi
  done < <(awk -F '\t' -v marker="$marker" '$1 == marker {print $2 "\t" $3}' "$apk_versions")
done < <(jq -r 'to_entries[] | [.key, .value] | @tsv' "$policy")

if [[ -s "$errors" ]]; then
  echo "Dependency version policy violations:" >&2
  sort -u "$errors" | sed 's/^/  - /' >&2
  exit 1
fi

echo "Verified one dependency version across $apk_count APKs."
