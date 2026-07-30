#!/usr/bin/env python3
"""Foreman: a small authenticated TCP bridge to local Codex sessions."""

from __future__ import annotations

import argparse
import asyncio
import base64
import binascii
from collections import deque
from datetime import datetime, timezone
from http import HTTPStatus
import json
import math
import mimetypes
import os
import signal
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable
from urllib.parse import unquote, urlsplit

from codex import (
    FOREMAN_VERSION,
    SEARCH_SNIPPET_LIMIT,
    SEARCH_SNIPPETS_PER_SESSION,
    Codex,
    CodexError,
    matching_snippet,
    normalize_event,
    search_matches,
    session,
)
from diagnostics import DiagnosticBuffer, request_category
from protocol import (
    MAX_FRAME_BYTES,
    VERSION,
    ProtocolError,
    decode_message,
    encode,
    error,
    read,
    response,
)
from state import State
from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.datastructures import Headers
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request, Response

MAX_IMAGES = 4
MAX_IMAGE_PAYLOAD_BYTES = 8 * 1024 * 1024
IMAGE_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}
MAX_SEARCH_RESULTS = 100
MAX_SEARCH_QUERY_BYTES = 500
MAX_TRANSCRIPT_SEARCH_CANDIDATES = 100
SEARCH_STATUSES = {
    "active",
    "working",
    "waiting",
    "completed",
    "idle",
    "failed",
    "interrupted",
}


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


@dataclass(eq=False)
class Client:
    writer: asyncio.StreamWriter | None
    peer: str
    websocket: ServerConnection | None = None
    authenticated: bool = False
    device_id: str | None = None
    subscriptions: set[str] = field(default_factory=set)
    write_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    requests: set[asyncio.Task[Any]] = field(default_factory=set)
    search_task: asyncio.Task[dict[str, Any]] | None = None

    async def send(self, message: dict[str, Any]) -> None:
        async with self.write_lock:
            if self.websocket is not None:
                encoded = encode(message)
                await self.websocket.send(encoded[:-1].decode("utf-8"))
            elif self.writer is not None:
                self.writer.write(encode(message))
                await self.writer.drain()
            else:
                raise ConnectionError("client transport is closed")

    def track(self, task: asyncio.Task[Any]) -> None:
        self.requests.add(task)
        task.add_done_callback(self.request_finished)

    def request_finished(self, task: asyncio.Task[Any]) -> None:
        self.requests.discard(task)
        if not task.cancelled():
            task.exception()


