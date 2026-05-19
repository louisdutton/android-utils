# GrapheneOS Essentials apps

This directory contains the Android application modules that make up the
GrapheneOS Essentials suite. Each child directory is intended to produce its
own APK with a distinct application id.

- `agent`: assistant and voice interaction app carried forward from the
  original project.
- `maps`: hard-forked CoMaps-derived offline Maps replacement with its own
  Android Gradle project under `apps/maps/android`.
- `maps-legacy`: the previous MapLibre prototype, retained as a small
  integration reference while Maps moves to the CoMaps native/offline stack.
- `messaging`: messaging app scaffold.
- `calendar`: provider-backed calendar app with event management.
- `nfc-reader`: minimal read-only NFC/RFID-compatible card reader for
  educational inspection.
