# Agent Instructions

## Android Development

- `apps/maps` is the hard-forked CoMaps-derived production Maps app, imported
  as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-comaps-android.sh`
- `apps/messages` is the hard-forked Quik-derived production Messages app,
  imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-messages-android.sh`
- `apps/wallet` is the hard-forked Catima-derived production Wallet app,
  imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-wallet-android.sh`
- `apps/keyboard` is the hard-forked FUTO Keyboard-derived production Keyboard
  app, imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-keyboard-android.sh`
- `apps/vault` is the hard-forked KeePassDX-derived production Vault app,
  imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-vault-android.sh`
- To build Maps with the bundled offline Cornwall rail timetable, use:
  `./scripts/build-maps-android-with-rail.sh`
  Generated rail assets default to `/tmp/grapheneos-essentials-rail-assets`.
- The old MapLibre prototype is `:apps:maps-legacy`; keep it for integration
  reference work, not as the production Maps target.
- Prefer reusing an already-running Android emulator while iterating on app changes.
- Before starting a new emulator, check for connected devices with:
  `nix develop --no-write-lock-file --command adb devices -l`
- If an emulator is already connected, install updated debug builds onto that device instead of cold-booting a fresh system:
  `nix develop --no-write-lock-file --command gradle :apps:calendar:installDebug`
- Keep the emulator running across build/install/debug cycles unless the user asks to shut it down, the emulator is unhealthy, or the task is complete and there is no reason to keep it open.
- Use a cold boot only when no usable emulator is already running or when a clean device state is specifically needed.
- Store emulator screenshots and temporary test captures outside the project, for example under `/tmp`, not in the repository.
