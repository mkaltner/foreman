#!/usr/bin/env python3
"""Fail when release metadata drifts across Foreman deliverables."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise SystemExit(f"{path}:{number}: expected key=value")
        values[key.strip()] = value.strip()
    return values


def require_text(path: Path, expected: str) -> None:
    if expected not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{path}: missing release value {expected!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Require metadata to match this v-prefixed tag")
    args = parser.parse_args()

    metadata = properties(ROOT / "release.properties")
    required = {
        "foremanVersion",
        "androidVersionCode",
        "protocolVersion",
        "androidSigningCertificateSha256",
    }
    missing = sorted(required - metadata.keys())
    if missing:
        raise SystemExit(f"release.properties: missing {', '.join(missing)}")

    version = metadata["foremanVersion"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?", version):
        raise SystemExit(f"release.properties: invalid foremanVersion {version!r}")
    if args.tag is not None and args.tag != f"v{version}":
        raise SystemExit(f"tag {args.tag!r} does not match v{version}")

    try:
        version_code = int(metadata["androidVersionCode"])
        protocol_version = int(metadata["protocolVersion"])
    except ValueError as error:
        raise SystemExit("Android and protocol versions must be integers") from error
    if version_code < 1 or protocol_version < 1:
        raise SystemExit("Android and protocol versions must be positive")
    if not re.fullmatch(r"[0-9a-f]{64}", metadata["androidSigningCertificateSha256"]):
        raise SystemExit("androidSigningCertificateSha256 must be a lowercase SHA-256 digest")

    require_text(ROOT / "linux/codex.py", f'FOREMAN_VERSION = "{version}"')
    require_text(ROOT / "linux/protocol.py", f"VERSION = {protocol_version}")
    require_text(ROOT / "web/src/protocol.ts", f"PROTOCOL_VERSION = {protocol_version};")
    require_text(
        ROOT / "android/app/src/main/java/net/kaltner/foreman/ForemanConnection.kt",
        "version = BuildConfig.FOREMAN_PROTOCOL_VERSION",
    )

    package = json.loads((ROOT / "web/package.json").read_text(encoding="utf-8"))
    lock = json.loads((ROOT / "web/package-lock.json").read_text(encoding="utf-8"))
    if package.get("version") != version:
        raise SystemExit("web/package.json version does not match release.properties")
    if lock.get("version") != version or lock.get("packages", {}).get("", {}).get("version") != version:
        raise SystemExit("web/package-lock.json version does not match release.properties")

    notes = ROOT / "docs" / "releases" / f"{version}.md"
    if not notes.is_file():
        raise SystemExit(f"missing release notes: {notes}")

    print(
        f"release metadata verified: v{version}, Android {version_code}, "
        f"protocol {protocol_version}"
    )


if __name__ == "__main__":
    main()
