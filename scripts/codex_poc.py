#!/usr/bin/env python3
"""Small real Codex app-server proof of concept.

This deliberately has no Foreman protocol or product abstractions.  It verifies
the installed app-server's thread and turn primitives before the service is
built on top of them.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any


class AppServer:
    def __init__(self, executable: str) -> None:
        self.process = subprocess.Popen(
            [executable, "app-server", "--stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self._next_id = 1
        self._responses: dict[int, queue.Queue[dict[str, Any]]] = {}
        self.events: queue.Queue[dict[str, Any]] = queue.Queue()
        self.stderr: list[str] = []
        self._reader = threading.Thread(target=self._read_stdout, daemon=True)
        self._err_reader = threading.Thread(target=self._read_stderr, daemon=True)
        self._reader.start()
        self._err_reader.start()

    def _read_stdout(self) -> None:
        assert self.process.stdout
        for line in self.process.stdout:
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                self.stderr.append(f"non-JSON stdout: {line.rstrip()}")
                continue
            response_id = message.get("id")
            if response_id is not None and ("result" in message or "error" in message):
                response = self._responses.get(response_id)
                if response:
                    response.put(message)
                    continue
            self.events.put(message)

    def _read_stderr(self) -> None:
        assert self.process.stderr
        for line in self.process.stderr:
            self.stderr.append(line.rstrip())

    def send(self, message: dict[str, Any]) -> None:
        if self.process.poll() is not None:
            raise RuntimeError(f"app-server exited with {self.process.returncode}")
        assert self.process.stdin
        self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self.process.stdin.flush()

    def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        self.send({"method": method, "params": params or {}})

    def request(
        self, method: str, params: dict[str, Any] | None = None, timeout: float = 30
    ) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        result_queue: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=1)
        self._responses[request_id] = result_queue
        try:
            self.send({"id": request_id, "method": method, "params": params or {}})
            response = result_queue.get(timeout=timeout)
        except queue.Empty as error:
            detail = "\n".join(self.stderr[-20:])
            raise TimeoutError(f"{method} timed out\n{detail}") from error
        finally:
            self._responses.pop(request_id, None)
        if "error" in response:
            raise RuntimeError(f"{method}: {response['error']}")
        return response["result"]

    def wait_for(
        self,
        method: str,
        *,
        turn_id: str | None = None,
        timeout: float = 120,
        print_events: bool = False,
    ) -> tuple[dict[str, Any], str]:
        deadline = time.monotonic() + timeout
        assistant_text: list[str] = []
        while time.monotonic() < deadline:
            try:
                event = self.events.get(timeout=min(1, deadline - time.monotonic()))
            except queue.Empty:
                continue
            event_method = event.get("method")
            params = event.get("params", {})
            if print_events:
                print(f"event {event_method}")
            if event_method == "item/agentMessage/delta":
                event_turn_id = params.get("turnId")
                if turn_id is None or event_turn_id == turn_id:
                    assistant_text.append(params.get("delta", ""))
            if event_method == method:
                event_turn_id = params.get("turn", {}).get("id") or params.get("turnId")
                if turn_id is None or event_turn_id == turn_id:
                    return event, "".join(assistant_text)
            if "id" in event and "method" in event:
                raise RuntimeError(
                    f"proof unexpectedly received server request {event['method']}; "
                    "the harmless prompt should not require approval or input"
                )
        raise TimeoutError(f"waiting for {method} timed out")

    def close(self) -> None:
        if self.process.poll() is not None:
            return
        if self.process.stdin:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=5)


def text_input(text: str) -> list[dict[str, Any]]:
    return [{"type": "text", "text": text, "text_elements": []}]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--codex",
        default=os.environ.get("FOREMAN_CODEX_EXECUTABLE") or shutil.which("codex"),
    )
    parser.add_argument("--skip-controls", action="store_true")
    parser.add_argument("--verbose-events", action="store_true")
    args = parser.parse_args()
    if not args.codex:
        parser.error("codex executable not found")

    server = AppServer(args.codex)
    try:
        initialized = server.request(
            "initialize",
            {
                "clientInfo": {
                    "name": "foreman_poc",
                    "title": "Foreman proof of concept",
                    "version": "0.1.0",
                },
                "capabilities": {"experimentalApi": True},
            },
        )
        server.notify("initialized")
        print(f"initialized: {initialized.get('userAgent', 'ok')}")

        listed = server.request(
            "thread/list",
            {"limit": 10, "sortKey": "recency_at", "sortDirection": "desc"},
        )
        threads = listed["data"]
        print(f"existing threads: {len(threads)} returned")
        if not threads:
            raise RuntimeError("thread/list returned no existing local threads")
        existing_id = threads[0]["id"]
        existing = server.request(
            "thread/read", {"threadId": existing_id, "includeTurns": True}
        )
        print(
            "read existing thread: "
            f"{existing_id} ({len(existing['thread'].get('turns', []))} turns)"
        )

        with tempfile.TemporaryDirectory(prefix="foreman-poc-") as repo:
            subprocess.run(["git", "init", "-q", repo], check=True)
            Path(repo, "README.md").write_text(
                "# Foreman app-server proof repository\n", encoding="utf-8"
            )
            started = server.request(
                "thread/start", {"cwd": repo, "ephemeral": True}
            )
            thread_id = started["thread"]["id"]
            print(f"created disposable thread: {thread_id}")

            prompt = server.request(
                "turn/start",
                {
                    "threadId": thread_id,
                    "input": text_input(
                        "Reply with exactly FOREMAN_POC_OK. Do not use any tools."
                    ),
                },
                timeout=60,
            )
            turn_id = prompt["turn"]["id"]
            completed, assistant = server.wait_for(
                "turn/completed",
                turn_id=turn_id,
                print_events=args.verbose_events,
            )
            status = completed["params"]["turn"]["status"]
            print(f"prompt completed: {status}; assistant: {assistant.strip()!r}")
            if status != "completed" or "FOREMAN_POC_OK" not in assistant:
                raise RuntimeError("assistant response or terminal status was unexpected")

            current = server.request(
                "thread/read", {"threadId": thread_id, "includeTurns": False}
            )
            print(f"read disposable thread metadata: {current['thread']['id']}")

            if not args.skip_controls:
                steer_turn = server.request(
                    "turn/start",
                    {
                        "threadId": thread_id,
                        "input": text_input(
                            "Write one short sentence containing the word ALPHA."
                        ),
                    },
                )["turn"]["id"]
                server.wait_for("turn/started", turn_id=steer_turn)
                steered = server.request(
                    "turn/steer",
                    {
                        "threadId": thread_id,
                        "expectedTurnId": steer_turn,
                        "input": text_input("Also include the word BRAVO."),
                    },
                )
                server.wait_for("turn/completed", turn_id=steer_turn)
                print(f"steer accepted: {steered['turnId']}")

                interrupt_turn = server.request(
                    "turn/start",
                    {
                        "threadId": thread_id,
                        "input": text_input(
                            "Think carefully about the numbers from 1 to 100000, "
                            "then explain their sum. Do not use tools."
                        ),
                    },
                )["turn"]["id"]
                server.wait_for("turn/started", turn_id=interrupt_turn)
                server.request(
                    "turn/interrupt",
                    {"threadId": thread_id, "turnId": interrupt_turn},
                )
                interrupted, _ = server.wait_for(
                    "turn/completed", turn_id=interrupt_turn
                )
                print(
                    "interrupt accepted; terminal status: "
                    f"{interrupted['params']['turn']['status']}"
                )

        print("FOREMAN_CODEX_POC_PASS")
        return 0
    finally:
        server.close()


if __name__ == "__main__":
    sys.exit(main())
