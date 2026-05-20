# Essentials Keyboard upstream

This app was imported from the public FUTO Keyboard source mirror:

- Repository: https://github.com/futo-org/android-keyboard
- Upstream commit: `b01ef3311d9a996415ece1c071237774ff60ed7d`
- License: FUTO Source First License 1.1, preserved in `LICENSE.md`

Recursive upstream dependencies were materialized as normal source files:

- `java/assets/layouts`: `022575e71af58a103d1e8bab3d8407cc3f026c81`
- `java/assets/themes`: `b7426dc6f30f10f122bd4156c53f8ac6ad1cb348`
- `java/res-large`: `d87d9dbdf3966bbe18413be375dab2f6c7bbdfdd`
- `libs`: `12e6a93490b80d3c0489d4963cf116fd0718d12a`
- `translations`: `f2b58d1d4dd14f29b334526d934e998047686eaf`
- `voiceinput-shared/src/main/ml`: `7692bc6096c51c44beba716a3c429ff09dbfffd9`

The source namespace remains `org.futo.inputmethod.latin` to keep the fork
small, but the installable Android application id is
`digital.dutton.essentials.keyboard`.
