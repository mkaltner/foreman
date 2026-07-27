from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))

import protocol  # noqa: E402
from codex import (  # noqa: E402
    Codex,
    access_level,
    access_params,
    bound_message_images,
    model,
    normalize_event,
    normalize_item,
    session,
    status,
    user_input,
)
from foreman_service import (  # noqa: E402
    Client,
    Foreman,
    PairingLimiter,
    image_payloads,
)
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
        self.active: set[str] = set()
        self.archived: list[str] = []
        self.deleted: list[str] = []
        self.prompts: list[dict[str, Any]] = []
        self.reads: list[str] = []

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    def supports(self, method: str) -> bool:
        return method in {
            "thread/archive",
            "thread/delete",
            "model/list",
            "permissionProfile/list",
        }

    async def list_threads(self) -> list[dict[str, Any]]:
        return [{**THREAD, "turns": []}]

    async def read_thread(self, thread_id: str) -> dict[str, Any]:
        self.reads.append(thread_id)
        return {
            **THREAD,
            "id": thread_id,
            "status":
                {"type": "active", "activeFlags": []}
                if thread_id in self.active
                else {"type": "idle"},
        }

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

    async def subscribe_thread(self, thread_id: str) -> None:
        pass

    async def list_models(self, refresh: bool = False) -> list[dict[str, Any]]:
        return [
            {
                "id": "model-test",
                "displayName": "Test",
                "description": "Test model",
                "reasoningEfforts": ["low", "high"],
                "defaultReasoningEffort": "low",
                "visible": True,
                "isDefault": True,
                "inputModalities": ["text", "image"],
            }
        ]

    async def list_access_levels(
        self, refresh: bool = False
    ) -> list[dict[str, str]]:
        return [
            {
                "id": "ask",
                "displayName": "Ask for approval",
                "description": "Ask before leaving the workspace",
            },
            {
                "id": "auto",
                "displayName": "Approve for me",
                "description": "Automatically review requests",
            },
            {
                "id": "full",
                "displayName": "Full access",
                "description": "Unrestricted access",
            },
        ]

    async def prompt(
        self,
        thread_id: str,
        text: str,
        images: list[dict[str, str]] | None = None,
        model_id: str | None = None,
        effort: str | None = None,
        selected_access_level: str | None = None,
    ) -> dict[str, Any]:
        self.prompts.append(
            {
                "threadId": thread_id,
                "text": text,
                "images": images or [],
                "model": model_id,
                "effort": effort,
                "accessLevel": selected_access_level,
            }
        )
        content: list[dict[str, Any]] = []
        if text:
            content.append({"type": "text", "text": text})
        content.extend(
            {
                "type": "image",
                "url": f"data:{image['mimeType']};base64,{image['data']}",
            }
            for image in images or []
        )
        await self.on_event(
            {
                "method": "item/started",
                "params": {
                    "threadId": thread_id,
                    "turnId": "turn-new",
                    "item": {
                        "id": "user-new",
                        "type": "userMessage",
                        "content": content,
                    },
                },
            }
        )
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
        self,
        thread_id: str,
        turn_id: str,
        text: str,
        images: list[dict[str, str]] | None = None,
    ) -> dict[str, Any]:
        return {"turnId": turn_id}

    async def interrupt(self, thread_id: str, turn_id: str) -> None:
        pass

    async def archive_thread(self, thread_id: str) -> None:
        self.archived.append(thread_id)

    async def delete_thread(self, thread_id: str) -> None:
        self.deleted.append(thread_id)


class ProtocolTests(unittest.TestCase):
    def test_frame_round_trip_and_validation(self) -> None:
        message = {"version": 1, "id": "1", "type": "ping", "payload": {}}
        self.assertEqual(protocol.decode(protocol.encode(message)), message)
        with self.assertRaises(protocol.ProtocolError):
            protocol.decode(b'{"version":2,"type":"ping"}\n')
        with self.assertRaises(protocol.ProtocolError):
            protocol.decode(b"x" * (protocol.MAX_FRAME_BYTES + 1) + b"\n")


