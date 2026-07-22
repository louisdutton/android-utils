# GrapheneOS Essentials apps

This directory contains the Android application modules that make up the
GrapheneOS Essentials suite. Each child directory is intended to produce its
own APK with a distinct application id.

- `assistant`: local default assistant app for mapping typed or transcribed
  natural language requests to Android system intents.
- `maps`: hard-forked CoMaps-derived offline Maps replacement with its own
  Android Gradle project under `apps/maps/android`.
- `messaging`: messaging app scaffold.
- `calendar`: provider-backed calendar app with event management.
- `documents`: native PDF-first document viewer for legal documents, forms,
  music scores, and other local files.
- `notes`: markdown-backed text and audio notes app.
- `pitch`: simple piano keyboard and reference tone app.
- `scores`: offline-first music score reader and importer for MusicXML/MXL
  files, with on-device OMR entry points for PDF and image score imports.
  Local OMR/debug iteration can be run without an Android device with
  `nix develop --no-write-lock-file --command ./scripts/scores-local-import.sh <pdf-or-image>`.
- `trainer`: Learn flashcard app with Anki package import and offline study.
- `weather`: Open-Meteo weather app with saved city search and forecasts.
- `store`: private Essentials app repository client derived from the GrapheneOS
  App Store, with unattended Android 12+ suite updates.
- `keyboard`: hard-forked FUTO Keyboard-derived input method replacement with
  its own Android Gradle project under `apps/keyboard`.
- `vault`: hard-forked KeePassDX-derived local KeePass/KeePassXC-compatible
  password vault with its own Android Gradle project under `apps/vault`.
