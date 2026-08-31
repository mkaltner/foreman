#!/usr/bin/env python3
"""External entry point that survives replacement of foreman.service."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


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
    sys.path.insert(0, str(args.install_directory))
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
