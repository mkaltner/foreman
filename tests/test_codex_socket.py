from __future__ import annotations

import asyncio
import json
import os
import socket
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux" / "vendor"))
sys.path.insert(0, str(ROOT / "linux"))

from websockets.asyncio.server import unix_serve  # noqa: E402

import protocol  # noqa: E402
from codex import (  # noqa: E402
    Codex,
    CodexError,
    SHARED_DESKTOP_LIVE_STATUS_AVAILABLE,
    SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE,
)
from foreman_service import Foreman  # noqa: E402
from state import State  # noqa: E402


def thread(status: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "id": "thread-shared",
        "cwd": "/projects/example",
        "preview": "Shared thread",
        "name": None,
        "status": status or {"type": "idle"},
        "updatedAt": 100,
        "recencyAt": 100,
        "turns": [],
    }


def result(method: str, params: dict[str, Any]) -> dict[str, Any]:
    if method == "initialize":
        return {"userAgent": "fake"}
    if method == "thread/list":
        return {"data": [thread()], "nextCursor": None}
    if method == "thread/resume":
        return {
            "thread": thread(),
            "model": "model-test",
            "reasoningEffort": "high",
            "approvalPolicy": "on-request",
            "approvalsReviewer": "auto_review",
            "activePermissionProfile": {"id": ":workspace"},
        }
    if method == "thread/read":
        return {"thread": thread()}
    if method == "thread/start":
        return {
            "thread": thread(),
            "model": "model-test",
            "reasoningEffort": "high",
            "approvalPolicy": "on-request",
            "approvalsReviewer": "user",
            "activePermissionProfile": {"id": ":workspace"},
        }
    if method == "permissionProfile/list":
        return {
            "data": [
                {"id": ":workspace", "allowed": True},
                {"id": ":danger-full-access", "allowed": True},
                {"id": ":read-only", "allowed": True},
            ],
            "nextCursor": None,
        }
    if method == "model/list":
        return {
            "data": [
                {
                    "id": "model-test",
                    "model": "model-test",
                    "displayName": "Test",
                    "description": "Test",
                    "hidden": False,
                    "isDefault": True,
                    "defaultReasoningEffort": "high",
                    "supportedReasoningEfforts": [
                        {"reasoningEffort": "high", "description": "High"}
                    ],
                }
            ],
            "nextCursor": None,
        }
    if method == "turn/start":
        return {"turn": {"id": "turn-one"}}
    return {}


