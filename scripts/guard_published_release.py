#!/usr/bin/env python3
"""Return a newly published GitHub release to draft when assets are incomplete."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Callable, Sequence

try:
    from .verify_release_assets import verify_directory, verify_manifest
except ImportError:  # Executed directly from scripts/ in GitHub Actions.
    from verify_release_assets import verify_directory, verify_manifest


Runner = Callable[..., subprocess.CompletedProcess[str]]


def _run(runner: Runner, arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return runner(arguments, check=True, text=True, capture_output=True)


def return_to_draft(
    repository: str,
    release_id: str,
    runner: Runner = subprocess.run,
) -> None:
    _run(
        runner,
        [
            "gh",
            "api",
            "--method",
            "PATCH",
            f"repos/{repository}/releases/{release_id}",
            "-F",
            "draft=true",
        ],
    )


def guard_release(
    repository: str,
    release_id: str,
    tag: str,
    runner: Runner = subprocess.run,
) -> list[str]:
    errors: list[str] = []
    try:
        response = _run(
            runner,
            ["gh", "api", f"repos/{repository}/releases/{release_id}"],
        )
        manifest: Any = json.loads(response.stdout)
        if not isinstance(manifest, dict):
            errors.append("release manifest root must be an object")
        else:
            errors.extend(verify_manifest(manifest, tag))

        if not errors:
            with tempfile.TemporaryDirectory(prefix="foreman-release-guard-") as temporary:
                directory = Path(temporary)
                _run(
                    runner,
                    [
                        "gh",
                        "release",
                        "download",
                        tag,
                        "--repo",
                        repository,
                        "--dir",
                        str(directory),
                    ],
                )
                errors.extend(verify_directory(directory, tag))
    except (json.JSONDecodeError, OSError, subprocess.CalledProcessError, ValueError) as error:
        errors.append(f"release asset validation failed: {error}")

    if errors:
        try:
            return_to_draft(repository, release_id, runner)
        except (OSError, subprocess.CalledProcessError) as error:
            errors.append(f"failed to return incomplete release to draft: {error}")
    return errors


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--tag", required=True)
    return parser


def main() -> None:
    args = _parser().parse_args()
    errors = guard_release(args.repository, args.release_id, args.tag)
    if errors:
        draft_failed = any(
            error.startswith("failed to return incomplete release to draft:")
            for error in errors
        )
        outcome = (
            f"\nRelease {args.tag} is incomplete and could not be returned to "
            "draft. Inspect it immediately and do not distribute its assets."
            if draft_failed
            else f"\nRelease {args.tag} was incomplete and has been returned to "
            "draft. Upload and verify every required asset before publishing it "
            "again."
        )
        raise SystemExit(
            "\n".join(errors) + outcome
        )
    print(f"published release assets verified for {args.tag}")


if __name__ == "__main__":
    main()
