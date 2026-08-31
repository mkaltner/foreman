#!/usr/bin/env python3
"""External entry point that survives replacement of foreman.service."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys


RUNTIME_FILES = ("server_update.py", "release_updates.py", "state.py")
OPERATION_ID = re.compile(r"^fmu_[A-Za-z0-9_-]{16,80}$")
ACTIVATION_PHASES = {
    "activationScheduled", "activating", "restarting", "healthChecking", "rollingBack",
}


def prepare_runtime(state_directory: Path, operation: str, install_directory: Path) -> Path:
    """Copy the helper runtime before activation can replace the installation."""
    if not OPERATION_ID.fullmatch(operation):
        raise RuntimeError("The update operation ID is invalid.")
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


def latest_operation(state_directory: Path) -> str | None:
    try:
        operation = (state_directory / "updates" / "latest").read_text(
            encoding="ascii"
        ).strip()
    except (OSError, UnicodeError):
        return None
    return operation if OPERATION_ID.fullmatch(operation) else None


def latest_activation_operation(state_directory: Path) -> str | None:
    operation_id = latest_operation(state_directory)
    if operation_id is None:
        return None
    try:
        raw = (
            state_directory / "updates" / "operations" / f"{operation_id}.json"
        ).read_bytes()
        if len(raw) > 32 * 1024:
            return None
        operation = json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None
    return operation_id if (
        isinstance(operation, dict)
        and operation.get("id") == operation_id
        and operation.get("phase") in ACTIVATION_PHASES
    ) else None


def main() -> int:
    parser = argparse.ArgumentParser()
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--operation")
    operation.add_argument("--resume-latest", action="store_true")
    parser.add_argument("--state-directory", type=Path, required=True)
    parser.add_argument("--install-directory", type=Path, required=True)
    parser.add_argument("--launcher-file", type=Path, required=True)
    parser.add_argument("--unit-file", type=Path, required=True)
    parser.add_argument("--helper-file", type=Path, required=True)
    parser.add_argument("--health-port", type=int, default=8766)
    args = parser.parse_args()
    operation_id = (
        latest_activation_operation(args.state_directory)
        if args.resume_latest
        else args.operation
    )
    if operation_id is None:
        return 0
    runtime = prepare_runtime(args.state_directory, operation_id, args.install_directory)
    sys.path.insert(0, str(runtime))
    from server_update import ACTIVATION_PHASES, OperationStore, run_external_helper

    if args.resume_latest:
        stored = OperationStore(args.state_directory).read(operation_id)
        if stored is None or stored.get("phase") not in ACTIVATION_PHASES:
            return 0

    return run_external_helper(
        operation_id=operation_id,
        state_directory=args.state_directory,
        install_directory=args.install_directory,
        launcher_file=args.launcher_file,
        unit_file=args.unit_file,
        helper_file=args.helper_file,
        health_port=args.health_port,
    )


if __name__ == "__main__":
    raise SystemExit(main())
