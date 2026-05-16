# Maps

Maps is the GrapheneOS Essentials navigation app. It is a hard fork of CoMaps,
trimmed to the Android app and the native libraries needed to build it in this
repository.

This fork intentionally does not carry upstream iOS, desktop UI, desktop
packaging, or upstream CI workflow files. The supported local build path is the
Android Gradle project via the repository Nix environment.

```sh
nix develop --no-write-lock-file --command ./scripts/build-comaps-android.sh
```

The Android application id is `digital.dutton.essentials.maps`.

The original CoMaps project is licensed under Apache-2.0 and contains third
party components under their own licenses. See [LICENSE](LICENSE),
[NOTICE](NOTICE), and [data/copyright.html](data/copyright.html).