class PairingLimiter:
    def __init__(
        self,
        limit: int = 5,
        window_seconds: float = 60,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.limit = limit
        self.window_seconds = window_seconds
        self.clock = clock
        self.failures: dict[str, deque[float]] = {}

    def allowed(self, peer: str) -> bool:
        failures = self._recent(peer)
        return len(failures) < self.limit

    def failed(self, peer: str) -> None:
        self._recent(peer).append(self.clock())

    def succeeded(self, peer: str) -> None:
        self.failures.pop(peer, None)

    def _recent(self, peer: str) -> deque[float]:
        now = self.clock()
        failures = self.failures.setdefault(peer, deque())
        while failures and now - failures[0] >= self.window_seconds:
            failures.popleft()
        return failures


class Foreman:
    def __init__(
        self,
        host: str,
        port: int,
        repository_root: Path,
        state: State,
        codex_executable: str,
        codex_factory=Codex,
        web_host: str | None = None,
        web_port: int | None = None,
        web_root: Path | None = None,
        web_origins: tuple[str, ...] = (),
        remote_restart_enabled: bool = False,
        restart_runner: Callable[[], Awaitable[int]] | None = None,
        diagnostics: DiagnosticBuffer | None = None,
    ) -> None:
        self.host = host
        self.port = port
        self.repository_root = repository_root.resolve()
        self.state = state
        self.clients: set[Client] = set()
        self.thread_locks: dict[str, asyncio.Lock] = {}
        self.pairing_limiter = PairingLimiter()
        self.codex = codex_factory(codex_executable, self.codex_event)
        self.server: asyncio.Server | None = None
        self.web_host = web_host or host
        self.web_port = web_port
        self.web_root = (web_root or Path(__file__).resolve().parent / "web").resolve()
        self.web_origins = web_origins
        self.web_server: Server | None = None
        self.started_monotonic = time.monotonic()
        self.session_overlays: dict[str, dict[str, Any]] = {}
        self.remote_restart_enabled = remote_restart_enabled
        self.restart_runner = restart_runner or self.systemd_restart
        self.restart_scheduled = False
        self.restart_task: asyncio.Task[None] | None = None
        self.diagnostics = diagnostics or DiagnosticBuffer()
        self.known_pairing_count = 0

    async def start(self) -> None:
        await self.codex.start()
        if self.codex.runtime_status == "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE":
            self.diagnostics.record("runtime.shared_attached")
        elif getattr(self.codex, "process", None) is not None:
            self.diagnostics.record("runtime.fallback_started")
        print(f"Codex runtime: {self.codex.runtime_status}", flush=True)
        self.server = await asyncio.start_server(
            self.client_connected,
            self.host,
            self.port,
            limit=MAX_FRAME_BYTES + 1,
        )
        if self.web_port is not None:
            self.web_server = await serve(
                self.websocket_connected,
                self.web_host,
                self.web_port,
                process_request=self.http_request,
                max_size=MAX_FRAME_BYTES,
                compression=None,
                server_header="Foreman",
            )
        self.diagnostics.record("listeners.started")
        self.diagnostics.record("service.started")

    async def stop(self) -> None:
        self.diagnostics.record("service.stopping")
        if self.server:
            self.server.close()
            await self.server.wait_closed()
        if self.web_server:
            self.web_server.close()
            await self.web_server.wait_closed()
        writers = []
        for client in list(self.clients):
            if client.writer is not None:
                client.writer.close()
                writers.append(client.writer.wait_closed())
            elif client.websocket is not None:
                writers.append(client.websocket.close())
        if writers:
            await asyncio.gather(*writers, return_exceptions=True)
        await self.codex.stop()

    async def codex_event(self, message: dict[str, Any]) -> None:
        method = message.get("method")
        if method == "foreman/runtime/disconnected":
            self.diagnostics.record("runtime.disconnected")
            return
        if method == "foreman/runtime/reconnected":
            self.diagnostics.record("runtime.reconnected")
            return
        if method in (
            "foreman/approval/requested",
            "foreman/approval/updated",
            "foreman/approval/resolved",
        ):
            approval = (message.get("params") or {}).get("approval")
            if isinstance(approval, dict):
                await self.approval_event(method.rsplit("/", 1)[-1], approval)
            return
        if method in (
            "foreman/input/requested",
            "foreman/input/updated",
            "foreman/input/resolved",
        ):
            pending = (message.get("params") or {}).get("input")
            if isinstance(pending, dict):
                await self.input_event(method.rsplit("/", 1)[-1], pending)
            return
        thread_id, event = normalize_event(message)
        if not thread_id:
            return
        event["observedAt"] = int(time.time())
        if (
            event.get("type") == "turn/started"
            and event.get("kind") == "status"
            and event.get("status") == "working"
            and not event.get("startedAt")
        ):
            # The notification itself is the authoritative start signal even
            # when this app-server version omits the optional timestamp.
            event["startedAt"] = event["observedAt"]
        self.remember_session_event(thread_id, event)
        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {"sessionId": thread_id, "event": event},
        }
        targets = [
            client
            for client in self.clients
            if client.authenticated
            and (
                event.get("kind") == "status"
                or thread_id in client.subscriptions
            )
        ]
        await asyncio.gather(
            *(client.send(outgoing) for client in targets), return_exceptions=True
        )

    async def approval_event(self, action: str, approval: dict[str, Any]) -> None:
        thread_id = approval.get("sessionId")
        if not isinstance(thread_id, str) or not thread_id:
            return
        overlay = self.session_overlays.setdefault(thread_id, {})
        observed_at = int(time.time())
        if action in ("requested", "updated"):
            request_type = approval.get("type")
            labels = {
                "command": "Waiting for command approval",
                "fileChange": "Waiting for file-change approval",
                "permission": "Waiting for permission grant",
            }
            overlay.update(
                {
                    "status": "waiting",
                    "attention": True,
                    "activeTurnId": approval.get("turnId"),
                    "waitType": "approval",
                    "waitDescription": labels.get(request_type, "Approval required"),
                    "activityLabel": labels.get(request_type, "Approval required"),
                    "statusChangedAt": overlay.get("statusChangedAt") or observed_at,
                    "lastActivity": observed_at,
                }
            )
        else:
            has_approval = any(
                item.get("sessionId") == thread_id
                for item in self.codex.list_approvals()
            )
            has_input = any(
                item.get("sessionId") == thread_id
                for item in self.codex.list_inputs()
            )
            if not has_approval:
                overlay["attention"] = has_input
                if overlay.get("status") == "waiting" and not has_input:
                    overlay["status"] = "working"
                overlay["waitType"] = "input" if has_input else None
                if not has_input:
                    overlay["waitDescription"] = None
                overlay["activityLabel"] = "Approval resolved"
                overlay["lastActivity"] = observed_at
        outgoing = {
            "version": VERSION,
            "type": f"approval.{action}",
            "payload": {"approval": approval},
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    async def input_event(self, action: str, pending: dict[str, Any]) -> None:
        thread_id = pending.get("sessionId")
        if not isinstance(thread_id, str) or not thread_id:
            return
        overlay = self.session_overlays.setdefault(thread_id, {})
        observed_at = int(time.time())
        if action in ("requested", "updated"):
            label = (
                "Waiting for user input"
                if pending.get("supported") is True
                else "Waiting for unsupported user input"
            )
            overlay.update(
                {
                    "status": "waiting",
                    "attention": True,
                    "activeTurnId": pending.get("turnId"),
                    "waitType": "input",
                    "waitDescription": label,
                    "activityLabel": label,
                    "statusChangedAt": overlay.get("statusChangedAt") or observed_at,
                    "lastActivity": observed_at,
                }
            )
        elif not any(
            item.get("sessionId") == thread_id
            for item in self.codex.list_inputs()
        ):
            overlay["attention"] = any(
                item.get("sessionId") == thread_id
                for item in self.codex.list_approvals()
            )
            if overlay.get("status") == "waiting" and not overlay["attention"]:
                overlay["status"] = "working"
            if not overlay["attention"]:
                overlay["waitType"] = None
                overlay["waitDescription"] = None
            overlay["activityLabel"] = "Input request resolved"
            overlay["lastActivity"] = observed_at
        outgoing = {
            "version": VERSION,
            "type": f"input.{action}",
            "payload": {"input": pending},
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    async def broadcast_service_status(self) -> None:
        outgoing = {
            "version": VERSION,
            "type": "service.event",
            "payload": self.service_status(),
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated and client.websocket is not None
            ),
            return_exceptions=True,
        )

    def remember_session_event(self, thread_id: str, event: dict[str, Any]) -> None:
        overlay = self.session_overlays.setdefault(thread_id, {})
        observed_at = event.get("observedAt")
        if isinstance(observed_at, (int, float)):
            overlay["lastActivity"] = observed_at
        kind = event.get("kind")
        if kind == "status":
            projected_status = event.get("status")
            if isinstance(projected_status, str):
                if projected_status != overlay.get("status"):
                    overlay["statusChangedAt"] = observed_at
                overlay["status"] = projected_status
                overlay["attention"] = projected_status == "waiting"
            if projected_status in ("working", "waiting"):
                overlay["activeTurnId"] = event.get("turnId") or overlay.get("activeTurnId")
                overlay["activeTurnStartedAt"] = event.get("startedAt") or overlay.get("activeTurnStartedAt")
                overlay["terminalAt"] = None
                overlay["turnDurationMs"] = None
                overlay["failureSummary"] = None
            else:
                overlay["activeTurnId"] = None
                overlay["activeTurnStartedAt"] = None
            if projected_status in ("completed", "failed", "interrupted"):
                overlay["terminalAt"] = event.get("completedAt") or observed_at
                overlay["turnDurationMs"] = event.get("durationMs")
                overlay["failureSummary"] = event.get("failureSummary")
            if event.get("waitType") in ("approval", "input"):
                overlay["waitType"] = event["waitType"]
                overlay["waitDescription"] = event.get("waitDescription")
                overlay["activityLabel"] = (
                    "Waiting for approval"
                    if event["waitType"] == "approval"
                    else "Waiting for input"
                )
            elif projected_status != "waiting":
                overlay["waitType"] = None
                overlay["waitDescription"] = None
        elif kind == "activity":
            label = event.get("label")
            text = event.get("text")
            if isinstance(label, str) and label:
                overlay["activityLabel"] = label
            if isinstance(text, str) and text:
                overlay["activityText"] = (
                    f"{overlay.get('activityText', '')}{text}"
                    if event.get("append")
                    else text
                )[-2000:]
        elif kind == "assistant.delta":
            overlay["activityLabel"] = "Responding"
            overlay["activityText"] = ""
        elif kind == "item" and event.get("phase") == "started":
            item = event.get("item") or {}
            item_kind = item.get("kind")
            description = item.get("description")
            if item_kind == "command":
                overlay["activityLabel"] = "Running command"
                overlay["activityText"] = ""
            elif isinstance(description, str) and description.lower().startswith("web search"):
                overlay["activityLabel"] = "Searching the web"
            elif isinstance(description, str) and description.startswith("Editing "):
                overlay["activityLabel"] = description
            else:
                overlay["activityLabel"] = "Using tool"
            if item_kind != "command" and isinstance(description, str):
                overlay["activityText"] = description
        elif kind == "item" and event.get("phase") == "completed":
            overlay["activityLabel"] = "Thinking"
        elif kind == "route":
            for key in ("model", "reasoningEffort", "accessLevel"):
                if isinstance(event.get(key), str):
                    overlay[key] = event[key]

    def projected_session(
        self, thread: dict[str, Any], include_messages: bool = False
    ) -> dict[str, Any]:
        projected = session(thread, include_messages)
        return {**projected, **self.session_overlays.get(projected["id"], {})}

    async def broadcast_lifecycle(
        self, thread_id: str, action: str, projected: dict[str, Any] | None = None
    ) -> None:
        event: dict[str, Any] = {
            "kind": "lifecycle",
            "action": action,
            "observedAt": int(time.time()),
        }
        if projected is not None:
            event["session"] = projected
        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {"sessionId": thread_id, "event": event},
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    async def client_connected(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        peer_info = writer.get_extra_info("peername")
        peer = str(peer_info[0]) if isinstance(peer_info, tuple) else "unknown"
        client = Client(writer, peer)
        self.clients.add(client)
        request: dict[str, Any] | None = None
        try:
            while request := await read(reader):
                task = asyncio.create_task(self.handle_request(client, request))
                client.track(task)
        except ProtocolError as exc:
            self.diagnostics.record("protocol.incompatible")
            try:
                await client.send(error(request, "protocolError", str(exc)))
            except (ConnectionResetError, BrokenPipeError, ConnectionError, OSError):
                pass
        except (
            ConnectionResetError,
            BrokenPipeError,
            asyncio.IncompleteReadError,
        ):
            pass
        except asyncio.CancelledError:
            pass
        finally:
            for task in list(client.requests):
                task.cancel()
            if client.requests:
                await asyncio.gather(*client.requests, return_exceptions=True)
            self.clients.discard(client)
            if client.authenticated:
                self.diagnostics.record(
                    "client.disconnected",
                    ids={"clientId": client.device_id} if client.device_id else None,
                )
                await self.broadcast_service_status()
            try:
                if client.writer is not None:
                    client.writer.close()
                    await client.writer.wait_closed()
            except (ConnectionError, OSError):
                pass

    async def websocket_connected(self, websocket: ServerConnection) -> None:
        path = websocket.request.path if websocket.request else ""
        if urlsplit(path).path != "/ws":
            await websocket.close(1008, "WebSocket endpoint is /ws")
            return
        peer_info = websocket.remote_address
        peer = str(peer_info[0]) if isinstance(peer_info, tuple) else "unknown"
        client = Client(None, peer, websocket=websocket)
        self.clients.add(client)
        try:
            async for frame in websocket:
                if not isinstance(frame, str):
                    await client.send(error(None, "protocolError", "binary frames are not supported"))
                    await websocket.close(1003, "binary frames are not supported")
                    break
                try:
                    request = decode_message(frame.encode("utf-8"))
                except ProtocolError as exc:
                    self.diagnostics.record("protocol.incompatible")
                    await client.send(error(None, "protocolError", str(exc)))
                    continue
                task = asyncio.create_task(self.handle_request(client, request))
                client.track(task)
        except ConnectionClosed:
            pass
        except asyncio.CancelledError:
            pass
        finally:
            for task in list(client.requests):
                task.cancel()
            if client.requests:
                await asyncio.gather(*client.requests, return_exceptions=True)
            self.clients.discard(client)
            if client.authenticated:
                self.diagnostics.record(
                    "client.disconnected",
                    ids={"clientId": client.device_id} if client.device_id else None,
                )
                await self.broadcast_service_status()

    async def handle_request(
        self, client: Client, request: dict[str, Any]
    ) -> None:
        try:
            if request.get("type") in ("hello", "pair"):
                self.observe_pairings()
            result = await self.dispatch(client, request)
            try:
                await client.send(response(request, result))
            except Exception:
                if request["type"] == "service.restart":
                    self.restart_scheduled = False
                raise
            if request["type"] == "service.restart":
                self.schedule_restart()
            if request["type"] in ("pair", "authenticate") and client.authenticated:
                await self.broadcast_service_status()
            elif request["type"] == "client.revoke":
                await self.disconnect_device(result["clientId"])
        except PermissionError as exc:
            self.record_request_failure(request)
            await client.send(error(request, "unauthorized", str(exc)))
        except (ValueError, CodexError) as exc:
            self.record_request_failure(request)
            await client.send(error(request, "requestFailed", str(exc)))
        except (ConnectionResetError, BrokenPipeError, ConnectionError, OSError):
            raise
        except asyncio.CancelledError:
            try:
                await client.send(error(request, "cancelled", "request cancelled"))
            except (ConnectionResetError, BrokenPipeError, ConnectionError, OSError):
                pass
        except Exception as exc:
            print(f"request error: {exc}", flush=True)
            self.record_request_failure(request)
            await client.send(error(request, "internalError", "request failed"))

    def record_request_failure(self, request: dict[str, Any]) -> None:
        self.diagnostics.record(
            "request.failed", request=request_category(request.get("type"))
        )

    def observe_pairings(self) -> None:
        active = self.state.active_pairing_count()
        for _ in range(max(0, active - self.known_pairing_count)):
            self.diagnostics.record("pairing.created")
        self.known_pairing_count = active

    def schedule_restart(self) -> None:
        if self.restart_task is None or self.restart_task.done():
            self.restart_task = asyncio.create_task(self.run_scheduled_restart())

    async def run_scheduled_restart(self) -> None:
        # The result envelope has already been awaited and flushed by handle_request.
        await asyncio.sleep(0)
        try:
            return_code = await self.restart_runner()
        except Exception:
            self.restart_scheduled = False
            self.diagnostics.record("request.failed", request="service")
            return
        if return_code != 0:
            self.restart_scheduled = False
            self.diagnostics.record("request.failed", request="service")

    @staticmethod
    async def systemd_restart() -> int:
        process = await asyncio.create_subprocess_exec(
            "systemctl",
            "--user",
            "restart",
            "--no-block",
            "foreman.service",
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
        return await process.wait()

    def http_request(
        self, connection: ServerConnection, request: Request
    ) -> Response | None:
        path = urlsplit(request.path).path
        if path == "/ws":
            rejection = self.origin_rejection(connection, request)
            return rejection
        if request.headers.get("Upgrade", "").lower() == "websocket":
            return self.http_response(HTTPStatus.NOT_FOUND, b"Not found\n", "text/plain")
        if path == "/health":
            body = json.dumps(self.health(), separators=(",", ":")).encode("utf-8")
            return self.http_response(HTTPStatus.OK, body, "application/json", no_store=True)
        asset = self.static_asset(path)
        if asset is None:
            return self.http_response(HTTPStatus.NOT_FOUND, b"Not found\n", "text/plain")
        data, content_type, cache = asset
        headers = {"Cache-Control": "public, max-age=31536000, immutable"} if cache else {}
        return self.http_response(
            HTTPStatus.OK,
            data,
            content_type,
            headers,
            no_store=not cache,
        )

    def origin_rejection(
        self, connection: ServerConnection, request: Request
    ) -> Response | None:
        origin = request.headers.get("Origin")
        if origin is None:
            return None
        host = request.headers.get("Host", "")
        parsed = urlsplit(origin)
        same_origin = parsed.scheme in ("http", "https") and parsed.netloc == host
        if same_origin or origin in self.web_origins:
            return None
        return self.http_response(HTTPStatus.FORBIDDEN, b"Origin not allowed\n", "text/plain")

    def static_asset(self, path: str) -> tuple[bytes, str, bool] | None:
        relative = unquote(path).lstrip("/")
        if not relative or relative in ("dashboard", "sessions", "settings") or relative.startswith("sessions/"):
            relative = "index.html"
        if not relative or not (
            relative in ("index.html", "sw.js", "favicon.svg")
            or relative.startswith("assets/")
        ):
            return None
        candidate = (self.web_root / relative).resolve()
        try:
            candidate.relative_to(self.web_root)
        except ValueError:
            return None
        if not candidate.is_file():
            return None
        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type in (
            "application/javascript",
            "application/json",
            "image/svg+xml",
        ):
            content_type += "; charset=utf-8"
        return candidate.read_bytes(), content_type, relative.startswith("assets/")

    @staticmethod
    def http_response(
        status: HTTPStatus,
        body: bytes,
        content_type: str,
        extra_headers: dict[str, str] | None = None,
        no_store: bool = False,
    ) -> Response:
        headers = Headers()
        headers["Content-Type"] = content_type
        headers["Content-Length"] = str(len(body))
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data: blob:; "
            "style-src 'self'; script-src 'self'; connect-src 'self' ws: wss:; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
        )
        headers["Referrer-Policy"] = "no-referrer"
        if no_store:
            headers["Cache-Control"] = "no-store"
        for key, value in (extra_headers or {}).items():
            headers[key] = value
        return Response(status.value, status.phrase, headers, body)

    def health(self) -> dict[str, Any]:
        runtime = self.codex.runtime_status
        available = runtime == "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE"
        return {
            "status": "ok",
            "foremanConnected": True,
            "codexConnected": getattr(self.codex, "is_connected", True),
            "sharedDesktopRuntimeAttached": available,
            "fallbackRuntimeActive": not available,
            "codexRuntime": runtime,
        }

    def service_status(self) -> dict[str, Any]:
        codex_connected = getattr(self.codex, "is_connected", True)
        runtime = self.codex.runtime_status
        if not codex_connected:
            mode = "unavailable"
        elif runtime == "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE":
            mode = "shared"
        else:
            mode = "fallback"
        last_communication = getattr(self.codex, "last_communication", None)
        last_event = getattr(self.codex, "last_event", None)
        last_successful_request = getattr(
            self.codex, "last_successful_request", None
        )
        attached_at = getattr(self.codex, "attached_at", None)
        owned_process = getattr(self.codex, "process", None)
        owns_current_runtime = (
            mode == "fallback"
            and owned_process is not None
            and getattr(owned_process, "returncode", None) is None
        )
        tcp_port = self.port
        if self.server and self.server.sockets:
            tcp_port = self.server.sockets[0].getsockname()[1]
        web_port = self.web_port
        if self.web_server and self.web_server.sockets:
            web_port = self.web_server.sockets[0].getsockname()[1]
        return {
            "foremanVersion": FOREMAN_VERSION,
            "connected": True,
            "remoteRestartEnabled": self.remote_restart_enabled,
            "uptimeSeconds": max(0, int(time.monotonic() - self.started_monotonic)),
            "codex": {
                "connected": codex_connected,
                "mode": mode,
                "runtimeStatus": runtime,
                "version": getattr(self.codex, "version", None),
                "lastCommunication": (
                    datetime.fromtimestamp(last_communication, timezone.utc).isoformat()
                    if isinstance(last_communication, (int, float))
                    else None
                ),
                "lastEvent": self.iso_timestamp(last_event),
                "lastSuccessfulRequest": self.iso_timestamp(last_successful_request),
                "attachedAt": self.iso_timestamp(attached_at),
                "loadedThreadCount": len(getattr(self.codex, "_loaded", set())),
                "subscribedThreadCount": len(
                    getattr(self.codex, "_subscribed", set())
                ),
                "ownedByForeman": owns_current_runtime,
                "appServerPid": (
                    getattr(owned_process, "pid", None)
                    if owns_current_runtime
                    else None
                ),
                "socketPath": str(getattr(self.codex, "socket_path", "")) or None,
            },
            "listeners": {"tcpPort": tcp_port, "webPort": web_port},
            "repositoryRoot": str(self.repository_root),
            "activeBrowserConnections": sum(
                1
                for connected in self.clients
                if connected.authenticated and connected.websocket is not None
            ),
            "activeTcpConnections": sum(
                1
                for connected in self.clients
                if connected.authenticated and connected.writer is not None
            ),
        }

    def paired_clients(self, requester: Client) -> list[dict[str, Any]]:
        projected = []
        for device in self.state.list_devices():
            connections = [
                connected
                for connected in self.clients
                if connected.authenticated and connected.device_id == device["id"]
            ]
            transport_types = {
                "browser" if connected.websocket is not None else "android"
                for connected in connections
            }
            stored_type = device.get("type", "unknown")
            client_type = (
                next(iter(transport_types))
                if len(transport_types) == 1
                else "mixed" if transport_types
                else stored_type
            )
            projected.append(
                {
                    "id": device["id"],
                    "name": device["name"],
                    "type": client_type,
                    "pairedAt": self.iso_timestamp(device.get("createdAt")),
                    "connected": bool(connections),
                    "connectionCount": len(connections),
                    "current": requester.device_id == device["id"],
                }
            )
        return sorted(
            projected,
            key=lambda item: (not item["connected"], not item["current"], item["name"].lower()),
        )

    async def disconnect_device(self, device_id: str) -> None:
        targets = [client for client in self.clients if client.device_id == device_id]
        for target in targets:
            target.authenticated = False
            target.device_id = None
        await asyncio.gather(
            *(self.close_client(target) for target in targets), return_exceptions=True
        )
        await self.broadcast_service_status()

    @staticmethod
    async def close_client(client: Client) -> None:
        if client.websocket is not None:
            await client.websocket.close(4003, "Device token revoked")
        elif client.writer is not None:
            client.writer.close()
            await client.writer.wait_closed()

    @staticmethod
    def iso_timestamp(value: Any) -> str | None:
        return (
            datetime.fromtimestamp(value, timezone.utc).isoformat()
            if isinstance(value, (int, float))
            else None
        )

    async def dispatch(
        self, client: Client, request: dict[str, Any]
    ) -> dict[str, Any]:
        message_type = request["type"]
        payload = request.get("payload") or {}
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")

        if message_type == "hello":
            return {
                "server": "Foreman",
                "protocolVersion": VERSION,
                "codexRuntime": self.codex.runtime_status,
                "codexConnected": getattr(self.codex, "is_connected", True),
                "capabilities": {
                    "steer": True,
                    "interrupt": True,
                    "archive": self.codex.supports("thread/archive"),
                    "delete": self.codex.supports("thread/delete"),
                    "approvals": True,
                    "structuredInput": bool(
                        self.codex.supports("item/tool/requestUserInput")
                        or self.codex.supports("mcpServer/elicitation/request")
                    ),
                    "models": self.codex.supports("model/list"),
                    "access": self.codex.supports("permissionProfile/list"),
                    "threadSettings": self.codex.supports("thread/settings/update"),
                    "images": True,
                    "search": True,
                    "diagnostics": True,
                    "remoteRestart": self.remote_restart_enabled,
                },
            }
        if message_type == "pair":
            if not self.pairing_limiter.allowed(client.peer):
                raise PermissionError("too many pairing attempts; try again later")
            key = required_text(payload, "pairingKey", 100)
            name = required_text(payload, "deviceName", 80)
            device_type = "browser" if client.websocket is not None else "android"
            token = self.state.pair(key, name, device_type)
            if not token:
                self.pairing_limiter.failed(client.peer)
                raise PermissionError("pairing key is invalid or expired")
            self.pairing_limiter.succeeded(client.peer)
            client.authenticated = True
            client.device_id = self.state.authenticate_device(token)["id"]
            self.diagnostics.record(
                "pairing.consumed", ids={"clientId": client.device_id}
            )
            self.observe_pairings()
            self.diagnostics.record(
                "client.connected", ids={"clientId": client.device_id}
            )
            return {"deviceToken": token}
        if message_type == "authenticate":
            token = required_text(payload, "deviceToken", 200)
            device = self.state.authenticate_device(token)
            client.authenticated = device is not None
            client.device_id = device["id"] if device else None
            if device is None:
                raise PermissionError("device token is invalid")
            self.diagnostics.record(
                "client.connected", ids={"clientId": client.device_id}
            )
            return {"authenticated": True}
        if message_type == "ping":
            return {"time": int(time.time())}
        if not client.authenticated:
            raise PermissionError("authenticate first")

        if message_type == "repository.list":
            return {"repositories": await asyncio.to_thread(self.repositories)}
        if message_type == "service.status":
            return self.service_status()
        if message_type == "diagnostics.list":
            return {"events": self.diagnostics.entries(), "limit": 100}
        if message_type == "service.restart":
            if not self.remote_restart_enabled:
                raise ValueError("remote restart is disabled")
            if self.restart_scheduled:
                raise ValueError("restart is already scheduled")
            self.restart_scheduled = True
            return {"scheduled": True, "timeoutSeconds": 45}
        if message_type == "client.list":
            return {"clients": self.paired_clients(client)}
        if message_type == "client.revoke":
            client_id = required_text(payload, "clientId", 100)
            if not self.state.revoke_device(client_id):
                raise ValueError("client is no longer paired")
            self.diagnostics.record("token.revoked", ids={"clientId": client_id})
            return {"revoked": True, "clientId": client_id}
        if message_type == "model.list":
            return {
                "models": await self.codex.list_models(
                    refresh=payload.get("refresh") is True
                )
            }
        if message_type == "access.list":
            return {
                "levels": await self.codex.list_access_levels(
                    refresh=payload.get("refresh") is True
                )
            }
        if message_type == "approval.list":
            return {"approvals": self.codex.list_approvals()}
        if message_type == "approval.respond":
            approval_id = required_text(payload, "approvalId", 100)
            decision = payload.get("decision")
            if not isinstance(decision, dict):
                raise ValueError("decision must be an object")
            approval = await self.codex.respond_approval(approval_id, decision)
            return {"accepted": True, "approval": approval}
        if message_type == "input.list":
            return {"inputs": self.codex.list_inputs()}
        if message_type == "input.respond":
            input_id = required_text(payload, "inputId", 100)
            input_response = payload.get("response")
            if not isinstance(input_response, dict):
                raise ValueError("response must be an object")
            pending = await self.codex.respond_input(input_id, input_response)
            return {"accepted": True, "input": pending}
        if message_type == "session.list":
            return {
                "sessions": [
                    self.projected_session(item)
                    for item in await self.codex.list_threads()
                ]
            }
        if message_type == "session.search":
            previous = client.search_task
            if previous and not previous.done():
                previous.cancel()
            task = asyncio.create_task(self.search_sessions(payload))
            client.search_task = task
            try:
                return await task
            finally:
                if client.search_task is task:
                    client.search_task = None
        if message_type == "session.read":
            thread_id = required_text(payload, "sessionId", 100)
            return {
                "session": self.projected_session(
                    await self.codex.read_thread(thread_id), True
                )
            }

        if message_type == "session.start":
            repository_id = required_text(payload, "repositoryId", 500)
            repository = self.resolve_repository(repository_id)
            thread = await self.codex.start_thread(str(repository))
            client.subscriptions.add(thread["id"])
            projected = self.projected_session(thread, True)
            await self.broadcast_lifecycle(thread["id"], "created", projected)
            return {"session": projected}
        if message_type == "session.resume":
            thread_id = required_text(payload, "sessionId", 100)
            thread = await self.codex.resume_thread(thread_id)
            return {"session": session(thread, True)}
        if message_type == "session.subscribe":
            thread_id = required_text(payload, "sessionId", 100)
            client.subscriptions.add(thread_id)
            await self.codex.subscribe_thread(thread_id)
            return {"subscribed": True}
        if message_type == "session.unsubscribe":
            thread_id = required_text(payload, "sessionId", 100)
            client.subscriptions.discard(thread_id)
            return {"subscribed": False}
        if message_type == "session.archive":
            thread_id = required_text(payload, "sessionId", 100)
            async with self.thread_lock(thread_id):
                await self.require_inactive_session(thread_id)
                await self.codex.archive_thread(thread_id)
            self.discard_subscriptions(thread_id)
            self.session_overlays.pop(thread_id, None)
            await self.broadcast_lifecycle(thread_id, "removed")
            return {"archived": True}
        if message_type == "session.delete":
            thread_id = required_text(payload, "sessionId", 100)
            if payload.get("confirm") is not True:
                raise ValueError("permanent deletion requires confirm=true")
            async with self.thread_lock(thread_id):
                await self.require_inactive_session(thread_id)
                await self.codex.delete_thread(thread_id)
            self.discard_subscriptions(thread_id)
            self.session_overlays.pop(thread_id, None)
            await self.broadcast_lifecycle(thread_id, "removed")
            return {"deleted": True}
        if message_type == "session.settings":
            thread_id = required_text(payload, "sessionId", 100)
            if not any(
                payload.get(key) is not None
                for key in ("model", "reasoningEffort", "accessLevel")
            ):
                raise ValueError("at least one session setting is required")
            model_id, effort = await self.route(payload)
            selected_access_level = await self.access_level(payload)
            async with self.thread_lock(thread_id):
                await self.codex.update_thread_settings(
                    thread_id,
                    model_id,
                    effort,
                    selected_access_level,
                )
            return {"updated": True}
        if message_type == "turn.prompt":
            thread_id = required_text(payload, "sessionId", 100)
            images = image_payloads(payload)
            text = message_text(payload, images)
            model_id, effort = await self.route(payload)
            selected_access_level = await self.access_level(payload)
            async with self.thread_lock(thread_id):
                result = await self.codex.prompt(
                    thread_id,
                    text,
                    images,
                    model_id,
                    effort,
                    selected_access_level,
                )
            client.subscriptions.add(thread_id)
            return {"accepted": True, "turnId": result["turn"]["id"]}
        if message_type == "turn.steer":
            thread_id = required_text(payload, "sessionId", 100)
            turn_id = required_text(payload, "turnId", 100)
            if (
                payload.get("model") is not None
                or payload.get("reasoningEffort") is not None
                or payload.get("accessLevel") is not None
            ):
                raise ValueError(
                    "model, reasoning effort, and access level cannot change while steering"
                )
            images = image_payloads(payload)
            text = message_text(payload, images)
            async with self.thread_lock(thread_id):
                result = await self.codex.steer(thread_id, turn_id, text, images)
            return {"accepted": True, "turnId": result["turnId"]}
        if message_type == "turn.interrupt":
            thread_id = required_text(payload, "sessionId", 100)
            turn_id = required_text(payload, "turnId", 100)
            async with self.thread_lock(thread_id):
                await self.codex.interrupt(thread_id, turn_id)
                await self.codex.expire_turn(thread_id, turn_id, "interrupted")
            return {"accepted": True}
        raise ValueError(f"unknown message type: {message_type}")

    async def search_sessions(self, payload: dict[str, Any]) -> dict[str, Any]:
        query = payload.get("query", "")
        if not isinstance(query, str):
            raise ValueError("query must be text")
        query = query.strip()
        if len(query.encode()) > MAX_SEARCH_QUERY_BYTES:
            raise ValueError("query is too large")
        repository = payload.get("repository")
        if repository is not None:
            if not isinstance(repository, str) or not repository.strip():
                raise ValueError("repository must be non-empty text or null")
            repository = os.path.normpath(repository.strip())
        raw_statuses = payload.get("statuses", [])
        if not isinstance(raw_statuses, list) or len(raw_statuses) > 6:
            raise ValueError("statuses must be a list of at most 6 values")
        statuses = set()
        for value in raw_statuses:
            if not isinstance(value, str) or value not in SEARCH_STATUSES:
                raise ValueError("status is unavailable")
            statuses.add("working" if value == "active" else value)
        date_from = optional_number(payload, "dateFrom")
        date_to = optional_number(payload, "dateTo")
        if date_from is not None and date_to is not None and date_from > date_to:
            raise ValueError("dateFrom must not be after dateTo")
        raw_limit = payload.get("limit", MAX_SEARCH_RESULTS)
        if not isinstance(raw_limit, int) or isinstance(raw_limit, bool) or raw_limit < 1:
            raise ValueError("limit must be a positive integer")
        limit = min(raw_limit, MAX_SEARCH_RESULTS)

        threads = await self.codex.list_threads()
        repository_roots = await asyncio.to_thread(self.search_repository_roots)
        candidates: list[tuple[dict[str, Any], dict[str, Any], str]] = []
        for thread in threads:
            summary = self.projected_session(thread)
            identity = self.search_repository_identity(
                summary.get("repository", ""), repository_roots
            )
            if repository and identity != repository:
                continue
            projected_status = summary.get("status", "idle")
            if statuses and projected_status not in statuses and not (
                projected_status == "idle" and "completed" in statuses
            ):
                continue
            activity = summary.get("lastActivity")
            if date_from is not None and (
                not isinstance(activity, (int, float)) or activity < date_from
            ):
                continue
            if date_to is not None and (
                not isinstance(activity, (int, float)) or activity > date_to
            ):
                continue
            candidates.append((thread, summary, identity))

        if not query:
            return {
                "results": [
                    {"session": summary, "matches": []}
                    for _, summary, _ in candidates[:limit]
                ],
                "limit": limit,
                "truncated": len(candidates) > limit,
                "transcriptsScanned": 0,
            }

        needle = query.casefold()
        summary_results: list[tuple[int, float, dict[str, Any]]] = []
        transcript_pool: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for thread, summary, identity in candidates:
            title = str(summary.get("title", ""))
            path = str(summary.get("repository", ""))
            title_folded = title.casefold()
            activity = summary.get("lastActivity")
            recency = float(activity) if isinstance(activity, (int, float)) else 0
            if needle in title_folded:
                rank = 0 if title_folded == needle else 1
                summary_results.append(
                    (
                        rank,
                        -recency,
                        {
                            "session": summary,
                            "matches": [self.summary_match("title", title, query)],
                        },
                    )
                )
            elif needle in path.casefold() or needle in identity.casefold():
                summary_results.append(
                    (
                        2,
                        -recency,
                        {
                            "session": summary,
                            "matches": [self.summary_match("workspace", path, query)],
                        },
                    )
                )
            else:
                transcript_pool.append((thread, summary))

        summary_results.sort(key=lambda item: (item[0], item[1], item[2]["session"]["id"]))
        results = [item[2] for item in summary_results[:limit]]
        transcript_candidates = 0
        for index, (_, summary) in enumerate(
            transcript_pool[:MAX_TRANSCRIPT_SEARCH_CANDIDATES]
        ):
            if len(results) >= limit:
                break
            transcript_candidates += 1
            try:
                full_thread = await self.codex.search_thread(summary["id"])
            except CodexError:
                continue
            matches = search_matches(
                full_thread,
                query,
                SEARCH_SNIPPETS_PER_SESSION,
                SEARCH_SNIPPET_LIMIT,
            )
            if matches:
                results.append({"session": summary, "matches": matches})
            if index % 10 == 0:
                await asyncio.sleep(0)
        truncated = len(summary_results) > limit or (
            len(transcript_pool) > transcript_candidates
            and (
                transcript_candidates >= MAX_TRANSCRIPT_SEARCH_CANDIDATES
                or len(results) >= limit
            )
        )
        return {
            "results": results,
            "limit": limit,
            "truncated": truncated,
            "transcriptsScanned": transcript_candidates,
        }

    @staticmethod
    def summary_match(kind: str, text: str, query: str) -> dict[str, Any]:
        return {
            "kind": kind,
            "snippet": matching_snippet(text, query),
            "turnId": None,
            "itemId": None,
        }

    def search_repository_roots(self) -> list[str]:
        return sorted(
            (
                os.path.normpath(
                    item["path"]
                    if os.path.isabs(item["path"])
                    else str(self.repository_root / item["path"])
                )
                for item in self.repositories()
            ),
            key=len,
            reverse=True,
        )

    @staticmethod
    def search_repository_identity(path: Any, roots: list[str]) -> str:
        normalized = os.path.normpath(path) if isinstance(path, str) and path else ""
        return next(
            (
                root
                for root in roots
                if normalized == root or normalized.startswith(f"{root}{os.sep}")
            ),
            normalized,
        )

    async def route(
        self, payload: dict[str, Any]
    ) -> tuple[str | None, str | None]:
        model_id = optional_text(payload, "model", 200)
        effort = optional_text(payload, "reasoningEffort", 100)
        if model_id is None and effort is None:
            return None, None
        if model_id is None:
            raise ValueError("model is required when reasoningEffort is set")
        models = await self.codex.list_models()
        selected = next((item for item in models if item["id"] == model_id), None)
        if selected is None or selected.get("visible") is False:
            raise ValueError("selected model is unavailable")
        if effort is not None and effort not in selected["reasoningEfforts"]:
            raise ValueError("reasoning effort is not supported by the selected model")
        return model_id, effort

    async def access_level(self, payload: dict[str, Any]) -> str | None:
        selected = optional_text(payload, "accessLevel", 100)
        if selected is None:
            return None
        levels = await self.codex.list_access_levels()
        if not any(item["id"] == selected for item in levels):
            raise ValueError("selected access level is unavailable")
        return selected

    async def require_inactive_session(self, thread_id: str) -> None:
        projected = session(await self.codex.read_thread(thread_id))
        if projected["status"] in ("working", "waiting"):
            raise ValueError("session is active; interrupt it before archive or delete")

    def thread_lock(self, thread_id: str) -> asyncio.Lock:
        return self.thread_locks.setdefault(thread_id, asyncio.Lock())

    def discard_subscriptions(self, thread_id: str) -> None:
        for connected in self.clients:
            connected.subscriptions.discard(thread_id)

    def repositories(self) -> list[dict[str, Any]]:
        found: list[dict[str, Any]] = []
        if not self.repository_root.is_dir():
            return found
        for current, directories, files in os.walk(self.repository_root):
            if ".git" not in directories and ".git" not in files:
                continue
            path = Path(current)
            relative = str(path.relative_to(self.repository_root)) or "."
            directories[:] = []
            branch = git(path, "branch", "--show-current") or "(detached)"
            dirty = bool(git(path, "status", "--porcelain"))
            found.append(
                {
                    "id": relative,
                    "name": path.name,
                    "path": relative,
                    "branch": branch,
                    "dirty": dirty,
                }
            )
            if len(found) >= 500:
                break
        return sorted(found, key=lambda item: item["path"].lower())

    def resolve_repository(self, repository_id: str) -> Path:
        path = (self.repository_root / repository_id).resolve()
        try:
            path.relative_to(self.repository_root)
        except ValueError as error:
            raise ValueError("repository is outside configured root") from error
        if not path.is_dir() or not ((path / ".git").exists()):
            raise ValueError("repository was not found")
        return path


def required_text(payload: dict[str, Any], key: str, maximum: int) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} is required")
    if len(value.encode()) > maximum:
        raise ValueError(f"{key} is too large")
    return value.strip()


def optional_text(
    payload: dict[str, Any], key: str, maximum: int
) -> str | None:
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} must be non-empty text")
    if len(value.encode()) > maximum:
        raise ValueError(f"{key} is too large")
    return value.strip()


