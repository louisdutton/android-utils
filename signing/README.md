# Signing material

Private signing inputs in this directory are encrypted with SOPS for the
dedicated CI age recipient declared in `.sops.yaml`. The age private key is
stored outside this repository and in the `SOPS_AGE_KEY` GitHub Actions secret.

`apps.0.pub` is the public repository metadata verification key and is
intentionally unencrypted.
