#!/usr/bin/env python3
"""Verify the signer certificate fingerprint in apksigner output."""

from __future__ import annotations

import argparse
import re
import sys


SHA256_VALUE = re.compile(r"(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])", re.IGNORECASE)
SHA256_LABEL = re.compile(r"\bsha(?:-|\s)?256\b", re.IGNORECASE)
CERTIFICATE_LABEL = re.compile(r"\bcertificate\b", re.IGNORECASE)
DIGEST_LABEL = re.compile(r"\b(?:digest|fingerprint)\b", re.IGNORECASE)


def certificate_sha256(output: str) -> str:
    """Return the sole certificate SHA-256 digest in apksigner output."""

    candidates: list[str] = []
    for line in output.splitlines():
        if not (
            CERTIFICATE_LABEL.search(line)
            and SHA256_LABEL.search(line)
            and DIGEST_LABEL.search(line)
        ):
            continue
        candidates.extend(match.lower() for match in SHA256_VALUE.findall(line))

    unique = list(dict.fromkeys(candidates))
    if not unique:
        raise ValueError("apksigner output has no certificate SHA-256 fingerprint")
    if len(unique) != 1:
        raise ValueError("apksigner output has multiple certificate SHA-256 fingerprints")
    return unique[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected", required=True, help="Expected lowercase SHA-256 digest")
    args = parser.parse_args()

    if not re.fullmatch(r"[0-9a-f]{64}", args.expected):
        raise SystemExit("expected certificate must be a lowercase SHA-256 digest")

    try:
        actual = certificate_sha256(sys.stdin.read())
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if actual != args.expected:
        raise SystemExit("APK signing certificate does not match release.properties")

    print("APK signing certificate matches release.properties")


if __name__ == "__main__":
    main()
