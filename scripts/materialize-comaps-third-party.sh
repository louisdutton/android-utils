#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maps_dir="$repo_root/apps/maps"

link_source() {
  local relative_target="$1"
  local env_name="$2"
  local source_path="${!env_name:-}"
  local target_path="$maps_dir/$relative_target"

  if [[ -z "$source_path" ]]; then
    if [[ -e "$target_path" ]]; then
      return
    fi
    echo "Missing $env_name for $relative_target. Run inside nix develop." >&2
    exit 1
  fi

  if [[ ! -e "$source_path" ]]; then
    echo "$env_name points at missing path: $source_path" >&2
    exit 1
  fi

  if [[ -L "$target_path" ]]; then
    local current_target
    current_target="$(readlink "$target_path")"
    if [[ "$current_target" == "$source_path" ]]; then
      return
    fi
    rm "$target_path"
  elif [[ -e "$target_path" ]]; then
    echo "$relative_target already exists and is not a Nix materialized symlink." >&2
    echo "Remove it before running this script again." >&2
    exit 1
  fi

  mkdir -p "$(dirname "$target_path")"
  ln -s "$source_path" "$target_path"
}

link_source "3party/boost" "COMAPS_BOOST_SRC"
link_source "3party/expat/expat" "COMAPS_EXPAT_SRC"
link_source "3party/freetype/freetype" "COMAPS_FREETYPE_SRC"
link_source "3party/gflags" "COMAPS_GFLAGS_SRC"
link_source "3party/harfbuzz/harfbuzz" "COMAPS_HARFBUZZ_SRC"
link_source "3party/icu/icu" "COMAPS_ICU_SRC"
link_source "3party/jansson/jansson" "COMAPS_JANSSON_SRC"
link_source "3party/protobuf/protobuf" "COMAPS_PROTOBUF_SRC"
link_source "3party/pugixml/pugixml" "COMAPS_PUGIXML_SRC"
link_source "3party/Vulkan-Headers" "COMAPS_VULKAN_HEADERS_SRC"
link_source "data/borders" "COMAPS_BORDERS_SRC"