def optional_number(payload: dict[str, Any], key: str) -> float | None:
    value = payload.get(key)
    if value is None:
        return None
    if (
        not isinstance(value, (int, float))
        or isinstance(value, bool)
        or not math.isfinite(value)
        or value < 0
    ):
        raise ValueError(f"{key} must be a non-negative timestamp or null")
    return float(value)


def environment_flag(value: str) -> bool:
    return value.strip().lower() in ("1", "true", "yes", "on")


def message_text(
    payload: dict[str, Any], images: list[dict[str, str]]
) -> str:
    value = payload.get("text", "")
    if not isinstance(value, str):
        raise ValueError("text must be text")
    if len(value.encode()) > 100_000:
        raise ValueError("text is too large")
    text = value.strip()
    if not text and not images:
        raise ValueError("text or an image is required")
    return text


def image_payloads(payload: dict[str, Any]) -> list[dict[str, str]]:
    raw = payload.get("images", [])
    if not isinstance(raw, list):
        raise ValueError("images must be a list")
    if len(raw) > MAX_IMAGES:
        raise ValueError(f"at most {MAX_IMAGES} images are allowed")
    total = 0
    images: list[dict[str, str]] = []
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("each image must be an object")
        mime_type = item.get("mimeType")
        data = item.get("data")
        if mime_type not in IMAGE_MIME_TYPES:
            raise ValueError("unsupported image MIME type")
        if not isinstance(data, str) or not data:
            raise ValueError("image data is required")
        try:
            encoded = data.encode("ascii")
        except UnicodeEncodeError as error:
            raise ValueError("image data is not valid base64") from error
        total += len(encoded)
        if total > MAX_IMAGE_PAYLOAD_BYTES:
            raise ValueError("combined image payload is too large")
        try:
            base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("image data is not valid base64") from error
        images.append({"mimeType": mime_type, "data": data})
    return images


