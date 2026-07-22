#!/usr/bin/env python3
"""Build a signed static repository for Essentials from signed APKs."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import time
import tomllib


def sdk_tool(name: str) -> Path:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise SystemExit("ANDROID_HOME is unset; run this script through nix develop")
    build_tools = Path(android_home) / "build-tools"
    candidates = [path / name for path in build_tools.iterdir() if (path / name).is_file()]
    if not candidates:
        raise SystemExit(f"Android SDK tool not found: {name}")

    def version_key(path: Path) -> tuple[int, ...]:
        parts = []
        for part in path.parent.name.split("."):
            try:
                parts.append(int(part))
            except ValueError:
                parts.append(-1)
        return tuple(parts)

    return max(candidates, key=version_key)


def command_output(args: list[str | Path]) -> str:
    return subprocess.check_output([str(arg) for arg in args], text=True)


def inspect_apk(apk: Path, aapt2: Path, apksigner: Path) -> dict[str, object]:
    badging = command_output([aapt2, "dump", "badging", apk])
    lines = badging.splitlines()
    if not lines:
        raise ValueError(f"No badging output for {apk}")

    package_values: dict[str, str] = {}
    for token in shlex.split(lines[0]):
        if "=" in token:
            key, value = token.split("=", 1)
            package_values[key] = value

    package_name = package_values.get("name")
    version_code = package_values.get("versionCode")
    version_name = package_values.get("versionName")
    if not package_name or not version_code:
        raise ValueError(f"Unable to read package/version from {apk}")

    label: str | None = None
    min_sdk: int | None = None
    abis: list[str] = []
    for line in lines[1:]:
        if line.startswith("application-label:") and label is None:
            values = shlex.split(line.split(":", 1)[1])
            if values:
                label = values[0]
        elif line.startswith(("minSdkVersion:", "sdkVersion:")):
            values = shlex.split(line.split(":", 1)[1])
            if values:
                min_sdk = int(values[0])
        elif line.startswith("native-code:"):
            abis = shlex.split(line.split(":", 1)[1])

    if label is None or min_sdk is None:
        raise ValueError(f"Unable to read label/minSdk from {apk}")

    signer_output = subprocess.check_output(
        [str(apksigner), "verify", "--print-certs", "--verbose", str(apk)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    cert_digest: str | None = None
    for output_line in signer_output.splitlines():
        line = output_line.strip()
        marker = "certificate SHA-256 digest: "
        if line.startswith("Signer ") and marker in line:
            digest = line.split(marker, 1)[1].strip().replace(":", "").lower()
            if cert_digest is not None and digest != cert_digest:
                raise ValueError(f"Multiple APK signers are not supported: {apk}")
            cert_digest = digest
    if cert_digest is None:
        raise ValueError(f"Unable to read signing certificate from {apk}")

    return {
        "packageName": package_name,
        "versionCode": int(version_code),
        "versionName": version_name or version_code,
        "label": label,
        "minSdk": min_sdk,
        "abis": abis,
        "certificate": cert_digest,
    }


def load_package_config(path: Path | None) -> dict[str, dict[str, object]]:
    if path is None:
        return {}
    with path.open("rb") as config_file:
        data = tomllib.load(config_file)
    packages = data.get("packages", {})
    if not isinstance(packages, dict):
        raise ValueError("Configuration must contain a [packages] table")
    return packages


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def link_or_copy(source: Path, destination: Path) -> None:
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def gzip_apk(source: Path, destination: Path, cache: Path, apk_hash: str) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    cached = cache / f"{apk_hash}.apk.gz"
    if cached.is_file():
        link_or_copy(cached, destination)
        return

    temporary = cache / f".{apk_hash}.{os.getpid()}.tmp"
    with source.open("rb") as input_file, destination.open("wb") as output_file:
        with gzip.GzipFile(
            filename="",
            mode="wb",
            compresslevel=9,
            fileobj=output_file,
            mtime=0,
        ) as compressed_file:
            shutil.copyfileobj(input_file, compressed_file)
    destination.replace(temporary)
    try:
        temporary.replace(cached)
    except OSError:
        if not cached.is_file():
            raise
        temporary.unlink(missing_ok=True)
    link_or_copy(cached, destination)


def package_metadata(
    apk: Path,
    destination_root: Path,
    info: dict[str, object],
    config: dict[str, object],
    artifact_cache: Path,
) -> tuple[str, str, dict[str, object], dict[str, object]]:
    package_name = str(info["packageName"])
    version_code = str(info["versionCode"])
    package_dir = destination_root / "packages" / package_name
    version_dir = package_dir / version_code
    version_dir.mkdir(parents=True, exist_ok=False)

    apk_hash = hash_file(apk)
    copied_apk = version_dir / "base.apk"
    link_or_copy(apk, copied_apk)
    compressed_apk = version_dir / "base.apk.gz"
    gzip_apk(copied_apk, compressed_apk, artifact_cache / "gzip", apk_hash)

    variant: dict[str, object] = {
        "versionCode": int(info["versionCode"]),
        "versionName": info["versionName"],
        "label": config.get("label", info["label"]),
        "channel": config.get("channel", "stable"),
        "minSdk": int(info["minSdk"]),
        "apks": ["base.apk"],
        "apkHashes": [apk_hash],
        "apkSizes": [copied_apk.stat().st_size],
        "apkGzSizes": [compressed_apk.stat().st_size],
    }
    if info["abis"]:
        variant["abis"] = info["abis"]
    if "description" in config:
        variant["description"] = config["description"]
    if "releaseNotes" in config:
        variant["releaseNotes"] = config["releaseNotes"]

    common: dict[str, object] = {
        "source": "GrapheneOS_build",
        "signatures": [info["certificate"]],
        "requestUpdateOwnership": bool(config.get("requestUpdateOwnership", True)),
        "variants": {},
    }
    for key in ("description", "isTopLevel", "showAutoUpdateNotifications", "optOutOfBulkUpdates"):
        if key in config:
            common[key] = config[key]

    icon_value = config.get("icon")
    if icon_value:
        icon = Path(str(icon_value)).expanduser().resolve()
        if icon.suffix.lower() != ".webp":
            raise ValueError(f"Package icon must be WebP: {icon}")
        shutil.copy2(icon, package_dir / "icon.webp")
        common["iconType"] = "webp"

    return package_name, version_code, common, variant


def public_key_value(public_key_file: Path) -> str:
    lines = public_key_file.read_text(encoding="utf-8").splitlines()
    if len(lines) < 2:
        raise ValueError(f"Invalid Signify public key: {public_key_file}")
    return lines[1].strip()


def safe_replace(staging: Path, output: Path) -> None:
    output = output.resolve()
    if output == Path(output.anchor) or output == Path.home().resolve():
        raise ValueError(f"Refusing unsafe output path: {output}")
    if output.is_symlink():
        raise ValueError(f"Refusing to replace symlink: {output}")

    backup = output.with_name(f".{output.name}.previous")
    if backup.exists():
        shutil.rmtree(backup)
    try:
        if output.exists():
            output.rename(backup)
        staging.rename(output)
    except Exception:
        if not output.exists() and backup.exists():
            backup.rename(output)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--key-version", default=0, type=int)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--artifact-cache", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()

    private_key = args.private_key.expanduser().resolve()
    public_key = args.public_key.expanduser().resolve()
    output = args.output.expanduser().resolve()
    artifact_cache = args.artifact_cache.expanduser().resolve()
    apk_paths = [apk.expanduser().resolve() for apk in args.apks]
    for path in (private_key, public_key, *apk_paths):
        if not path.is_file():
            raise SystemExit(f"File not found: {path}")

    aapt2 = sdk_tool("aapt2")
    apksigner = sdk_tool("apksigner")
    minisign = shutil.which("minisign")
    if minisign is None:
        raise SystemExit("minisign is not on PATH; run this script through nix develop")

    config = load_package_config(args.config)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging: Path | None = Path(
        tempfile.mkdtemp(prefix=f".{output.name}.tmp-", dir=output.parent)
    )
    try:
        packages: dict[str, dict[str, object]] = {}
        for apk in apk_paths:
            info = inspect_apk(apk, aapt2, apksigner)
            package_name = str(info["packageName"])
            package_config = config.get(package_name, {})
            if not isinstance(package_config, dict):
                raise ValueError(f"Invalid configuration for {package_name}")
            name, version, common, variant = package_metadata(
                apk, staging, info, package_config, artifact_cache
            )
            package = packages.setdefault(name, common)
            if package["signatures"] != common["signatures"]:
                raise ValueError(f"Signing certificate changed for {name}")
            variants = package["variants"]
            assert isinstance(variants, dict)
            if version in variants:
                raise ValueError(f"Duplicate package version: {name} {version}")
            variants[version] = variant

        metadata = {
            "time": int(time.time()),
            "packages": dict(sorted(packages.items())),
            "fsVerityCerts": {},
        }
        metadata_json = staging / "metadata.1.json"
        metadata_json.write_text(
            json.dumps(metadata, separators=(",", ":")), encoding="utf-8"
        )
        signature_file = staging / f"metadata.1.json.{args.key_version}.sig"
        subprocess.run(
            [
                minisign,
                "-S",
                "-l",
                "-s",
                str(private_key),
                "-m",
                str(metadata_json),
                "-x",
                str(signature_file),
            ],
            check=True,
        )
        signature_lines = signature_file.read_text(encoding="utf-8").splitlines()
        if len(signature_lines) < 2:
            raise ValueError("Minisign did not produce a valid legacy signature")
        signed_json = staging / f"metadata.1.{args.key_version}.sjson"
        signed_json.write_bytes(
            metadata_json.read_bytes() + b"\n" + signature_lines[1].encode("ascii") + b"\n"
        )

        safe_replace(staging, output)
        staging = None
        print(f"Repository: {output}")
        print(f"Packages: {len(packages)}")
        print(f"ESSENTIALS_REPO_PUBLIC_KEY={public_key_value(public_key)}")
        print(f"ESSENTIALS_REPO_KEY_VERSION={args.key_version}")
    finally:
        if staging is not None and staging.exists():
            shutil.rmtree(staging)


if __name__ == "__main__":
    main()
