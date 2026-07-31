from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))

from claude_code import ClaudeCode, ClaudeCodeError, MAX_BRIDGE_MESSAGE_BYTES  # noqa: E402
from session_identity import SessionIdentity  # noqa: E402


BRIDGE = ROOT / "linux" / "claude_bridge" / "bridge.mjs"
FAKE_SDK = (ROOT / "linux" / "claude_bridge" / "test_fake_sdk.mjs").as_uri()


class ClaudeCodeTests(unittest.IsolatedAsyncioTestCase):
    def adapter(
        self,
        root: Path,
        events: list[dict] | None = None,
        bridge: Path = BRIDGE,
        restart_delays: tuple[float, ...] = (0.01, 0.02),
        query_timeout: float = 30,
    ) -> ClaudeCode:
        observed = events if events is not None else []
        return ClaudeCode(
            root,
            root / ".state" / "claude-code-sessions.json",
            on_event=observed.append,
            node_executable=sys.executable if Path(sys.executable).name == "node" else "node",
            bridge_path=bridge,
            env={
                **os.environ,
                "FOREMAN_CLAUDE_SDK_MODULE": FAKE_SDK,
                "FOREMAN_CLAUDE_EXECUTABLE": "node",
            },
            restart_delays=restart_delays,
            query_timeout=query_timeout,
        )

    async def wait_for(self, predicate, timeout: float = 3) -> object:
        async with asyncio.timeout(timeout):
            while True:
                value = predicate()
                if value:
                    return value
                await asyncio.sleep(0.01)

    async def test_detection_start_stream_model_permission_and_minimal_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events)
            try:
                status = await adapter.start()
                self.assertTrue(status["installed"])
                self.assertTrue(status["available"])
                self.assertRegex(status["cliVersion"], r"\d+\.\d+\.\d+")
                self.assertEqual(status["sdkVersion"], "test")
                self.assertEqual(
                    status["permissionModes"],
                    ["default", "dontAsk", "acceptEdits", "plan", "auto", "bypassPermissions"],
                )
                self.assertFalse(status["capabilities"]["liveAttachExternal"])

                started = await adapter.start_session(
                    root,
                    "Read a file",
                    model="sonnet",
                    permission_mode="acceptEdits",
                )
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                self.assertTrue(started["sessionId"].startswith("managed-session-"))
                self.assertTrue(any(event["kind"] == "assistant.delta" for event in events))
                self.assertTrue(any(event["kind"] == "assistant.completed" for event in events))
                tool = next(event for event in events if event["kind"] == "tool" and event["status"] == "started")
                self.assertNotIn("input", tool)
                started_event = next(event for event in events if event["kind"] == "query.started")
                self.assertEqual(started_event["model"], "sonnet")
                self.assertEqual(started_event["permissionMode"], "acceptEdits")

                state = json.loads(adapter.state_path.read_text(encoding="utf-8"))
                self.assertEqual(set(state), {"version", "sessions"})
                self.assertEqual(set(state["sessions"][0]), {"sessionId", "cwd"})
            finally:
                await adapter.stop()

    async def test_approval_denial_dont_ask_and_no_file_creation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events)
            try:
                await adapter.start()
                await adapter.start_session(root, "approval", permission_mode="default")
                allowed = await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "permission.requested"), None)
                )
                await adapter.answer_approval(allowed["requestId"], allow=True)
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "permission.allowed"), None)
                )
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                events.clear()
                await adapter.start_session(root, "approval", permission_mode="default")
                requested = await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "permission.requested"), None)
                )
                self.assertIn(requested["requestId"], adapter.pending_approvals)
                await adapter.answer_approval(requested["requestId"], allow=False)
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                self.assertFalse(adapter.pending_approvals)

                events.clear()
                await adapter.start_session(root, "approval", permission_mode="dontAsk")
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "permission.denied"), None)
                )
                self.assertFalse(any(event["kind"] == "permission.requested" for event in events))
                self.assertFalse((root / "SHOULD_NOT_EXIST").exists())
            finally:
                await adapter.stop()

    async def test_every_verified_permission_mode_is_passed_through(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events)
            try:
                await adapter.start()
                for mode in ("default", "dontAsk", "acceptEdits", "plan", "auto", "bypassPermissions"):
                    index = len(events)
                    started = await adapter.start_session(root, "no tools", permission_mode=mode)
                    started_event = await self.wait_for(
                        lambda: next(
                            (
                                item
                                for item in events[index:]
                                if item["kind"] == "query.started"
                                and item["sessionId"] == started["sessionId"]
                            ),
                            None,
                        )
                    )
                    self.assertEqual(started_event["permissionMode"], mode)
                    await self.wait_for(
                        lambda: next(
                            (
                                item
                                for item in events[index:]
                                if item["kind"] == "query.completed"
                                and item["sessionId"] == started["sessionId"]
                            ),
                            None,
                        )
                    )
            finally:
                await adapter.stop()

    async def test_missing_claude_does_not_start_node(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapter = ClaudeCode(
                root,
                root / "state.json",
                bridge_path=BRIDGE,
                env={"PATH": ""},
            )
            status = await adapter.start()
            self.assertFalse(status["available"])
            self.assertIn("native claude executable", status["limitation"])
            self.assertIsNone(adapter.process)

    async def test_interrupt_post_interrupt_resume_and_inactive_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events)
            try:
                await adapter.start()
                started = await adapter.start_session(root, "sleep", permission_mode="dontAsk")
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "tool"), None)
                )
                result = await adapter.interrupt(started["sessionId"])
                self.assertTrue(result["interrupted"])
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.interrupted"), None)
                )
                events.clear()
                resumed = await adapter.resume_session(
                    started["sessionId"], root, "continue", model="haiku", permission_mode="plan"
                )
                self.assertEqual(resumed["sessionId"], started["sessionId"])
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                with self.assertRaisesRegex(ClaudeCodeError, "not active"):
                    await adapter.interrupt(started["sessionId"])
            finally:
                await adapter.stop()

    async def test_restart_resume_and_external_discovery(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.adapter(root)
            await first.start()
            started = await first.start_session(root, "first")
            await self.wait_for(lambda: first.process and first.process.returncode is None)
            await first.stop()

            events: list[dict] = []
            restarted = self.adapter(root, events)
            try:
                await restarted.start()
                resumed = await restarted.resume_session(started["sessionId"], root, "after restart")
                self.assertEqual(resumed["sessionId"], started["sessionId"])
                sessions = await restarted.discover(root)
                external = next(item for item in sessions if item["sessionId"] == "external-session")
                self.assertEqual(external["classification"], "resumable")
                self.assertFalse(external["active"])
                self.assertFalse(external["liveAttachSupported"])
                with self.assertRaisesRegex(ClaudeCodeError, "not supported"):
                    await restarted.attach_external(external["sessionId"])
                adopted = await restarted.resume_session(external["sessionId"], root, "adopt external")
                self.assertEqual(adopted["sessionId"], external["sessionId"])
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                managed = await restarted.discover(root)
                self.assertEqual(managed[0]["classification"], "managed")
            finally:
                await restarted.stop()

    async def test_process_crash_restarts_without_replaying_query(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events)
            try:
                await adapter.start()
                await adapter.start_session(root, "once")
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.completed"), None)
                )
                started_count = sum(event["kind"] == "query.started" for event in events)
                for _ in range(3):
                    old_process = adapter.process
                    self.assertIsNotNone(old_process)
                    old_process.kill()
                    await old_process.wait()
                    await self.wait_for(
                        lambda: adapter.process is not None
                        and adapter.process is not old_process
                        and adapter.process.returncode is None
                        and adapter.restart_attempt == 0
                    )
                self.assertEqual(sum(event["kind"] == "query.started" for event in events), started_count)
                self.assertTrue((await adapter.status())["available"])
            finally:
                await adapter.stop()

    async def test_timed_out_start_is_cancelled_before_retry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events: list[dict] = []
            adapter = self.adapter(root, events, query_timeout=0.1)
            try:
                await adapter.start()
                with self.assertRaisesRegex(ClaudeCodeError, "timed out"):
                    await adapter.start_session(root, "slow-init")
                await self.wait_for(
                    lambda: next((event for event in events if event["kind"] == "query.interrupted"), None)
                )
                process = adapter.process
                self.assertIsNotNone(process)
                self.assertIsNone(process.returncode)
                index = len(events)
                retry = await adapter.start_session(root, "retry")
                await self.wait_for(
                    lambda: next(
                        (
                            event
                            for event in events[index:]
                            if event["kind"] == "query.completed"
                            and event["sessionId"] == retry["sessionId"]
                        ),
                        None,
                    )
                )
            finally:
                await adapter.stop()

    async def test_cwd_request_size_clean_shutdown_and_sanitized_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            adapter = self.adapter(root)
            await adapter.start()
            with self.assertRaisesRegex(ClaudeCodeError, "configured repository root"):
                await adapter.start_session(Path(outside), "no")
            with self.assertRaisesRegex(ClaudeCodeError, "too large"):
                await adapter.start_session(root, "x" * MAX_BRIDGE_MESSAGE_BYTES)
            process = adapter.process
            await adapter.stop()
            self.assertIsNotNone(process)
            self.assertIsNotNone(process.returncode)

            bad_bridge = root / "bad-bridge.mjs"
            bad_bridge.write_text(
                "process.stderr.write('api_key=SUPER_SECRET\\n');"
                "setTimeout(() => process.exit(1), 20);\n",
                encoding="utf-8",
            )
            broken = self.adapter(root, bridge=bad_bridge, restart_delays=())
            status = await broken.start()
            self.assertFalse(status["available"])
            combined = json.dumps(status) + str(broken.last_stderr)
            self.assertNotIn("SUPER_SECRET", combined)
            self.assertIn("[redacted]", combined)
            await broken.stop()


class SessionIdentityTests(unittest.TestCase):
    def test_explicit_provider_host_and_session_identity(self) -> None:
        identity = SessionIdentity("claude-code", "host-1", "session-1")
        self.assertEqual(
            identity.projection(),
            {"provider": "claude-code", "hostId": "host-1", "sessionId": "session-1"},
        )
        self.assertEqual(SessionIdentity("codex", "host-1", "thread-1").provider, "codex")
        with self.assertRaises(ValueError):
            SessionIdentity("other", "host-1", "session-1")
