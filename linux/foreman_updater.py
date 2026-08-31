#!/usr/bin/env python3
"""External entry point that survives replacement of foreman.service."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import sys


RUNTIME_FILES = ("server_update.py", "release_updates.py", "state.py")


def prepare_runtime(state_directory: Path, operation: str, install_directory: Path) -> Path:
    """Copy the helper runtime before activation can replace the installation."""
    runtime = state_directory / "updates" / operation / "helper-runtime"
    ready = runtime / ".ready"
    if ready.is_file():
        return runtime
    shutil.rmtree(runtime, ignore_errors=True)
    runtime.mkdir(parents=True, mode=0o700)
    for name in RUNTIME_FILES:
        source = install_directory / name
        if not source.is_file():
            raise RuntimeError("The installed updater runtime is incomplete.")
        destination = runtime / name
        shutil.copy2(source, destination)
        os.chmod(destination, 0o600)
    ready.write_text("1\n", encoding="ascii")
    os.chmod(ready, 0o600)
    return runtime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--operation", required=True)
    parser.add_argument("--state-directory", type=Path, required=True)
    parser.add_argument("--install-directory", type=Path, required=True)
    parser.add_argument("--launcher-file", type=Path, required=True)
    parser.add_argument("--unit-file", type=Path, required=True)
    parser.add_argument("--helper-file", type=Path, required=True)
    parser.add_argument("--health-port", type=int, default=8766)
    args = parser.parse_args()
    runtime = prepare_runtime(args.state_directory, args.operation, args.install_directory)
    sys.path.insert(0, str(runtime))
    from server_update import run_external_helper

    return run_external_helper(
        operation_id=args.operation,
        state_directory=args.state_directory,
        install_directory=args.install_directory,
        launcher_file=args.launcher_file,
        unit_file=args.unit_file,
        helper_file=args.helper_file,
        health_port=args.health_port,
    )


if __name__ == "__main__":
    raise SystemExit(main())
