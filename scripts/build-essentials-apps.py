#!/usr/bin/env python3
"""Build and cache only changed Essentials Android apps."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_STAGING = Path("/tmp/grapheneos-essentials-suite-apks")
EXCLUDED_DIRECTORIES = {".cxx", ".gradle", ".idea", "build", "nativeOutputs"}
EXCLUDED_FILES = {"local.properties"}


@dataclass(frozen=True)
class App:
    name: str
    package: str
    source_paths: tuple[str, ...]


ROOT_SHARED = (
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "core/locations",
)

APPS = {
    app.name: app
    for app in (
        App("assistant", "digital.dutton.essentials.assistant", ("apps/assistant", *ROOT_SHARED)),
        App("calendar", "digital.dutton.essentials.calendar", ("apps/calendar", *ROOT_SHARED)),
        App("documents", "digital.dutton.essentials.documents", ("apps/documents", *ROOT_SHARED)),
        App("keyboard", "digital.dutton.essentials.keyboard", ("apps/keyboard",)),
        App("learn", "digital.dutton.essentials.learn", ("apps/trainer", *ROOT_SHARED)),
        App("maps", "digital.dutton.essentials.maps", ("apps/maps",)),
        App("messages", "digital.dutton.essentials.messages", ("apps/messages",)),
        App("notes", "digital.dutton.essentials.notes", ("apps/notes", *ROOT_SHARED)),
        App("piano", "digital.dutton.essentials.piano", ("apps/pitch", *ROOT_SHARED)),
        App("recorder", "digital.dutton.essentials.recorder", ("apps/recorder", *ROOT_SHARED)),
        App("scores", "digital.dutton.essentials.scores", ("apps/scores", *ROOT_SHARED)),
        App("vault", "digital.dutton.essentials.vault", ("apps/vault",)),
        App("wallet", "digital.dutton.essentials.wallet", ("apps/wallet",)),
        App("weather", "digital.dutton.essentials.weather", ("apps/weather", *ROOT_SHARED)),
        App("store", "digital.dutton.essentials.store", ("apps/store",)),
    )
}

COMMON_INPUTS = (
    "flake.nix",
    "flake.lock",
    "gradle/essentials-dependency-versions.json",
    "scripts/build-essentials-suite-android.sh",
    "scripts/essentials-dependency-policy.init.gradle",
    "scripts/essentials-release-version.init.gradle",
)


def files_under(path: Path):
    if not path.exists():
        return
    if path.is_file():
        yield path
        return
    for current_root, directories, files in os.walk(path):
        directories[:] = sorted(
            directory for directory in directories if directory not in EXCLUDED_DIRECTORIES
        )
        for name in sorted(files):
            if name not in EXCLUDED_FILES:
                yield Path(current_root) / name


def source_fingerprint(app: App, signing_inputs: tuple[Path, ...]) -> str:
    digest = hashlib.sha256()
    paths = [*app.source_paths, *COMMON_INPUTS]
    for relative in sorted(set(paths)):
        for path in files_under(ROOT / relative):
            digest.update(path.relative_to(ROOT).as_posix().encode())
            digest.update(b"\0")
            if path.is_symlink():
                digest.update(os.readlink(path).encode())
            else:
                with path.open("rb") as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
            digest.update(b"\0")
    for signing_input in signing_inputs:
        with signing_input.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def apk_version(apk: Path, aapt: Path) -> int | None:
    if not apk.is_file():
        return None
    output = subprocess.check_output([aapt, "dump", "badging", apk], text=True)
    first_line = output.splitlines()[0]
    for token in shlex.split(first_line):
        if token.startswith("versionCode="):
            return int(token.split("=", 1)[1])
    return None


def link_or_copy(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def load_metadata(path: Path) -> dict[str, object]:
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def build_app(
    app: App,
    force: bool,
    staging: Path,
    release_root: Path,
    signing_key: Path,
    aapt: Path,
) -> bool:
    cached_apk = release_root / "apks" / f"{app.package}.apk"
    dependency_report = release_root / "dependencies" / f"{app.package}.tsv"
    metadata_path = release_root / "metadata" / f"{app.package}.json"
    metadata = load_metadata(metadata_path)
    fingerprint = source_fingerprint(app, (signing_key,))

    if (
        not force
        and cached_apk.is_file()
        and dependency_report.is_file()
        and metadata.get("sourceFingerprint") == fingerprint
    ):
        link_or_copy(cached_apk, staging / f"{app.package}.apk")
        print(f"Reused {app.name} {metadata.get('versionName', '')}")
        return False

    previous_versions = [int(metadata.get("versionCode", 0))]
    staged_version = apk_version(staging / f"{app.package}.apk", aapt)
    if staged_version is not None:
        previous_versions.append(staged_version)
    version_code = max(int(time.time()), *previous_versions) + 1
    version_name = dt.datetime.now(dt.UTC).strftime("%Y.%m.%d.%H%M%S")

    environment = os.environ.copy()
    temporary_report = dependency_report.with_suffix(".tsv.tmp")
    temporary_report.parent.mkdir(parents=True, exist_ok=True)
    temporary_report.unlink(missing_ok=True)
    environment.update(
        {
            "ESSENTIALS_APK_OUTPUT": str(staging),
            "ESSENTIALS_KEEP_STAGED": "1",
            "ESSENTIALS_RECLAIM_BUILD_SPACE": "0",
            "ESSENTIALS_SKIP_DEPENDENCY_VERIFY": "1",
            "ESSENTIALS_TARGET_APP": app.name,
            "ESSENTIALS_VERSION_CODE": str(version_code),
            "ESSENTIALS_VERSION_NAME": version_name,
            "ESSENTIALS_DEPENDENCY_REPORT": str(temporary_report),
        }
    )
    subprocess.run(
        [str(ROOT / "scripts/build-essentials-suite-android.sh")],
        cwd=ROOT,
        env=environment,
        check=True,
    )

    staged_apk = staging / f"{app.package}.apk"
    if not staged_apk.is_file():
        raise SystemExit(f"Build did not stage {app.package}")
    if not temporary_report.is_file():
        raise SystemExit(f"Build did not resolve release dependencies for {app.package}")

    cached_apk.parent.mkdir(parents=True, exist_ok=True)
    temporary_apk = cached_apk.with_suffix(".apk.tmp")
    shutil.copy2(staged_apk, temporary_apk)
    temporary_apk.replace(cached_apk)
    dependency_report.write_text(
        "\n".join(sorted(set(temporary_report.read_text(encoding="utf-8").splitlines())))
        + "\n",
        encoding="utf-8",
    )
    temporary_report.unlink(missing_ok=True)
    final_fingerprint = source_fingerprint(app, (signing_key,))
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(
        json.dumps(
            {
                "app": app.name,
                "package": app.package,
                "sourceFingerprint": final_fingerprint,
                "versionCode": version_code,
                "versionName": version_name,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Built {app.name} {version_name} ({version_code})")
    return True


def build_store(
    app: App,
    force: bool,
    release_root: Path,
    signing_dir: Path,
    aapt: Path,
) -> bool:
    signing_key = signing_dir / "essentials-store.keystore"
    password_file = signing_dir / "store-password.txt"
    public_key_file = signing_dir / "repository/apps.0.pub"
    for required in (signing_key, password_file, public_key_file):
        if not required.is_file():
            raise SystemExit(f"Release input not found: {required}")

    cached_apk = release_root / "apks" / f"{app.package}.apk"
    dependency_report = release_root / "dependencies" / f"{app.package}.tsv"
    metadata_path = release_root / "metadata" / f"{app.package}.json"
    output_apk = ROOT / "apps/store/app/build/outputs/apk/release/app-release.apk"
    metadata = load_metadata(metadata_path)
    fingerprint = source_fingerprint(app, (signing_key, public_key_file))
    if (
        not force
        and cached_apk.is_file()
        and dependency_report.is_file()
        and metadata.get("sourceFingerprint") == fingerprint
    ):
        output_apk.parent.mkdir(parents=True, exist_ok=True)
        output_apk.unlink(missing_ok=True)
        shutil.copy2(cached_apk, output_apk)
        print(f"Reused store {metadata.get('versionName', '')}")
        return False

    previous_versions = [int(metadata.get("versionCode", 0))]
    output_version = apk_version(output_apk, aapt)
    if output_version is not None:
        previous_versions.append(output_version)
    version_code = max(int(time.time()), *previous_versions) + 1
    version_name = dt.datetime.now(dt.UTC).strftime("%Y.%m.%d.%H%M%S")
    password = password_file.read_text(encoding="utf-8").strip()
    public_key_lines = public_key_file.read_text(encoding="utf-8").splitlines()
    if len(public_key_lines) < 2:
        raise SystemExit(f"Invalid repository public key: {public_key_file}")

    environment = os.environ.copy()
    temporary_report = dependency_report.with_suffix(".tsv.tmp")
    temporary_report.parent.mkdir(parents=True, exist_ok=True)
    temporary_report.unlink(missing_ok=True)
    environment.update(
        {
            "ESSENTIALS_REPO_BASE_URL": environment.get(
                "ESSENTIALS_REPO_BASE_URL", "http://127.0.0.1:8080"
            ),
            "ESSENTIALS_REPO_PUBLIC_KEY": public_key_lines[1].strip(),
            "ESSENTIALS_REPO_KEY_VERSION": "0",
            "ESSENTIALS_STORE_VERSION_CODE": str(version_code),
            "ESSENTIALS_STORE_VERSION_NAME": version_name,
            "ESSENTIALS_STORE_KEYSTORE_FILE": str(signing_key),
            "ESSENTIALS_STORE_KEYSTORE_PASSWORD": password,
            "ESSENTIALS_STORE_KEY_ALIAS": "essentials-store",
            "ESSENTIALS_STORE_KEY_PASSWORD": password,
            "ESSENTIALS_DEPENDENCY_POLICY": str(
                ROOT / "gradle/essentials-dependency-versions.json"
            ),
            "ESSENTIALS_DEPENDENCY_REPORT": str(temporary_report),
        }
    )
    gradle_command = [
        str(ROOT / "scripts/build-store-android.sh"),
        "-I",
        str(ROOT / "scripts/essentials-dependency-policy.init.gradle"),
        "-Dorg.gradle.caching=true",
    ]
    if environment.get("ESSENTIALS_UPDATE_DEPENDENCY_VERIFICATION") == "1":
        gradle_command.extend(("--write-verification-metadata", "sha256"))
    gradle_command.append(":app:assembleRelease")
    subprocess.run(
        gradle_command,
        cwd=ROOT,
        env=environment,
        check=True,
    )
    if not output_apk.is_file():
        raise SystemExit("Store build did not produce a release APK")
    if not temporary_report.is_file():
        raise SystemExit("Store build did not resolve release dependencies")

    cached_apk.parent.mkdir(parents=True, exist_ok=True)
    temporary_apk = cached_apk.with_suffix(".apk.tmp")
    shutil.copy2(output_apk, temporary_apk)
    temporary_apk.replace(cached_apk)
    dependency_report.write_text(
        "\n".join(sorted(set(temporary_report.read_text(encoding="utf-8").splitlines())))
        + "\n",
        encoding="utf-8",
    )
    temporary_report.unlink(missing_ok=True)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(
        json.dumps(
            {
                "app": app.name,
                "package": app.package,
                "sourceFingerprint": source_fingerprint(
                    app, (signing_key, public_key_file)
                ),
                "versionCode": version_code,
                "versionName": version_name,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Built store {version_name} ({version_code})")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apps", nargs="*", choices=sorted(APPS))
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--skip-suite-verify", action="store_true")
    args = parser.parse_args()
    if args.all == bool(args.apps):
        parser.error("specify app names or --all")

    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        raise SystemExit("ANDROID_HOME is unset; use build-essentials-apps-android.sh")

    data_home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    signing_dir = Path(
        os.environ.get(
            "ESSENTIALS_SIGNING_DIR",
            data_home / "grapheneos-essentials/signing",
        )
    )
    signing_key = signing_dir / "essentials-store.keystore"
    if not signing_key.is_file():
        raise SystemExit(f"Signing key not found: {signing_key}")

    staging = Path(os.environ.get("ESSENTIALS_APK_OUTPUT", DEFAULT_STAGING))
    release_root = Path(
        os.environ.get(
            "ESSENTIALS_RELEASE_CACHE",
            data_home / "grapheneos-essentials/releases",
        )
    )
    aapt = Path(android_home) / "build-tools/36.0.0/aapt"
    selected = list(APPS) if args.all else args.apps
    staging.mkdir(parents=True, exist_ok=True)

    built = 0
    for name in selected:
        if name == "store":
            built += build_store(APPS[name], args.force, release_root, signing_dir, aapt)
        else:
            built += build_app(
                APPS[name], args.force, staging, release_root, signing_key, aapt
            )

    if not args.skip_suite_verify:
        verification_paths = [str(staging)]
        store_apk = release_root / "apks/digital.dutton.essentials.store.apk"
        if store_apk.is_file():
            verification_paths.append(str(store_apk))
        subprocess.run(
            [
                str(ROOT / "scripts/verify-suite-dependency-versions.py"),
                "--policy",
                str(ROOT / "gradle/essentials-dependency-versions.json"),
                "--reports-dir",
                str(release_root / "dependencies"),
                *verification_paths,
            ],
            cwd=ROOT,
            check=True,
        )
    print(f"{built} built, {len(selected) - built} reused")


if __name__ == "__main__":
    main()
