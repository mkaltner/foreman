"""Small async adapter for the installed Codex app-server."""

from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

VENDOR_DIR = Path(__file__).resolve().parent / "vendor"
if VENDOR_DIR.is_dir():
    sys.path.insert(0, str(VENDOR_DIR))

from websockets.asyncio.client import unix_connect

from approvals import (
    APPROVAL_METHODS,
    ApprovalError,
    PendingApproval,
    approval_key,
    bounded_approval_params,
)
from inputs import INPUT_METHODS, PendingInput, bounded_input_params

EventHandler = Callable[[dict[str, Any]], Awaitable[None]]
APP_SERVER_MAX_MESSAGE_BYTES = 16 * 1024 * 1024
MODEL_CACHE_SECONDS = 30
ACCESS_CACHE_SECONDS = 30
PROJECTED_IMAGE_BYTES = 8 * 1024 * 1024
SESSION_LIST_LIMIT = 500
THREAD_HISTORY_LIMIT = 1_000
SESSION_HISTORY_TAIL_BYTES = 4 * 1024 * 1024
SEARCH_SNIPPET_LIMIT = 200
SEARCH_SNIPPETS_PER_SESSION = 3
DESKTOP_ATTACHMENT_HEADER = "# Files mentioned by the user:\n"
DESKTOP_REQUEST_MARKER = "\n## My request for Codex:\n"
SHARED_DESKTOP_LIVE_STATUS_AVAILABLE = "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE"
SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE = "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE"
FOREMAN_VERSION = "1.1.0"


def _foreman_release_build() -> bool:
    for candidate in (
        Path(__file__).resolve().parent / "release.properties",
        Path(__file__).resolve().parent.parent / "release.properties",
    ):
        try:
            for line in candidate.read_text(encoding="utf-8").splitlines():
                if line.strip() == "releaseBuild=true":
                    return True
                if line.strip() == "releaseBuild=false":
                    return False
        except OSError:
            continue
    return False


FOREMAN_RELEASE_BUILD = _foreman_release_build()

ACCESS_LEVELS = (
    {
        "id": "ask",
        "displayName": "Ask for approval",
        "description": "Always ask to edit external files and use the Internet",
        "permissionProfile": ":workspace",
        "approvalPolicy": "on-request",
        "approvalsReviewer": "user",
    },
    {
        "id": "auto",
        "displayName": "Approve for me",
        "description": "Only ask for actions detected as potentially unsafe",
        "permissionProfile": ":workspace",
        "approvalPolicy": "on-request",
        "approvalsReviewer": "auto_review",
    },
    {
        "id": "full",
        "displayName": "Full access",
        "description": "Unrestricted access to the Internet and any file on your computer",
        "permissionProfile": ":danger-full-access",
        "approvalPolicy": "never",
        "approvalsReviewer": "user",
    },
)


class CodexError(RuntimeError):
    pass


def is_unmaterialized_thread_history_error(error: CodexError) -> bool:
    message = str(error).lower()
    return (
        "not materialized yet" in message
        and "before first user message" in message
    )


