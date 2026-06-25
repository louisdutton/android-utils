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
- `apps/assistant` is the local default assistant app. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-assistant-android.sh`
- `apps/documents` is the local PDF-first document viewer app. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-documents-android.sh`
- `apps/recorder` is the local audio recorder app. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-recorder-android.sh`
- `apps/trainer` is the local Learn flashcard app with Anki package import.
  Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-trainer-android.sh`
- `apps/keyboard` is the hard-forked FUTO Keyboard-derived production Keyboard
  app, imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-keyboard-android.sh`
- `apps/vault` is the hard-forked KeePassDX-derived production Vault app,
  imported as normal source with its own Gradle project. Build it with:
  `nix develop --no-write-lock-file --command ./scripts/build-vault-android.sh`
- To build Maps with the bundled offline Cornwall rail timetable, use:
  `./scripts/build-maps-android-with-rail.sh`
  Generated rail assets default to `/tmp/grapheneos-essentials-rail-assets`.
- Prefer reusing an already-running Android emulator while iterating on app changes.
- Before starting a new emulator, check for connected devices with:
  `nix develop --no-write-lock-file --command adb devices -l`
- If an emulator is already connected, install updated debug builds onto that device instead of cold-booting a fresh system:
  `nix develop --no-write-lock-file --command gradle :apps:calendar:installDebug`
- Keep the emulator running across build/install/debug cycles unless the user asks to shut it down, the emulator is unhealthy, or the task is complete and there is no reason to keep it open.
- Use a cold boot only when no usable emulator is already running or when a clean device state is specifically needed.
- Store emulator screenshots and temporary test captures outside the project, for example under `/tmp`, not in the repository.
- Launcher icons for Essentials apps must follow `docs/launcher-icons.md`.

## UI/UX

- Build Android UI around Material 3 / Material You conventions unless an imported
  upstream app already has a stronger local design system.
- Prefer polished, ergonomic product UI over decorative screens: clear hierarchy,
  predictable navigation, minimal friction, and controls placed where users expect
  them.
- Match the app's existing visual language before adding new patterns. Reuse local
  components, spacing, typography, colors, shapes, icons, and interaction states.
- Keep interfaces clean and focused. Avoid redundant labels, dense secondary text,
  nested cards, marketing-style layouts, and visible instructional copy when the
  control itself can be made obvious.
- Use Material-appropriate controls: icons for common actions, FABs for primary
  creation flows, bottom sheets or dialogs for focused edits, navigation drawers or
  tabs for major destinations, switches/checkboxes for binary settings, and menus
  for compact option sets.
- Design for real device ergonomics: touch targets must be comfortable, text must
  not clip or overlap, lists must scroll smoothly, loading states must avoid layout
  shift, and empty/error states must be useful without being noisy.
- Prefer true black backgrounds for OLED-focused dark UI where consistent with the
  app, while preserving accessible contrast and clear state differences.
- Before considering UI work complete, verify important screens on device or with
  screenshots when feasible, including portrait/landscape and narrow widths where
  relevant.
