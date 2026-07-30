from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux" / "vendor"))
sys.path.insert(0, str(ROOT / "linux"))

from approvals import ApprovalError  # noqa: E402
from codex import Codex  # noqa: E402
from inputs import (  # noqa: E402
    MCP_ELICITATION_METHOD,
    USER_INPUT_METHOD,
    PendingInput,
    bounded_input_params,
)


def mcp_input(schema: dict[str, Any]) -> PendingInput:
    return PendingInput(
        upstream_id="upstream-1",
        method=MCP_ELICITATION_METHOD,
        params=bounded_input_params(
            MCP_ELICITATION_METHOD,
            {
                "threadId": "thread-1",
                "turnId": "turn-1",
                "serverName": "verified-server",
                "mode": "form",
                "message": "Provide bounded information",
                "requestedSchema": schema,
            },
        ),
    )


class InputContractTests(unittest.TestCase):
    def test_normalizes_and_validates_every_supported_mcp_field_type(self) -> None:
        pending = mcp_input(
            {
                "type": "object",
                "properties": {
                    "single": {
                        "type": "string",
                        "title": "One",
                        "oneOf": [
                            {"const": "a", "title": "Alpha"},
                            {"const": "b", "title": "Beta"},
                        ],
                    },
                    "multiple": {
                        "type": "array",
                        "title": "Many",
                        "items": {"type": "string", "enum": ["x", "y", "z"]},
                        "minItems": 1,
                        "maxItems": 2,
                    },
                    "short": {"type": "string", "minLength": 2, "maxLength": 20},
                    "long": {"type": "string", "maxLength": 1000},
                    "toggle": {"type": "boolean", "default": False},
                },
                "required": ["single", "multiple", "short", "long", "toggle"],
            }
        )
        projection = pending.projection()
        self.assertTrue(projection["supported"])
        self.assertEqual(
            [field["type"] for field in projection["fields"]],
            ["singleChoice", "multipleChoice", "shortText", "longText", "boolean"],
        )
        result, resolution = pending.response_result(
            {
                "action": "accept",
                "values": {
                    "single": "a",
                    "multiple": ["x", "z"],
                    "short": "ok",
                    "long": "details",
                    "toggle": True,
                },
            }
        )
        self.assertEqual(resolution, "accepted")
        self.assertEqual(result["action"], "accept")
        self.assertEqual(result["content"]["multiple"], ["x", "z"])

        invalid_values = (
            {"single": "missing", "multiple": ["x"], "short": "ok", "long": "x", "toggle": True},
            {"single": "a", "multiple": [], "short": "ok", "long": "x", "toggle": True},
            {"single": "a", "multiple": ["x"], "short": "x", "long": "x", "toggle": True},
            {"single": "a", "multiple": ["x"], "short": "ok", "long": "x", "toggle": "yes"},
        )
        for values in invalid_values:
            with self.subTest(values=values), self.assertRaises(ApprovalError):
                pending.response_result({"action": "accept", "values": values})

    def test_boolean_confirmation_and_mcp_decline_cancel(self) -> None:
        pending = mcp_input(
            {
                "type": "object",
                "properties": {"confirmed": {"type": "boolean", "title": "Continue?"}},
                "required": ["confirmed"],
            }
        )
        self.assertEqual(pending.projection()["fields"][0]["type"], "confirmation")
        self.assertEqual(
            pending.response_result({"action": "accept", "values": {"confirmed": False}})[0],
            {"action": "accept", "content": {"confirmed": False}},
        )
        self.assertEqual(pending.response_result({"action": "decline"})[0], {"action": "decline", "content": None})
        self.assertEqual(pending.response_result({"action": "cancel"})[0], {"action": "cancel", "content": None})

    def test_zero_field_mcp_form_is_an_action_confirmation(self) -> None:
        pending = mcp_input({"type": "object", "properties": {}})
        projection = pending.projection()

        self.assertTrue(projection["supported"])
        self.assertEqual(projection["title"], "Confirmation requested")
        self.assertEqual(projection["fields"], [])
        self.assertTrue(projection["canDecline"])
        self.assertTrue(projection["canCancel"])
        self.assertEqual(
            pending.response_result({"action": "accept", "values": {}})[0],
            {"action": "accept", "content": {}},
        )

    def test_tool_questions_support_choice_other_text_and_exact_response_shape(self) -> None:
        pending = PendingInput(
            upstream_id=71,
            method=USER_INPUT_METHOD,
            params=bounded_input_params(
                USER_INPUT_METHOD,
                {
                    "threadId": "thread-1",
                    "turnId": "turn-1",
                    "itemId": "item-1",
                    "questions": [
                        {
                            "id": "choice",
                            "header": "Pick",
                            "question": "Choose one",
                            "isOther": True,
                            "isSecret": False,
                            "options": [
                                {"label": "Alpha", "description": "First"},
                                {"label": "Beta", "description": "Second"},
                            ],
                        },
                        {
                            "id": "text",
                            "header": "Name",
                            "question": "Enter a short value",
                            "isOther": False,
                            "isSecret": True,
                            "options": None,
                        },
                    ],
                },
            ),
        )
        fields = pending.projection()["fields"]
        self.assertEqual([field["type"] for field in fields], ["singleChoice", "shortText"])
        self.assertTrue(fields[0]["allowOther"])
        self.assertTrue(fields[1]["secret"])
        result, _ = pending.response_result(
            {"values": {"choice": "custom", "text": "bounded"}}
        )
        self.assertEqual(
            result,
            {
                "answers": {
                    "choice": {"answers": ["custom"]},
                    "text": {"answers": ["bounded"]},
                }
            },
        )
        with self.assertRaises(ApprovalError):
            pending.response_result({"action": "cancel"})

    def test_unsupported_schemas_are_honest_and_only_mcp_has_exit_actions(self) -> None:
        cases = [
            {"mode": "url", "requestedSchema": {}},
            {"mode": "openai/form", "requestedSchema": {"type": "object"}},
            {
                "mode": "form",
                "requestedSchema": {
                    "type": "object",
                    "properties": {"count": {"type": "integer"}},
                },
            },
            {
                "mode": "form",
                "requestedSchema": {
                    "type": "object",
                    "properties": {"email": {"type": "string", "format": "email"}},
                },
            },
        ]
        for case in cases:
            with self.subTest(case=case):
                pending = PendingInput(
                    upstream_id="unsupported",
                    method=MCP_ELICITATION_METHOD,
                    params=bounded_input_params(
                        MCP_ELICITATION_METHOD,
                        {"threadId": "thread", "serverName": "mcp", "message": "x", **case},
                    ),
                )
                projection = pending.projection()
                self.assertFalse(projection["supported"])
                self.assertTrue(projection["unsupportedMessage"])
                self.assertTrue(projection["canDecline"])
                self.assertTrue(projection["canCancel"])
                with self.assertRaises(ApprovalError):
                    pending.response_result({"action": "accept", "values": {}})

        tool = PendingInput(
            upstream_id=1,
            method=USER_INPUT_METHOD,
            params=bounded_input_params(USER_INPUT_METHOD, {"threadId": "thread", "turnId": "turn", "questions": []}),
        )
        self.assertFalse(tool.projection()["supported"])
        self.assertFalse(tool.projection()["canDecline"])
        self.assertFalse(tool.projection()["canCancel"])