def git(path: Path, *args: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(path), *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=3,
            check=False,
        )
        return result.stdout.strip() if result.returncode == 0 else ""
    except (OSError, subprocess.TimeoutExpired):
        return ""


async def run_service(args: argparse.Namespace) -> None:
    app = Foreman(
        args.host,
        args.port,
        Path(args.repository_root),
        State(args.state_directory),
        args.codex,
        web_host=args.web_host,
        web_port=args.web_port,
        web_root=Path(args.web_root),
        web_origins=tuple(
            origin.strip() for origin in args.web_origins.split(",") if origin.strip()
        ),
        remote_restart_enabled=args.remote_restart,
    )
    await app.start()
    sockets = ", ".join(str(sock.getsockname()) for sock in app.server.sockets or [])
    print(f"Foreman listening on {sockets}", flush=True)
    if app.web_server:
        web_sockets = ", ".join(
            str(sock.getsockname()) for sock in app.web_server.sockets
        )
        print(f"Foreman web listening on {web_sockets}", flush=True)
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signum, stopped.set)
    await stopped.wait()
    await app.stop()


def arguments() -> argparse.Namespace:
    config = Path(
        os.environ.get(
            "FOREMAN_CONFIG", "~/.config/foreman/foreman.env"
        )
    ).expanduser()
    load_env(config)
    parser = argparse.ArgumentParser()
    parser.add_argument("--create-pairing", action="store_true")
    parser.add_argument("--print-web-url", action="store_true")
    parser.add_argument("--host", default=os.environ.get("FOREMAN_HOST", "0.0.0.0"))
    parser.add_argument(
        "--port", type=int, default=int(os.environ.get("FOREMAN_PORT", "8765"))
    )
    parser.add_argument(
        "--web-host", default=os.environ.get("FOREMAN_WEB_HOST", "0.0.0.0")
    )
    parser.add_argument(
        "--web-port", type=int, default=int(os.environ.get("FOREMAN_WEB_PORT", "8766"))
    )
    parser.add_argument(
        "--web-root",
        default=os.environ.get(
            "FOREMAN_WEB_ROOT", str(Path(__file__).resolve().parent / "web")
        ),
    )
    parser.add_argument(
        "--web-origins", default=os.environ.get("FOREMAN_WEB_ORIGINS", "")
    )
    parser.add_argument(
        "--remote-restart",
        action=argparse.BooleanOptionalAction,
        default=environment_flag(os.environ.get("FOREMAN_REMOTE_RESTART", "0")),
    )
    parser.add_argument(
        "--repository-root",
        default=os.environ.get(
            "FOREMAN_REPOSITORY_ROOT", str(Path.home() / "projects")
        ),
    )
    parser.add_argument(
        "--codex",
        default=os.environ.get("FOREMAN_CODEX_EXECUTABLE", "codex"),
    )
    parser.add_argument(
        "--state-directory",
        default=os.environ.get(
            "FOREMAN_STATE_DIRECTORY", str(Path.home() / ".local/state/foreman")
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = arguments()
    if args.print_web_url:
        host = args.web_host
        if host in ("0.0.0.0", "::"):
            host = "localhost"
        elif ":" in host and not host.startswith("["):
            host = f"[{host}]"
        print(f"http://{host}:{args.web_port}")
        return
    if args.create_pairing:
        key, expires_at = State(args.state_directory).create_pairing()
        print(f"Pairing key: {key}")
        print("Expires: 10 minutes")
        return
    asyncio.run(run_service(args))


if __name__ == "__main__":
    main()
