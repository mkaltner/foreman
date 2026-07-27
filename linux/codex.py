"""Small async adapter for the installed Codex app-server."""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import tempfile
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from websockets.asyncio.client import unix_connect

EventHandler = Callable[[dict[str, Any]], Awaitable[None]]
APP_SERVER_MAX_MESSAGE_BYTES = 16 * 1024 * 1024
MODEL_CACHE_SECONDS = 30
PROJECTED_IMAGE_BYTES = 8 * 1024 * 1024


class CodexError(RuntimeError):
    pass


class Codex:
    def __init__(
        self,
        executable: str,
        on_event: EventHandler,
        socket_path: str | Path | None = None,
    ) -> None:
        self.executable = executable
        self.on_event = on_event
        self._uses_default_socket = (
            socket_path is None and not os.environ.get("FOREMAN_CODEX_SOCKET")
        )
        self.socket_path = Path(socket_path or resolve_socket_path()).expanduser()
        self.process: asyncio.subprocess.Process | None = None
        self._next_id = 1
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._write_lock = asyncio.Lock()
        self._connect_lock = asyncio.Lock()
        self._websocket: Any | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._reconnect_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._stderr: list[str] = []
        self._loaded: set[str] = set()
        self._subscribed: set[str] = set()
        self._routes: dict[str, tuple[str | None, str | None]] = {}
        self._supported_methods: set[str] = set()
        self._model_cache: tuple[float, list[dict[str, Any]]] | None = None
        self._stopping = False

    async def start(self) -> None:
        self._supported_methods = await asyncio.to_thread(self._discover_supported_methods)
        self._stopping = False
        await self._connect()

    async def _connect(self) -> None:
        async with self._connect_lock:
            if self._websocket is not None:
                return
            websocket: Any | None = None
            if self.socket_path.exists():
                try:
                    websocket = await self._open_websocket()
                except Exception:
                    await self._retire_owned_process()
                    if await self._socket_accepts_connections():
                        raise CodexError(
                            "Codex socket accepted a connection but its WebSocket "
                            "handshake failed"
                        )
                else:
                    try:
                        await self._activate(websocket)
                    except Exception:
                        await self._discard_connection(websocket)
                        raise
                    return

            await self._launch_app_server()
            deadline = asyncio.get_running_loop().time() + 10
            last_error: Exception | None = None
            while asyncio.get_running_loop().time() < deadline:
                try:
                    websocket = await self._open_websocket()
                    await self._activate(websocket)
                    return
                except Exception as error:
                    last_error = error
                    if websocket is not None:
                        await self._discard_connection(websocket)
                        websocket = None
                    if self.process and self.process.returncode is not None:
                        break
                    await asyncio.sleep(0.1)
            detail = "\n".join(self._stderr[-10:])
            raise CodexError(
                "Codex app-server socket did not become ready: "
                f"{last_error or detail or self.socket_path}"
            )

    async def _open_websocket(self) -> Any:
        return await unix_connect(
            str(self.socket_path),
            uri="ws://localhost/",
            max_size=APP_SERVER_MAX_MESSAGE_BYTES,
            open_timeout=3,
            close_timeout=2,
            ping_interval=20,
            ping_timeout=20,
            compression=None,
        )

    async def _activate(self, websocket: Any) -> None:
        self._websocket = websocket
        self._loaded.clear()
        self._reader_task = asyncio.create_task(self._read(websocket))
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
        await self._refresh_and_subscribe()

    async def _launch_app_server(self) -> None:
        await self._retire_owned_process()
        self.socket_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.socket_path.exists() and await self._socket_accepts_connections():
            return
        listen = "unix://" if self._uses_default_socket else f"unix://{self.socket_path}"
        self.process = await asyncio.create_subprocess_exec(
            self.executable,
            "app-server",
            "--listen",
            listen,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        self._stderr_task = asyncio.create_task(self._read_stderr(self.process))

    async def _refresh_and_subscribe(self) -> None:
        threads = await self.list_threads()
        ids = {
            item["id"]
            for item in threads
            if isinstance(item, dict) and isinstance(item.get("id"), str)
        }
        ids.update(self._subscribed)
        for thread_id in ids:
            try:
                thread = await self.resume_thread(thread_id)
            except CodexError:
                continue
            await self._emit_reconciled(thread)

    async def _emit_reconciled(self, thread: dict[str, Any]) -> None:
        turns = thread.get("turns", [])
        active_turn_id = next(
            (
                turn.get("id")
                for turn in reversed(turns)
                if turn.get("status") == "inProgress"
            ),
            None,
        )
        await self.on_event(
            {
                "method": "thread/status/changed",
                "params": {
                    "threadId": thread["id"],
                    "status": thread.get("status", {"type": "notLoaded"}),
                    "activeTurnId": active_turn_id,
                },
            }
        )

    def supports(self, method: str) -> bool:
        return method in self._supported_methods

    def _discover_supported_methods(self) -> set[str]:
        with tempfile.TemporaryDirectory(prefix="foreman-schema-") as directory:
            try:
                completed = subprocess.run(
                    [
                        self.executable,
                        "app-server",
                        "generate-json-schema",
                        "--experimental",
                        "--out",
                        directory,
                    ],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=15,
                    check=False,
                )
                if completed.returncode != 0:
                    return set()
                schema = json.loads(Path(directory, "ClientRequest.json").read_text())
            except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
                return set()

        methods: set[str] = set()

        def visit(value: Any) -> None:
            if isinstance(value, dict):
                method = value.get("properties", {}).get("method", {})
                if isinstance(method, dict):
                    methods.update(
                        item for item in method.get("enum", []) if isinstance(item, str)
                    )
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        visit(schema)
        return methods

    async def stop(self) -> None:
        self._stopping = True
        if self._reconnect_task:
            self._reconnect_task.cancel()
            await asyncio.gather(self._reconnect_task, return_exceptions=True)
            self._reconnect_task = None
        websocket, self._websocket = self._websocket, None
        if websocket is not None:
            await self._close_websocket(websocket)
        if self._reader_task:
            self._reader_task.cancel()
            await asyncio.gather(self._reader_task, return_exceptions=True)
            self._reader_task = None
        self._fail_pending("Codex app-server disconnected")
        await self._retire_owned_process()

    async def _retire_owned_process(self) -> None:
        process, self.process = self.process, None
        if process and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=10)
            except TimeoutError:
                process.kill()
                await process.wait()
        if self._stderr_task:
            self._stderr_task.cancel()
            await asyncio.gather(self._stderr_task, return_exceptions=True)
            self._stderr_task = None

    async def _close_websocket(self, websocket: Any) -> None:
        try:
            await websocket.close()
        except Exception:
            pass

    async def _discard_connection(self, websocket: Any) -> None:
        if self._websocket is websocket:
            self._websocket = None
        await self._close_websocket(websocket)
        if self._reader_task and self._reader_task is not asyncio.current_task():
            self._reader_task.cancel()
            await asyncio.gather(self._reader_task, return_exceptions=True)
            self._reader_task = None
        self._fail_pending("Codex app-server disconnected")

    async def _socket_accepts_connections(self) -> bool:
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_unix_connection(self.socket_path),
                timeout=1,
            )
        except (OSError, TimeoutError):
            return False
        writer.close()
        await asyncio.gather(writer.wait_closed(), return_exceptions=True)
        return True

    def _fail_pending(self, reason: str) -> None:
        for future in list(self._pending.values()):
            if not future.done():
                future.set_exception(CodexError(reason))

    async def _send(self, message: dict[str, Any]) -> None:
        websocket = self._websocket
        if websocket is None:
            raise CodexError("Codex app-server is reconnecting")
        data = json.dumps(message, separators=(",", ":"))
        async with self._write_lock:
            try:
                await websocket.send(data)
            except Exception as error:
                raise CodexError("Codex app-server disconnected") from error

    async def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        await self._send({"method": method, "params": params or {}})

    async def request(
        self, method: str, params: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._send(
                {"id": request_id, "method": method, "params": params or {}}
            )
            message = await asyncio.wait_for(future, timeout=120)
        finally:
            self._pending.pop(request_id, None)
            if not future.done():
                future.cancel()
        if "error" in message:
            raise CodexError(message["error"].get("message", "Codex request failed"))
        return message["result"]

    async def _read(self, websocket: Any) -> None:
        failure: Exception | None = None
        try:
            async for raw in websocket:
                try:
                    message = json.loads(raw)
                except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
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
        if self._websocket is websocket:
            self._websocket = None
            self._loaded.clear()
            reason = f"Codex app-server connection closed: {failure or 'closed'}"
            self._fail_pending(reason)
            if not self._stopping and (
                self._reconnect_task is None or self._reconnect_task.done()
            ):
                self._reconnect_task = asyncio.create_task(self._reconnect())

    async def _reconnect(self) -> None:
        delay = 0.25
        while not self._stopping:
            try:
                await self._connect()
                return
            except asyncio.CancelledError:
                raise
            except Exception:
                await asyncio.sleep(delay)
                delay = min(delay * 2, 5)

    async def _read_stderr(self, process: asyncio.subprocess.Process) -> None:
        if not process.stderr:
            return
        while line := await process.stderr.readline():
            self._stderr.append(line.decode(errors="replace").rstrip())
            self._stderr = self._stderr[-100:]

    async def list_threads(self) -> list[dict[str, Any]]:
        result = await self.request(
            "thread/list",
            {"limit": 100, "sortKey": "recency_at", "sortDirection": "desc"},
        )
        return [self._with_route(thread) for thread in result["data"]]

    async def read_thread(self, thread_id: str) -> dict[str, Any]:
        await self.ensure_resumed(thread_id)
        try:
            result = await self.request(
                "thread/read", {"threadId": thread_id, "includeTurns": True}
            )
        except CodexError:
            result = await self.request(
                "thread/read", {"threadId": thread_id, "includeTurns": False}
            )
        return self._with_route(result["thread"])

    async def start_thread(
        self, cwd: str, ephemeral: bool = False
    ) -> dict[str, Any]:
        result = await self.request(
            "thread/start",
            {"cwd": cwd, "ephemeral": ephemeral},
        )
        thread = self._remember_route(result)
        self._loaded.add(thread["id"])
        self._subscribed.add(thread["id"])
        return thread

    async def resume_thread(self, thread_id: str) -> dict[str, Any]:
        result = await self.request("thread/resume", {"threadId": thread_id})
        self._loaded.add(thread_id)
        return self._remember_route(result)

    def _remember_route(self, result: dict[str, Any]) -> dict[str, Any]:
        thread = result["thread"]
        self._routes[thread["id"]] = (
            result.get("model"),
            result.get("reasoningEffort"),
        )
        return self._with_route(thread)

    def _with_route(self, thread: dict[str, Any]) -> dict[str, Any]:
        model, effort = self._routes.get(thread["id"], (None, None))
        return {
            **thread,
            "_foremanModel": model,
            "_foremanReasoningEffort": effort,
        }

    async def ensure_resumed(self, thread_id: str) -> None:
        if thread_id not in self._loaded:
            await self.resume_thread(thread_id)

    async def subscribe_thread(self, thread_id: str) -> None:
        self._subscribed.add(thread_id)
        await self.ensure_resumed(thread_id)

    async def list_models(self, refresh: bool = False) -> list[dict[str, Any]]:
        now = time.monotonic()
        if (
            not refresh
            and self._model_cache
            and now - self._model_cache[0] < MODEL_CACHE_SECONDS
        ):
            return self._model_cache[1]
        result = await self.request("model/list", {"limit": 100})
        models = [model(item) for item in result.get("data", [])]
        models = [item for item in models if item is not None]
        self._model_cache = (now, models)
        return models

    async def prompt(
        self,
        thread_id: str,
        text: str,
        images: list[dict[str, str]] | None = None,
        model_id: str | None = None,
        effort: str | None = None,
    ) -> dict[str, Any]:
        await self.ensure_resumed(thread_id)
        params: dict[str, Any] = {
            "threadId": thread_id,
            "input": user_input(text, images or []),
        }
        if model_id:
            params["model"] = model_id
        if effort:
            params["effort"] = effort
        result = await self.request(
            "turn/start",
            params,
        )
        previous_model, previous_effort = self._routes.get(thread_id, (None, None))
        self._routes[thread_id] = (
            model_id or previous_model,
            effort or previous_effort,
        )
        return result

    async def steer(
        self,
        thread_id: str,
        turn_id: str,
        text: str,
        images: list[dict[str, str]] | None = None,
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
                "input": user_input(text, images or []),
            },
        )

    async def interrupt(self, thread_id: str, turn_id: str) -> None:
        await self.request(
            "turn/interrupt", {"threadId": thread_id, "turnId": turn_id}
        )

    async def archive_thread(self, thread_id: str) -> None:
        await self.request("thread/archive", {"threadId": thread_id})
        self._loaded.discard(thread_id)
        self._subscribed.discard(thread_id)
        self._routes.pop(thread_id, None)

    async def delete_thread(self, thread_id: str) -> None:
        await self.request("thread/delete", {"threadId": thread_id})
        self._loaded.discard(thread_id)
        self._subscribed.discard(thread_id)
        self._routes.pop(thread_id, None)


