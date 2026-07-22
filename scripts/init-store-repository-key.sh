#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 /private/key/directory" >&2
  exit 2
fi

key_dir="$1"
mkdir -p "$key_dir"
key_dir="$(cd "$key_dir" && pwd)"

case "$key_dir" in
  /|"$repo_root"|"$repo_root"/*)
    echo "Store repository keys must be kept outside the source repository." >&2
    exit 1
    ;;
esac

private_key="$key_dir/apps.0.sec"
public_key="$key_dir/apps.0.pub"
if [[ -e "$private_key" || -e "$public_key" ]]; then
  echo "Refusing to overwrite an existing repository key." >&2
  exit 1
fi

umask 077
minisign -G -W -p "$public_key" -s "$private_key"

echo "Repository signing key created in $key_dir"
echo "Back up this directory before publishing the first release."
echo "ESSENTIALS_REPO_PUBLIC_KEY=$(sed -n '2p' "$public_key")"
echo "ESSENTIALS_REPO_KEY_VERSION=0"
