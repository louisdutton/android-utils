#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

asset_root="${RAIL_SCHEDULE_ASSET_ROOT:-/tmp/grapheneos-essentials-rail-assets}"
output_dir="$asset_root/rail-schedules"

builder_args=(
  --output-dir "$output_dir"
  --omit-sqlite
)

if [[ -n "${RAIL_SCHEDULE_SOURCE_ZIP:-}" ]]; then
  builder_args+=(--source-zip "$RAIL_SCHEDULE_SOURCE_ZIP")
fi

if [[ -n "${RAIL_SCHEDULE_SOURCE_URL:-}" ]]; then
  builder_args+=(--source-url "$RAIL_SCHEDULE_SOURCE_URL")
fi

if [[ -n "${RAIL_SCHEDULE_CACHE_DIR:-}" ]]; then
  builder_args+=(--cache-dir "$RAIL_SCHEDULE_CACHE_DIR")
fi

case "${RAIL_SCHEDULE_EMIT_FILTERED_GTFS:-0}" in
  1|true|TRUE|yes|YES)
    builder_args+=(--emit-filtered-gtfs)
    ;;
esac

"$repo_root/scripts/build-cornwall-rail-schedule.py" "${builder_args[@]}" "$@"

echo
echo "Rail schedule asset root: $asset_root"
echo "Rail schedule asset dir:  $output_dir"

manifest="$output_dir/gb-cornwall-rail.manifest.json"
package="$output_dir/gb-cornwall-rail.sqlite.gzip"

if [[ -f "$manifest" ]]; then
  echo "Manifest:                 $manifest"
fi

if [[ -f "$package" ]]; then
  echo "Package:                  $package"
fi
