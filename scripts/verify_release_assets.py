#!/usr/bin/env python3
"""Verify Foreman's required GitHub release asset set and checksums."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import ssl
import subprocess
import tempfile
from pathlib import Path
from typing import Any


TAG_PATTERN = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?$")
CHECKSUM_PATTERN = re.compile(r"^([0-9A-Fa-f]{64}) [ *](.+)$")


def expected_asset_names(tag: str) -> tuple[str, str, str, str, str]:
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError(f"invalid release tag {tag!r}")
    return (
        f"foreman-{tag}.apk",
        f"foreman-linux-{tag}.tar.gz",
        "SHA256SUMS",
        "SHA256SUMS.sig",
        "foreman-release-cert.pem",
    )


def verify_manifest(manifest: dict[str, Any], tag: str) -> list[str]:
    expected = set(expected_asset_names(tag))
    assets = manifest.get("assets")
    if not isinstance(assets, list):
        return ["release manifest has no assets list"]

    names: list[str] = []
    errors: list[str] = []
    for index, asset in enumerate(assets):
        if not isinstance(asset, dict):
            errors.append(f"asset {index} is not an object")
            continue
        name = asset.get("name")
        size = asset.get("size")
        if not isinstance(name, str) or not name:
            errors.append(f"asset {index} has no valid name")
            continue
        names.append(name)
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            errors.append(f"release asset {name} is empty or has an invalid size")

    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        errors.append(f"duplicate release assets: {', '.join(duplicates)}")
    actual = set(names)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing:
        errors.append(f"missing release assets: {', '.join(missing)}")
    if unexpected:
        errors.append(f"unexpected release assets: {', '.join(unexpected)}")
    return errors


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_directory(directory: Path, tag: str) -> list[str]:
    expected_names = set(expected_asset_names(tag))
    errors: list[str] = []
    if not directory.is_dir():
        return [f"release asset directory does not exist: {directory}"]

    entries = list(directory.iterdir())
    actual_names = {entry.name for entry in entries}
    missing = sorted(expected_names - actual_names)
    unexpected = sorted(actual_names - expected_names)
    if missing:
        errors.append(f"missing release files: {', '.join(missing)}")
    if unexpected:
        errors.append(f"unexpected release files: {', '.join(unexpected)}")

    for name in sorted(expected_names & actual_names):
        path = directory / name
        if not path.is_file() or path.stat().st_size <= 0:
            errors.append(f"release file {name} is empty or is not a regular file")

    checksum_path = directory / "SHA256SUMS"
    if not checksum_path.is_file() or checksum_path.stat().st_size <= 0:
        return errors

    checksums: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        checksum_path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        match = CHECKSUM_PATTERN.fullmatch(raw_line)
        if not match:
            errors.append(f"SHA256SUMS line {line_number} is invalid")
            continue
        digest, name = match.groups()
        if name in checksums:
            errors.append(f"SHA256SUMS contains duplicate entry {name}")
            continue
        checksums[name] = digest.lower()

    payload_names = {
        f"foreman-{tag}.apk",
        f"foreman-linux-{tag}.tar.gz",
    }
    missing_checksums = sorted(payload_names - set(checksums))
    unexpected_checksums = sorted(set(checksums) - payload_names)
    if missing_checksums:
        errors.append(f"missing checksums: {', '.join(missing_checksums)}")
    if unexpected_checksums:
        errors.append(f"unexpected checksums: {', '.join(unexpected_checksums)}")

    for name in sorted(payload_names & set(checksums) & actual_names):
        path = directory / name
        if path.is_file() and path.stat().st_size > 0:
            actual_digest = _sha256(path)
            if actual_digest != checksums[name]:
                errors.append(f"checksum mismatch for {name}")
    return errors


def verify_release_signature(directory: Path, expected_fingerprint: str) -> list[str]:
    certificate = directory / "foreman-release-cert.pem"
    signature = directory / "SHA256SUMS.sig"
    manifest = directory / "SHA256SUMS"
    try:
        der = ssl.PEM_cert_to_DER_cert(certificate.read_text(encoding="ascii"))
        if hashlib.sha256(der).hexdigest() != expected_fingerprint:
            return ["release signing certificate does not match the pinned fingerprint"]
        extracted = subprocess.run(
            ["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10,
        )
        if extracted.returncode != 0 or not extracted.stdout:
            return ["release signing certificate is invalid"]
        with tempfile.NamedTemporaryFile() as public_key:
            public_key.write(extracted.stdout)
            public_key.flush()
            verified = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", public_key.name, "-signature", str(signature), str(manifest)],
                check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
            )
        return [] if verified.returncode == 0 else ["release manifest signature is invalid"]
    except (OSError, UnicodeError, ValueError, subprocess.TimeoutExpired):
        return ["release manifest signature could not be verified"]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--directory", type=Path)
    source.add_argument("--manifest", type=Path)
    return parser


def main() -> None:
    args = _parser().parse_args()
    try:
        if args.directory is not None:
            errors = verify_directory(args.directory, args.tag)
        else:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            if not isinstance(manifest, dict):
                raise ValueError("release manifest root must be an object")
            errors = verify_manifest(manifest, args.tag)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise SystemExit(str(error)) from error

    if errors:
        raise SystemExit("\n".join(errors))
    print(f"release assets verified for {args.tag}")


if __name__ == "__main__":
    main()
