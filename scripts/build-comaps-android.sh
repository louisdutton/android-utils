#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maps_dir="$repo_root/apps/maps"

if [[ ! -d "$maps_dir/android" ]]; then
  echo "Expected hard-forked Maps Android project at apps/maps/android." >&2
  exit 1
fi

: "${ANDROID_HOME:?ANDROID_HOME is not set. Run this script inside nix develop.}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export SKIP_MAP_DOWNLOAD="${SKIP_MAP_DOWNLOAD:-1}"
export SKIP_GENERATE_STRINGS="${SKIP_GENERATE_STRINGS:-1}"
export SKIP_GENERATE_SERBIAN_LATIN_STRINGS="${SKIP_GENERATE_SERBIAN_LATIN_STRINGS:-1}"

"$maps_dir/tools/android/set_up_android.py" --sdk "$ANDROID_HOME"

(
  cd "$maps_dir"
  if [[ -f build/CMakeCache.txt ]] && grep -q "COMPILER_.*-NOTFOUND" build/CMakeCache.txt; then
    rm -rf build
  fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    export CC="${COMAPS_HOST_CC:-/usr/bin/clang}"
    export CXX="${COMAPS_HOST_CXX:-/usr/bin/clang++}"
    export AR="${COMAPS_HOST_AR:-$(command -v clang-ar)}"
    export RANLIB="${COMAPS_HOST_RANLIB:-$(command -v clang-ranlib)}"
  fi
  real_cmake="$(command -v cmake)"
  cmake_wrapper_dir="$(mktemp -d "${TMPDIR:-/tmp}/comaps-cmake.XXXXXX")"
  trap 'rm -rf "$cmake_wrapper_dir"' EXIT
  cat > "$cmake_wrapper_dir/cmake" <<EOF
#!/usr/bin/env bash
for arg in "\$@"; do
  if [[ "\$arg" == "--build" || "\$arg" == "--install" || "\$arg" == "-E" ]]; then
    exec "$real_cmake" "\$@"
  fi
done
exec "$real_cmake" \\
  -DCMAKE_C_COMPILER_AR="$AR" \\
  -DCMAKE_CXX_COMPILER_AR="$AR" \\
  -DCMAKE_C_COMPILER_RANLIB="$RANLIB" \\
  -DCMAKE_CXX_COMPILER_RANLIB="$RANLIB" \\
  "\$@"
EOF
  chmod +x "$cmake_wrapper_dir/cmake"
  export PATH="$cmake_wrapper_dir:$PATH"
  ./configure.sh --skip-map-download
)

cd "$maps_dir/android"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleFdroidDebug -Parm64 --no-daemon -Pnjobs="${COMAPS_NJOBS:-4}"
fi

./gradlew "$@"