class FakeSocket:
    def __init__(self) -> None:
        self.messages: list[dict[str, Any]] = []

    async def send(self, value: str) -> None:
        self.messages.append(json.loads(value))


class InputLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_lifecycle_duplicate_stale_desktop_first_reconnect_and_recreation(self) -> None:
        events: list[dict[str, Any]] = []

        async def on_event(event: dict[str, Any]) -> None:
            events.append(event)

        adapter = Codex("codex", on_event)
        socket = FakeSocket()
        adapter._websocket = socket
        params = {
            "threadId": "thread-1",
            "turnId": "turn-1",
            "itemId": "item-1",
            "questions": [
                {
                    "id": "answer",
                    "header": "Answer",
                    "question": "Choose",
                    "options": [{"label": "Yes", "description": "Continue"}],
                }
            ],
        }
        await adapter._server_request({"id": "upstream", "method": USER_INPUT_METHOD, "params": params}, socket)
        await adapter._server_request({"id": "upstream", "method": USER_INPUT_METHOD, "params": params}, socket)
        self.assertEqual(len(adapter.list_inputs()), 1)
        pending_id = adapter.list_inputs()[0]["id"]
        self.assertNotEqual(pending_id, "upstream")
        await adapter.respond_input(pending_id, {"values": {"answer": "Yes"}})
        self.assertEqual(socket.messages[-1]["id"], "upstream")
        await adapter._server_request_lifecycle(
            {"method": "serverRequest/resolved", "params": {"threadId": "thread-1", "requestId": "upstream"}}
        )
        self.assertEqual(adapter.list_inputs(), [])
        with self.assertRaisesRegex(ApprovalError, "Already resolved"):
            await adapter.respond_input(pending_id, {"values": {"answer": "Yes"}})

        await adapter._server_request({"id": 2, "method": USER_INPUT_METHOD, "params": params}, socket)
        desktop_id = adapter.list_inputs()[0]["id"]
        await adapter._server_request_lifecycle(
            {"method": "serverRequest/resolved", "params": {"threadId": "thread-1", "requestId": 2}}
        )
        self.assertEqual(events[-1]["params"]["input"]["resolution"], "resolvedElsewhere")
        with self.assertRaises(ApprovalError):
            await adapter.respond_input(desktop_id, {"values": {"answer": "Yes"}})

        await adapter._server_request({"id": 3, "method": USER_INPUT_METHOD, "params": params}, socket)
        await adapter._expire_inputs("disconnected", connection=socket)
        self.assertEqual(adapter.list_inputs(), [])
        recreated = Codex("codex", on_event)
        self.assertEqual(recreated.list_inputs(), [])