class Codex:
    def __init__(
        self,
        executable: str,
        on_event: EventHandler,
        socket_path: str | Path | None = None,
        fallback_socket_path: str | Path | None = None,
        allow_fallback: bool = True,
        session_history_root: str | Path | None = None,
    ) -> None:
        self.executable = executable
        self.on_event = on_event
        self.primary_socket_path = Path(socket_path or resolve_socket_path()).expanduser()
        self.fallback_socket_path = Path(
            fallback_socket_path or resolve_fallback_socket_path()
        ).expanduser()
        if self.primary_socket_path == self.fallback_socket_path:
            raise ValueError("Foreman fallback socket must differ from the Codex socket")
        self.allow_fallback = allow_fallback
        self.session_history_root = Path(
            session_history_root or resolve_codex_home() / "sessions"
        ).expanduser()
        self.socket_path = self.primary_socket_path
        self.runtime_status = SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE
        self.process: asyncio.subprocess.Process | None = None
        self._next_id = 1
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._approvals: dict[str, PendingApproval] = {}
        self._approval_requests: dict[str, str] = {}
        self._approval_tombstones: dict[str, str] = {}
        self._inputs: dict[str, PendingInput] = {}
        self._input_requests: dict[str, str] = {}
        self._input_tombstones: dict[str, str] = {}
        self._items: dict[tuple[str, str, str], dict[str, Any]] = {}
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
        self._access_levels: dict[str, str | None] = {}
        self._session_history_files: dict[str, Path] | None = None
        self._historical_access_levels: dict[str, str | None] = {}
        self._supported_methods: set[str] = set()
        self._model_cache: tuple[float, list[dict[str, Any]]] | None = None
        self._access_cache: tuple[float, list[dict[str, Any]]] | None = None
        self.version: str | None = None
        self.last_communication: float | None = None
        self.last_event: float | None = None
        self.last_successful_request: float | None = None
        self.attached_at: float | None = None
        self._stopping = False

    @property
    def is_connected(self) -> bool:
        return self._websocket is not None

    async def start(self) -> None:
        self._supported_methods, self.version = await asyncio.gather(
            asyncio.to_thread(self._discover_supported_methods),
            asyncio.to_thread(self._discover_version),
        )
        self._stopping = False
        await self._connect()

    def _discover_version(self) -> str | None:
        try:
            completed = subprocess.run(
                [self.executable, "--version"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=5,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            return None
        if completed.returncode != 0:
            return None
        value = completed.stdout.strip()
        return value.removeprefix("codex-cli ")[:80] or None

    async def _connect(self) -> None:
        async with self._connect_lock:
            if self._websocket is not None:
                return
            self.socket_path = self.primary_socket_path
            if await self._attach_existing(
                SHARED_DESKTOP_LIVE_STATUS_AVAILABLE,
                "Desktop Codex socket",
                fail_if_present=True,
            ):
                return
            if not self.allow_fallback:
                raise CodexError(
                    f"Desktop Codex socket is unavailable: {self.primary_socket_path}"
                )

            self.socket_path = self.fallback_socket_path
            self.runtime_status = SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE
            if await self._attach_existing(
                SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE,
                "Foreman fallback socket",
            ):
                return
            await self._launch_fallback_app_server()
            deadline = asyncio.get_running_loop().time() + 10
            last_error: Exception | None = None
            websocket: Any | None = None
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

    async def _attach_existing(
        self, status: str, label: str, fail_if_present: bool = False
    ) -> bool:
        path_was_present = os.path.lexists(self.socket_path)
        if not path_was_present and not await self._socket_accepts_connections():
            return False
        websocket: Any | None = None
        try:
            websocket = await self._open_websocket()
        except Exception as error:
            if await self._socket_accepts_connections():
                raise CodexError(
                    f"{label} accepted a connection but its WebSocket handshake failed: "
                    f"{self.socket_path}"
                ) from error
            if fail_if_present and path_was_present:
                raise CodexError(
                    f"{label} exists but Foreman could not attach: {self.socket_path}"
                ) from error
            return False
        try:
            self.runtime_status = status
            await self._activate(websocket)
        except Exception:
            await self._discard_connection(websocket)
            raise
        return True

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
                    "version": FOREMAN_VERSION,
                },
                "capabilities": {"experimentalApi": True},
            },
        )
        await self.notify("initialized")
        await self._refresh_and_subscribe()
        self.attached_at = time.time()

    async def _launch_fallback_app_server(self) -> None:
        if self.socket_path != self.fallback_socket_path:
            raise CodexError("refusing to launch an app-server on the Desktop socket")
        await self._retire_owned_process()
        self.socket_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.socket_path.exists() and await self._socket_accepts_connections():
            return
        self.process = await asyncio.create_subprocess_exec(
            self.executable,
            "app-server",
            "--listen",
            f"unix://{self.socket_path}",
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
        latest_turn = turns[-1] if turns else None
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
                    "latestTurn": latest_turn,
                    "_foremanReconciled": True,
                    "_foremanActivityAt": thread.get("recencyAt") or thread.get("updatedAt"),
                    "_foremanActivityComplete": (
                        isinstance(thread.get("status"), dict)
                        and (
                            token_count(thread.get("recencyAt")) is not None
                            or token_count(thread.get("updatedAt")) is not None
                        )
                    ),
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
            try:
                notification_schema = json.loads(
                    Path(directory, "ServerNotification.json").read_text()
                )
            except (OSError, json.JSONDecodeError):
                notification_schema = {}
            try:
                thread_list = json.loads(
                    Path(directory, "v2", "ThreadListParams.json").read_text()
                )
            except (OSError, json.JSONDecodeError):
                thread_list = {}

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
        visit(notification_schema)
        if isinstance(thread_list.get("properties", {}).get("archived"), dict):
            # The list method predates its archived scope. Keep field support
            # explicit so older Codex binaries fail closed instead of being
            # identified by provider name or by thread/list alone.
            methods.add("thread/list:archived")
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
        await self._expire_approvals("disconnected")
        await self._expire_inputs("disconnected")
        await self._retire_owned_process()

    async def _retire_owned_process(self) -> None:
        process, self.process = self.process, None
        if process and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=5)
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
        await self._expire_approvals("disconnected", connection=websocket)
        await self._expire_inputs("disconnected", connection=websocket)

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
        await self._send_on_connection(websocket, message)

    async def _send_on_connection(
        self, websocket: Any, message: dict[str, Any]
    ) -> None:
        data = json.dumps(message, separators=(",", ":"))
        async with self._write_lock:
            if self._websocket is not websocket:
                raise CodexError("Codex app-server disconnected")
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
        self.last_successful_request = time.time()
        return message["result"]

    async def _read(self, websocket: Any) -> None:
        failure: Exception | None = None
        try:
            async for raw in websocket:
                try:
                    message = json.loads(raw)
                except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
                    continue
                self.last_communication = time.time()
                request_id = message.get("id")
                if request_id is not None and ("result" in message or "error" in message):
                    future = self._pending.get(request_id)
                    if future and not future.done():
                        future.set_result(message)
                    continue
                if request_id is not None and isinstance(message.get("method"), str):
                    await self._server_request(message, websocket)
                    continue
                self.last_event = time.time()
                self._remember_settings_event(message)
                self._remember_item_event(message)
                await self._server_request_lifecycle(message)
                await self.on_event(message)
        except Exception as error:
            failure = error
        if self._websocket is websocket:
            self._websocket = None
            self.attached_at = None
            self._loaded.clear()
            reason = f"Codex app-server connection closed: {failure or 'closed'}"
            self._fail_pending(reason)
            await self._expire_approvals("disconnected", connection=websocket)
            await self._expire_inputs("disconnected", connection=websocket)
            await self.on_event(
                {"method": "foreman/runtime/disconnected", "params": {}}
            )
            if not self._stopping and (
                self._reconnect_task is None or self._reconnect_task.done()
            ):
                self._reconnect_task = asyncio.create_task(self._reconnect())

    def _remember_item_event(self, message: dict[str, Any]) -> None:
        if message.get("method") not in ("item/started", "item/completed"):
            return
        params = message.get("params")
        if not isinstance(params, dict) or not isinstance(params.get("item"), dict):
            return
        item = params["item"]
        values = (params.get("threadId"), params.get("turnId"), item.get("id"))
        if all(isinstance(value, str) for value in values):
            self._items[values] = item
            if len(self._items) > 1_000:
                self._items.pop(next(iter(self._items)))

    async def _server_request(
        self, message: dict[str, Any], websocket: Any
    ) -> None:
        method = message.get("method")
        if method not in APPROVAL_METHODS | INPUT_METHODS:
            # Unknown server requests are intentionally left to compatible
            # clients; they must never be projected as notifications.
            return
        params = message.get("params")
        upstream_id = message.get("id")
        if not isinstance(params, dict) or not isinstance(upstream_id, (str, int)) or isinstance(upstream_id, bool):
            return
        if method in INPUT_METHODS:
            await self._input_request(method, upstream_id, params, websocket)
            return
        params = bounded_approval_params(method, params)
        key = approval_key(upstream_id)
        existing_id = self._approval_requests.get(key)
        if existing_id and existing_id in self._approvals:
            return
        values = (params.get("threadId"), params.get("turnId"), params.get("itemId"))
        item = self._items.get(values) if all(isinstance(value, str) for value in values) else None
        approval = PendingApproval(
            upstream_id=upstream_id,
            method=method,
            params=params,
            item=item,
            connection=websocket,
        )
        # Register before any client can observe the request.
        self._approvals[approval.id] = approval
        self._approval_requests[key] = approval.id
        self.last_event = time.time()
        await self.on_event(
            {
                "method": "foreman/approval/requested",
                "params": {"approval": approval.projection()},
            }
        )

    async def _input_request(
        self,
        method: str,
        upstream_id: str | int,
        params: dict[str, Any],
        websocket: Any,
    ) -> None:
        params = bounded_input_params(method, params)
        key = approval_key(upstream_id)
        existing_id = self._input_requests.get(key)
        if existing_id and existing_id in self._inputs:
            return
        pending = PendingInput(
            upstream_id=upstream_id,
            method=method,
            params=params,
            connection=websocket,
        )
        self._inputs[pending.id] = pending
        self._input_requests[key] = pending.id
        self.last_event = time.time()
        await self.on_event(
            {
                "method": "foreman/input/requested",
                "params": {"input": pending.projection()},
            }
        )

    def list_approvals(self) -> list[dict[str, Any]]:
        return [
            approval.projection()
            for approval in self._approvals.values()
            if approval.status in ("pending", "submitting")
        ]

    def list_inputs(self) -> list[dict[str, Any]]:
        return [
            pending.projection()
            for pending in self._inputs.values()
            if pending.status in ("pending", "submitting")
        ]

    async def respond_approval(
        self, approval_id: str, decision: dict[str, Any]
    ) -> dict[str, Any]:
        approval = self._approvals.get(approval_id)
        if approval is None:
            if approval_id in self._approval_tombstones:
                raise ApprovalError("Already resolved")
            raise ApprovalError("Approval is no longer available")
        async with approval.lock:
            if approval.status != "pending":
                raise ApprovalError("Already resolved")
            if approval.connection is not self._websocket:
                await self._resolve_approval(approval, "expired", "reconnected")
                raise ApprovalError("Approval expired during reconnect")
            result, resolution = approval.response_result(decision)
            approval.status = "submitting"
            approval.resolution = resolution
            await self.on_event(
                {
                    "method": "foreman/approval/updated",
                    "params": {"approval": approval.projection()},
                }
            )
            if (
                self._approvals.get(approval.id) is not approval
                or approval.status != "submitting"
            ):
                raise ApprovalError("Already resolved")
            try:
                await self._send_on_connection(
                    approval.connection,
                    {"id": approval.upstream_id, "result": result},
                )
            except CodexError:
                await self._resolve_approval(approval, "expired", "disconnected")
                raise ApprovalError("Approval expired while the response was sent")
            return approval.projection()

    async def respond_input(
        self, input_id: str, response: dict[str, Any]
    ) -> dict[str, Any]:
        pending = self._inputs.get(input_id)
        if pending is None:
            if input_id in self._input_tombstones:
                raise ApprovalError("Already resolved")
            raise ApprovalError("Input request is no longer available")
        async with pending.lock:
            if pending.status != "pending":
                raise ApprovalError("Already resolved")
            if pending.connection is not self._websocket:
                await self._resolve_input(pending, "expired", "reconnected")
                raise ApprovalError("Input request expired during reconnect")
            result, resolution = pending.response_result(response)
            pending.status = "submitting"
            pending.resolution = resolution
            await self.on_event(
                {
                    "method": "foreman/input/updated",
                    "params": {"input": pending.projection()},
                }
            )
            if self._inputs.get(pending.id) is not pending or pending.status != "submitting":
                raise ApprovalError("Already resolved")
            try:
                await self._send_on_connection(
                    pending.connection,
                    {"id": pending.upstream_id, "result": result},
                )
            except CodexError:
                await self._resolve_input(pending, "expired", "disconnected")
                raise ApprovalError("Input request expired while the response was sent")
            return pending.projection()

    async def expire_turn(self, thread_id: str, turn_id: str, reason: str) -> None:
        await self._expire_approvals(reason, thread_id=thread_id, turn_id=turn_id)
        await self._expire_inputs(reason, thread_id=thread_id, turn_id=turn_id)

    async def _server_request_lifecycle(self, message: dict[str, Any]) -> None:
        method = message.get("method")
        params = message.get("params")
        if not isinstance(params, dict):
            return
        if method == "serverRequest/resolved":
            approval_id = self._approval_requests.get(approval_key(params.get("requestId")))
            approval = self._approvals.get(approval_id or "")
            if approval:
                await self._resolve_approval(
                    approval, "resolved", approval.resolution or "resolvedElsewhere"
                )
            input_id = self._input_requests.get(approval_key(params.get("requestId")))
            pending = self._inputs.get(input_id or "")
            if pending:
                await self._resolve_input(
                    pending, "resolved", pending.resolution or "resolvedElsewhere"
                )
        elif method == "turn/started":
            turn = params.get("turn")
            turn_id = turn.get("id") if isinstance(turn, dict) else params.get("turnId")
            thread_id = params.get("threadId")
            if isinstance(thread_id, str) and isinstance(turn_id, str):
                for approval in list(self._approvals.values()):
                    if approval.thread_id == thread_id and approval.turn_id != turn_id:
                        await self._resolve_approval(approval, "expired", "replaced")
                for pending in list(self._inputs.values()):
                    if pending.thread_id == thread_id and pending.turn_id and pending.turn_id != turn_id:
                        await self._resolve_input(pending, "expired", "replaced")
        elif method == "turn/completed":
            turn = params.get("turn")
            turn_id = turn.get("id") if isinstance(turn, dict) else params.get("turnId")
            thread_id = params.get("threadId")
            if isinstance(thread_id, str) and isinstance(turn_id, str):
                await self._expire_approvals(
                    "turnEnded", thread_id=thread_id, turn_id=turn_id
                )
                await self._expire_inputs(
                    "turnEnded", thread_id=thread_id, turn_id=turn_id
                )

    async def _approval_lifecycle(self, message: dict[str, Any]) -> None:
        """Compatibility alias for tests and older in-process integrations."""
        await self._server_request_lifecycle(message)

    async def _expire_approvals(
        self,
        reason: str,
        *,
        thread_id: str | None = None,
        turn_id: str | None = None,
        connection: Any | None = None,
    ) -> None:
        for approval in list(self._approvals.values()):
            if thread_id is not None and approval.thread_id != thread_id:
                continue
            if turn_id is not None and approval.turn_id != turn_id:
                continue
            if connection is not None and approval.connection is not connection:
                continue
            await self._resolve_approval(approval, "expired", reason)

    async def _resolve_approval(
        self, approval: PendingApproval, status: str, resolution: str
    ) -> None:
        if approval.id not in self._approvals:
            return
        approval.status = status
        approval.resolution = resolution
        self._approvals.pop(approval.id, None)
        self._approval_requests.pop(approval_key(approval.upstream_id), None)
        self._approval_tombstones[approval.id] = resolution
        while len(self._approval_tombstones) > 500:
            self._approval_tombstones.pop(next(iter(self._approval_tombstones)))
        await self.on_event(
            {
                "method": "foreman/approval/resolved",
                "params": {"approval": approval.projection()},
            }
        )

    async def _expire_inputs(
        self,
        reason: str,
        *,
        thread_id: str | None = None,
        turn_id: str | None = None,
        connection: Any | None = None,
    ) -> None:
        for pending in list(self._inputs.values()):
            if thread_id is not None and pending.thread_id != thread_id:
                continue
            if turn_id is not None and pending.turn_id != turn_id:
                continue
            if connection is not None and pending.connection is not connection:
                continue
            await self._resolve_input(pending, "expired", reason)

    async def _resolve_input(
        self, pending: PendingInput, status: str, resolution: str
    ) -> None:
        if pending.id not in self._inputs:
            return
        pending.status = status
        pending.resolution = resolution
        self._inputs.pop(pending.id, None)
        self._input_requests.pop(approval_key(pending.upstream_id), None)
        self._input_tombstones[pending.id] = resolution
        while len(self._input_tombstones) > 500:
            self._input_tombstones.pop(next(iter(self._input_tombstones)))
        await self.on_event(
            {
                "method": "foreman/input/resolved",
                "params": {"input": pending.projection()},
            }
        )

    def _remember_settings_event(self, message: dict[str, Any]) -> None:
        if message.get("method") != "thread/settings/updated":
            return
        params = message.get("params", {})
        settings = params.get("threadSettings", {})
        thread_id = params.get("threadId")
        if not isinstance(thread_id, str) or not isinstance(settings, dict):
            return
        previous_model, previous_effort = self._routes.get(thread_id, (None, None))
        self._routes[thread_id] = (
            settings.get("model")
            if isinstance(settings.get("model"), str)
            else previous_model,
            settings.get("effort")
            if isinstance(settings.get("effort"), str)
            else previous_effort,
        )
        selected_access_level = access_level(settings)
        if selected_access_level is not None:
            self._access_levels[thread_id] = selected_access_level

    async def _reconnect(self) -> None:
        delay = 0.25
        while not self._stopping:
            try:
                await self._connect()
                await self.on_event(
                    {"method": "foreman/runtime/reconnected", "params": {}}
                )
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

    async def list_threads(self, archived: bool = False) -> list[dict[str, Any]]:
        threads: list[dict[str, Any]] = []
        cursor: str | None = None
        seen_cursors: set[str] = set()
        while len(threads) < SESSION_LIST_LIMIT:
            params: dict[str, Any] = {
                "limit": min(100, SESSION_LIST_LIMIT - len(threads)),
                "sortKey": "recency_at",
                "sortDirection": "desc",
            }
            if archived:
                params["archived"] = True
            if cursor:
                params["cursor"] = cursor
            result = await self.request("thread/list", params)
            data = result.get("data", [])
            threads.extend(item for item in data if isinstance(item, dict))
            next_cursor = result.get("nextCursor")
            if not isinstance(next_cursor, str) or not next_cursor or next_cursor in seen_cursors:
                break
            seen_cursors.add(next_cursor)
            cursor = next_cursor
        return [self._with_route(thread) for thread in threads[:SESSION_LIST_LIMIT]]

    async def read_thread(self, thread_id: str) -> dict[str, Any]:
        await self.ensure_resumed(thread_id)
        return await self._read_thread_with_history(thread_id)

    async def _read_thread_with_history(self, thread_id: str) -> dict[str, Any]:
        if self.supports("thread/turns/list"):
            result = await self.request(
                "thread/read", {"threadId": thread_id, "includeTurns": False}
            )
            thread = result["thread"]
            try:
                thread["turns"] = await self._list_thread_turns(thread_id)
            except CodexError as error:
                # Codex exposes a newly started thread through thread/list and
                # thread/read before its first user message creates the rollout.
                # Such a thread is a valid empty Foreman session even though the
                # paginated history endpoint cannot read it yet.
                if not is_unmaterialized_thread_history_error(error):
                    raise
                thread["turns"] = []
            return self._with_route(thread)
        try:
            result = await self.request(
                "thread/read", {"threadId": thread_id, "includeTurns": True}
            )
        except CodexError:
            result = await self.request(
                "thread/read", {"threadId": thread_id, "includeTurns": False}
            )
        return self._with_route(result["thread"])

    async def _list_thread_turns(self, thread_id: str) -> list[dict[str, Any]]:
        turns: list[dict[str, Any]] = []
        cursor: str | None = None
        seen_cursors: set[str] = set()
        while len(turns) < THREAD_HISTORY_LIMIT:
            params: dict[str, Any] = {
                "threadId": thread_id,
                "limit": min(100, THREAD_HISTORY_LIMIT - len(turns)),
                "sortDirection": "asc",
                "itemsView": "full",
            }
            if cursor:
                params["cursor"] = cursor
            result = await self.request("thread/turns/list", params)
            data = result.get("data", [])
            turns.extend(item for item in data if isinstance(item, dict))
            next_cursor = result.get("nextCursor")
            if not isinstance(next_cursor, str) or not next_cursor or next_cursor in seen_cursors:
                break
            seen_cursors.add(next_cursor)
            cursor = next_cursor
        return turns[:THREAD_HISTORY_LIMIT]

    async def search_thread(self, thread_id: str) -> dict[str, Any]:
        """Read authoritative history without subscribing or resuming the thread."""
        return await self._read_thread_with_history(thread_id)

    async def read_archived_thread(self, thread_id: str) -> dict[str, Any]:
        """Read archived history without loading, resuming, or subscribing it."""
        return await self._read_thread_with_history(thread_id)

    async def start_thread(
        self,
        cwd: str,
        ephemeral: bool = False,
        model_id: str | None = None,
        effort: str | None = None,
        selected_access_level: str | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"cwd": cwd, "ephemeral": ephemeral}
        if model_id:
            params["model"] = model_id
        if effort:
            params["config"] = {"model_reasoning_effort": effort}
        if selected_access_level:
            params.update(thread_start_access_params(selected_access_level))
        result = await self.request(
            "thread/start",
            params,
        )
        thread = self._remember_route(result)
        previous_model, previous_effort = self._routes[thread["id"]]
        self._routes[thread["id"]] = (
            model_id or previous_model,
            effort or previous_effort,
        )
        if selected_access_level:
            self._access_levels[thread["id"]] = selected_access_level
        self._loaded.add(thread["id"])
        self._subscribed.add(thread["id"])
        return self._with_route(result["thread"])

    async def resume_thread(self, thread_id: str) -> dict[str, Any]:
        result = await self.request("thread/resume", {"threadId": thread_id})
        self._loaded.add(thread_id)
        return self._remember_route(result)

    def _remember_route(self, result: dict[str, Any]) -> dict[str, Any]:
        thread = result["thread"]
        previous_model, previous_effort = self._routes.get(thread["id"], (None, None))
        self._routes[thread["id"]] = (
            result.get("model")
            if isinstance(result.get("model"), str)
            else previous_model,
            result.get("reasoningEffort")
            if isinstance(result.get("reasoningEffort"), str)
            else previous_effort,
        )
        selected_access_level = access_level(result)
        if selected_access_level is not None:
            self._access_levels[thread["id"]] = selected_access_level
        return self._with_route(thread)

    def _with_route(self, thread: dict[str, Any]) -> dict[str, Any]:
        thread_id = thread["id"]
        if not isinstance(self._access_levels.get(thread_id), str):
            historical = self._historical_access_level(thread_id)
            if historical is not None:
                self._access_levels[thread_id] = historical
        model, effort = self._routes.get(thread["id"], (None, None))
        return {
            **thread,
            "_foremanModel": model,
            "_foremanReasoningEffort": effort,
            "_foremanAccessLevel": self._access_levels.get(thread["id"]),
        }

    def _historical_access_level(self, thread_id: str) -> str | None:
        if not re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            thread_id,
        ):
            return None
        if thread_id in self._historical_access_levels:
            return self._historical_access_levels[thread_id]
        if self._session_history_files is None:
            self._session_history_files = {}
            try:
                candidates = self.session_history_root.rglob("*.jsonl")
                for path in candidates:
                    candidate_id = path.stem[-36:]
                    if re.fullmatch(
                        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        candidate_id,
                    ):
                        self._session_history_files[candidate_id] = path
            except OSError:
                pass
        level = session_history_access_level(
            self._session_history_files.get(thread_id)
        )
        self._historical_access_levels[thread_id] = level
        return level

    async def ensure_resumed(self, thread_id: str) -> None:
        if thread_id not in self._loaded:
            await self.resume_thread(thread_id)

    async def subscribe_thread(self, thread_id: str) -> None:
        self._subscribed.add(thread_id)
        await self.ensure_resumed(thread_id)

    async def account_rate_limits(self) -> dict[str, Any]:
        if not self.supports("account/rateLimits/read"):
            return {"available": False}
        try:
            result = await self.request("account/rateLimits/read")
        except CodexError:
            return {"available": False}
        snapshot = rate_limit_snapshot(result.get("rateLimits"))
        return {
            "available": snapshot is not None,
            **({"rateLimits": snapshot} if snapshot else {}),
        }

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

    async def list_access_levels(
        self, refresh: bool = False
    ) -> list[dict[str, str]]:
        now = time.monotonic()
        if (
            not refresh
            and self._access_cache
            and now - self._access_cache[0] < ACCESS_CACHE_SECONDS
        ):
            return self._access_cache[1]
        result = await self.request("permissionProfile/list", {"limit": 100})
        allowed_profiles = {
            item["id"]
            for item in result.get("data", [])
            if isinstance(item, dict)
            and isinstance(item.get("id"), str)
            and item.get("allowed") is True
        }
        levels = [
            {
                "id": level["id"],
                "displayName": level["displayName"],
                "description": level["description"],
            }
            for level in ACCESS_LEVELS
            if level["permissionProfile"] in allowed_profiles
        ]
        self._access_cache = (now, levels)
        return levels

    async def prompt(
        self,
        thread_id: str,
        text: str,
        images: list[dict[str, str]] | None = None,
        model_id: str | None = None,
        effort: str | None = None,
        selected_access_level: str | None = None,
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
        if selected_access_level:
            params.update(access_params(selected_access_level))
        result = await self.request(
            "turn/start",
            params,
        )
        previous_model, previous_effort = self._routes.get(thread_id, (None, None))
        self._routes[thread_id] = (
            model_id or previous_model,
            effort or previous_effort,
        )
        if selected_access_level:
            self._access_levels[thread_id] = selected_access_level
        return result

    async def update_thread_settings(
        self,
        thread_id: str,
        model_id: str | None = None,
        effort: str | None = None,
        selected_access_level: str | None = None,
    ) -> None:
        """Update Codex defaults for subsequent turns on an existing thread."""
        await self.ensure_resumed(thread_id)
        params: dict[str, Any] = {"threadId": thread_id}
        if model_id is not None:
            params["model"] = model_id
        if effort is not None:
            params["effort"] = effort
        if selected_access_level is not None:
            params.update(access_params(selected_access_level))
        await self.request("thread/settings/update", params)

        previous_model, previous_effort = self._routes.get(thread_id, (None, None))
        self._routes[thread_id] = (
            model_id if model_id is not None else previous_model,
            effort if effort is not None else previous_effort,
        )
        if selected_access_level is not None:
            self._access_levels[thread_id] = selected_access_level

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

    async def unarchive_thread(self, thread_id: str) -> dict[str, Any]:
        result = await self.request("thread/unarchive", {"threadId": thread_id})
        thread = result.get("thread")
        if not isinstance(thread, dict):
            # Keep the response useful across compatible versions whose
            # unarchive result doesn't include the full thread projection.
            return await self.read_archived_thread(thread_id)
        return self._with_route(thread)

    async def delete_thread(self, thread_id: str) -> None:
        await self.request("thread/delete", {"threadId": thread_id})
        self._loaded.discard(thread_id)
        self._subscribed.discard(thread_id)
        self._routes.pop(thread_id, None)
        self._access_levels.pop(thread_id, None)


def resolve_socket_path() -> Path:
    override = os.environ.get("FOREMAN_CODEX_SOCKET")
    if override:
        return Path(override).expanduser()
    return resolve_codex_home() / "app-server-control" / "app-server-control.sock"


def resolve_codex_home() -> Path:
    return Path(
        os.environ.get("CODEX_HOME", Path.home() / ".codex")
    ).expanduser()


def resolve_fallback_socket_path() -> Path:
    override = os.environ.get("FOREMAN_CODEX_FALLBACK_SOCKET")
    if override:
        return Path(override).expanduser()
    state_home = Path(
        os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")
    ).expanduser()
    return state_home / "foreman" / "codex-app-server.sock"


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


def access_params(selected: str) -> dict[str, str]:
    level = next((item for item in ACCESS_LEVELS if item["id"] == selected), None)
    if level is None:
        raise ValueError("selected access level is unavailable")
    return {
        "permissions": level["permissionProfile"],
        "approvalPolicy": level["approvalPolicy"],
        "approvalsReviewer": level["approvalsReviewer"],
    }


def thread_start_access_params(selected: str) -> dict[str, str]:
    params = access_params(selected)
    permission_profile = params.pop("permissions")
    params["sandbox"] = (
        "danger-full-access"
        if permission_profile == ":danger-full-access"
        else "workspace-write"
    )
    return params


def access_level(result: dict[str, Any]) -> str | None:
    profile = result.get("activePermissionProfile")
    profile_id = profile.get("id") if isinstance(profile, dict) else None
    approval = result.get("approvalPolicy")
    reviewer = result.get("approvalsReviewer")
    if profile_id == ":danger-full-access" and approval == "never":
        return "full"
    if profile_id == ":workspace" and reviewer == "auto_review":
        return "auto"
    if profile_id == ":workspace":
        return "ask"
    return None


def turn_context_access_level(payload: Any) -> str | None:
    if not isinstance(payload, dict):
        return None
    sandbox = payload.get("sandbox_policy")
    sandbox_type = sandbox.get("type") if isinstance(sandbox, dict) else None
    approval = payload.get("approval_policy")
    reviewer = payload.get("approvals_reviewer")
    if sandbox_type == "danger-full-access" and approval == "never":
        return "full"
    if sandbox_type == "workspace-write" and approval == "on-request":
        return "auto" if reviewer == "auto_review" else "ask"
    return None


def session_history_access_level(path: Path | None) -> str | None:
    if path is None:
        return None
    try:
        with path.open("rb") as handle:
            size = handle.seek(0, os.SEEK_END)
            start = max(0, size - SESSION_HISTORY_TAIL_BYTES)
            handle.seek(start)
            if start:
                handle.readline()
            lines = handle.readlines()
    except OSError:
        return None
    for raw in reversed(lines):
        try:
            event = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if isinstance(event, dict) and event.get("type") == "turn_context":
            return turn_context_access_level(event.get("payload"))
    return None


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


def token_count(value: Any) -> int | None:
    """Return a bounded public token count from an app-server notification."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if value < 0 or value != value or value in (float("inf"), float("-inf")):
        return None
    return min(int(value), 1_000_000_000_000)


def token_usage_breakdown(raw: Any) -> dict[str, int] | None:
    if not isinstance(raw, dict):
        return None
    fields = (
        "totalTokens",
        "inputTokens",
        "cachedInputTokens",
        "cacheWriteInputTokens",
        "outputTokens",
        "reasoningOutputTokens",
    )
    result = {
        field: count
        for field in fields
        if (count := token_count(raw.get(field))) is not None
    }
    return result if "totalTokens" in result else None


def thread_token_usage(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    total = token_usage_breakdown(raw.get("total"))
    last = token_usage_breakdown(raw.get("last"))
    context_window = token_count(raw.get("modelContextWindow"))
    if not last or not context_window:
        return None
    return {
        **({"total": total} if total else {}),
        "last": last,
        "modelContextWindow": context_window,
    }


def rate_limit_window(raw: Any) -> dict[str, int | float] | None:
    if not isinstance(raw, dict):
        return None
    used = raw.get("usedPercent")
    if isinstance(used, bool) or not isinstance(used, (int, float)):
        return None
    if used != used or used in (float("inf"), float("-inf")):
        return None
    result: dict[str, int | float] = {
        "usedPercent": round(max(0, min(100, used)), 1),
    }
    duration = token_count(raw.get("windowDurationMins"))
    resets_at = token_count(raw.get("resetsAt"))
    if duration is not None:
        result["windowDurationMins"] = min(duration, 525_600)
    if resets_at is not None:
        result["resetsAt"] = resets_at
    return result


def rate_limit_snapshot(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    primary = rate_limit_window(raw.get("primary"))
    secondary = rate_limit_window(raw.get("secondary"))
    if not primary and not secondary:
        return None
    result: dict[str, Any] = {"primary": primary, "secondary": secondary}
    for key in ("limitId", "limitName", "planType", "rateLimitReachedType"):
        value = raw.get(key)
        if isinstance(value, str) and value:
            result[key] = value[:100]
    return result


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
        "title": compact_session_title(
            thread.get("name") or thread.get("preview") or "Untitled session"
        ),
        "status": projected_status,
        "lastActivity": thread.get("recencyAt") or thread.get("updatedAt"),
        "attention": projected_status == "waiting",
        "model": thread.get("_foremanModel"),
        "reasoningEffort": thread.get("_foremanReasoningEffort"),
        "accessLevel": thread.get("_foremanAccessLevel"),
        "activeTurnId": active_turn_id,
    }
    if turns:
        latest = turns[-1]
        value.update(
            {
                "activeTurnStartedAt": latest.get("startedAt") if active_turn_id else None,
                "terminalAt": latest.get("completedAt"),
                "turnDurationMs": latest.get("durationMs"),
                "failureSummary": safe_failure_summary(latest.get("error")),
            }
        )
    wait_type, wait_description = waiting_details(thread.get("status"))
    if wait_type:
        value["waitType"] = wait_type
        value["waitDescription"] = wait_description
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
    return value


def compact_session_title(raw: Any, limit: int = 72) -> str:
    """Project a scan-friendly title without exposing the full initial prompt."""
    if not isinstance(raw, str):
        return "Untitled session"
    title = next((line.strip() for line in raw.splitlines() if line.strip()), "")
    title = " ".join(title.split()).strip()
    title = re.sub(r"^(?:#{1,6}|[-*+] |\d+[.)] )\s*", "", title)
    title = re.sub(r"\bForeman[’']s\b", "Foreman", title, flags=re.IGNORECASE)
    title = re.split(
        r"\s+(?=(?:Repository|GitHub|GitLab|Goal|Requirements|Acceptance criteria):)",
        title,
        maxsplit=1,
        flags=re.IGNORECASE,
    )[0]
    title = re.sub(r"[.?!]+$", "", title).strip()
    first_sentence = re.split(r"(?<=[.!?])\s+(?=[A-Z])", title, maxsplit=1)[0]
    if first_sentence and len(first_sentence) <= limit:
        title = first_sentence
    if len(title) > limit:
        title = f"{title[:limit - 1].rstrip()}…"
    return title or "Untitled session"


def search_matches(
    thread: dict[str, Any],
    query: str,
    maximum: int = SEARCH_SNIPPETS_PER_SESSION,
    snippet_limit: int = SEARCH_SNIPPET_LIMIT,
) -> list[dict[str, Any]]:
    """Search only the same normalized, user-visible projection sent to clients."""
    needle = query.casefold()
    if not needle:
        return []
    matches: list[dict[str, Any]] = []
    for turn in thread.get("turns", []):
        for raw_item in turn.get("items", []):
            if not isinstance(raw_item, dict):
                continue
            item = normalize_item(raw_item)
            if not item:
                continue
            value = item.get("text") or item.get("description")
            if not isinstance(value, str) or needle not in value.casefold():
                continue
            matches.append(
                {
                    "kind": item["kind"],
                    "snippet": matching_snippet(value, query, snippet_limit),
                    "turnId": turn.get("id"),
                    "itemId": item.get("id"),
                }
            )
            if len(matches) >= maximum:
                return matches
    return matches


def matching_snippet(text: str, query: str, limit: int = SEARCH_SNIPPET_LIMIT) -> str:
    compact = " ".join(text.split())
    if len(compact) <= limit:
        return compact
    index = compact.casefold().find(query.casefold())
    if index < 0:
        return f"{compact[:limit - 1].rstrip()}…"
    padding = max(0, (limit - len(query)) // 2)
    start = max(0, index - padding)
    end = min(len(compact), start + limit)
    if end - start < limit:
        start = max(0, end - limit)
    snippet = compact[start:end].strip()
    if start:
        snippet = f"…{snippet[1:]}"
    if end < len(compact):
        snippet = f"{snippet[:-1]}…"
    return snippet


def safe_failure_summary(raw: Any) -> str | None:
    message = raw.get("message") if isinstance(raw, dict) else raw
    if not isinstance(message, str):
        return None
    compact = " ".join(message.split())
    lowered = compact.lower()
    if not compact or any(
        marker in lowered
        for marker in (
            "traceback",
            "json-rpc",
            "authorization: bearer",
            "private key",
            "password=",
            "token=",
            "api_key=",
            "/home/",
            "/tmp/",
        )
    ):
        return "Turn failed"
    return compact[:240]


def waiting_details(raw: Any) -> tuple[str | None, str | None]:
    if not isinstance(raw, dict) or raw.get("type") != "active":
        return None, None
    flags = raw.get("activeFlags", [])
    if "waitingOnApproval" in flags:
        return "approval", "Approval is required."
    if "waitingOnUserInput" in flags:
        return "input", "Codex requested structured input in another compatible client."
    return None, None


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


def display_user_text(text: str) -> str:
    candidate = text.lstrip("\r\n").replace("\r\n", "\n")
    if not candidate.startswith(DESKTOP_ATTACHMENT_HEADER):
        return text
    marker = candidate.find(DESKTOP_REQUEST_MARKER)
    if marker == -1:
        return text
    return candidate[marker + len(DESKTOP_REQUEST_MARKER) :].strip()


def normalize_item(item: dict[str, Any]) -> dict[str, Any] | None:
    kind = item.get("type")
    base = {"id": item.get("id", ""), "rawType": kind}
    if kind == "userMessage":
        content = item.get("content", [])
        if not isinstance(content, list):
            content = []
        text = "".join(
            part.get("text", "")
            for part in content
            if isinstance(part, dict)
            and part.get("type") == "text"
            and isinstance(part.get("text", ""), str)
        )
        images: list[dict[str, str]] = []
        image_count = 0
        for part in content:
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
            "text": display_user_text(text),
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
    if kind == "contextCompaction":
        return {
            **base,
            "kind": "compaction",
            "description": "Context compacted",
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
                "_foremanAdvancesActivity": True,
                "turnId": params.get("turnId"),
                "itemId": params.get("itemId"),
                "text": params.get("delta", ""),
            }
        )
    elif method == "item/reasoning/summaryTextDelta":
        event.update(
            {
                "kind": "activity",
                "_foremanAdvancesActivity": True,
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
                "_foremanAdvancesActivity": True,
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
                "_foremanAdvancesActivity": True,
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
                "_foremanAdvancesActivity": True,
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
                "_foremanAdvancesActivity": True,
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
                "_foremanAdvancesActivity": normalized_item is not None,
                "phase": "started" if method == "item/started" else "completed",
                "turnId": params.get("turnId"),
                "item": normalized_item,
            }
        )
    elif method == "turn/started":
        turn = params.get("turn", {})
        event.update(
            {
                "kind": "status",
                "_foremanAdvancesActivity": True,
                "status": "working",
                "turnId": turn.get("id"),
                "startedAt": turn.get("startedAt"),
            }
        )
    elif method == "turn/completed":
        turn = params.get("turn", {})
        event.update(
            {
                "kind": "status",
                "_foremanAdvancesActivity": True,
                "status": status({}, turn.get("status")),
                "turnId": turn.get("id"),
                "completedAt": turn.get("completedAt"),
                "durationMs": turn.get("durationMs"),
                "failureSummary": safe_failure_summary(turn.get("error")),
            }
        )
    elif method == "thread/status/changed":
        latest_turn = params.get("latestTurn") or {}
        raw_status = params.get("status")
        wait_type, wait_description = waiting_details(raw_status)
        event.update(
            {
                "kind": "status",
                "status": status(raw_status, latest_turn.get("status")),
                "turnId": params.get("activeTurnId"),
                "startedAt": latest_turn.get("startedAt"),
                "completedAt": latest_turn.get("completedAt"),
                "durationMs": latest_turn.get("durationMs"),
                "failureSummary": safe_failure_summary(latest_turn.get("error")),
                # A raw status notification is also emitted while an existing
                # thread is resumed. Only provider turn timestamps make it an
                # activity signal; receipt by Foreman never does.
                "_foremanAdvancesActivity": any(
                    token_count(latest_turn.get(key)) is not None
                    for key in ("startedAt", "completedAt")
                ),
            }
        )
        if wait_type:
            event["waitType"] = wait_type
            event["waitDescription"] = wait_description
    elif method == "thread/settings/updated":
        settings = params.get("threadSettings", {})
        event["kind"] = "route"
        selected_access_level = access_level(settings)
        if selected_access_level:
            event["accessLevel"] = selected_access_level
        if isinstance(settings.get("model"), str):
            event["model"] = settings["model"]
        if isinstance(settings.get("effort"), str):
            event["reasoningEffort"] = settings["effort"]
    elif method == "thread/tokenUsage/updated":
        usage = thread_token_usage(params.get("tokenUsage"))
        event.update(
            {
                "kind": "usage",
                "turnId": params.get("turnId"),
                "tokenUsage": usage or {},
            }
        )
    elif method in (
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/tool/requestUserInput",
        "permissions/requestApproval",
        "item/permissions/requestApproval",
    ):
        wait_type = "input" if method == "item/tool/requestUserInput" else "approval"
        descriptions = {
            "item/commandExecution/requestApproval": "Approval is required for a command.",
            "item/fileChange/requestApproval": "Approval is required for file changes.",
            "item/tool/requestUserInput": "Codex requested structured input in another compatible client.",
            "permissions/requestApproval": "Permission approval is required.",
            "item/permissions/requestApproval": "Permission approval is required.",
        }
        event.update(
            {
                "kind": "status",
                "_foremanAdvancesActivity": True,
                "status": "waiting",
                "reason": "inputUnsupported" if wait_type == "input" else "approvalRequired",
                "turnId": params.get("turnId"),
                "waitType": wait_type,
                "waitDescription": descriptions.get(method),
            }
        )
    else:
        # Thread-scoped startup, goal, MCP, and future metadata notifications
        # are observable but aren't evidence of user-visible session work.
        event["kind"] = "metadata"
    return thread_id, event