class ImagePayloadTests(unittest.TestCase):
    def test_accepts_one_or_multiple_images_and_rejects_bad_input(self) -> None:
        valid = {"mimeType": "image/jpeg", "data": "YWJj"}
        self.assertEqual(image_payloads({"images": [valid]}), [valid])
        self.assertEqual(
            len(image_payloads({"images": [valid, {**valid, "mimeType": "image/png"}]})),
            2,
        )
        with self.assertRaisesRegex(ValueError, "base64"):
            image_payloads({"images": [{**valid, "data": "not base64!"}]})
        with self.assertRaisesRegex(ValueError, "MIME"):
            image_payloads({"images": [{**valid, "mimeType": "image/gif"}]})
        with self.assertRaisesRegex(ValueError, "at most"):
            image_payloads({"images": [valid] * 5})

    def test_bounds_historical_image_projection_preferring_recent_images(self) -> None:
        messages = [
            {
                "images": [{"mimeType": "image/jpeg", "data": "older"}],
                "imageCount": 1,
            },
            {
                "images": [{"mimeType": "image/jpeg", "data": "new"}],
                "imageCount": 1,
            },
        ]
        bound_message_images(messages, maximum=3)
        self.assertEqual(messages[0]["images"], [])
        self.assertEqual(messages[1]["images"][0]["data"], "new")
        self.assertEqual(messages[0]["imageCount"], 1)


class StateTests(unittest.TestCase):
    def test_pairing_is_one_time_and_tokens_are_hashed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = State(directory)
            key, _ = state.create_pairing()
            self.assertRegex(key, re.compile(r"^\d{6}$"))
            token = state.pair(key, "Phone")
            self.assertIsNotNone(token)
            self.assertIsNone(state.pair(key, "Second phone"))
            self.assertTrue(state.authenticate(token or ""))
            self.assertFalse(state.authenticate("fmt_wrong"))
            raw = Path(directory, "state.json").read_text(encoding="utf-8")
            self.assertNotIn(key, raw)
            self.assertNotIn(token or "", raw)


class PairingLimiterTests(unittest.TestCase):
    def test_limits_each_peer_without_revoking_codes(self) -> None:
        now = [100.0]
        limiter = PairingLimiter(clock=lambda: now[0])
        for _ in range(5):
            self.assertTrue(limiter.allowed("attacker"))
            limiter.failed("attacker")
        self.assertFalse(limiter.allowed("attacker"))
        self.assertTrue(limiter.allowed("phone"))

        now[0] += 60
        self.assertTrue(limiter.allowed("attacker"))
        limiter.failed("attacker")
        limiter.succeeded("attacker")
        self.assertTrue(limiter.allowed("attacker"))


class MappingTests(unittest.TestCase):
    def test_maps_shared_thread_access_and_route_changes(self) -> None:
        thread_id, event = normalize_event(
            {
                "method": "thread/settings/updated",
                "params": {
                    "threadId": "thread-1",
                    "threadSettings": {
                        "model": "gpt-test",
                        "effort": "high",
                        "approvalPolicy": "on-request",
                        "approvalsReviewer": "auto_review",
                        "activePermissionProfile": {"id": ":workspace"},
                    },
                },
            }
        )

        self.assertEqual(thread_id, "thread-1")
        self.assertEqual(event["kind"], "route")
        self.assertEqual(event["accessLevel"], "auto")
        self.assertEqual(event["model"], "gpt-test")
        self.assertEqual(event["reasoningEffort"], "high")

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
        persisted_active = session(
            {
                **THREAD,
                "status": {"type": "notLoaded"},
                "turns": [{"id": "turn-live", "status": "inProgress", "items": []}],
            },
            include_messages=True,
        )
        self.assertEqual(persisted_active["status"], "working")
        self.assertEqual(persisted_active["activeTurnId"], "turn-live")

    def test_maps_public_live_activity_without_raw_reasoning(self) -> None:
        thread_id, event = normalize_event(
            {
                "method": "item/reasoning/summaryTextDelta",
                "params": {
                    "threadId": "thread-1",
                    "turnId": "turn-1",
                    "itemId": "reasoning-1",
                    "delta": "Checking the connection",
                },
            }
        )
        self.assertEqual(thread_id, "thread-1")
        self.assertEqual(event["kind"], "activity")
        self.assertEqual(event["label"], "Thinking")
        self.assertEqual(event["text"], "Checking the connection")
        self.assertTrue(event["append"])
        _, plan_event = normalize_event(
            {
                "method": "turn/plan/updated",
                "params": {
                    "threadId": "thread-1",
                    "turnId": "turn-1",
                    "explanation": "Preparing the update",
                    "plan": [
                        {"step": "Inspecting current status", "status": "inProgress"}
                    ],
                },
            }
        )
        self.assertEqual(plan_event["label"], "Planning")
        self.assertEqual(plan_event["text"], "Inspecting current status")
        self.assertFalse(plan_event["append"])
        self.assertIsNone(
            normalize_item(
                {
                    "id": "reasoning-1",
                    "type": "reasoning",
                    "content": [{"type": "reasoning_text", "text": "private"}],
                }
            )
        )
        sensitive_query = "token=secret-123 /home/private/file"
        normalized_search = normalize_item(
            {"id": "search-1", "type": "webSearch", "query": sensitive_query}
        )
        self.assertEqual(normalized_search["description"], "Web search")
        self.assertNotIn(sensitive_query, str(normalized_search))

    def test_maps_available_historical_images_without_local_paths(self) -> None:
        item = normalize_item(
            {
                "id": "user-image",
                "type": "userMessage",
                "content": [
                    {"type": "text", "text": "Inspect"},
                    {
                        "type": "image",
                        "url": "data:image/png;base64,YWJj",
                    },
                    {
                        "type": "localImage",
                        "path": "/private/screenshot.png",
                    },
                ],
            }
        )
        self.assertEqual(item["text"], "Inspect")
        self.assertEqual(item["imageCount"], 2)
        self.assertEqual(item["images"], [{"mimeType": "image/png", "data": "YWJj"}])
        self.assertNotIn("/private/screenshot.png", str(item))


class CodexAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_discovers_supported_methods_from_installed_schema(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory, "schema-codex")
            executable.write_text(
                """#!/usr/bin/env python3
import json, pathlib, sys
output = pathlib.Path(sys.argv[-1])
output.mkdir(parents=True, exist_ok=True)
schema = {"oneOf": [
    {"properties": {"method": {"enum": ["thread/archive"]}}},
    {"properties": {"method": {"enum": ["thread/delete"]}}},
]}
(output / "ClientRequest.json").write_text(json.dumps(schema))
""",
                encoding="utf-8",
            )
            os.chmod(executable, 0o700)
            adapter = Codex(str(executable), lambda _: asyncio.sleep(0))

            methods = await asyncio.to_thread(adapter._discover_supported_methods)

            self.assertEqual(methods, {"thread/archive", "thread/delete"})

    async def test_steer_reconciles_a_stale_active_turn_id(self) -> None:
        adapter = Codex("unused", lambda _: asyncio.sleep(0))
        requests: list[tuple[str, dict[str, Any]]] = []

        async def read_thread(_: str) -> dict[str, Any]:
            return {
                **THREAD,
                "status": {"type": "active", "activeFlags": []},
                "turns": [{"id": "turn-current", "status": "inProgress", "items": []}],
            }

        async def request(method: str, params: dict[str, Any]) -> dict[str, Any]:
            requests.append((method, params))
            return {"turnId": params["expectedTurnId"]}

        adapter.read_thread = read_thread  # type: ignore[method-assign]
        adapter.request = request  # type: ignore[method-assign]

        result = await adapter.steer("thread-1", "turn-stale", "continue")

        self.assertEqual(result["turnId"], "turn-current")
        self.assertEqual(requests[0][1]["expectedTurnId"], "turn-current")

    async def test_maps_model_metadata_and_exact_turn_input(self) -> None:
        mapped = model(
            {
                "id": "gpt-test",
                "displayName": "GPT Test",
                "description": "Useful",
                "hidden": False,
                "isDefault": True,
                "defaultReasoningEffort": "high",
                "supportedReasoningEfforts": [
                    {"reasoningEffort": "low", "description": "Fast"},
                    {"reasoningEffort": "high", "description": "Thorough"},
                ],
                "inputModalities": ["text", "image"],
            }
        )
        self.assertEqual(mapped["reasoningEfforts"], ["low", "high"])
        self.assertTrue(mapped["visible"])
        self.assertTrue(mapped["isDefault"])
        self.assertEqual(
            user_input(
                "Inspect",
                [{"mimeType": "image/jpeg", "data": "YWJj"}],
            ),
            [
                {"type": "text", "text": "Inspect", "text_elements": []},
                {
                    "type": "image",
                    "url": "data:image/jpeg;base64,YWJj",
                },
            ],
        )
        self.assertEqual(
            access_params("auto"),
            {
                "permissions": ":workspace",
                "approvalPolicy": "on-request",
                "approvalsReviewer": "auto_review",
            },
        )
        self.assertEqual(
            access_level(
                {
                    "activePermissionProfile": {"id": ":danger-full-access"},
                    "approvalPolicy": "never",
                    "approvalsReviewer": "user",
                }
            ),
            "full",
        )

    async def test_forwards_exact_access_profile_on_turn_start(self) -> None:
        adapter = Codex("unused", lambda _: asyncio.sleep(0))
        adapter._loaded.add("thread-1")
        requests: list[tuple[str, dict[str, Any]]] = []

        async def request(method: str, params: dict[str, Any]) -> dict[str, Any]:
            requests.append((method, params))
            return {"turn": {"id": "turn-1"}}

        adapter.request = request  # type: ignore[method-assign]
        await adapter.prompt(
            "thread-1",
            "Inspect",
            model_id="gpt-test",
            effort="high",
            selected_access_level="full",
        )

        self.assertEqual(requests[0][0], "turn/start")
        self.assertEqual(requests[0][1]["permissions"], ":danger-full-access")
        self.assertEqual(requests[0][1]["approvalPolicy"], "never")
        self.assertEqual(requests[0][1]["approvalsReviewer"], "user")

    async def test_lists_only_access_levels_allowed_by_codex(self) -> None:
        adapter = Codex("unused", lambda _: asyncio.sleep(0))

        async def request(_: str, __: dict[str, Any]) -> dict[str, Any]:
            return {
                "data": [
                    {"id": ":workspace", "allowed": True},
                    {"id": ":danger-full-access", "allowed": False},
                ]
            }

        adapter.request = request  # type: ignore[method-assign]
        levels = await adapter.list_access_levels()

        self.assertEqual([item["id"] for item in levels], ["ask", "auto"])


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
        message = await self.exchange(message_type, payload)
        self.assertNotEqual(message["type"], "error", message)
        return message["payload"]

    async def request_error(
        self, message_type: str, payload: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        message = await self.exchange(message_type, payload)
        self.assertEqual(message["type"], "error", message)
        return message["payload"]

    async def exchange(
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
                return message
            self.unsolicited.append(message)

    async def test_pair_list_read_start_and_prompt(self) -> None:
        hello = await self.request("hello")
        self.assertTrue(hello["capabilities"]["steer"])
        self.assertTrue(hello["capabilities"]["archive"])
        self.assertTrue(hello["capabilities"]["delete"])
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
        self.assertEqual(started["messages"], [])
        self.assertNotIn(started["id"], self.app.codex.reads)
        models = (await self.request("model.list"))["models"]
        self.assertEqual(models[0]["id"], "model-test")
        levels = (await self.request("access.list"))["levels"]
        self.assertEqual([level["id"] for level in levels], ["ask", "auto", "full"])
        prompted = await self.request(
            "turn.prompt",
            {
                "sessionId": started["id"],
                "text": "Hello",
                "images": [{"mimeType": "image/jpeg", "data": "YWJj"}],
                "model": "model-test",
                "reasoningEffort": "high",
                "accessLevel": "auto",
            },
        )
        self.assertTrue(prompted["accepted"])
        self.assertEqual(
            self.app.codex.prompts[-1],
            {
                "threadId": started["id"],
                "text": "Hello",
                "images": [{"mimeType": "image/jpeg", "data": "YWJj"}],
                "model": "model-test",
                "effort": "high",
                "accessLevel": "auto",
            },
        )
        event = next(
            message
            for message in self.unsolicited
            if message.get("type") == "session.event"
            and message["payload"]["event"].get("kind") == "assistant.delta"
        )
        self.assertEqual(event["payload"]["event"]["text"], "Hello")
        user_event = next(
            message["payload"]["event"]
            for message in self.unsolicited
            if message.get("type") == "session.event"
            and message["payload"]["event"].get("kind") == "item"
        )
        self.assertEqual(user_event["item"]["kind"], "user")
        self.assertEqual(user_event["item"]["text"], "Hello")
        self.assertEqual(user_event["item"]["imageCount"], 1)

        archived = await self.request(
            "session.archive", {"sessionId": "thread-1"}
        )
        self.assertTrue(archived["archived"])
        self.assertEqual(self.app.codex.archived, ["thread-1"])
        deleted = await self.request(
            "session.delete", {"sessionId": started["id"], "confirm": True}
        )
        self.assertTrue(deleted["deleted"])
        self.assertEqual(self.app.codex.deleted, [started["id"]])

    async def test_rejects_unconfirmed_or_active_session_deletion(self) -> None:
        await self.request(
            "pair",
            {"pairingKey": self.pairing_key, "deviceName": "Test phone"},
        )
        unconfirmed = await self.request_error(
            "session.delete", {"sessionId": "thread-1"}
        )
        self.assertIn("confirm=true", unconfirmed["message"])

        self.app.codex.active.add("thread-1")
        active = await self.request_error(
            "session.delete", {"sessionId": "thread-1", "confirm": True}
        )
        self.assertIn("session is active", active["message"])
        self.assertEqual(self.app.codex.deleted, [])
        archived = await self.request_error(
            "session.archive", {"sessionId": "thread-1"}
        )
        self.assertIn("session is active", archived["message"])
        self.assertEqual(self.app.codex.archived, [])

    async def test_serializes_archive_check_with_same_session_prompt(self) -> None:
        read_started = asyncio.Event()
        release_read = asyncio.Event()
        original_read = self.app.codex.read_thread

        async def paused_read(thread_id: str) -> dict[str, Any]:
            if thread_id == "thread-race":
                read_started.set()
                await release_read.wait()
            return await original_read(thread_id)

        self.app.codex.read_thread = paused_read
        client = Client(self.writer, "test", authenticated=True)
        archive = asyncio.create_task(
            self.app.dispatch(
                client,
                {
                    "type": "session.archive",
                    "payload": {"sessionId": "thread-race"},
                },
            )
        )
        await read_started.wait()
        prompt = asyncio.create_task(
            self.app.dispatch(
                client,
                {
                    "type": "turn.prompt",
                    "payload": {"sessionId": "thread-race", "text": "Hello"},
                },
            )
        )
        await asyncio.sleep(0)
        self.assertFalse(prompt.done())

        release_read.set()
        self.assertTrue((await archive)["archived"])
        self.assertTrue((await prompt)["accepted"])

    async def test_rejects_unsupported_model_effort_and_bad_images(self) -> None:
        await self.request(
            "pair",
            {"pairingKey": self.pairing_key, "deviceName": "Test phone"},
        )
        unsupported_model = await self.request_error(
            "turn.prompt",
            {
                "sessionId": "thread-1",
                "text": "Hello",
                "model": "made-up",
            },
        )
        self.assertIn("unavailable", unsupported_model["message"])
        unsupported_effort = await self.request_error(
            "turn.prompt",
            {
                "sessionId": "thread-1",
                "text": "Hello",
                "model": "model-test",
                "reasoningEffort": "ultra",
            },
        )
        self.assertIn("not supported", unsupported_effort["message"])
        unsupported_access = await self.request_error(
            "turn.prompt",
            {
                "sessionId": "thread-1",
                "text": "Hello",
                "accessLevel": "unlimited",
            },
        )
        self.assertIn("access level", unsupported_access["message"])
        malformed = await self.request_error(
            "turn.prompt",
            {
                "sessionId": "thread-1",
                "text": "",
                "images": [{"mimeType": "image/jpeg", "data": "secret-not-base64"}],
            },
        )
        self.assertIn("base64", malformed["message"])
        self.assertNotIn("secret-not-base64", str(malformed))

    async def test_abrupt_authenticated_disconnect_is_cleaned_up(self) -> None:
        socket = self.app.server.sockets[0]
        reader, writer = await asyncio.open_connection(*socket.getsockname()[:2])
        request = {
            "version": 1,
            "id": "abrupt-pair",
            "type": "pair",
            "payload": {
                "pairingKey": self.pairing_key,
                "deviceName": "Abrupt phone",
            },
        }
        writer.write(protocol.encode(request))
        await writer.drain()
        paired = protocol.decode(await reader.readline())
        self.assertEqual(paired["type"], "pair.result")
        writer.write(
            protocol.encode(
                {
                    "version": 1,
                    "id": "abrupt-subscribe",
                    "type": "session.subscribe",
                    "payload": {"sessionId": "thread-1"},
                }
            )
        )
        await writer.drain()
        self.assertEqual(
            protocol.decode(await reader.readline())["type"],
            "session.subscribe.result",
        )
        self.assertEqual(len(self.app.clients), 2)

        writer.transport.abort()
        for _ in range(50):
            if len(self.app.clients) == 1:
                break
            await asyncio.sleep(0.01)
        self.assertEqual(len(self.app.clients), 1)


if __name__ == "__main__":
    unittest.main()