def resolve_socket_path() -> Path:
    override = os.environ.get("FOREMAN_CODEX_SOCKET")
    if override:
        return Path(override).expanduser()
    codex_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")).expanduser()
    return codex_home / "app-server-control" / "app-server-control.sock"


def model(item: Any) -> dict[str, Any] | None:
    if not isinstance(item, dict) or not isinstance(item.get("id"), str):
        return None
    efforts = [
        option["reasoningEffort"]
        for option in item.get("supportedReasoningEfforts", [])
        if isinstance(option, dict)
        and isinstance(option.get("reasoningEffort"), str)
    ]
    value = {
        "id": item["id"],
        "displayName": item.get("displayName") or item["id"],
        "description": item.get("description") or "",
        "reasoningEfforts": efforts,
        "defaultReasoningEffort": item.get("defaultReasoningEffort"),
        "visible": item.get("hidden") is not True,
        "isDefault": item.get("isDefault") is True,
        "inputModalities": [
            entry
            for entry in item.get("inputModalities", [])
            if isinstance(entry, str)
        ],
    }
    return value


def user_input(text: str, images: list[dict[str, str]]) -> list[dict[str, Any]]:
    inputs: list[dict[str, Any]] = []
    if text:
        inputs.append({"type": "text", "text": text, "text_elements": []})
    inputs.extend(
        {
            "type": "image",
            "url": f"data:{image['mimeType']};base64,{image['data']}",
        }
        for image in images
    )
    return inputs


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
        "model": thread.get("_foremanModel"),
        "reasoningEffort": thread.get("_foremanReasoningEffort"),
    }
    if include_messages:
        messages: list[dict[str, Any]] = []
        for turn in turns:
            for item in turn.get("items", []):
                normalized = normalize_item(item)
                if normalized:
                    normalized["turnId"] = turn.get("id")
                    messages.append(normalized)
        bound_message_images(messages)
        value["messages"] = messages
        value["activeTurnId"] = active_turn_id
    return value


