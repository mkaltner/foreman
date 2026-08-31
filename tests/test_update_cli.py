from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from typing import Any


ROOT = Path(__file__).parents[1]


def operation(phase: str, *, code: str | None = None) -> dict[str, Any]:
    return {
        "id": "fmu_1234567890abcdef",
        "phase": phase,
        "currentVersion": "1.0.2",
        "targetVersion": "1.0.3",
        "source": "Official Foreman GitHub releases",
        "sourceUrl": "https://github.com/mkaltner/foreman/releases",
        "releaseNotesUrl": "https://github.com/mkaltner/foreman/releases/tag/v1.0.3",
        "progress": 100 if phase in {"succeeded", "rolledBack", "recoveryRequired"} else 5,
        "createdAt": "2026-08-31T00:00:00Z",
        "updatedAt": "2026-08-31T00:01:00Z",
        "message": "Safe update status.",
        **({"resultCode": code} if code else {}),
        **({"recoveryCommand": "foreman update --recover"} if phase == "recoveryRequired" else {}),
    }


def check(*, available: bool, blockers=None) -> dict[str, Any]:
    return {
        "currentVersion": "1.0.2",
        "releaseBuild": True,
        "source": "Official Foreman GitHub releases",
        "sourceUrl": "https://github.com/mkaltner/foreman/releases",
        "updateAvailable": available,
        "target": {
            "version": "1.0.3", "tag": "v1.0.3", "title": "Foreman 1.0.3",
            "publishedAt": "2026-08-31T00:00:00Z",
            "releaseNotesUrl": "https://github.com/mkaltner/foreman/releases/tag/v1.0.3",
            "artifactAvailable": True,
        } if available else None,
        "blockers": blockers or [],
        "operation": None,
    }


class FakeControl:
    def __init__(self, path: Path, responses: dict[str, list[dict[str, Any]]]) -> None:
        self.path = path
        self.responses = responses
        self.calls: list[str] = []
        self.server: asyncio.Server | None = None

    async def start(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.server = await asyncio.start_unix_server(self.handle, self.path)

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        request = json.loads(await reader.readline())
        message_type = request["type"]
        self.calls.append(message_type)
        values = self.responses[message_type]
        payload = values.pop(0) if len(values) > 1 else values[0]
        writer.write((json.dumps({
            "version": 1,
            "id": request["id"],
            "type": f"{message_type}.result",
            "payload": payload,
        }) + "\n").encode())
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()


class UpdateCliTests(unittest.IsolatedAsyncioTestCase):
    async def run_cli(self, state: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        environment = {**os.environ, "FOREMAN_STATE_DIRECTORY": str(state)}
        return await asyncio.to_thread(
            subprocess.run,
            [sys.executable, str(ROOT / "linux/update_cli.py"), *arguments],
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )

    async def test_check_exit_codes_for_current_available_and_blocked(self) -> None:
        cases = [
            (check(available=False), 0, "Target: none"),
            (check(available=True), 10, "Target: 1.0.3"),
            (check(available=True, blockers=[{"category": "pendingInput", "count": 1}]), 20, "Blocked by: 1 pending input"),
        ]
        for index, (response, exit_code, expected) in enumerate(cases):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as temporary:
                state = Path(temporary)
                server = FakeControl(state / "control.sock", {"update.check": [response]})
                await server.start()
                try:
                    result = await self.run_cli(state, "--check")
                finally:
                    await server.stop()
                self.assertEqual(result.returncode, exit_code, result.stderr)
                self.assertIn(expected, result.stdout)

    async def test_update_waits_through_reconnect_contract_and_reports_success(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            server = FakeControl(state / "control.sock", {
                "update.check": [check(available=True)],
                "update.start": [{"operation": operation("downloading")}],
                "update.status": [{"operation": operation("succeeded", code="updated")}],
            })
            await server.start()
            try:
                result = await self.run_cli(state, "--yes")
            finally:
                await server.stop()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(server.calls, ["update.check", "update.start", "update.status"])
            self.assertIn("Update complete", result.stdout.lower().replace("update complete", "Update complete"))

    async def test_status_returns_meaningful_terminal_exit_codes(self) -> None:
        for phase, code, expected_exit in (
            ("rolledBack", "rollbackSucceeded", 24),
            ("recoveryRequired", "rollbackFailed", 25),
            ("blocked", "activeWork", 20),
            ("failed", "verificationFailed", 21),
            ("failed", "authorizationRevoked", 23),
        ):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                state = Path(temporary)
                server = FakeControl(state / "control.sock", {"update.status": [{"operation": operation(phase, code=code)}]})
                await server.start()
                try:
                    result = await self.run_cli(state, "--status")
                finally:
                    await server.stop()
                self.assertEqual(result.returncode, expected_exit)
                self.assertNotIn("TOKEN", result.stdout + result.stderr)

    async def test_service_unavailable_has_sysexits_style_code(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = await self.run_cli(Path(temporary), "--check")
        self.assertEqual(result.returncode, 69)
        self.assertIn("start it", result.stderr)


if __name__ == "__main__":
    unittest.main()
