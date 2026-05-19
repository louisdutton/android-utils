# NFC Reader

Minimal read-only NFC/RFID-compatible tag reader for educational inspection.

The app uses Android reader mode while it is in the foreground and reports:

- tag identifier bytes
- Android-supported tag technologies
- public protocol metadata for NFC-A, NFC-B, NFC-F, NFC-V, ISO-DEP, MIFARE
  Classic, and MIFARE Ultralight when the platform exposes it
- standard NDEF message records and decoded text/URI previews
- named saved scans for later parser and UI comparison
- Android HCE playback of the active saved NDEF message as a test Type 4 tag

It does not write tags, clone tags, brute force keys, or attempt to read
protected sectors. The NDEF emulation mode does not spoof a card identifier,
relay another card, emulate MIFARE Classic sectors, or forward arbitrary APDUs.
Android NFC hardware reads 13.56 MHz NFC-family tags; it is not a general
low-frequency RFID reader.

Build from the repository root:

```sh
nix develop --no-write-lock-file --command gradle :apps:nfc-reader:assembleDebug
```
