#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

asset_root="${RAIL_SCHEDULE_ASSET_ROOT:-/tmp/grapheneos-essentials-rail-assets}"
asset_dir="$asset_root/rail-schedules"
manifest="$asset_dir/gb-cornwall-rail.manifest.json"
package="$asset_dir/gb-cornwall-rail.sqlite.gzip"

case "${RAIL_SCHEDULE_SKIP_BUILD:-0}" in
  1|true|TRUE|yes|YES)
    ;;
  *)
    "$repo_root/scripts/build-cornwall-rail-assets.sh"
    ;;
esac

if [[ ! -f "$manifest" || ! -f "$package" ]]; then
  echo "Expected rail assets under $asset_dir." >&2
  echo "Run scripts/build-cornwall-rail-assets.sh first, or unset RAIL_SCHEDULE_SKIP_BUILD." >&2
  exit 1
fi

gradle_args=("$@")
if [[ "${#gradle_args[@]}" -eq 0 ]]; then
  gradle_args=(
    :app:assembleDebug
    -Parm64
    --no-daemon
    -Pnjobs="${COMAPS_NJOBS:-4}"
  )
fi

gradle_args+=("-PrailScheduleAssetDir=$asset_root")

if [[ -n "${ANDROID_HOME:-}" ]]; then
  "$repo_root/scripts/build-comaps-android.sh" "${gradle_args[@]}"
else
  nix develop --no-write-lock-file --command ./scripts/build-comaps-android.sh "${gradle_args[@]}"
fi
