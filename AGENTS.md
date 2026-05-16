# Agent Instructions

## Android Development

- Prefer reusing an already-running Android emulator while iterating on app changes.
- Before starting a new emulator, check for connected devices with:
  `nix develop --no-write-lock-file --command adb devices -l`
- If an emulator is already connected, install updated debug builds onto that device instead of cold-booting a fresh system:
  `nix develop --no-write-lock-file --command gradle :apps:calendar:installDebug`
- Keep the emulator running across build/install/debug cycles unless the user asks to shut it down, the emulator is unhealthy, or the task is complete and there is no reason to keep it open.
- Use a cold boot only when no usable emulator is already running or when a clean device state is specifically needed.
- Store emulator screenshots and temporary test captures outside the project, for example under `/tmp`, not in the repository.
