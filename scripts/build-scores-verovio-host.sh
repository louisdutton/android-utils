#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${VEROVIO_SRC_DIR:-}" || ! -f "${VEROVIO_SRC_DIR:-}/cmake/CMakeLists.txt" ]]; then
  exec nix develop --no-write-lock-file --command "$0" "$@"
fi

host_dir="${SCORES_VEROVIO_HOST_DIR:-/tmp/grapheneos-essentials-scores-verovio-host}"
source_dir="$host_dir/source"
build_dir="$host_dir/build"
install_dir="$host_dir/install"
verovio_bin="$install_dir/bin/verovio"

if [[ -x "$verovio_bin" ]]; then
  printf '%s\n' "$verovio_bin"
  exit 0
fi

mkdir -p "$host_dir" "$build_dir" "$install_dir"
if [[ ! -f "$source_dir/cmake/CMakeLists.txt" ]]; then
  rm -rf "$source_dir" "$build_dir" "$install_dir"
  mkdir -p "$host_dir" "$build_dir" "$install_dir"
  cp -R "$VEROVIO_SRC_DIR" "$source_dir"
  chmod -R u+w "$source_dir"
fi

cmake \
  -S "$source_dir/cmake" \
  -B "$build_dir" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$install_dir" >&2

cmake --build "$build_dir" --target verovio >&2
cmake --install "$build_dir" >&2

if [[ ! -x "$verovio_bin" ]]; then
  echo "Verovio host build did not produce $verovio_bin." >&2
  exit 1
fi

printf '%s\n' "$verovio_bin"
