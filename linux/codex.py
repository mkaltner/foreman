"""Small async adapter for the installed Codex app-server."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Awaitable, Callable
from typing import Any

EventHandler = Callable[[dict[str, Any]], Awaitable[None]]
APP_SERVER_MAX_MESSAGE_BYTES = 16 * 1024 * 1024


class CodexError(RuntimeError):
    pass


class Codex:
    def __init__(self, executable: str, on_event: EventHandler) -> None:
        self.executable = executable
        self.on_event = on_event
        self.process: asyncio.subprocess.Process | None = None
        self._next_id = 1
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._write_lock = asyncio.Lock()
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._stderr: list[str] = []
        self._loaded: set[str] = set()

    async def start(self) -> None:
        self.process = await asyncio.create_subprocess_exec(
            self.executable,
            "app-server",
            "--stdio",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            limit=APP_SERVER_MAX_MESSAGE_BYTES,
        )
        self._reader_task = asyncio.create_task(self._read())
        self._stderr_task = asyncio.create_task(self._read_stderr())
        await self.request(
            "initialize",
            {
                "clientInfo": {
                    "name": "foreman",
                    "title": "Foreman",
                    "version": "0.1.0",
                },
                "capabilities": {"experimentalApi": True},
            },
        )
        await self.notify("initialized")

    async def stop(self) -> None:
        if not self.process:
            return
        if self.process.stdin:
            self.process.stdin.close()
            try:
                await asyncio.wait_for(self.process.stdin.wait_closed(), timeout=2)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
        try:
            await asyncio.wait_for(self.process.wait(), timeout=10)
        except TimeoutError:
            self.process.terminate()
            await self.process.wait()
        for task in (self._reader_task, self._stderr_task):
            if task:
                task.cancel()

    async def _send(self, message: dict[str, Any]) -> None:
        if not self.process or not self.process.stdin:
            raise CodexError("Codex app-server is not running")
        data = json.dumps(message, separators=(",", ":")).encode() + b"\n"
        async with self._write_lock:
            self.process.stdin.write(data)
            await self.process.stdin.drain()

    async def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        await self._send({"method": method, "params": params or {}})

    async def request(
        self, method: str, params: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        await self._send({"id": request_id, "method": method, "params": params or {}})
        try:
            message = await asyncio.wait_for(future, timeout=120)
        finally:
            self._pending.pop(request_id, None)
        if "error" in message:
            raise CodexError(message["error"].get("message", "Codex request failed"))
        return message["result"]

    async def _read(self) -> None:
        assert self.process and self.process.stdout
        failure: Exception | None = None
        try:
            while line := await self.process.stdout.readline():
                try:
                    message = json.loads(line)
                except json.JSONDecodeError:
                    continue
                request_id = message.get("id")
                if request_id is not None and ("result" in message or "error" in message):
                    future = self._pending.get(request_id)
                    if future and not future.done():
                        future.set_result(message)
                    continue
                await self.on_event(message)
        except Exception as error:
            failure = error
        detail = "\n".join(self._stderr[-10:])
        reason = f"Codex app-server response reader stopped: {failure or detail or 'closed'}"
        for future in list(self._pending.values()):
            if not future.done():
                future.set_exception(CodexError(reason))

    async def _read_stderr(self) -> None:
        assert self.process and self.process.stderr
        while line := await self.process.stderr.readline():
            self._stderr.append(line.decode(errors="replace").rstrip())
            self._stderr = self._stderr[-100:]

    async def list_threads(self) -> list[dict[str, Any]]:
        result = await self.request(
            "thread/list",
            {"limit": 100, "sortKey": "recency_at", "sortDirection": "desc"},
        )
        return result["data"]

    async def read_thread(self, thread_id: str) -> dict[str, Any]:
        result = await self.request(
            "thread/read", {"threadId": thread_id, "includeTurns": True}
        )
        return result["thread"]

    async def start_thread(self, cwd: str) -> dict[str, Any]:
        result = await self.request("thread/start", {"cwd": cwd})
        thread = result["thread"]
        self._loaded.add(thread["id"])
        return thread

    async def resume_thread(self, thread_id: str) -> dict[str, Any]:
        result = await self.request("thread/resume", {"threadId": thread_id})
        self._loaded.add(thread_id)
        return result["thread"]

    async def ensure_resumed(self, thread_id: str) -> None:
        if thread_id not in self._loaded:
            await self.resume_thread(thread_id)

    async def prompt(self, thread_id: str, text: str) -> dict[str, Any]:
        await self.ensure_resumed(thread_id)
        return await self.request(
            "turn/start",
            {
                "threadId": thread_id,
                "input": [{"type": "text", "text": text, "text_elements": []}],
            },
        )

    async def steer(
        self, thread_id: str, turn_id: str, text: str
    ) -> dict[str, Any]:
        # A different Codex client can start a newer turn after Foreman last read
        # the thread. Reconcile immediately before steering so an otherwise valid
        # message does not fail with an "expected active turn id" race.
        current = session(await self.read_thread(thread_id), True)
        current_turn_id = current.get("activeTurnId")
        if current_turn_id:
            turn_id = current_turn_id
        return await self.request(
            "turn/steer",
            {
                "threadId": thread_id,
                "expectedTurnId": turn_id,
                "input": [{"type": "text", "text": text, "text_elements": []}],
            },
        )

    async def interrupt(self, thread_id: str, turn_id: str) -> None:
        await self.request(
            "turn/interrupt", {"threadId": thread_id, "turnId": turn_id}
        )

    async def archive_thread(self, thread_id: str) -> None:
        await self.request("thread/archive", {"threadId": thread_id})
        self._loaded.discard(thread_id)

    async def delete_thread(self, thread_id: str) -> None:
        await self.request("thread/delete", {"threadId": thread_id})
        self._loaded.discard(thread_id)


def status(raw: Any, last_turn: str | None = None) -> str:
    if isinstance(raw, dict):
        kind = raw.get("type")
        flags = raw.get("activeFlags", [])
        if kind == "active" and "waitingOnApproval" in flags:
            return "waiting"
        if kind == "active" and "waitingOnUserInput" in flags:
            return "waiting"
        if kind == "active":
            return "working"
        if kind == "systemError":
            return "failed"
        if kind in ("idle", "notLoaded"):
            return last_turn if last_turn in ("completed", "failed", "interrupted") else "idle"
    return last_turn or "idle"


def session(thread: dict[str, Any], include_messages: bool = False) -> dict[str, Any]:
    turns = thread.get("turns", [])
    last_turn = turns[-1].get("status") if turns else None
    active_turn_id = next(
        (turn.get("id") for turn in reversed(turns) if turn.get("status") == "inProgress"),
        None,
    )
    projected_status = status(thread.get("status"), last_turn)
    if active_turn_id and projected_status == "idle":
        projected_status = "working"
    value: dict[str, Any] = {
        "id": thread["id"],
        "repository": thread.get("cwd", ""),
        "title": thread.get("name") or thread.get("preview") or "Untitled session",
        "status": projected_status,
        "lastActivity": thread.get("recencyAt") or thread.get("updatedAt"),
        "attention": projected_status == "waiting",
    }
    if include_messages:
        messages: list[dict[str, Any]] = []
        for turn in turns:
            for item in turn.get("items", []):
                normalized = normalize_item(item)
                if normalized:
                    normalized["turnId"] = turn.get("id")
                    messages.append(normalized)
        value["messages"] = messages
        value["activeTurnId"] = active_turn_id
    return value


def normalize_item(item: dict[str, Any]) -> dict[str, Any] | None:
    kind = item.get("type")
    base = {"id": item.get("id", ""), "rawType": kind}
    if kind == "userMessage":
        text = "".join(
            part.get("text", "")
            for part in item.get("content", [])
            if part.get("type") == "text"
        )
        return {**base, "kind": "user", "text": text}
    if kind == "agentMessage":
        return {**base, "kind": "assistant", "text": item.get("text", "")}
    if kind == "commandExecution":
        return {
            **base,
            "kind": "command",
            "description": item.get("command", ""),
            "status": item.get("status", "inProgress"),
            "exitCode": item.get("exitCode"),
        }
    if kind in (
        "mcpToolCall",
        "dynamicToolCall",
        "collabToolCall",
        "collabAgentToolCall",
    ):
        description = item.get("tool", "tool")
        if item.get("server"):
            description = f"{item['server']}: {description}"
        return {
            **base,
            "kind": "tool",
            "description": description,
            "status": item.get("status", "inProgress"),
        }
    if kind == "webSearch":
        return {
            **base,
            "kind": "tool",
            # Search terms can contain credentials, private paths, or other
            # sensitive input. Never include them in Android-facing metadata.
            "description": "Web search",
            "status": item.get("status", "inProgress"),
        }
    if kind == "fileChange":
        changes = item.get("changes", [])
        count = len(changes) if isinstance(changes, list) else 0
        noun = "file" if count == 1 else "files"
        return {
            **base,
            "kind": "tool",
            "description": f"Editing {count} {noun}" if count else "Editing files",
            "status": item.get("status", "inProgress"),
        }
    if kind == "imageView":
        return {
            **base,
            "kind": "tool",
            "description": "Viewing an image",
            "status": item.get("status", "inProgress"),
        }
    return None


def normalize_event(message: dict[str, Any]) -> tuple[str | None, dict[str, Any]]:
    method = message.get("method", "")
    params = message.get("params", {})
    thread_id = params.get("threadId")
    event: dict[str, Any] = {"type": method}
    if method == "item/agentMessage/delta":
        event.update(
            {
                "kind": "assistant.delta",
                "turnId": params.get("turnId"),
                "itemId": params.get("itemId"),
                "text": params.get("delta", ""),
            }
        )
    elif method == "item/reasoning/summaryTextDelta":
        event.update(
            {
                "kind": "activity",
                "label": "Thinking",
                "turnId": params.get("turnId"),
                "itemId": params.get("itemId"),
                "text": params.get("delta", ""),
                "append": True,
            }
        )
    elif method == "item/reasoning/summaryPartAdded":
        event.update(
            {
                "kind": "activity",
                "label": "Thinking",
                "turnId": params.get("turnId"),
                "itemId": params.get("itemId"),
                "text": "\n",
                "append": True,
            }
        )
    elif method in ("item/plan/delta", "turn/plan/updated"):
        event.update(
            {
                "kind": "activity",
                "label": "Planning",
                "turnId": params.get("turnId"),
                "text": "",
                "append": False,
            }
        )
    elif method == "item/commandExecution/outputDelta":
        event.update(
            {
                "kind": "activity",
                "label": "Running command",
                "turnId": params.get("turnId"),
                "itemId": params.get("itemId"),
                "text": "",
                "append": False,
            }
        )
    elif method in ("item/started", "item/completed"):
        raw_item = params.get("item", {})
        normalized_item = normalize_item(raw_item)
        if (
            normalized_item
            and method == "item/completed"
            and not raw_item.get("status")
            and normalized_item.get("kind") in ("command", "tool")
        ):
            normalized_item["status"] = "completed"
        event.update(
            {
                "kind": "item",
                "phase": "started" if method == "item/started" else "completed",
                "turnId": params.get("turnId"),
                "item": normalized_item,
            }
        )
    elif method == "turn/started":
        event.update(
            {
                "kind": "status",
                "status": "working",
                "turnId": params.get("turn", {}).get("id"),
            }
        )
    elif method == "turn/completed":
        turn = params.get("turn", {})
        event.update(
            {
                "kind": "status",
                "status": status({}, turn.get("status")),
                "turnId": turn.get("id"),
                "error": turn.get("error"),
            }
        )
    elif method == "thread/status/changed":
        event.update({"kind": "status", "status": status(params.get("status"))})
    elif method in (
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/tool/requestUserInput",
        "permissions/requestApproval",
    ):
        event.update(
            {
                "kind": "status",
                "status": "waiting",
                "reason": "approvalOrInputUnsupported",
            }
        )
    else:
        event["kind"] = "activity"
    return thread_id, event
