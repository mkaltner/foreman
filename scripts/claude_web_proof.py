#!/usr/bin/env python3
"""Opt-in authenticated proof through Foreman's production WebSocket surface."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid
from typing import Any, Callable


ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))
sys.path.insert(0, str(ROOT / "linux" / "vendor"))

import protocol  # noqa: E402
from claude_code import ClaudeCode  # noqa: E402
from foreman_service import Foreman  # noqa: E402
from state import State  # noqa: E402
from websockets.asyncio.client import connect  # noqa: E402


class ProofCodex:
    """Minimal healthy Codex status used to isolate the real Claude web path."""

    def __init__(self, _executable: str, _on_event: Callable[..., Any]) -> None:
        self.runtime_status = "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE"
        self.is_connected = True
        self.version = "proof"
        self.process = None

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None


class BrowserProtocol:
    def __init__(self, websocket: Any) -> None:
        self.websocket = websocket
        self.next_id = 0
        self.events: list[dict[str, Any]] = []

    async def request(
        self,
        message_type: str,
        payload: dict[str, Any] | None = None,
        *,
        expect_error: str | None = None,
    ) -> dict[str, Any]:
        self.next_id += 1
        request_id = f"proof-{self.next_id}"
        await self.websocket.send(
            json.dumps(
                {
                    "version": 1,
                    "id": request_id,
                    "type": message_type,
                    "payload": payload or {},
                }
            )
        )
        async with asyncio.timeout(180):
            while True:
                message = json.loads(await self.websocket.recv())
                if message.get("id") != request_id:
                    self.events.append(message)
                    continue
                if message.get("type") == "error":
                    code = message.get("payload", {}).get("code")
                    if expect_error == code:
                        return message
                    raise RuntimeError(f"{message_type} failed: {message.get('payload')}")
                if expect_error is not None:
                    raise RuntimeError(f"{message_type} unexpectedly succeeded")
                return message.get("payload", {})

    async def event(
        self,
        predicate: Callable[[dict[str, Any]], bool],
        start_index: int = 0,
    ) -> dict[str, Any]:
        match = next((item for item in self.events[start_index:] if predicate(item)), None)
        if match is not None:
            return match
        async with asyncio.timeout(180):
            while True:
                message = json.loads(await self.websocket.recv())
                self.events.append(message)
                if predicate(message):
                    return message


def session_event(
    message: dict[str, Any], session_id: str, kind: str | None = None
) -> bool:
    payload = message.get("payload", {})
    event = payload.get("event", {})
    return (
        message.get("type") == "session.event"
        and payload.get("provider") == "claude-code"
        and payload.get("sessionId") == session_id
        and (kind is None or event.get("kind") == kind)
    )


def assistant_text(messages: list[dict[str, Any]], session_id: str) -> str:
    return "".join(
        str(item.get("payload", {}).get("event", {}).get("text", ""))
        for item in messages
        if session_event(item, session_id, "assistant.delta")
    )


async def terminal(
    browser: BrowserProtocol, session_id: str, start_index: int = 0
) -> dict[str, Any]:
    message = await browser.event(
        lambda item: session_event(item, session_id, "status")
        and item["payload"]["event"].get("status")
        in {"completed", "failed", "interrupted"},
        start_index,
    )
    return message["payload"]["event"]


async def proof() -> dict[str, object]:
    claude = shutil.which("claude")
    if claude is None:
        raise RuntimeError("native claude executable is required")
    with tempfile.TemporaryDirectory(prefix="foreman-claude-web-proof-") as directory:
        base = Path(directory)
        repository_root = base / "projects"
        repository = repository_root / "repo"
        repository.mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        (repository / "FOREMAN_WEB_PROOF.txt").write_text(
            "FOREMAN_WEB_MARKER\n", encoding="utf-8"
        )
        state = State(base / "state")
        pairing_key, _ = state.create_pairing()
        app = Foreman(
            "127.0.0.1",
            0,
            repository_root,
            state,
            "proof-codex",
            codex_factory=ProofCodex,
            web_host="127.0.0.1",
            web_port=0,
            web_root=ROOT / "web" / "dist",
            claude_factory=ClaudeCode,
            claude_bridge=ROOT / "linux" / "claude_bridge" / "bridge.mjs",
        )
        external_process: subprocess.Popen[bytes] | None = None
        await app.start()
        try:
            assert app.web_server is not None
            port = app.web_server.sockets[0].getsockname()[1]
            async with connect(
                f"ws://127.0.0.1:{port}/ws",
                origin=f"http://127.0.0.1:{port}",
                max_size=protocol.MAX_FRAME_BYTES,
                proxy=None,
            ) as websocket:
                browser = BrowserProtocol(websocket)
                paired = await browser.request(
                    "pair",
                    {
                        "pairingKey": pairing_key,
                        "deviceName": "Claude web production proof",
                    },
                )
                if not paired.get("deviceToken"):
                    raise RuntimeError("web pairing did not return a host token")

                catalog = await browser.request("provider.list")
                claude_provider = next(
                    item for item in catalog["providers"] if item["id"] == "claude-code"
                )
                if not claude_provider.get("available"):
                    raise RuntimeError(f"Claude provider unavailable: {claude_provider}")
                if "external-running-no-live-attach" not in claude_provider["limitations"]:
                    raise RuntimeError("provider catalog omitted the external attachment limitation")

                models = await browser.request(
                    "provider.model.list", {"provider": "claude-code"}
                )
                if models.get("dynamic") or [item["id"] for item in models["models"]] != [
                    "sonnet",
                    "haiku",
                ]:
                    raise RuntimeError("web model catalog was not the validated adapter list")
                permissions = await browser.request(
                    "provider.permission.list", {"provider": "claude-code"}
                )
                if not permissions["modes"][-1].get("highRisk"):
                    raise RuntimeError("bypassPermissions was not marked high risk")

                event_index = len(browser.events)
                started = await browser.request(
                    "provider.session.start",
                    {
                        "provider": "claude-code",
                        "repositoryId": "repo",
                        "text": (
                            "Use Read to read FOREMAN_WEB_PROOF.txt. Then use Bash to run exactly "
                            "`pwd`. Reply with exactly FOREMAN_WEB_MARKER."
                        ),
                        "model": "sonnet",
                        "permissionMode": "bypassPermissions",
                    },
                )
                if not started.get("accepted"):
                    raise RuntimeError("web start was not accepted")
                started_id = started["session"]["sessionId"]
                started_terminal = await terminal(browser, started_id)
                if started_terminal["status"] != "completed":
                    raise RuntimeError("web Read/Bash query did not complete")
                started_events = browser.events[event_index:]
                visible = json.dumps(started_events)
                if "FOREMAN_WEB_MARKER" not in assistant_text(started_events, started_id):
                    raise RuntimeError("web assistant deltas did not contain the marker")
                if "Reading a file" not in visible or "Running a command (output hidden)" not in visible:
                    raise RuntimeError("web safe Read/Bash activity was not observed")
                history = await browser.request(
                    "provider.session.read",
                    {
                        "provider": "claude-code",
                        "sessionId": started_id,
                        "repositoryId": "repo",
                    },
                )
                if not history["session"].get("messages"):
                    raise RuntimeError("web history reconciliation returned no messages")

                interrupt_index = len(browser.events)
                sleeping = await browser.request(
                    "provider.session.start",
                    {
                        "provider": "claude-code",
                        "repositoryId": "repo",
                        "text": "Use Bash to run exactly `sleep 20`, then reply INTERRUPT_MISSED.",
                        "model": "haiku",
                        "permissionMode": "bypassPermissions",
                    },
                )
                sleeping_id = sleeping["session"]["sessionId"]
                await browser.event(
                    lambda item: session_event(item, sleeping_id, "item")
                    and "Running a command" in json.dumps(item)
                )
                interrupted = await browser.request(
                    "provider.turn.interrupt",
                    {"provider": "claude-code", "sessionId": sleeping_id},
                )
                if not interrupted.get("accepted"):
                    raise RuntimeError("web interrupt was not accepted")
                if (await terminal(browser, sleeping_id))["status"] != "interrupted":
                    raise RuntimeError("web interrupt did not reach terminal interrupted state")
                resume_index = len(browser.events)
                resumed = await browser.request(
                    "provider.session.resume",
                    {
                        "provider": "claude-code",
                        "sessionId": sleeping_id,
                        "repositoryId": "repo",
                        "text": "Reply with exactly WEB_RESUME_OK. Do not use tools.",
                        "model": "haiku",
                        "permissionMode": "dontAsk",
                    },
                )
                if resumed["session"]["sessionId"] != sleeping_id:
                    raise RuntimeError("web resume changed the interrupted session ID")
                if (await terminal(browser, sleeping_id, resume_index))["status"] != "completed":
                    raise RuntimeError("web resume did not complete")
                if "WEB_RESUME_OK" not in assistant_text(
                    browser.events[interrupt_index:], sleeping_id
                ):
                    raise RuntimeError("web resume assistant text was not streamed")

                denial_index = len(browser.events)
                denied = await browser.request(
                    "provider.session.start",
                    {
                        "provider": "claude-code",
                        "repositoryId": "repo",
                        "text": "Use Bash to run exactly `touch WEB_DENIED_SHOULD_NOT_EXIST`.",
                        "model": "haiku",
                        "permissionMode": "dontAsk",
                    },
                )
                denied_id = denied["session"]["sessionId"]
                await terminal(browser, denied_id)
                if (repository / "WEB_DENIED_SHOULD_NOT_EXIST").exists():
                    raise RuntimeError("web dontAsk query created the denied file")
                if '"status": "denied"' not in json.dumps(browser.events[denial_index:]):
                    raise RuntimeError("web dontAsk denial was not visible")

                external_id = str(uuid.uuid4())
                external_process = subprocess.Popen(
                    [
                        claude,
                        "-p",
                        "--session-id",
                        external_id,
                        "--model",
                        "haiku",
                        "--dangerously-skip-permissions",
                        "Remember WEB_EXTERNAL_731. Use Bash to run exactly `sleep 5`, then reply done.",
                    ],
                    cwd=repository,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    env={**os.environ, "FOREMAN_CLAUDE_EXECUTABLE": claude},
                )
                async with asyncio.timeout(30):
                    while True:
                        listed = await browser.request(
                            "provider.session.list", {"provider": "claude-code"}
                        )
                        external = next(
                            (
                                item
                                for item in listed["sessions"]
                                if item["sessionId"] == external_id
                            ),
                            None,
                        )
                        if external is not None:
                            break
                        await asyncio.sleep(0.25)
                if (
                    external["source"] != "external"
                    or external["state"] != "resumable"
                    or external["liveAttached"]
                ):
                    raise RuntimeError("web falsely projected an external session as live")
                await browser.request(
                    "provider.turn.interrupt",
                    {"provider": "claude-code", "sessionId": external_id},
                    expect_error="capabilityUnavailable",
                )
                await asyncio.to_thread(external_process.wait, 60)
                if external_process.returncode != 0:
                    raise RuntimeError("external Claude CLI proof process failed")
                external_process = None
                external_index = len(browser.events)
                external_resume = await browser.request(
                    "provider.session.resume",
                    {
                        "provider": "claude-code",
                        "sessionId": external_id,
                        "repositoryId": "repo",
                        "text": "Reply with only the exact WEB_EXTERNAL token I gave you.",
                        "model": "haiku",
                        "permissionMode": "dontAsk",
                    },
                )
                if external_resume["session"]["sessionId"] != external_id:
                    raise RuntimeError("web external resume changed the session ID")
                if (await terminal(browser, external_id))["status"] != "completed":
                    raise RuntimeError("web external resume did not complete")
                if "WEB_EXTERNAL_731" not in assistant_text(
                    browser.events[external_index:], external_id
                ):
                    raise RuntimeError("web external resume did not restore context")

                return {
                    "providerCatalog": {
                        "cliVersion": claude_provider.get("cliVersion"),
                        "sdkVersion": claude_provider.get("sdkVersion"),
                    },
                    "hostPairing": True,
                    "sonnetStartReadBashStream": started_id,
                    "historyRead": True,
                    "haikuInterruptResume": sleeping_id,
                    "dontAskDeniedWrite": True,
                    "externalDiscoverResume": external_id,
                    "externalLiveControlRejected": True,
                    "providerEvents": all(
                        item.get("payload", {}).get("provider") == "claude-code"
                        for item in browser.events
                        if item.get("type") == "session.event"
                        and item.get("payload", {}).get("sessionId")
                        in {started_id, sleeping_id, denied_id, external_id}
                    ),
                }
        finally:
            if external_process is not None and external_process.poll() is None:
                external_process.terminate()
                try:
                    await asyncio.to_thread(external_process.wait, 5)
                except subprocess.TimeoutExpired:
                    external_process.kill()
                    await asyncio.to_thread(external_process.wait)
            await app.stop()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--acknowledge-live-costs",
        action="store_true",
        help="required: runs authenticated Claude requests in disposable repositories",
    )
    args = parser.parse_args()
    if not args.acknowledge_live_costs:
        parser.error("--acknowledge-live-costs is required")
    print(json.dumps(asyncio.run(proof()), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