class FakeSocketServer:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.server: Any | None = None
        self.connections: set[Any] = set()
        self.methods: list[str] = []
        self.messages: list[dict[str, Any]] = []
        self.server_responses: dict[str, asyncio.Future[dict[str, Any]]] = {}

    async def start(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.server = await unix_serve(self.handle, str(self.path))

    async def handle(self, websocket: Any) -> None:
        self.connections.add(websocket)
        try:
            async for raw in websocket:
                message = json.loads(raw)
                self.messages.append(message)
                if "id" in message and ("result" in message or "error" in message):
                    future = self.server_responses.get(json.dumps(message["id"]))
                    if future and not future.done():
                        future.set_result(message)
                    continue
                method = message.get("method", "")
                self.methods.append(method)
                if "id" in message and "method" in message:
                    await websocket.send(
                        json.dumps(
                            {
                                "id": message["id"],
                                "result": result(method, message.get("params", {})),
                            }
                        )
                    )
        finally:
            self.connections.discard(websocket)

    async def emit(self, method: str, params: dict[str, Any]) -> None:
        payload = json.dumps({"method": method, "params": params})
        await asyncio.gather(*(item.send(payload) for item in self.connections))

    async def request(
        self, request_id: Any, method: str, params: dict[str, Any]
    ) -> dict[str, Any]:
        key = json.dumps(request_id)
        future = asyncio.get_running_loop().create_future()
        self.server_responses[key] = future
        payload = json.dumps({"id": request_id, "method": method, "params": params})
        await asyncio.gather(*(item.send(payload) for item in self.connections))
        try:
            return await asyncio.wait_for(future, 2)
        finally:
            self.server_responses.pop(key, None)

    async def stop(self) -> None:
        if not self.server:
            return
        self.server.close(close_connections=True)
        await self.server.wait_closed()
        self.server = None
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


FAKE_CODEX = r"""#!/usr/bin/env python3
import asyncio, json, os, pathlib, signal, socket, sys
sys.path.insert(0, os.environ["FAKE_CODEX_VENDOR"])
from websockets.asyncio.server import unix_serve

if "generate-json-schema" in sys.argv:
    output = pathlib.Path(sys.argv[-1])
    output.mkdir(parents=True, exist_ok=True)
    (output / "ClientRequest.json").write_text('{"oneOf":[]}')
    raise SystemExit(0)

listen = sys.argv[sys.argv.index("--listen") + 1]
path = listen.removeprefix("unix://")
if not path:
    home = pathlib.Path(os.environ.get("CODEX_HOME", pathlib.Path.home() / ".codex"))
    path = str(home / "app-server-control" / "app-server-control.sock")
log = pathlib.Path(os.environ["FAKE_CODEX_LOG"])
stopped = asyncio.Event()

def response(method):
    thread = {
        "id":"thread-shared","cwd":"/projects/example","preview":"Shared thread",
        "name":None,"status":{"type":"idle"},"updatedAt":100,"recencyAt":100,"turns":[]
    }
    if method == "initialize": return {"userAgent":"fake"}
    if method == "thread/list": return {"data":[thread],"nextCursor":None}
    if method == "thread/resume":
        return {"thread":thread,"model":"model-test","reasoningEffort":"high"}
    if method == "thread/read": return {"thread":thread}
    if method == "thread/start":
        return {"thread":thread,"model":"model-test","reasoningEffort":"high"}
    if method == "turn/start": return {"turn":{"id":"turn-one"}}
    return {}

async def handler(ws):
    async for raw in ws:
        message = json.loads(raw)
        method = message.get("method","")
        with log.open("a") as stream:
            stream.write(method + "\n")
        if "id" in message:
            await ws.send(json.dumps({"id":message["id"],"result":response(method)}))

async def main():
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    if pathlib.Path(path).exists():
        probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            probe.connect(path)
        except OSError:
            pathlib.Path(path).unlink(missing_ok=True)
        else:
            raise RuntimeError("socket is already active")
        finally:
            probe.close()
    server = await unix_serve(handler, path)
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(signum, stopped.set)
    await stopped.wait()
    server.close(close_connections=True)
    await server.wait_closed()

asyncio.run(main())
"""


class SocketAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.socket_path = self.base / "control.sock"
        self.fallback_socket_path = self.base / "foreman-fallback.sock"
        self.log_path = self.base / "requests.log"
        self.executable = self.base / "fake-codex"
        self.executable.write_text(FAKE_CODEX, encoding="utf-8")
        self.executable.chmod(
            self.executable.stat().st_mode | stat.S_IXUSR
        )
        self.previous_log = os.environ.get("FAKE_CODEX_LOG")
        self.previous_vendor = os.environ.get("FAKE_CODEX_VENDOR")
        os.environ["FAKE_CODEX_LOG"] = str(self.log_path)
        os.environ["FAKE_CODEX_VENDOR"] = str(ROOT / "linux" / "vendor")

    async def asyncTearDown(self) -> None:
        if self.previous_log is None:
            os.environ.pop("FAKE_CODEX_LOG", None)
        else:
            os.environ["FAKE_CODEX_LOG"] = self.previous_log
        if self.previous_vendor is None:
            os.environ.pop("FAKE_CODEX_VENDOR", None)
        else:
            os.environ["FAKE_CODEX_VENDOR"] = self.previous_vendor
        self.temporary.cleanup()

    async def test_attaches_to_healthy_socket_without_launch_or_ownership(self) -> None:
        server = FakeSocketServer(self.socket_path)
        await server.start()
        adapter = Codex(
            "missing-codex",
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        await adapter.start()
        try:
            self.assertIsNone(adapter.process)
            self.assertEqual(
                adapter.runtime_status, SHARED_DESKTOP_LIVE_STATUS_AVAILABLE
            )
            self.assertEqual(adapter.socket_path, self.socket_path)
            self.assertFalse(self.fallback_socket_path.exists())
            self.assertEqual(server.methods.count("initialize"), 1)
            self.assertEqual(server.methods.count("thread/resume"), 1)
        finally:
            await adapter.stop()
        self.assertIsNotNone(server.server)
        await server.stop()

    async def test_launches_when_absent_and_stops_only_owned_process(self) -> None:
        adapter = Codex(
            str(self.executable),
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        await adapter.start()
        process = adapter.process
        self.assertIsNotNone(process)
        self.assertIsNone(process.returncode)
        self.assertFalse(self.socket_path.exists())
        self.assertEqual(adapter.socket_path, self.fallback_socket_path)
        self.assertEqual(
            adapter.runtime_status, SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE
        )
        await adapter.stop()
        self.assertIsNotNone(process.returncode)

    async def test_preserves_a_stale_desktop_socket_and_reports_attach_failure(
        self,
    ) -> None:
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stale.bind(str(self.socket_path))
        stale.close()
        inode = self.socket_path.stat().st_ino
        adapter = Codex(
            str(self.executable),
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        with self.assertRaisesRegex(CodexError, "exists but Foreman could not attach"):
            await adapter.start()
        try:
            self.assertIsNone(adapter.process)
            self.assertEqual(self.socket_path.stat().st_ino, inode)
            self.assertFalse(self.fallback_socket_path.exists())
        finally:
            await adapter.stop()

    async def test_does_not_replace_a_desktop_socket_rebound_during_attach(self) -> None:
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stale.bind(str(self.socket_path))
        stale.close()
        replacement: asyncio.Server | None = None

        async def close_client(
            _: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            writer.close()
            await writer.wait_closed()

        class RacingCodex(Codex):
            raced = False

            async def _open_websocket(self) -> Any:
                nonlocal replacement
                if self.socket_path == self.primary_socket_path and not self.raced:
                    self.raced = True
                    self.socket_path.unlink()
                    replacement = await asyncio.start_unix_server(
                        close_client, path=self.socket_path
                    )
                    raise ConnectionRefusedError("socket changed during attach")
                return await super()._open_websocket()

        adapter = RacingCodex(
            str(self.executable),
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        with self.assertRaisesRegex(CodexError, "WebSocket handshake failed"):
            await adapter.start()
        try:
            self.assertIsNone(adapter.process)
            self.assertFalse(self.fallback_socket_path.exists())
            self.assertIsNotNone(replacement)
        finally:
            await adapter.stop()
            if replacement:
                replacement.close()
                await replacement.wait_closed()

    async def test_falls_back_after_disconnect_and_does_not_resend_prompt(self) -> None:
        server = FakeSocketServer(self.socket_path)
        await server.start()
        adapter = Codex(
            str(self.executable),
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        await adapter.start()
        await adapter.subscribe_thread("thread-shared")
        await adapter.prompt("thread-shared", "once")
        await adapter.interrupt("thread-shared", "turn-one")
        shared_attached_at = adapter.attached_at
        self.assertEqual(server.methods.count("turn/start"), 1)
        self.assertEqual(server.methods.count("turn/interrupt"), 1)

        await server.stop()
        for _ in range(100):
            if (
                adapter.process is not None
                and adapter._websocket is not None
                and adapter.attached_at is not None
                and adapter.attached_at != shared_attached_at
            ):
                break
            await asyncio.sleep(0.05)
        self.assertIsNotNone(adapter.process)
        self.assertIsNotNone(adapter._websocket)
        self.assertEqual(adapter.socket_path, self.fallback_socket_path)
        self.assertEqual(
            adapter.runtime_status, SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE
        )
        child_methods = self.log_path.read_text(encoding="utf-8").splitlines()
        self.assertIn("thread/resume", child_methods)
        self.assertNotIn("turn/start", child_methods)
        self.assertNotIn("turn/interrupt", child_methods)
        await adapter.stop()

    async def test_reconnects_after_empty_thread_creation_before_first_prompt(self) -> None:
        server = FakeSocketServer(self.socket_path)
        await server.start()
        adapter = Codex(
            str(self.executable),
            lambda _: asyncio.sleep(0),
            self.socket_path,
            self.fallback_socket_path,
        )
        await adapter.start()
        created = await adapter.start_thread("/projects/example")
        self.assertEqual(created["turns"], [])
        await server.stop()
        for _ in range(100):
            if adapter.process is not None and adapter._websocket is not None:
                break
            await asyncio.sleep(0.05)
        prompted = await adapter.prompt(created["id"], "first")
        self.assertEqual(prompted["turn"]["id"], "turn-one")
        child_methods = self.log_path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(child_methods.count("turn/start"), 1)
        await adapter.stop()

    async def test_desktop_originated_live_and_terminal_status_reach_tcp_client(self) -> None:
        server = FakeSocketServer(self.socket_path)
        await server.start()
        repository_root = self.base / "projects"
        repository = repository_root / "example"
        repository.mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        state = State(self.base / "state")
        pairing_key, _ = state.create_pairing()

        def factory(executable: str, event: Any) -> Codex:
            return Codex(
                executable,
                event,
                self.socket_path,
                self.fallback_socket_path,
            )

        app = Foreman(
            "127.0.0.1",
            0,
            repository_root,
            state,
            "missing-codex",
            codex_factory=factory,
        )
        await app.start()
        socket = app.server.sockets[0]
        reader, writer = await asyncio.open_connection(*socket.getsockname()[:2])

        async def exchange(identifier: str, kind: str, payload: dict[str, Any]) -> None:
            writer.write(
                protocol.encode(
                    {
                        "version": 1,
                        "id": identifier,
                        "type": kind,
                        "payload": payload,
                    }
                )
            )
            await writer.drain()
            response = protocol.decode(await reader.readline())
            self.assertEqual(response["id"], identifier)

        await exchange(
            "pair",
            "pair",
            {"pairingKey": pairing_key, "deviceName": "Phone"},
        )
        await server.emit(
            "turn/started",
            {
                "threadId": "thread-shared",
                "turn": {"id": "turn-desktop"},
            },
        )
        working = protocol.decode(await asyncio.wait_for(reader.readline(), 2))
        self.assertEqual(working["payload"]["event"]["status"], "working")
        await server.emit(
            "turn/completed",
            {
                "threadId": "thread-shared",
                "turn": {"id": "turn-desktop", "status": "completed"},
            },
        )
        completed = protocol.decode(await asyncio.wait_for(reader.readline(), 2))
        self.assertEqual(completed["payload"]["event"]["status"], "completed")

        writer.close()
        await writer.wait_closed()
        await app.stop()
        await server.stop()

    async def test_full_approval_round_trip_and_desktop_resolution(self) -> None:
        server = FakeSocketServer(self.socket_path)
        await server.start()
        repository_root = self.base / "projects"
        repository = repository_root / "example"
        repository.mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        state = State(self.base / "state")
        pairing_key, _ = state.create_pairing()

        def factory(executable: str, event: Any) -> Codex:
            return Codex(executable, event, self.socket_path, self.fallback_socket_path)

        app = Foreman(
            "127.0.0.1", 0, repository_root, state, "missing-codex", codex_factory=factory
        )
        await app.start()
        listener = app.server.sockets[0]
        reader, writer = await asyncio.open_connection(*listener.getsockname()[:2])
        sequence = 0
        unsolicited: list[dict[str, Any]] = []

        async def client_request(kind: str, payload: dict[str, Any]) -> dict[str, Any]:
            nonlocal sequence
            sequence += 1
            identifier = f"client-{sequence}"
            writer.write(
                protocol.encode(
                    {"version": 1, "id": identifier, "type": kind, "payload": payload}
                )
            )
            await writer.drain()
            while True:
                message = protocol.decode(await asyncio.wait_for(reader.readline(), 2))
                if message.get("id") == identifier:
                    self.assertNotEqual(message["type"], "error", message)
                    return message["payload"]
                unsolicited.append(message)

        async def next_event(kind: str) -> dict[str, Any]:
            for index, message in enumerate(unsolicited):
                if message.get("type") == kind:
                    return unsolicited.pop(index)
            while True:
                message = protocol.decode(await asyncio.wait_for(reader.readline(), 2))
                if message.get("type") == kind:
                    return message
                unsolicited.append(message)

        await client_request(
            "pair", {"pairingKey": pairing_key, "deviceName": "Approval phone"}
        )
        await client_request(
            "session.subscribe", {"sessionId": "thread-shared"}
        )
        scenarios = [
            (
                "command-upstream",
                "item/commandExecution/requestApproval",
                {
                    "threadId": "thread-shared",
                    "turnId": "turn-command",
                    "itemId": "command-item",
                    "startedAtMs": 1_720_000_000_000,
                    "reason": "Run a safe check",
                    "command": "printf safe",
                    "cwd": "/projects/example",
                    "availableDecisions": ["accept", "decline", "cancel"],
                },
                {"type": "accept"},
                {"decision": "accept"},
            ),
            (
                "file-upstream",
                "item/fileChange/requestApproval",
                {
                    "threadId": "thread-shared",
                    "turnId": "turn-file",
                    "itemId": "file-item",
                    "startedAtMs": 1_720_000_001_000,
                    "reason": "Review the proposed edit",
                },
                {"type": "decline"},
                {"decision": "decline"},
            ),
            (
                "permission-upstream",
                "item/permissions/requestApproval",
                {
                    "threadId": "thread-shared",
                    "turnId": "turn-permission",
                    "itemId": "permission-item",
                    "startedAtMs": 1_720_000_002_000,
                    "cwd": "/projects/example",
                    "permissions": {
                        "fileSystem": {"write": ["/projects/example", "/tmp/safe"]},
                        "network": {"enabled": True},
                    },
                },
                {
                    "type": "grant",
                    "scope": "turn",
                    "permissions": {"fileSystem": {"write": ["/projects/example"]}},
                },
                {
                    "permissions": {"fileSystem": {"write": ["/projects/example"]}},
                    "scope": "turn",
                },
            ),
        ]
        for upstream_id, method, params, decision, expected in scenarios:
            if method == "item/fileChange/requestApproval":
                await server.emit(
                    "item/started",
                    {
                        "threadId": "thread-shared",
                        "turnId": params["turnId"],
                        "item": {
                            "id": params["itemId"],
                            "type": "fileChange",
                            "status": "inProgress",
                            "changes": [
                                {
                                    "path": "/projects/example/safe.txt",
                                    "kind": "update",
                                    "diff": "-old\n+new",
                                }
                            ],
                        },
                    },
                )
                await next_event("session.event")
            upstream = asyncio.create_task(server.request(upstream_id, method, params))
            requested = await next_event("approval.requested")
            approval = requested["payload"]["approval"]
            self.assertNotEqual(approval["id"], upstream_id)
            listed = await client_request("approval.list", {})
            self.assertTrue(any(item["id"] == approval["id"] for item in listed["approvals"]))
            await client_request(
                "approval.respond",
                {"approvalId": approval["id"], "decision": decision},
            )
            self.assertEqual((await upstream)["result"], expected)
            await server.emit(
                "serverRequest/resolved",
                {"threadId": "thread-shared", "requestId": upstream_id},
            )
            resolved = await next_event("approval.resolved")
            self.assertEqual(resolved["payload"]["approval"]["status"], "resolved")

        desktop = asyncio.create_task(
            server.request(
                "desktop-first",
                "item/commandExecution/requestApproval",
                {
                    "threadId": "thread-shared",
                    "turnId": "turn-desktop",
                    "itemId": "desktop-command",
                    "startedAtMs": 1_720_000_003_000,
                    "availableDecisions": ["accept", "decline"],
                },
            )
        )
        requested = await next_event("approval.requested")
        desktop_approval_id = requested["payload"]["approval"]["id"]
        await server.emit(
            "serverRequest/resolved",
            {"threadId": "thread-shared", "requestId": "desktop-first"},
        )
        resolved = await next_event("approval.resolved")
        self.assertEqual(resolved["payload"]["approval"]["resolution"], "resolvedElsewhere")
        self.assertNotIn(desktop_approval_id, [item["id"] for item in app.codex.list_approvals()])
        desktop.cancel()
        await asyncio.gather(desktop, return_exceptions=True)

        writer.close()
        await writer.wait_closed()
        await app.stop()
        await server.stop()


if __name__ == "__main__":
    unittest.main()
