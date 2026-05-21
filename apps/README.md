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
- `keyboard`: hard-forked FUTO Keyboard-derived input method replacement with
  its own Android Gradle project under `apps/keyboard`.
- `vault`: hard-forked KeePassDX-derived local KeePass/KeePassXC-compatible
  password vault with its own Android Gradle project under `apps/vault`.
