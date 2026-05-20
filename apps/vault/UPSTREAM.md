# Vault Upstream

Vault is a hard fork of KeePassDX.

- Upstream: https://github.com/Kunzisoft/KeePassDX
- Imported tag: `4.4.2`
- Imported commit: `581df551ea3ad70562c6d72a3fb49780d03da6a8`
- License: GPL-3.0-or-later

KeePassDX was selected as a modern Android KeePass/KeePassXC-compatible base:
it supports KDBX 1-4 databases, passkeys, biometrics, OTP, Autofill, and it
does not request Android Internet permission. It also does not expose KeePass
desktop-style plugin support, which keeps the local vault attack surface
smaller for this fork.

The project is vendored as normal source under `apps/vault` so the GrapheneOS
Essentials fork can make direct product, UI, package identity, and build-system
changes without trying to remain patch-compatible with upstream.
