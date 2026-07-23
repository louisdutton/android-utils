# Essentials

Essentials is a minimally branded fork of the GrapheneOS App Store. It
uses the same signed metadata format and Android 12+ unattended update flow,
but is configured for the private Essentials application repository.
The build, metadata-generation, signing, and deployment pipeline is driven by
shell scripts and standard command-line tools.

## Debug build

```sh
./scripts/build-store-android.sh
```

Debug builds use a deliberately invalid repository endpoint and key unless
configuration is supplied. They are suitable for UI and installer testing.

## Repository configuration

Set these environment variables or their equivalent Gradle properties:

| Environment variable | Gradle property | Purpose |
| --- | --- | --- |
| `ESSENTIALS_REPO_BASE_URL` | `essentialsRepoBaseUrl` | Repository base URL reachable over WireGuard |
| `ESSENTIALS_REPO_PUBLIC_KEY` | `essentialsRepoPublicKey` | Base64 Signify-compatible repository public key |
| `ESSENTIALS_REPO_KEY_VERSION` | `essentialsRepoKeyVersion` | Repository key generation, initially `0` |
| `ESSENTIALS_STORE_VERSION_CODE` | `essentialsStoreVersionCode` | Monotonically increasing Android version code |
| `ESSENTIALS_STORE_VERSION_NAME` | `essentialsStoreVersionName` | Human-readable store version |

Release builds also require a private `keystore.properties` based on
`keystore.properties.example`, or the equivalent `ESSENTIALS_STORE_KEYSTORE_*`
environment variables used by local automation. A release build fails rather than falling
back to debug signing or the placeholder repository configuration.

HTTPS with an Android-trusted certificate is preferred on general networks.
HTTP is also safe for the private deployment when bound exclusively to
WireGuard: WireGuard encrypts transport and the Store independently verifies
the signed repository metadata and APK signatures.

## Build a repository

Build or reuse every signed app first:

```sh
./scripts/build-essentials-apps-android.sh --all
```

The command fingerprints each app independently, so unchanged APKs are reused.
Pass app names to build only those apps, or add `--force` to rebuild. Cached APKs,
fingerprints, and dependency reports live under
`~/.local/share/grapheneos-essentials/releases` by default.

All Gradle builds consume the shared dependency policy in
`gradle/essentials-dependency-versions.json`. Every complete suite build then
fails if the APK reports contain multiple real versions of the same dependency.

Create the repository metadata key once, outside this source checkout:

```sh
./scripts/init-store-repository-key.sh /private/backup/essentials-store
```

Then build a static repository from production-signed APKs:

```sh
nix develop --no-write-lock-file --command \
  ./scripts/build-store-repository.sh \
  --private-key /private/backup/essentials-store/apps.0.sec \
  --public-key /private/backup/essentials-store/apps.0.pub \
  --config apps/manifest.json \
  --artifact-cache ~/.cache/grapheneos-essentials/repository \
  --output /tmp/essentials-store-repository \
  /path/to/signed/*.apk
```

Publish the generated directory as the exact base URL configured in the Store.
`apps/manifest.json` is the single source of truth for suite identity, build
inputs, build commands, and repository presentation metadata. The
repository private key and APK signing keys are separate and must both be
backed up.

## Local USB hosting

For development and household deployment before the home server is available,
serve the signed repository over an Android Debug Bridge reverse tunnel:

```sh
./scripts/serve-store-repository-over-adb.sh
```

This builds only apps whose inputs changed, audits dependency versions, reuses
compressed repository artifacts, creates the ADB reverse tunnel, and starts the
local HTTP server. Set `ESSENTIALS_REBUILD_SUITE=1` to force every app to rebuild.

The Store connects to `http://127.0.0.1:8080`, which ADB forwards to the local
server. Repository metadata remains signature-verified. The tunnel exists only
while USB debugging is connected; the generated static directory can later be
served unchanged by the home server.

## Deploy to Mini

Mini serves the repository only on its WireGuard address. After enabling its
NixOS `essentials-repository.nix` module, publish atomically from this laptop:

```sh
./scripts/deploy-store-repository.sh
```

The stable Store URL is `http://10.70.0.1:8080`. Each deployment creates an
immutable release on Mini and switches the `current` symlink only after the
upload finishes.