class InstalledContractProofTests(unittest.TestCase):
    def test_installed_codex_generated_schema_contains_only_verified_requests(self) -> None:
        executable = shutil.which("codex")
        if not executable:
            self.skipTest("Codex is not installed")
        with tempfile.TemporaryDirectory(prefix="foreman-installed-proof-") as directory:
            completed = subprocess.run(
                [executable, "app-server", "generate-json-schema", "--experimental", "--out", directory],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
                check=False,
            )
            if completed.returncode != 0:
                self.skipTest(f"installed Codex cannot generate schemas: {completed.stderr}")
            tool = json.loads(Path(directory, "ToolRequestUserInputParams.json").read_text())
            mcp = json.loads(Path(directory, "McpServerElicitationRequestParams.json").read_text())
            mcp_response = json.loads(Path(directory, "McpServerElicitationRequestResponse.json").read_text())
            self.assertIn("questions", tool["properties"])
            self.assertIn("ToolRequestUserInputQuestion", tool["definitions"])
            self.assertIn("McpElicitationSchema", mcp["definitions"])
            self.assertIn("McpElicitationBooleanSchema", mcp["definitions"])
            self.assertIn("McpElicitationMultiSelectEnumSchema", mcp["definitions"])
            properties_schema = mcp["definitions"]["McpElicitationSchema"]["properties"]["properties"]
            self.assertNotIn("minProperties", properties_schema)
            self.assertEqual(
                mcp_response["definitions"]["McpServerElicitationAction"]["enum"],
                ["accept", "decline", "cancel"],
            )


if __name__ == "__main__":
    unittest.main()
