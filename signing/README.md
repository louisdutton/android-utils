# Signing material

Private signing inputs in this directory are encrypted with SOPS for the
dedicated age recipient declared in `.sops.yaml`. The age private key is stored
outside this repository; these encrypted files are a recoverable backup.

`apps.0.pub` is the public repository metadata verification key and is
intentionally unencrypted.
