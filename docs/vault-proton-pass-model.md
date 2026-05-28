# Vault Proton Pass Model

## Goal

Vault should become a local-first password manager with Proton Pass-style day-to-day
availability while keeping KeePass/KDBX technology as the durable vault format. The
home lab sync server should move encrypted vault data between devices; it should not
need plaintext passwords or a server-side account password database.

## Product Position

The current app behaves like a KeePass file viewer with Autofill attached. That means
Autofill works well only after the user has already opened the database and the entry
has a saved website/app origin. The target model is different:

- the selected KDBX vault is the local source of truth;
- the app unlocks that vault into a device-bound runtime session;
- Autofill and Credential Provider can resolve matching credentials while the device
  is unlocked;
- the full vault UI can still be privacy-locked;
- a hard lock wipes runtime key material and requires the master unlock path again.

This makes the device lock, GrapheneOS reboot state, and KDBX encryption the main
security boundaries. The app lock becomes a privacy and release-control boundary,
not a promise that no local component can ever access credentials while the phone is
unlocked.

## Security Model

There are three explicit states.

### Before First Unlock

After reboot and before the Android profile is unlocked, Vault has no usable
credential state. Autofill cannot serve passwords. This is the strongest local
protection state and should remain aligned with GrapheneOS expectations.

### Device Unlocked, Vault Session Available

After the user unlocks the device and has enabled local quick access for a vault,
Vault may keep enough device-bound state to decrypt the KDBX file and serve Autofill.
The user should not have to browse the vault or type the master password for every
login.

In this state:

- Autofill may show matched entries for the current website/app;
- filling requires an explicit user tap;
- a setting may require biometric/device authentication before releasing a password;
- the app UI may remain locked even though Autofill can serve credentials.

### Hard Locked

Hard lock clears runtime session state and cached decrypted database material. The
next Autofill request can still show safe metadata if available, but releasing the
secret requires re-opening the KDBX vault through the full unlock path.

Hard lock should occur when the user explicitly locks Vault, disables quick access,
removes device credentials, switches Android profile, or after a configured security
timeout if the user chooses that behavior.

## KeePass/KDBX Role

KDBX remains the canonical vault format. Entries, groups, custom fields, OTP data,
passkey data, history, and attachments continue to live in the KDBX database.

The Android app may add local-only indexes and session metadata, but these are
derivative caches:

- origin index: website/app origin to entry UUIDs;
- display index: entry UUID, title, username, favicon/icon reference, modified time;
- sync state: remote revision, local revision, conflict markers;
- quick access envelope: Android Keystore-wrapped material needed to reopen the KDBX.

The local index must never become the only copy of user data. If deleted, it should
be rebuilt from the KDBX file after unlock.

## Local Quick Access

To make Autofill available without repeated master-password prompts, Vault needs a
device-bound quick access mechanism.

Recommended design:

- User opens the KDBX once with the normal master credential.
- If the user enables always-available mode, store the required local unlock material
  in an encrypted envelope protected by Android Keystore.
- Prefer hardware-backed / StrongBox keys when available.
- Bind the envelope to the Android profile and invalidate it when device credentials
  are removed or changed where platform support allows.
- Keep decrypted database/session material in memory only while needed.
- Rebuild the runtime session lazily when Autofill or Credential Provider asks for it.

This is a deliberate mode, not a side effect of a long timeout. The settings should
make the security tradeoff explicit.

## Autofill Behavior

The new default Autofill flow should be:

1. Android sends an Autofill request with website/app context.
2. Vault parses the structure and resolves the origin.
3. Vault queries the local origin index and, if needed, the loaded KDBX session.
4. Vault returns matched datasets first, with manual selection as a secondary option.
5. User taps the matched credential.
6. Vault fills username/password/OTP, optionally after biometric/device auth.

Manual selection should remain available, but it should not consume the only inline
suggestion slot when a site-matched credential exists.

## Credential Provider

Classic Autofill is still required for broad compatibility, but Android 14+
Credential Provider support should be first-class for passwords and passkeys. The
Credential Provider should use the same vault session and origin index as Autofill,
so password and passkey behavior is consistent.

## Origin Matching

Every selected or saved credential should store origin metadata:

- web origin/domain for browser logins;
- Android package name and signing certificate fingerprint for app logins;
- relying party ID and credential ID for passkeys.

The current KeePass custom-field approach can continue for KDBX compatibility. The
local index should normalize this data for fast lookup.

## Sync Model

The home lab sync server should treat vault data as encrypted client-owned data.
There are two viable phases.

### Phase 1: Encrypted File Sync

Sync the KDBX file plus metadata such as revision, device ID, and last-modified time.
The server never decrypts the file. This is simple and preserves KeePass
compatibility, but concurrent edits can create conflicts.

Conflict handling for Phase 1:

- detect remote revision mismatch before upload;
- keep both conflicting KDBX files;
- ask the client to merge or choose;
- never silently overwrite another device's newer vault.

### Phase 2: KDBX-Aware Merge

Use KDBX entry UUIDs, group UUIDs, modified timestamps, and entry history to merge
non-conflicting changes client-side. The server can still remain blind to plaintext
by storing encrypted objects or encrypted file revisions.

Family sharing should prefer separate vaults:

- personal vault per person;
- shared household vault for credentials everyone can use;
- optional child/limited vaults later.

KDBX does not provide Proton-style server-enforced per-entry sharing by itself, so
fine-grained sharing needs a client-side sharing layer or separate encrypted vaults.

## Implementation Phases

1. Fix current Autofill ranking so matched credentials win inline suggestion slots.
2. Make origin capture reliable and automatic when a user manually selects an entry.
3. Add a local origin/display index rebuilt from KDBX after unlock.
4. Add device-bound quick access for the selected default vault.
5. Route Autofill through a `VaultCredentialRepository` instead of activity-driven
   database selection.
6. Add Android Credential Provider support using the same repository.
7. Add home lab encrypted file sync with conflict detection.
8. Add client-side KDBX merge or shared-vault workflows.

## Implemented Starting Point

The first implementation step makes the native vault behave much closer to this
model without changing the KDBX format:

- Autofill matched entries are offered before the manual selection fallback in
  inline keyboard suggestions.
- The default mode keeps the loaded vault available until the device screen locks,
  instead of idling out after a short app timeout.
- Autofill attempts a silent quick-open of the native KDBX vault using the existing
  Android Keystore-wrapped device-unlock credential, then falls back to the current
  unlock/select UI if the Keystore key is not usable.
- Android Keystore keys created for the default mode require the device to be
  unlocked and use the unlocked-device session rather than prompting for every
  Autofill request.

## Non-Goals

- Do not build a plaintext server.
- Do not replace KDBX as the export/sync-compatible vault format.
- Do not rely on the keyboard service as the main password manager surface.
- Do not make manual entry selection the common Autofill path.
