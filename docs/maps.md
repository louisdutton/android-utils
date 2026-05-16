# Maps

`apps/maps` is the production Maps replacement source. It is a hard fork of
CoMaps imported into this repository, including the Android app, native map
engine, resource-generation tooling, and vendored third-party sources needed by
the Android build.

The import started from upstream CoMaps commit
`ef074840953cf4570c3dbf56a2e46162f0096799`. From this point forward, the Maps
app is maintained as GrapheneOS Essentials source rather than as a submodule or
patch stack.

The previous MapLibre prototype lives at `apps/maps-legacy`. It remains useful
as a small integration reference for `:core:locations`, `geo:` intents, and
calendar location linking, but it is no longer the product Maps architecture.

## Build Entry Points

The suite Compose apps build from the root Gradle project:

```sh
nix develop --no-write-lock-file --command gradle assembleDebug
```

Maps has its own Android Gradle project under `apps/maps/android` and should
be built through the helper script:

```sh
nix develop --no-write-lock-file --command ./scripts/build-comaps-android.sh
```

The default Maps build is `:app:assembleFdroidDebug -Parm64`. The F-Droid debug
build uses the suite package id `digital.dutton.essentials.maps` and launcher
label `Maps`, so it updates the old Maps install instead of appearing as a
separate CoMaps debug app. Pass Gradle tasks to the script to override that
default, for example:

```sh
nix develop --no-write-lock-file --command ./scripts/build-comaps-android.sh :app:installFdroidDebug -Parm64
```

## Android Tooling

The suite modules use compile SDK 36, target SDK 36, and Android build tools
36.0.0.

Maps also targets SDK 36, but its inherited Android Gradle Plugin currently
requests build-tools 35.0.0 when no build tools version is specified in the
CoMaps Gradle files. The Nix shell therefore installs both 36.0.0 and 35.0.0:
36.0.0 is the suite baseline, and 35.0.0 is an upstream compatibility package
until we pin build tools 36.0.0 internally.

CoMaps also requires Android NDK 28.2.13676358 and SDK CMake 3.22.1 for its
native C++ build.

## Why Python And Protobuf Are Required

CoMaps is not only an Android UI project. Its build generates map rendering
resources, style data, localized type maps, shader sources, and native C++
protobuf outputs before packaging the APK.

The inherited CMake build explicitly checks for Python and requires the Python
`google.protobuf` package in the `>=3.20,<4.0` range. Current nixpkgs Python
protobuf is newer than that, so the flake pins Python protobuf 3.20.3 for the
Maps build shell. The shell also provides `protoc` from `protobuf_21` for the
native/resource generation steps.
