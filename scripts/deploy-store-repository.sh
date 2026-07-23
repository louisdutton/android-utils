#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
remote_host="${1:-Mini}"
remote_root="${ESSENTIALS_REMOTE_REPO_ROOT:-/var/lib/essentials-repository}"
repository_url="${ESSENTIALS_REPO_BASE_URL:-http://10.70.0.1:8080}"
repository_output="${ESSENTIALS_REPO_OUTPUT:-/tmp/grapheneos-essentials-repository}"

if [[ "$remote_host" == -* ]] || [[ ! "$remote_root" =~ ^/[A-Za-z0-9._/-]+$ ]] || [[ "$remote_root" == "/" ]]; then
  echo "Unsafe deployment destination." >&2
  exit 2
fi

ESSENTIALS_REPO_BASE_URL="$repository_url" \
ESSENTIALS_REPO_OUTPUT="$repository_output" \
  "$repo_root/scripts/build-store-repository-local.sh"

metadata="$repository_output/metadata.1.json"
if [[ ! -s "$metadata" ]]; then
  echo "Repository metadata was not generated." >&2
  exit 1
fi

metadata_hash="$(shasum -a 256 "$metadata" | awk '{print substr($1, 1, 12)}')"
release_id="$(date -u +%Y%m%dT%H%M%SZ)-$metadata_hash"
remote_release="$remote_root/releases/$release_id"

ssh -- "$remote_host" "test -d '$remote_root/releases'"

rsync_args=(
  --recursive
  --links
  --times
  --delete
  --delay-updates
  "--chmod=Du=rwx,Dg=rwxs,Do=,Fu=rw,Fg=r,Fo="
)
if ssh -- "$remote_host" "test -L '$remote_root/current'"; then
  rsync_args+=(--link-dest="$remote_root/current")
fi

ssh -- "$remote_host" "mkdir '$remote_release'"
rsync "${rsync_args[@]}" "$repository_output/" "$remote_host:$remote_release/"
ssh -- "$remote_host" \
  "test -s '$remote_release/metadata.1.json' && \
   ln -s 'releases/$release_id' '$remote_root/.current-$release_id' && \
   mv -Tf '$remote_root/.current-$release_id' '$remote_root/current'"

ssh -- "$remote_host" \
  "curl --fail --silent --show-error '$repository_url/metadata.1.json' >/dev/null"

echo "Published $release_id to $repository_url"
echo "Rollback target: $remote_release"
