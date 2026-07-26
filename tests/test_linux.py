from __future__ import annotations

import asyncio
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))

import protocol  # noqa: E402
from codex import normalize_item, session, status  # noqa: E402
from foreman_service import Foreman  # noqa: E402
from state import State  # noqa: E402


THREAD = {
    "id": "thread-1",
    "cwd": "/projects/example",
    "preview": "Hello Foreman",
    "name": None,
    "status": {"type": "idle"},
    "updatedAt": 123,
    "recencyAt": 124,
    "turns": [
        {
            "id": "turn-1",
            "status": "completed",
            "items": [
                {
                    "id": "user-1",
                    "type": "userMessage",
                    "content": [{"type": "text", "text": "Hello"}],
                },
                {
                    "id": "assistant-1",
                    "type": "agentMessage",
                    "text": "Hi",
                },
            ],
        }
    ],
}


class FakeCodex:
    def __init__(self, executable: str, on_event) -> None:
        self.on_event = on_event

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    async def list_threads(self) -> list[dict[str, Any]]:
        return [{**THREAD, "turns": []}]

    async def read_thread(self, thread_id: str) -> dict[str, Any]:
        return THREAD

    async def start_thread(self, cwd: str) -> dict[str, Any]:
        return {
            **THREAD,
            "id": "thread-new",
            "cwd": cwd,
            "turns": [],
            "status": {"type": "idle"},
        }

    async def resume_thread(self, thread_id: str) -> dict[str, Any]:
        return THREAD

    async def prompt(self, thread_id: str, text: str) -> dict[str, Any]:
        await self.on_event(
            {
                "method": "item/agentMessage/delta",
                "params": {
                    "threadId": thread_id,
                    "turnId": "turn-new",
                    "itemId": "item-new",
                    "delta": "Hello",
                },
            }
        )
        return {"turn": {"id": "turn-new"}}

    async def steer(
        self, thread_id: str, turn_id: str, text: str
    ) -> dict[str, Any]:
        return {"turnId": turn_id}

    async def interrupt(self, thread_id: str, turn_id: str) -> None:
        pass


class ProtocolTests(unittest.TestCase):
    def test_frame_round_trip_and_validation(self) -> None:
        message = {"version": 1, "id": "1", "type": "ping", "payload": {}}
        self.assertEqual(protocol.decode(protocol.encode(message)), message)
        with self.assertRaises(protocol.ProtocolError):
            protocol.decode(b'{"version":2,"type":"ping"}\n')
        with self.assertRaises(protocol.ProtocolError):
            protocol.decode(b"x" * (protocol.MAX_FRAME_BYTES + 1) + b"\n")


class StateTests(unittest.TestCase):
    def test_pairing_is_one_time_and_tokens_are_hashed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = State(directory)
            key, _ = state.create_pairing()
            token = state.pair(key, "Phone")
            self.assertIsNotNone(token)
            self.assertIsNone(state.pair(key, "Second phone"))
            self.assertTrue(state.authenticate(token or ""))
            self.assertFalse(state.authenticate("fmt_wrong"))
            raw = Path(directory, "state.json").read_text(encoding="utf-8")
            self.assertNotIn(key, raw)
            self.assertNotIn(token or "", raw)


class MappingTests(unittest.TestCase):
    def test_session_and_conversation_mapping(self) -> None:
        mapped = session(THREAD, include_messages=True)
        self.assertEqual(mapped["status"], "completed")
        self.assertEqual([item["kind"] for item in mapped["messages"]], ["user", "assistant"])
        self.assertEqual(mapped["messages"][1]["text"], "Hi")
        self.assertEqual(
            normalize_item(
                {
                    "id": "cmd",
                    "type": "commandExecution",
                    "command": "git status",
                    "status": "completed",
                    "exitCode": 0,
                }
            )["exitCode"],
            0,
        )
        self.assertEqual(
            status({"type": "active", "activeFlags": ["waitingOnUserInput"]}),
            "waiting",
        )


class TcpIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.repository_root = base / "projects"
        self.repository = self.repository_root / "example"
        self.repository.mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(self.repository)], check=True)
        (self.repository / "new.txt").write_text("dirty\n", encoding="utf-8")
        self.state = State(base / "state")
        self.pairing_key, _ = self.state.create_pairing()
        self.app = Foreman(
            "127.0.0.1",
            0,
            self.repository_root,
            self.state,
            "fake-codex",
            codex_factory=FakeCodex,
        )
        await self.app.start()
        socket = self.app.server.sockets[0]
        self.reader, self.writer = await asyncio.open_connection(
            *socket.getsockname()[:2]
        )
        self.next_id = 0
        self.unsolicited: list[dict[str, Any]] = []

    async def asyncTearDown(self) -> None:
        self.writer.close()
        await self.writer.wait_closed()
        await self.app.stop()
        self.temporary.cleanup()

    async def request(
        self, message_type: str, payload: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        self.next_id += 1
        request_id = f"test-{self.next_id}"
        self.writer.write(
            protocol.encode(
                {
                    "version": 1,
                    "id": request_id,
                    "type": message_type,
                    "payload": payload or {},
                }
            )
        )
        await self.writer.drain()
        while True:
            message = protocol.decode(await self.reader.readline())
            if message.get("id") == request_id:
                self.assertNotEqual(message["type"], "error", message)
                return message["payload"]
            self.unsolicited.append(message)

    async def test_pair_list_read_start_and_prompt(self) -> None:
        hello = await self.request("hello")
        self.assertTrue(hello["capabilities"]["steer"])
        paired = await self.request(
            "pair",
            {"pairingKey": self.pairing_key, "deviceName": "Test phone"},
        )
        self.assertTrue(paired["deviceToken"].startswith("fmt_"))
        repositories = (await self.request("repository.list"))["repositories"]
        self.assertEqual(repositories[0]["path"], "example")
        self.assertTrue(repositories[0]["dirty"])
        sessions = (await self.request("session.list"))["sessions"]
        self.assertEqual(sessions[0]["title"], "Hello Foreman")
        conversation = (
            await self.request("session.read", {"sessionId": "thread-1"})
        )["session"]
        self.assertEqual(len(conversation["messages"]), 2)
        started = (
            await self.request("session.start", {"repositoryId": "example"})
        )["session"]
        prompted = await self.request(
            "turn.prompt", {"sessionId": started["id"], "text": "Hello"}
        )
        self.assertTrue(prompted["accepted"])
        event = next(
            message
            for message in self.unsolicited
            if message.get("type") == "session.event"
        )
        self.assertEqual(event["payload"]["event"]["text"], "Hello")


if __name__ == "__main__":
    unittest.main()
