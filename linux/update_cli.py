"""Local CLI for the shared Foreman update protocol."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import sys
import time
from typing import Any

from protocol import VERSION
from server_update import OperationStore, UpdateFailure, recover_latest


EXIT_AVAILABLE = 10
EXIT_BLOCKED = 20
EXIT_VERIFICATION = 21
EXIT_CONCURRENT = 22
EXIT_ACTIVATION = 23
EXIT_ROLLED_BACK = 24
EXIT_RECOVERY_REQUIRED = 25
EXIT_UNAVAILABLE = 69
TERMINAL = {"succeeded", "rolledBack", "recoveryRequired", "blocked", "failed", "interrupted"}


class ProtocolFailure(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


async def request(socket_path: Path, message_type: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(socket_path), 3)
    except (OSError, TimeoutError) as error:
        raise ProtocolFailure("serviceUnavailable", "Foreman is unavailable; start it and try again.") from error
    try:
        request_id = "cli-" + os.urandom(8).hex()
        writer.write((json.dumps({
            "version": VERSION,
            "id": request_id,
            "type": message_type,
            "payload": payload or {},
        }, separators=(",", ":")) + "\n").encode())
        await writer.drain()
        raw = await asyncio.wait_for(reader.readline(), 125)
        response = json.loads(raw)
        if response.get("type") == "error":
            details = response.get("payload", {})
            raise ProtocolFailure(str(details.get("code", "requestFailed")), str(details.get("message", "Update request failed.")))
        if response.get("id") != request_id or not isinstance(response.get("payload"), dict):
            raise ProtocolFailure("invalidResponse", "Foreman returned an invalid update response.")
        return response["payload"]
    except (OSError, TimeoutError, json.JSONDecodeError) as error:
        raise ProtocolFailure("serviceUnavailable", "Foreman became unavailable during the update request.") from error
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except (OSError, ConnectionError):
            pass


def blocker_text(blockers: list[dict[str, Any]]) -> str:
    labels = {
        "workingSession": "working sessions",
        "waitingSession": "waiting sessions",
        "pendingApproval": "pending approvals",
        "pendingInput": "pending input",
    }
    return ", ".join(f"{item.get('count', 0)} {labels.get(str(item.get('category')), 'active items')}" for item in blockers)


def show_check(check: dict[str, Any]) -> None:
    build_suffix = "" if check.get("releaseBuild") is not False else " (development build)"
    print(f"Installed: {check.get('currentVersion', 'unknown')}{build_suffix}")
    target = check.get("target")
    print(f"Target: {target.get('version') if isinstance(target, dict) else 'none'}")
    print(f"Source: {check.get('source', 'Official Foreman releases')}")
    if isinstance(target, dict) and target.get("releaseNotesUrl"):
        print(f"Release notes: {target['releaseNotesUrl']}")
    if check.get("updateAvailable"):
        print("Restart: foreman.service only")
        print("Recovery: health-check the target and restore the previous payload automatically on failure")
    blockers = check.get("blockers")
    if isinstance(blockers, list) and blockers:
        print(f"Blocked by: {blocker_text(blockers)}")


def show_operation(operation: dict[str, Any] | None) -> None:
    if not operation:
        print("No update operation has been recorded.")
        return
    phase = str(operation.get("phase", "unknown"))
    label = {
        "downloading": "Downloading", "verifying": "Verifying signature",
        "staging": "Staging", "activationScheduled": "Activation scheduled",
        "activating": "Activating", "restarting": "Restarting Foreman",
        "healthChecking": "Health checking", "rollingBack": "Rolling back",
        "succeeded": "Update complete", "rolledBack": "Previous version restored",
        "recoveryRequired": "Recovery required", "blocked": "Blocked",
        "failed": "Update failed", "interrupted": "Update interrupted",
    }.get(phase, phase)
    print(f"Update {operation.get('id')}: {label}")
    print(f"Version: {operation.get('currentVersion')} -> {operation.get('targetVersion')}")
    if operation.get("message"):
        print(operation["message"])
    if operation.get("recoveryCommand"):
        print(f"Recovery: {operation['recoveryCommand']}")


def exit_for_code(code: str, phase: str | None = None) -> int:
    if phase == "rolledBack":
        return EXIT_ROLLED_BACK
    if phase == "recoveryRequired":
        return EXIT_RECOVERY_REQUIRED
    if code == "updateRecoveryRequired":
        return EXIT_RECOVERY_REQUIRED
    if code in {"updateBlocked", "activeWork", "activeStateUnavailable"}:
        return EXIT_BLOCKED
    if code in {"verificationFailed", "verificationUnavailable", "missingAsset", "untrustedSource", "incompatibleRelease"}:
        return EXIT_VERIFICATION
    if code == "updateConcurrent":
        return EXIT_CONCURRENT
    if code == "serviceUnavailable":
        return EXIT_UNAVAILABLE
    return EXIT_ACTIVATION


async def wait_for_result(socket_path: Path, operation_id: str, timeout_seconds: int = 150) -> tuple[dict[str, Any] | None, int]:
    deadline = time.monotonic() + timeout_seconds
    delay = 0.5
    last: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        try:
            result = await request(socket_path, "update.status", {"operationId": operation_id})
            operation = result.get("operation")
            if isinstance(operation, dict):
                if operation.get("phase") != (last or {}).get("phase"):
                    show_operation(operation)
                last = operation
                if operation.get("phase") in TERMINAL:
                    if operation.get("phase") == "succeeded":
                        return operation, 0
                    return operation, exit_for_code(str(operation.get("resultCode", "")), str(operation.get("phase")))
        except ProtocolFailure:
            pass
        await asyncio.sleep(delay)
        delay = min(delay * 1.5, 3)
    print("Timed out waiting for Foreman to reconnect. Run `foreman update --status`.", file=sys.stderr)
    return last, EXIT_UNAVAILABLE


async def async_main(args: argparse.Namespace) -> int:
    socket_path = Path(args.state_directory).expanduser() / "control.sock"
    if args.status:
        result = await request(socket_path, "update.status")
        operation = result.get("operation")
        show_operation(operation if isinstance(operation, dict) else None)
        if isinstance(operation, dict):
            phase = str(operation.get("phase", ""))
            if phase == "succeeded" or phase not in TERMINAL:
                return 0
            return exit_for_code(str(operation.get("resultCode", "")), phase)
        return 0
    check = await request(socket_path, "update.check")
    show_check(check)
    if args.check:
        if check.get("blockers"):
            return EXIT_BLOCKED
        return EXIT_AVAILABLE if check.get("updateAvailable") else 0
    if check.get("blockers"):
        print("Finish or interrupt the listed work, then run the update again.", file=sys.stderr)
        return EXIT_BLOCKED
    if not check.get("updateAvailable"):
        print(
            "Automatic updates require an installed official release build."
            if check.get("releaseBuild") is False
            else "Foreman is already current."
        )
        return 0
    if not args.yes:
        try:
            answer = input("Stage, activate, restart Foreman, and roll back automatically on failure? [y/N] ")
        except EOFError:
            print("Confirmation is required; rerun interactively or use --yes after reviewing --check.", file=sys.stderr)
            return 2
        if answer.strip().lower() not in {"y", "yes"}:
            print("Update cancelled.")
            return 0
    started = await request(socket_path, "update.start", {"requestId": "cli_" + os.urandom(12).hex()})
    operation = started.get("operation", started)
    if not isinstance(operation, dict) or not isinstance(operation.get("id"), str):
        raise ProtocolFailure("invalidResponse", "Foreman did not return an update operation.")
    show_operation(operation)
    _, result = await wait_for_result(socket_path, operation["id"])
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="foreman update")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--status", action="store_true")
    modes.add_argument("--recover", action="store_true")
    parser.add_argument("--yes", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--state-directory", default=os.environ.get("FOREMAN_STATE_DIRECTORY", "~/.local/state/foreman"), help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.recover:
        home = Path.home()
        try:
            return recover_latest(
                state_directory=Path(args.state_directory).expanduser(),
                install_directory=Path(os.environ.get("FOREMAN_INSTALL_DIR", home / ".local/share/foreman")),
                launcher_file=home / ".local/bin/foreman",
                unit_file=home / ".config/systemd/user/foreman.service",
                helper_file=home / ".local/libexec/foreman-updater",
            )
        except UpdateFailure as error:
            print(error.message, file=sys.stderr)
            return exit_for_code(error.code)
    try:
        return asyncio.run(async_main(args))
    except ProtocolFailure as error:
        print(str(error), file=sys.stderr)
        return exit_for_code(error.code)
    except KeyboardInterrupt:
        print("Update wait interrupted; the durable operation will continue. Run `foreman update --status`.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