def bound_message_images(
    messages: list[dict[str, Any]],
    maximum: int = PROJECTED_IMAGE_BYTES,
) -> None:
    remaining = maximum
    for message in reversed(messages):
        images = message.get("images")
        if not isinstance(images, list):
            continue
        kept: list[dict[str, str]] = []
        for image in reversed(images):
            size = len(image.get("data", "").encode())
            if size <= remaining:
                remaining -= size
                kept.append(image)
        message["images"] = list(reversed(kept))


def normalize_item(item: dict[str, Any]) -> dict[str, Any] | None:
    kind = item.get("type")
    base = {"id": item.get("id", ""), "rawType": kind}
    if kind == "userMessage":
        text = "".join(
            part.get("text", "")
            for part in item.get("content", [])
            if part.get("type") == "text"
        )
        images: list[dict[str, str]] = []
        image_count = 0
        for part in item.get("content", []):
            if not isinstance(part, dict) or part.get("type") not in (
                "image",
                "localImage",
            ):
                continue
            image_count += 1
            url = part.get("url")
            if not isinstance(url, str) or not url.startswith("data:image/"):
                continue
            header, separator, data = url.partition(",")
            mime_type = header[5:].split(";", 1)[0]
            if (
                separator
                and header.endswith(";base64")
                and mime_type in ("image/jpeg", "image/png", "image/webp")
            ):
                images.append({"mimeType": mime_type, "data": data})
        return {
            **base,
            "kind": "user",
            "text": text,
            "images": images,
            "imageCount": image_count,
        }
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
    elif method == "item/plan/delta":
        event.update(
            {
                "kind": "activity",
                "label": "Planning",
                "turnId": params.get("turnId"),
                "text": params.get("delta", ""),
                "append": True,
            }
        )
    elif method == "turn/plan/updated":
        active_step = next(
            (
                step.get("step", "")
                for step in params.get("plan", [])
                if step.get("status") == "inProgress"
            ),
            "",
        )
        event.update(
            {
                "kind": "activity",
                "label": "Planning",
                "turnId": params.get("turnId"),
                "text": active_step or params.get("explanation") or "",
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
        event.update(
            {
                "kind": "status",
                "status": status(params.get("status")),
                "turnId": params.get("activeTurnId"),
            }
        )
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
