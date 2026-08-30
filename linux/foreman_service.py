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
import stat
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
    rate_limit_snapshot,
    search_matches,
    session,
    thread_token_usage,
    token_count,
)
from claude_code import ClaudeCode, ClaudeCodeError, SUPPORTED_MODELS
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
MAX_WORKSPACE_FILE_BYTES = 1024 * 1024
MAX_WORKSPACE_PATH_BYTES = 4096
SHUTDOWN_TIMEOUT_SECONDS = 10
SEARCH_STATUSES = {
    "active",
    "working",
    "waiting",
    "completed",
    "idle",
    "failed",
    "interrupted",
}
PROVIDERS = {"codex", "claude-code"}
CODEX_OPERATIONS = {
    "model.list",
    "access.list",
    "approval.list",
    "approval.respond",
    "input.list",
    "input.respond",
    "session.list",
    "session.search",
    "session.read",
    "session.start",
    "session.resume",
    "session.subscribe",
    "session.unsubscribe",
    "session.archive",
    "session.delete",
    "session.settings",
    "turn.prompt",
    "turn.steer",
    "turn.interrupt",
}
CLAUDE_PERMISSION_MODES = (
    "default",
    "dontAsk",
    "acceptEdits",
    "plan",
    "auto",
    "bypassPermissions",
)
CLAUDE_LIMITATIONS = [
    "external-running-no-live-attach",
    "external-running-no-interrupt",
    "external-running-no-approval-response",
    "no-web-approval-response",
    "no-transcript-search",
    "no-notifications",
    "no-images",
]
CLAUDE_DISCOVERY_CONCURRENCY = 4
CLAUDE_DISCOVERY_DEADLINE_SECONDS = 60


class CapabilityError(ValueError):
    pass


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
    focused_session: str | None = None
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
        claude_factory=None,
        claude_node: str = "node",
        claude_bridge: str | Path | None = None,
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
        self.session_activity_sources: dict[tuple[str, str], str] = {}
        for thread_id, settings in self.state.session_settings("codex").items():
            self.session_overlays[thread_id] = dict(settings)
        for thread_id, raw_usage in self.state.session_token_usage().items():
            if usage := thread_token_usage(raw_usage):
                self.session_overlays.setdefault(thread_id, {})["tokenUsage"] = usage
        for thread_id, timestamps in self.state.session_timestamps("codex").items():
            restored = dict(timestamps)
            source = restored.pop("activitySource", None)
            if self.timestamp(restored.get("lastActivity")) is not None:
                self.session_activity_sources[("codex", thread_id)] = (
                    source if source in {"provider", "live"} else "legacy"
                )
            self.session_overlays.setdefault(thread_id, {}).update(restored)
        self.provider_enabled = {
            provider: self.state.provider_enabled(provider) for provider in PROVIDERS
        }
        if not any(self.provider_enabled.values()):
            self.provider_enabled["codex"] = True
            self.state.set_provider_enabled("codex", True)
        self.account_usage: dict[str, Any] = {"available": False}
        self.claude_account_usage: dict[str, Any] = {
            "available": False,
            "experimental": True,
            "availabilityReason": "Updates after a Foreman-managed Claude run begins.",
        }
        cached_claude_usage = self.state.provider_account_usage("claude-code")
        if isinstance(cached_claude_usage, dict):
            cached_snapshot = rate_limit_snapshot(
                cached_claude_usage.get("rateLimits")
            )
            cached_observed_at = token_count(cached_claude_usage.get("observedAt"))
            if cached_snapshot:
                self.claude_account_usage = {
                    "available": True,
                    "experimental": True,
                    "rateLimits": cached_snapshot,
                    **(
                        {"observedAt": cached_observed_at}
                        if cached_observed_at is not None
                        else {}
                    ),
                }
        self.claude_session_overlays: dict[str, dict[str, Any]] = {
            session_id: dict(settings)
            for session_id, settings in self.state.session_settings(
                "claude-code"
            ).items()
        }
        for session_id, timestamps in self.state.session_timestamps(
            "claude-code"
        ).items():
            restored = dict(timestamps)
            source = restored.pop("activitySource", None)
            if self.timestamp(restored.get("lastActivity")) is not None:
                self.session_activity_sources[("claude-code", session_id)] = (
                    source if source in {"provider", "live"} else "legacy"
                )
            self.claude_session_overlays.setdefault(session_id, {}).update(restored)
        self.claude_session_cwds: dict[str, str] = {}
        self.claude_session_messages: dict[str, list[dict[str, Any]]] = {}
        self.remote_restart_enabled = remote_restart_enabled
        self.restart_runner = restart_runner or self.systemd_restart
        self.restart_scheduled = False
        self.restart_task: asyncio.Task[None] | None = None
        self.timestamp_persistence_pending: set[tuple[str, str]] = set()
        self.timestamp_persistence_replacements: set[tuple[str, str]] = set()
        self.timestamp_persistence_task: asyncio.Task[bool] | None = None
        self.diagnostics = diagnostics or DiagnosticBuffer()
        self.known_pairing_count = 0
        self.stopping = False
        self.claude = (
            claude_factory(
                self.repository_root,
                self.state.directory / "claude-code-sessions.json",
                on_event=self.claude_event,
                node_executable=claude_node,
                bridge_path=claude_bridge,
            )
            if claude_factory is not None
            else None
        )

    async def start(self) -> None:
        if self.provider_enabled["codex"]:
            await self.codex.start()
            if self.codex.runtime_status == "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE":
                self.diagnostics.record("runtime.shared_attached")
            elif getattr(self.codex, "process", None) is not None:
                self.diagnostics.record("runtime.fallback_started")
            print(f"Codex runtime: {self.codex.runtime_status}", flush=True)
        else:
            print("Codex provider: disabled", flush=True)
        if self.claude is not None and self.provider_enabled["claude-code"]:
            try:
                claude_status = await self.claude.start()
            except Exception:
                claude_status = {"available": False}
            self.diagnostics.record(
                "claude.available" if claude_status.get("available") else "claude.unavailable"
            )
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
        self.stopping = True
        shutdown: list[Awaitable[Any]] = [self.codex.stop()]
        if self.claude is not None:
            shutdown.append(self.claude.stop())
        if self.server:
            self.server.close()
            shutdown.append(self.server.wait_closed())
        if self.web_server:
            self.web_server.close(close_connections=True)
            shutdown.append(self.web_server.wait_closed())
        for client in list(self.clients):
            if client.writer is not None:
                client.writer.close()
                shutdown.append(client.writer.wait_closed())
            elif client.websocket is not None:
                shutdown.append(client.websocket.close())
        try:
            await asyncio.wait_for(
                asyncio.gather(*shutdown, return_exceptions=True),
                timeout=SHUTDOWN_TIMEOUT_SECONDS,
            )
        except TimeoutError:
            self.diagnostics.record("service.shutdown_timed_out")
        await self.flush_session_timestamp_persistence()

    async def claude_event(self, message: dict[str, Any]) -> None:
        kind = message.get("kind")
        if kind == "provider.status":
            if message.get("available") is not True:
                observed_at = int(time.time())
                for session_id, overlay in self.claude_session_overlays.items():
                    if overlay.get("state") != "working":
                        continue
                    overlay.update(
                        {
                            "state": "resumable",
                            "status": "resumable",
                            "activeTurnId": None,
                            "activeTurnStartedAt": None,
                            "attention": False,
                            "waitType": None,
                            "waitDescription": None,
                        }
                    )
                    outgoing = {
                        "version": VERSION,
                        "type": "session.event",
                        "payload": {
                            "provider": "claude-code",
                            "sessionId": session_id,
                            "event": {
                                "kind": "status",
                                "status": "resumable",
                                "activityAt": self.timestamp(
                                    overlay.get("lastActivity")
                                ),
                                "observedAt": observed_at,
                            },
                        },
                    }
                    await asyncio.gather(
                        *(
                            client.send(outgoing)
                            for client in self.clients
                            if client.authenticated
                        ),
                        return_exceptions=True,
                    )
            await self.broadcast_provider_status()
            return
        if kind in {
            "query.started",
            "query.completed",
            "query.failed",
            "query.interrupted",
            "permission.requested",
            "permission.denied",
        }:
            self.diagnostics.record(f"claude.{kind}")

        session_id = message.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            return
        observed_at = int(time.time())
        cwd = message.get("cwd")
        if isinstance(cwd, str) and cwd:
            self.claude_session_cwds[session_id] = cwd
        overlay = self.claude_session_overlays.setdefault(session_id, {})
        known_activity = self.timestamp(overlay.get("lastActivity"))
        known_terminal = self.timestamp(overlay.get("terminalAt"))
        run_id = message.get("runId")
        outgoing_event: dict[str, Any]
        account_usage_changed = False
        if kind == "query.started":
            route_settings = self.remember_session_settings(
                "claude-code",
                session_id,
                {
                    "model": message.get("model"),
                    "permissionMode": message.get("permissionMode", "default"),
                },
            )
            overlay.update(
                {
                    "source": "managed",
                    "state": "working",
                    "status": "working",
                    "activeTurnId": run_id,
                    "activeTurnStartedAt": observed_at,
                    "attention": False,
                    "model": message.get("model"),
                    "permissionMode": message.get("permissionMode", "default"),
                    "lastActivity": observed_at,
                    **route_settings,
                }
            )
            outgoing_event = {
                "kind": "status",
                "status": "working",
                "turnId": run_id,
                "startedAt": observed_at,
                "observedAt": observed_at,
            }
        elif kind == "assistant.delta":
            text = message.get("text") if isinstance(message.get("text"), str) else ""
            overlay.update({"activityLabel": "Responding", "lastActivity": observed_at})
            outgoing_event = {
                "kind": "assistant.delta",
                "turnId": run_id,
                "itemId": f"assistant-{run_id or 'active'}",
                "text": text,
                "observedAt": observed_at,
            }
            messages = self.claude_session_messages.setdefault(session_id, [])
            item_id = outgoing_event["itemId"]
            assistant = next((item for item in messages if item.get("id") == item_id), None)
            if assistant is None:
                messages.append(
                    {
                        "id": item_id,
                        "kind": "assistant",
                        "text": text,
                        "turnId": run_id,
                    }
                )
            else:
                assistant["text"] = f"{assistant.get('text', '')}{text}"[-16_384:]
        elif kind == "tool":
            name = message.get("name") if isinstance(message.get("name"), str) else "Tool"
            raw_status = message.get("status")
            item_status = {
                "started": "running",
                "completed": "completed",
                "failed": "failed",
                "denied": "denied",
            }.get(raw_status, "running")
            tool_id = message.get("toolUseId")
            item_id = f"claude-tool-{tool_id or run_id or 'active'}"
            description = self.claude_tool_summary(name, item_status)
            overlay.update(
                {
                    "activityLabel": description if item_status == "running" else "Thinking",
                    "activityText": "",
                    "lastActivity": observed_at,
                }
            )
            outgoing_event = {
                "kind": "item",
                "phase": "started" if item_status == "running" else "completed",
                "turnId": run_id,
                "item": {
                    "id": item_id,
                    "kind": "tool",
                    "description": description,
                    "status": item_status,
                },
                "observedAt": observed_at,
            }
            messages = self.claude_session_messages.setdefault(session_id, [])
            existing = next((item for item in messages if item.get("id") == item_id), None)
            safe_item = dict(outgoing_event["item"])
            if existing is None:
                messages.append(safe_item)
            else:
                existing.update(safe_item)
        elif kind == "compaction":
            item_id = f"claude-compaction-{run_id or observed_at}-{len(self.claude_session_messages.get(session_id, []))}"
            pre_tokens = token_count(message.get("preTokens"))
            post_tokens = token_count(message.get("postTokens"))
            duration_ms = token_count(message.get("durationMs"))
            safe_item = {
                "id": item_id,
                "kind": "compaction",
                "description": "Context compacted",
                "compactionTrigger": (
                    "manual" if message.get("trigger") == "manual" else "auto"
                ),
                **({"preTokens": pre_tokens} if pre_tokens is not None else {}),
                **({"postTokens": post_tokens} if post_tokens is not None else {}),
                **({"durationMs": duration_ms} if duration_ms is not None else {}),
            }
            overlay.update(
                {
                    "activityLabel": "Context compacted",
                    "activityText": "",
                    "lastActivity": observed_at,
                }
            )
            outgoing_event = {
                "kind": "item",
                "phase": "completed",
                "turnId": run_id,
                "item": safe_item,
                "observedAt": observed_at,
            }
            self.claude_session_messages.setdefault(session_id, []).append(safe_item)
        elif kind == "usage":
            usage = thread_token_usage(message.get("tokenUsage"))
            if usage:
                overlay["tokenUsage"] = usage
            raw_account_usage = message.get("accountUsage")
            if isinstance(raw_account_usage, dict):
                snapshot = rate_limit_snapshot(raw_account_usage.get("rateLimits"))
                self.claude_account_usage = {
                    "available": snapshot is not None,
                    "experimental": True,
                    "observedAt": observed_at,
                    **({"rateLimits": snapshot} if snapshot else {}),
                    **(
                        {}
                        if snapshot
                        else {
                            "availabilityReason": "Claude plan limits are unavailable for this account."
                        }
                    ),
                }
                self.state.remember_provider_account_usage(
                    "claude-code", self.claude_account_usage
                )
                account_usage_changed = True
            outgoing_event = {
                "kind": "usage",
                "turnId": run_id,
                "tokenUsage": usage or {},
                "observedAt": observed_at,
            }
        elif kind == "permission.requested":
            item_id = f"claude-permission-{message.get('toolUseId') or run_id or 'active'}"
            name = message.get("name") if isinstance(message.get("name"), str) else "Tool"
            permission_item = {
                "id": item_id,
                "kind": "tool",
                "description": f"{self.claude_tool_summary(name, 'running')} · permission required",
                "status": "running",
            }
            overlay.update(
                {
                    "state": "working",
                    "status": "waiting",
                    "attention": True,
                    "waitType": "approval",
                    "waitDescription": "Permission required in Claude session. Foreman web approval support is not yet available.",
                    "activityLabel": "Permission required",
                    "lastActivity": observed_at,
                }
            )
            outgoing_event = {
                "kind": "status",
                "status": "waiting",
                "turnId": run_id,
                "waitType": "approval",
                "waitDescription": overlay["waitDescription"],
                "observedAt": observed_at,
            }
            messages = self.claude_session_messages.setdefault(session_id, [])
            existing = next((item for item in messages if item.get("id") == item_id), None)
            if existing is None:
                messages.append(permission_item)
            else:
                existing.update(permission_item)
        elif kind == "permission.denied":
            name = message.get("name") if isinstance(message.get("name"), str) else "Tool"
            item_id = f"claude-permission-{message.get('toolUseId') or run_id or 'active'}"
            overlay.update(
                {
                    "status": "working",
                    "attention": False,
                    "waitType": None,
                    "waitDescription": None,
                    "lastActivity": observed_at,
                }
            )
            outgoing_event = {
                "kind": "item",
                "phase": "completed",
                "turnId": run_id,
                "item": {
                    "id": item_id,
                    "kind": "tool",
                    "description": self.claude_tool_summary(name, "denied"),
                    "status": "denied",
                },
                "observedAt": observed_at,
            }
            messages = self.claude_session_messages.setdefault(session_id, [])
            existing = next((item for item in messages if item.get("id") == item_id), None)
            safe_item = dict(outgoing_event["item"])
            if existing is None:
                messages.append(safe_item)
            else:
                existing.update(safe_item)
        elif kind in {"query.completed", "query.failed", "query.interrupted"}:
            state = kind.removeprefix("query.")
            overlay.update(
                {
                    "state": state,
                    "status": state,
                    "activeTurnId": None,
                    "activeTurnStartedAt": None,
                    "attention": False,
                    "waitType": None,
                    "waitDescription": None,
                    "terminalAt": observed_at,
                    "lastActivity": observed_at,
                }
            )
            outgoing_event = {
                "kind": "status",
                "status": state,
                "turnId": run_id,
                "completedAt": observed_at,
                "failureSummary": message.get("message") if state == "failed" else None,
                "observedAt": observed_at,
            }
        else:
            return

        incoming_activity = self.timestamp(overlay.get("lastActivity"))
        activity_candidates = [
            value
            for value in (known_activity, incoming_activity)
            if value is not None
        ]
        if activity_candidates:
            overlay["lastActivity"] = max(activity_candidates)
        if overlay.get("status") in ("working", "waiting"):
            overlay.pop("terminalAt", None)
        else:
            terminal_candidates = [
                value
                for value in (
                    known_terminal,
                    self.timestamp(overlay.get("terminalAt")),
                )
                if value is not None
            ]
            if terminal_candidates:
                overlay["terminalAt"] = max(terminal_candidates)
        if outgoing_event.get("kind") not in ("route", "usage", "lifecycle"):
            outgoing_event["activityAt"] = self.timestamp(
                overlay.get("lastActivity")
            )
        if outgoing_event.get("kind") == "status" and overlay.get(
            "status"
        ) in ("completed", "failed", "interrupted"):
            outgoing_event["completedAt"] = self.timestamp(
                overlay.get("terminalAt")
            )
        if self.timestamp(overlay.get("lastActivity")) != known_activity:
            self.session_activity_sources[("claude-code", session_id)] = "live"
        self.persist_overlay_timestamps("claude-code", session_id, overlay)

        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {
                "provider": "claude-code",
                "sessionId": session_id,
                "event": outgoing_event,
            },
        }
        subscription = self.provider_subscription("claude-code", session_id)
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
                and (
                    outgoing_event.get("kind") == "status"
                    or subscription in client.subscriptions
                )
            ),
            return_exceptions=True,
        )
        if account_usage_changed:
            await self.broadcast_account_usage()

    async def provider_status(self) -> list[dict[str, Any]]:
        """Bounded provider catalog backed by the current adapter status."""
        codex_enabled = self.provider_enabled["codex"]
        claude_enabled = self.provider_enabled["claude-code"]
        claude = (
            await self.claude.status()
            if self.claude is not None and claude_enabled
            else {"provider": "claude-code", "available": False}
        )
        codex_capabilities = [
            "session.list",
            "session.read",
            "session.start",
            "session.resume",
            "session.subscribe",
            "session.settings",
            "turn.prompt",
            "turn.interrupt",
            "model.select",
            "permission.select",
        ]
        claude_capabilities = [
            "session.list",
            "session.read",
            "session.start",
            "session.resume",
            "session.subscribe",
            "session.settings",
            "session.delete",
            "turn.prompt",
            "turn.interrupt",
            "model.select",
            "permission.select",
        ] if claude.get("available") else []
        return [
            {
                "id": "codex",
                "provider": "codex",
                "displayName": "Codex",
                "enabled": codex_enabled,
                "available": codex_enabled and self.codex.is_connected,
                "version": self.codex.version,
                "runtime": self.codex.runtime_status,
                "capabilities": codex_capabilities if codex_enabled and self.codex.is_connected else [],
                "limitations": [],
            },
            {
                "id": "claude-code",
                "provider": "claude-code",
                "displayName": "Claude Code",
                "enabled": claude_enabled,
                "available": claude_enabled and claude.get("available") is True,
                "cliVersion": claude.get("cliVersion"),
                "sdkVersion": claude.get("sdkVersion"),
                "nodeVersion": claude.get("nodeVersion"),
                "capabilities": claude_capabilities if claude_enabled else [],
                "limitations": CLAUDE_LIMITATIONS,
                "unavailableReason": (
                    None if not claude_enabled or claude.get("available")
                    else self.claude_unavailable_reason(claude)
                ),
            },
        ]

    async def broadcast_provider_status(self) -> None:
        if self.stopping:
            return
        outgoing = {
            "version": VERSION,
            "type": "provider.event",
            "payload": {"providers": await self.provider_status()},
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    @staticmethod
    def provider_subscription(provider: str, session_id: str) -> str:
        return f"{provider}:{session_id}"

    @staticmethod
    def claude_unavailable_reason(status: dict[str, Any]) -> str:
        limitation = str(status.get("limitation", "")).lower()
        if "native claude" in limitation or "executable" in limitation:
            return "cli-missing"
        if "node.js" in limitation:
            return "node-missing"
        if "sdk" in limitation:
            return "sdk-missing"
        if "auth" in limitation:
            return "authentication-unavailable"
        return "adapter-unavailable"

    @staticmethod
    def claude_tool_summary(name: str, status: str) -> str:
        normalized = name.casefold()
        action = (
            "Reading a file"
            if normalized == "read"
            else "Running a command (output hidden)"
            if normalized == "bash"
            else "Editing a file"
            if normalized in {"edit", "write"}
            else "Searching files"
            if normalized in {"grep", "glob", "search"}
            else f"Using {name[:80]}"
        )
        if status == "denied":
            return f"{action} was denied"
        if status == "failed":
            return f"{action} failed"
        if status == "completed":
            return f"{action} completed"
        return action

    @staticmethod
    def required_provider(payload: dict[str, Any]) -> str:
        provider = required_text(payload, "provider", 40)
        if provider not in PROVIDERS:
            raise CapabilityError(f"provider {provider} is unsupported")
        return provider

    def require_provider_enabled(self, provider: str) -> None:
        if not self.provider_enabled.get(provider, False):
            display_name = "Claude Code" if provider == "claude-code" else "Codex"
            raise CapabilityError(f"{display_name} is disabled in Settings")

    async def provider_has_active_work(self, provider: str) -> bool:
        if provider == "codex":
            if self.codex.list_approvals() or self.codex.list_inputs():
                return True
            if any(
                overlay.get("status") in ("working", "waiting")
                for overlay in self.session_overlays.values()
            ):
                return True
            if not getattr(self.codex, "is_connected", False):
                return False
            return any(
                self.projected_session(thread)["status"] in ("working", "waiting")
                for thread in await self.codex.list_threads()
            )
        return any(
            overlay.get("state") == "working"
            or overlay.get("status") in ("working", "waiting")
            for overlay in self.claude_session_overlays.values()
        )

    async def configure_provider(self, provider: str, enabled: bool) -> None:
        if self.provider_enabled[provider] == enabled:
            return
        if not enabled:
            if sum(self.provider_enabled.values()) <= 1:
                raise ValueError("at least one provider must remain enabled")
            if await self.provider_has_active_work(provider):
                raise ValueError(
                    "provider has active or waiting sessions; resolve them before disabling it"
                )
            adapter = self.codex if provider == "codex" else self.claude
            if adapter is not None:
                await adapter.stop()
            self.provider_enabled[provider] = False
            self.state.set_provider_enabled(provider, False)
            return

        self.provider_enabled[provider] = True
        self.state.set_provider_enabled(provider, True)
        adapter = self.codex if provider == "codex" else self.claude
        if adapter is not None:
            try:
                await adapter.start()
            except Exception:
                # Enabled and unavailable are intentionally separate states.
                # The provider catalog exposes the bounded availability result.
                pass

    async def require_claude(self) -> None:
        self.require_provider_enabled("claude-code")
        if self.claude is None:
            raise CapabilityError("Claude Code is unavailable on this host")
        status = await self.claude.status()
        if status.get("available") is not True:
            reason = self.claude_unavailable_reason(status).replace("-", " ")
            raise CapabilityError(f"Claude Code is unavailable on this host: {reason}")

    def claude_repository_id(self, cwd: str) -> str:
        try:
            relative = str(Path(cwd).resolve().relative_to(self.repository_root))
        except (OSError, ValueError):
            return "."
        return relative or "."

    async def discover_claude_sessions(self) -> list[dict[str, Any]]:
        if self.claude is None:
            return []
        status = await self.claude.status()
        if status.get("available") is not True:
            return []
        repository_ids = [".", *(item["id"] for item in await asyncio.to_thread(self.repositories))]
        discovered: dict[tuple[str, str], dict[str, Any]] = {}
        semaphore = asyncio.Semaphore(CLAUDE_DISCOVERY_CONCURRENCY)

        async def discover_repository(repository_id: str) -> list[dict[str, Any]]:
            try:
                cwd = self.resolve_repository(repository_id)
                async with semaphore:
                    return await self.claude.discover(cwd)
            except (ValueError, ClaudeCodeError):
                return []

        tasks = [
            asyncio.create_task(discover_repository(repository_id))
            for repository_id in dict.fromkeys(repository_ids)
        ]
        completed, pending = await asyncio.wait(
            tasks,
            timeout=CLAUDE_DISCOVERY_DEADLINE_SECONDS,
        )
        for task in pending:
            task.cancel()
        batches = [
            task.result()
            for task in completed
            if not task.cancelled() and task.exception() is None
        ]
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        for items in batches:
            for item in items[:500]:
                session_id = item.get("sessionId")
                item_cwd = item.get("cwd")
                if not isinstance(session_id, str) or not isinstance(item_cwd, str):
                    continue
                key = (session_id, item_cwd)
                previous = discovered.get(key)
                if previous is None or (item.get("lastSeenAt") or 0) > (previous.get("lastSeenAt") or 0):
                    discovered[key] = item
                self.claude_session_cwds[session_id] = item_cwd
        projected = [self.project_claude_session(item) for item in discovered.values()]
        return self.sort_session_projections(projected)

    def project_claude_session(
        self, item: dict[str, Any], include_messages: bool = False
    ) -> dict[str, Any]:
        session_id = str(item.get("sessionId", ""))
        cwd = str(item.get("cwd", ""))
        provider_activity = item.get("lastSeenAt")
        provider_created = item.get("createdAt")
        overlay = self.claude_session_overlays.get(session_id, {})
        discovered = {
            key: item[key]
            for key in ("model", "permissionMode")
            if not isinstance(overlay.get(key), str)
            and isinstance(item.get(key), str)
            and item[key]
        }
        if session_id and discovered:
            self.remember_session_settings(
                "claude-code", session_id, discovered
            )
            overlay = self.claude_session_overlays.get(session_id, {})
        source = "managed" if item.get("classification") == "managed" or overlay.get("source") == "managed" else "external"
        state = "working" if item.get("active") else "resumable"
        if source == "managed" and overlay.get("state") in {
            "working",
            "completed",
            "failed",
            "interrupted",
        }:
            state = overlay["state"]
        capabilities = ["session.read", "session.resume", "session.delete"]
        if source == "managed":
            capabilities.append("turn.prompt")
        if state == "working":
            capabilities.append("turn.interrupt")
        projected = {
            "provider": "claude-code",
            "id": session_id,
            "sessionId": session_id,
            "cwd": cwd,
            "repository": cwd,
            "repositoryId": self.claude_repository_id(cwd),
            "title": item.get("title") or overlay.get("title") or "Claude Code session",
            "source": source,
            "state": state,
            "status": overlay.get("status", state),
            "lastActivity": overlay.get("lastActivity", item.get("lastSeenAt")),
            "model": overlay.get("model", item.get("model")),
            "permissionMode": overlay.get("permissionMode", item.get("permissionMode") or "default"),
            "capabilities": capabilities,
            "liveAttached": state == "working" and source == "managed",
            "externalLimitation": (
                "Not live-attached. Resume in Foreman to stream, interrupt, or prompt."
                if source == "external"
                else None
            ),
            **overlay,
        }
        if include_messages:
            messages = list(item.get("messages", []))
            existing_ids = {message.get("id") for message in messages}
            messages.extend(
                message
                for message in self.claude_session_messages.get(session_id, [])
                if message.get("id") not in existing_ids
            )
            projected["messages"] = messages[-500:]
        return self.restore_session_timestamps(
            "claude-code",
            session_id,
            projected,
            provider_activity=provider_activity,
            provider_terminal=item.get("terminalAt"),
            provider_created=provider_created,
        )

    def remember_claude_query_started(
        self,
        session_id: str,
        cwd: Path,
        text: str,
        model: str,
        permission_mode: str,
        run_id: str | None,
    ) -> dict[str, Any]:
        self.claude_session_cwds[session_id] = str(cwd)
        route_settings = self.remember_session_settings(
            "claude-code",
            session_id,
            {"model": model, "permissionMode": permission_mode},
        )
        overlay = self.claude_session_overlays.setdefault(session_id, {})
        known_activity = self.timestamp(overlay.get("lastActivity"))
        started_at = int(time.time())
        overlay.update(
            {
                "source": "managed",
                "state": "working",
                "status": "working",
                "title": overlay.get("title") or text.strip().splitlines()[0][:300],
                "model": model,
                "permissionMode": permission_mode,
                "activeTurnId": run_id,
                "lastActivity": max(
                    value
                    for value in (known_activity, started_at)
                    if value is not None
                ),
                **route_settings,
            }
        )
        overlay.pop("terminalAt", None)
        self.session_activity_sources[("claude-code", session_id)] = "live"
        self.persist_overlay_timestamps("claude-code", session_id, overlay)
        self.claude_session_messages.setdefault(session_id, []).append(
            {
                "id": f"user-{run_id or int(time.time() * 1000)}",
                "kind": "user",
                "text": text,
                "turnId": run_id,
            }
        )
        return overlay

    async def read_claude_session(
        self, session_id: str, repository_id: str
    ) -> dict[str, Any]:
        await self.require_claude()
        assert self.claude is not None
        cwd = self.resolve_repository(repository_id)
        item = await self.claude.read_session(session_id, cwd)
        self.claude_session_cwds[session_id] = str(cwd)
        if self.claude_session_overlays.get(session_id, {}).get("state") in {
            "completed",
            "failed",
            "interrupted",
        }:
            self.claude_session_messages.pop(session_id, None)
        classification = (
            "managed"
            if self.claude_session_overlays.get(session_id, {}).get("source") == "managed"
            else "resumable"
        )
        item["classification"] = classification
        return self.project_claude_session(item, True)

    async def broadcast_claude_lifecycle(
        self,
        session_id: str,
        session_projection: dict[str, Any] | None = None,
        action: str = "created",
    ) -> None:
        event: dict[str, Any] = {
            "kind": "lifecycle",
            "action": action,
            "observedAt": int(time.time()),
        }
        if session_projection is not None:
            event["session"] = session_projection
        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {
                "provider": "claude-code",
                "sessionId": session_id,
                "event": event,
            },
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    async def codex_event(self, message: dict[str, Any]) -> None:
        method = message.get("method")
        if method == "foreman/runtime/disconnected":
            self.diagnostics.record("runtime.disconnected")
            return
        if method == "foreman/runtime/reconnected":
            self.diagnostics.record("runtime.reconnected")
            return
        if method == "account/rateLimits/updated":
            snapshot = rate_limit_snapshot(
                (message.get("params") or {}).get("rateLimits")
            )
            if snapshot:
                previous = self.account_usage.get("rateLimits")
                merged = dict(previous) if isinstance(previous, dict) else {}
                merged.update(
                    (key, value)
                    for key, value in snapshot.items()
                    if value is not None
                )
                self.account_usage = {"available": True, "rateLimits": merged}
                await self.broadcast_account_usage()
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
        params = message.get("params") or {}
        reconciled = params.get("_foremanReconciled") is True
        observed_at = int(time.time())
        advances_activity = event.pop("_foremanAdvancesActivity", False) is True
        reconciled_activity = params.get("_foremanActivityAt")
        activity_source: str | None = None
        replace_activity = False
        if self.timestamp(reconciled_activity) is not None:
            event["activityAt"] = reconciled_activity
            activity_source = "provider"
            replace_activity = (
                params.get("_foremanActivityComplete") is True
                and event.get("status") not in ("working", "waiting")
            )
        elif reconciled:
            event["activityAt"] = None
        elif advances_activity:
            event_activity = self.timestamp(event.get("completedAt"))
            if event_activity is None:
                event_activity = self.timestamp(event.get("startedAt"))
            event["activityAt"] = (
                event_activity if event_activity is not None else observed_at
            )
            activity_source = "live"
        event["observedAt"] = observed_at
        if (
            event.get("type") == "turn/started"
            and event.get("kind") == "status"
            and event.get("status") == "working"
            and not event.get("startedAt")
            and self.timestamp(event.get("activityAt")) is not None
        ):
            # The notification itself is the authoritative start signal even
            # when this app-server version omits the optional timestamp.
            event["startedAt"] = event["activityAt"]
        self.remember_session_event(
            thread_id,
            event,
            activity_source=activity_source,
            replace_activity=replace_activity,
        )
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
        self.session_activity_sources[("codex", thread_id)] = "live"
        self.persist_overlay_timestamps("codex", thread_id, overlay)
        outgoing = {
            "version": VERSION,
            "type": f"approval.{action}",
            "payload": {
                "approval": approval,
                "activityAt": observed_at,
                "observedAt": observed_at,
            },
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
        self.session_activity_sources[("codex", thread_id)] = "live"
        self.persist_overlay_timestamps("codex", thread_id, overlay)
        outgoing = {
            "version": VERSION,
            "type": f"input.{action}",
            "payload": {
                "input": pending,
                "activityAt": observed_at,
                "observedAt": observed_at,
            },
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
        if self.stopping:
            return
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

    def session_presence_projection(self) -> list[dict[str, str]]:
        sessions: set[tuple[str, str]] = set()
        for client in self.clients:
            if not client.authenticated or client.focused_session is None:
                continue
            provider, separator, session_id = client.focused_session.partition(":")
            if separator and provider in PROVIDERS and session_id:
                sessions.add((provider, session_id))
        return [
            {"provider": provider, "sessionId": session_id}
            for provider, session_id in sorted(sessions)
        ]

    async def broadcast_session_presence(self) -> None:
        if self.stopping:
            return
        outgoing = {
            "version": VERSION,
            "type": "session.presence.event",
            "payload": {"sessions": self.session_presence_projection()},
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    async def set_session_presence(
        self, client: Client, payload: dict[str, Any]
    ) -> dict[str, Any]:
        before = self.session_presence_projection()
        provider_value = payload.get("provider")
        session_value = payload.get("sessionId")
        if provider_value is None and session_value is None:
            client.focused_session = None
        elif provider_value is None or session_value is None:
            raise ValueError("provider and sessionId must be provided together")
        else:
            provider = required_text(payload, "provider", 40)
            session_id = required_text(payload, "sessionId", 160)
            if provider not in PROVIDERS:
                raise ValueError("provider is unsupported")
            client.focused_session = f"{provider}:{session_id}"
        after = self.session_presence_projection()
        if after != before:
            await self.broadcast_session_presence()
        return {"sessions": after}

    async def remove_client(self, client: Client) -> None:
        before = self.session_presence_projection()
        self.clients.discard(client)
        if self.session_presence_projection() != before:
            await self.broadcast_session_presence()

    def remember_session_event(
        self,
        thread_id: str,
        event: dict[str, Any],
        *,
        activity_source: str | None = None,
        replace_activity: bool = False,
    ) -> None:
        overlay = self.session_overlays.setdefault(thread_id, {})
        kind = event.get("kind")
        key = ("codex", thread_id)
        before_activity = self.timestamp(overlay.get("lastActivity"))
        before_terminal = self.timestamp(overlay.get("terminalAt"))
        before_source = self.session_activity_sources.get(key)
        activity_at = event.get("activityAt")
        incoming_activity = self.timestamp(activity_at)
        known_activity = before_activity
        if incoming_activity is not None:
            if (
                replace_activity
                and activity_source == "provider"
                and before_source != "live"
            ):
                activity_at = incoming_activity
            else:
                activity_at = max(
                    value
                    for value in (known_activity, incoming_activity)
                    if value is not None
                )
            overlay["lastActivity"] = activity_at
            event["activityAt"] = activity_at
            if activity_source in {"provider", "live"} and (
                known_activity is None
                or incoming_activity > known_activity
                or (incoming_activity == known_activity and activity_source == "live")
                or (
                    replace_activity
                    and activity_source == "provider"
                    and before_source != "live"
                )
            ):
                if before_source != "live" or activity_source == "live":
                    self.session_activity_sources[key] = activity_source
        if kind == "status":
            projected_status = event.get("status")
            if isinstance(projected_status, str):
                if (
                    projected_status != overlay.get("status")
                    and self.timestamp(activity_at) is not None
                ):
                    overlay["statusChangedAt"] = activity_at
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
                event_terminal = self.timestamp(event.get("completedAt"))
                if event_terminal is None:
                    event_terminal = self.timestamp(activity_at)
                terminal_candidates = [
                    value
                    for value in (
                        self.timestamp(overlay.get("terminalAt")),
                        event_terminal,
                    )
                    if value is not None
                ]
                if terminal_candidates:
                    overlay["terminalAt"] = max(terminal_candidates)
                    event["completedAt"] = overlay["terminalAt"]
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
            route = {
                key: event[key]
                for key in ("model", "reasoningEffort", "accessLevel")
                if isinstance(event.get(key), str) and event[key]
            }
            if route:
                settings = self.remember_session_settings(
                    "codex", thread_id, route
                )
                overlay.update(settings)
                event.update(settings)
        elif kind == "usage" and isinstance(event.get("tokenUsage"), dict):
            usage = event["tokenUsage"]
            last = usage.get("last")
            if (
                isinstance(last, dict)
                and isinstance(last.get("totalTokens"), int)
                and isinstance(usage.get("modelContextWindow"), int)
                and usage["modelContextWindow"] > 0
            ):
                overlay["tokenUsage"] = usage
                self.state.remember_session_token_usage(thread_id, usage)
        timestamps_changed = (
            before_activity != self.timestamp(overlay.get("lastActivity"))
            or before_terminal != self.timestamp(overlay.get("terminalAt"))
            or before_source != self.session_activity_sources.get(key)
        )
        if timestamps_changed:
            self.persist_overlay_timestamps(
                "codex",
                thread_id,
                overlay,
                replace_activity=replace_activity,
            )
            if incoming_activity is not None:
                event["activityAt"] = self.timestamp(overlay.get("lastActivity"))

    def projected_session(
        self, thread: dict[str, Any], include_messages: bool = False
    ) -> dict[str, Any]:
        projected = session(thread, include_messages)
        provider_activity = projected.get("lastActivity")
        provider_terminal = projected.get("terminalAt")
        known = self.session_overlays.get(projected["id"], {})
        discovered = {
            key: projected[key]
            for key in ("model", "reasoningEffort", "accessLevel")
            if not isinstance(known.get(key), str)
            and isinstance(projected.get(key), str)
            and projected[key]
        }
        if discovered:
            self.remember_session_settings(
                "codex", projected["id"], discovered
            )
        merged = {**projected, **self.session_overlays.get(projected["id"], {})}
        return self.restore_session_timestamps(
            "codex",
            projected["id"],
            merged,
            provider_activity=provider_activity,
            provider_terminal=provider_terminal,
            provider_complete=(
                isinstance(thread.get("status"), dict)
                and self.timestamp(provider_activity) is not None
            ),
        )

    @staticmethod
    def timestamp(value: Any) -> int | float | None:
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or value < 0
            or value != value
            or value in (float("inf"), float("-inf"))
        ):
            return None
        return value

    @classmethod
    def sort_session_projections(
        cls, sessions: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        def rank(item: dict[str, Any]) -> tuple[int, float, str, str]:
            status = item.get("status")
            state_rank = (
                0
                if status == "waiting" or item.get("attention")
                else 1 if status == "working" else 2
            )
            activity = cls.timestamp(item.get("lastActivity"))
            if activity is None:
                activity = cls.timestamp(item.get("terminalAt"))
            return (
                state_rank,
                -(activity if activity is not None else 0),
                str(item.get("provider", "codex")),
                str(item.get("id", "")),
            )

        return sorted(sessions, key=rank)

    def restore_session_timestamps(
        self,
        provider: str,
        session_id: str,
        projected: dict[str, Any],
        *,
        provider_activity: Any = None,
        provider_terminal: Any = None,
        provider_created: Any = None,
        provider_complete: bool = False,
    ) -> dict[str, Any]:
        overlays = (
            self.session_overlays
            if provider == "codex"
            else self.claude_session_overlays
        )
        overlay = overlays.setdefault(session_id, {})
        key = (provider, session_id)
        known_source = self.session_activity_sources.get(key)
        active = projected.get("status") in ("working", "waiting")
        known_activity = self.timestamp(overlay.get("lastActivity"))
        known_terminal = self.timestamp(overlay.get("terminalAt"))
        provider_candidates = [
            value
            for value in (
                self.timestamp(provider_activity),
                self.timestamp(provider_terminal),
                self.timestamp(provider_created),
            )
            if value is not None
        ]
        authoritative_repair = (
            provider == "codex"
            and provider_complete
            and not active
            and known_source != "live"
            and bool(provider_candidates)
        )
        activity_candidates = list(provider_candidates)
        if known_terminal is not None:
            activity_candidates.append(known_terminal)
        if known_activity is not None and not authoritative_repair:
            activity_candidates.append(known_activity)
        terminal_candidates = [
            value
            for value in (
                self.timestamp(overlay.get("terminalAt")),
                self.timestamp(provider_terminal),
            )
            if value is not None
        ]
        desired: dict[str, int | float] = {}
        if activity_candidates:
            desired["lastActivity"] = max(activity_candidates)
        if terminal_candidates and not active:
            desired["terminalAt"] = max(terminal_candidates)
        known = {
            key: value
            for key in ("lastActivity", "terminalAt")
            if (value := self.timestamp(overlay.get(key))) is not None
        }
        timestamps = known if desired == known else desired
        overlay.pop("terminalAt", None)
        overlay.update(timestamps)
        desired_activity = self.timestamp(timestamps.get("lastActivity"))
        if desired_activity is not None:
            provider_max = max(provider_candidates) if provider_candidates else None
            if known_source != "live" and (
                authoritative_repair
                or (
                    provider_max is not None
                    and (known_activity is None or provider_max > known_activity)
                )
            ):
                self.session_activity_sources[key] = "provider"
        source_changed = known_source != self.session_activity_sources.get(key)
        if desired != known or source_changed:
            self.queue_session_timestamp_persistence(
                provider,
                session_id,
                replace_activity=authoritative_repair,
            )
        return {
            **projected,
            "lastActivity": timestamps.get("lastActivity"),
            "terminalAt": timestamps.get("terminalAt"),
            "observedAt": int(time.time()),
        }

    def persist_overlay_timestamps(
        self,
        provider: str,
        session_id: str,
        overlay: dict[str, Any],
        *,
        replace_activity: bool = False,
    ) -> None:
        active = overlay.get("status") in ("working", "waiting")
        activity_candidates = [
            value
            for value in (
                self.timestamp(overlay.get("lastActivity")),
                self.timestamp(overlay.get("terminalAt")),
            )
            if value is not None
        ]
        timestamps: dict[str, int | float] = {}
        if activity_candidates:
            timestamps["lastActivity"] = max(activity_candidates)
        terminal_at = self.timestamp(overlay.get("terminalAt"))
        if terminal_at is not None and not active:
            timestamps["terminalAt"] = terminal_at
        overlay.pop("terminalAt", None)
        overlay.update(timestamps)
        self.queue_session_timestamp_persistence(
            provider,
            session_id,
            replace_activity=replace_activity,
        )

    def queue_session_timestamp_persistence(
        self,
        provider: str,
        session_id: str,
        *,
        replace_activity: bool = False,
    ) -> None:
        key = (provider, session_id)
        self.timestamp_persistence_pending.add(key)
        if replace_activity:
            self.timestamp_persistence_replacements.add(key)
        elif self.session_activity_sources.get(key) == "live":
            self.timestamp_persistence_replacements.discard(key)
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self.persist_session_timestamp_batch(
                list(self.timestamp_persistence_pending)
            )
            self.timestamp_persistence_pending.clear()
            self.timestamp_persistence_replacements.clear()
            return
        if (
            self.timestamp_persistence_task is None
            or self.timestamp_persistence_task.done()
        ):
            self.timestamp_persistence_task = loop.create_task(
                self.persist_session_timestamp_worker()
            )

    def session_timestamp_entries(
        self, keys: list[tuple[str, str]]
    ) -> list[dict[str, Any]]:
        entries: list[dict[str, Any]] = []
        for provider, session_id in keys:
            overlays = (
                self.session_overlays
                if provider == "codex"
                else self.claude_session_overlays
            )
            overlay = overlays.get(session_id, {})
            entries.append(
                {
                    "provider": provider,
                    "sessionId": session_id,
                    "lastActivity": self.timestamp(overlay.get("lastActivity")),
                    "terminalAt": self.timestamp(overlay.get("terminalAt")),
                    "clearTerminal": overlay.get("status") in ("working", "waiting"),
                    "activitySource": self.session_activity_sources.get(
                        (provider, session_id)
                    ),
                    "replaceActivity": (provider, session_id)
                    in self.timestamp_persistence_replacements,
                }
            )
        return entries

    def persist_session_timestamp_batch(
        self, keys: list[tuple[str, str]]
    ) -> None:
        self.state.remember_session_timestamp_batch(
            self.session_timestamp_entries(keys)
        )

    async def persist_session_timestamp_worker(self) -> bool:
        await asyncio.sleep(0)
        keys: list[tuple[str, str]] = []
        try:
            while self.timestamp_persistence_pending:
                keys = list(self.timestamp_persistence_pending)
                self.timestamp_persistence_pending.difference_update(keys)
                entries = self.session_timestamp_entries(keys)
                await asyncio.to_thread(
                    self.state.remember_session_timestamp_batch,
                    entries,
                )
                for key in keys:
                    if key not in self.timestamp_persistence_pending:
                        self.timestamp_persistence_replacements.discard(key)
            return True
        except Exception:
            self.timestamp_persistence_pending.update(keys)
            self.diagnostics.record("state.timestamp_persist_failed")
            return False
        finally:
            self.timestamp_persistence_task = None

    async def flush_session_timestamp_persistence(self) -> None:
        retries_remaining = 1
        while self.timestamp_persistence_pending or (
            self.timestamp_persistence_task is not None
            and not self.timestamp_persistence_task.done()
        ):
            task = self.timestamp_persistence_task
            if task is None:
                self.queue_session_timestamp_persistence(
                    *next(iter(self.timestamp_persistence_pending))
                )
                task = self.timestamp_persistence_task
            if task is not None:
                persisted = await task
                if not persisted:
                    if retries_remaining <= 0:
                        raise RuntimeError("session timestamp persistence failed")
                    retries_remaining -= 1

    def remember_session_settings(
        self, provider: str, session_id: str, settings: dict[str, Any]
    ) -> dict[str, Any]:
        acknowledged = self.state.remember_session_settings(
            provider, session_id, settings
        )
        overlays = (
            self.session_overlays
            if provider == "codex"
            else self.claude_session_overlays
        )
        overlays.setdefault(session_id, {}).update(acknowledged)
        return acknowledged

    async def broadcast_route_settings(
        self, provider: str, session_id: str, settings: dict[str, Any]
    ) -> None:
        event = {
            "kind": "route",
            "observedAt": int(time.time()),
            **settings,
        }
        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {
                **({"provider": provider} if provider != "codex" else {}),
                "sessionId": session_id,
                "event": event,
            },
        }
        subscription = self.provider_subscription(provider, session_id)
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
                and (
                    (
                        provider == "codex"
                        and session_id in client.subscriptions
                    )
                    or subscription in client.subscriptions
                )
            ),
            return_exceptions=True,
        )

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

    async def broadcast_account_usage(self) -> None:
        outgoing = {
            "version": VERSION,
            "type": "usage.event",
            "payload": self.account_usage_projection(),
        }
        await asyncio.gather(
            *(
                client.send(outgoing)
                for client in self.clients
                if client.authenticated
            ),
            return_exceptions=True,
        )

    def account_usage_projection(self) -> dict[str, Any]:
        return {
            "providers": {
                **(
                    {"codex": self.account_usage}
                    if self.provider_enabled["codex"]
                    else {}
                ),
                **(
                    {"claude-code": self.claude_account_usage}
                    if self.provider_enabled["claude-code"]
                    else {}
                ),
            }
        }

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
            await self.remove_client(client)
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
            await self.remove_client(client)
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
        except CapabilityError as exc:
            self.record_request_failure(request)
            await client.send(error(request, "capabilityUnavailable", str(exc)))
        except (ValueError, CodexError, ClaudeCodeError) as exc:
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
        codex_connected = self.provider_enabled["codex"] and getattr(
            self.codex, "is_connected", True
        )
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
        presence_before = self.session_presence_projection()
        for target in targets:
            target.authenticated = False
            target.device_id = None
            target.focused_session = None
        if self.session_presence_projection() != presence_before:
            await self.broadcast_session_presence()
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
            codex_enabled = self.provider_enabled["codex"]
            return {
                "server": "Foreman",
                "protocolVersion": VERSION,
                "codexRuntime": self.codex.runtime_status,
                "codexConnected": self.provider_enabled["codex"]
                and getattr(self.codex, "is_connected", True),
                "capabilities": {
                    "steer": codex_enabled,
                    "interrupt": codex_enabled,
                    "archive": codex_enabled and self.codex.supports("thread/archive"),
                    "delete": codex_enabled and self.codex.supports("thread/delete"),
                    "approvals": codex_enabled,
                    "structuredInput": codex_enabled and bool(
                        self.codex.supports("item/tool/requestUserInput")
                        or self.codex.supports("mcpServer/elicitation/request")
                    ),
                    "models": codex_enabled and self.codex.supports("model/list"),
                    "access": codex_enabled and self.codex.supports("permissionProfile/list"),
                    "threadSettings": codex_enabled and self.codex.supports("thread/settings/update"),
                    "images": codex_enabled,
                    "search": codex_enabled,
                    "diagnostics": True,
                    "workspaceFiles": True,
                    "sessionPresence": True,
                    "providerConfiguration": True,
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

        if message_type == "session.presence":
            return await self.set_session_presence(client, payload)

        if message_type in CODEX_OPERATIONS:
            self.require_provider_enabled("codex")

        if message_type == "repository.list":
            return {"repositories": await asyncio.to_thread(self.repositories)}
        if message_type == "workspace.file.read":
            path = required_text(payload, "path", MAX_WORKSPACE_PATH_BYTES)
            return await asyncio.to_thread(self.read_workspace_file, path)
        if message_type == "provider.list":
            return {"providers": await self.provider_status()}
        if message_type == "provider.configure":
            provider = self.required_provider(payload)
            enabled = payload.get("enabled")
            if not isinstance(enabled, bool):
                raise ValueError("enabled must be a boolean")
            await self.configure_provider(provider, enabled)
            providers = await self.provider_status()
            await self.broadcast_provider_status()
            await self.broadcast_account_usage()
            return {"provider": provider, "enabled": enabled, "providers": providers}
        if message_type == "provider.session.list":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider == "codex":
                sessions = []
                for item in await self.codex.list_threads():
                    projected = self.projected_session(item)
                    sessions.append(
                        {
                            **projected,
                            "provider": "codex",
                            "sessionId": item["id"],
                            "source": "managed",
                            "state": projected.get("status", "idle"),
                        }
                    )
                return {
                    "provider": provider,
                    "sessions": self.sort_session_projections(sessions),
                }
            return {
                "provider": provider,
                "sessions": await self.discover_claude_sessions(),
            }
        if message_type == "provider.session.read":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            session_id = required_text(payload, "sessionId", 160)
            if provider == "codex":
                projected = self.projected_session(
                    await self.codex.read_thread(session_id), True
                )
                return {
                    "session": {
                        **projected,
                        "provider": "codex",
                        "sessionId": session_id,
                        "source": "managed",
                        "state": projected.get("status", "idle"),
                    }
                }
            repository_id = required_text(payload, "repositoryId", 500)
            return {
                "session": await self.read_claude_session(
                    session_id, repository_id
                )
            }
        if message_type == "provider.model.list":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider == "codex":
                return {
                    "provider": provider,
                    "models": await self.codex.list_models(
                        refresh=payload.get("refresh") is True
                    ),
                    "dynamic": True,
                }
            await self.require_claude()
            return {
                "provider": provider,
                "models": [dict(model) for model in SUPPORTED_MODELS],
                "dynamic": False,
                "source": "adapter-supported",
            }
        if message_type == "provider.permission.list":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider == "codex":
                return {
                    "provider": provider,
                    "modes": await self.codex.list_access_levels(
                        refresh=payload.get("refresh") is True
                    ),
                }
            await self.require_claude()
            descriptions = {
                "default": ("Default", "Ask when required"),
                "dontAsk": ("Don’t ask", "Deny unapproved actions"),
                "acceptEdits": ("Accept edits", "Allow file edits under Claude’s documented behavior"),
                "plan": ("Plan", "Planning-only behavior"),
                "auto": ("Auto", "Claude-managed automatic policy"),
                "bypassPermissions": ("Bypass permissions", "Unrestricted/high risk"),
            }
            return {
                "provider": provider,
                "modes": [
                    {
                        "id": mode,
                        "displayName": descriptions[mode][0],
                        "description": descriptions[mode][1],
                        "highRisk": mode == "bypassPermissions",
                    }
                    for mode in CLAUDE_PERMISSION_MODES
                ],
            }
        if message_type == "provider.session.settings":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider != "claude-code":
                raise CapabilityError(
                    "Use the compatible Codex session.settings operation"
                )
            session_id = required_text(payload, "sessionId", 160)
            repository_id = required_text(payload, "repositoryId", 500)
            if not any(
                payload.get(key) is not None for key in ("model", "permissionMode")
            ):
                raise ValueError("at least one session setting is required")
            route = self.claude_route(payload, session_id, complete=False)
            async with self.thread_lock(
                self.provider_subscription(provider, session_id)
            ):
                projected = await self.read_claude_session(
                    session_id, repository_id
                )
                self.require_configurable_projection(projected)
                settings = self.remember_session_settings(
                    provider, session_id, route
                )
                projected = {**projected, **settings}
            await self.broadcast_route_settings(provider, session_id, settings)
            return {"updated": True, "session": projected}
        if message_type in {"provider.session.start", "provider.session.resume", "provider.turn.prompt"}:
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider == "codex":
                raise CapabilityError(
                    "Use the compatible Codex session.start, session.resume, or turn.prompt operation"
                )
            await self.require_claude()
            assert self.claude is not None
            repository_id = required_text(payload, "repositoryId", 500)
            cwd = self.resolve_repository(repository_id)
            text = required_text(payload, "text", 100_000)
            session_id = (
                required_text(payload, "sessionId", 160)
                if message_type != "provider.session.start"
                else None
            )
            if message_type == "provider.session.start":
                route = self.claude_route(payload)
                model = route["model"]
                permission_mode = route["permissionMode"]
                result = await self.claude.start_session(
                    cwd, text, model, permission_mode
                )
                session_id = result["sessionId"]
                run_id = result.get("runId")
                overlay = self.remember_claude_query_started(
                    session_id, cwd, text, model, permission_mode, run_id
                )
            else:
                assert session_id is not None
                async with self.thread_lock(
                    self.provider_subscription(provider, session_id)
                ):
                    projected = await self.read_claude_session(
                        session_id, repository_id
                    )
                    self.require_configurable_projection(projected)
                    # Reading first reconciles any adapter-discovered route into
                    # the durable overlay before omitted values are resolved.
                    route = self.claude_route(payload, session_id)
                    model = route["model"]
                    permission_mode = route["permissionMode"]
                    result = await self.claude.resume_session(
                        session_id, cwd, text, model, permission_mode
                    )
                    session_id = result["sessionId"]
                    run_id = result.get("runId")
                    overlay = self.remember_claude_query_started(
                        session_id, cwd, text, model, permission_mode, run_id
                    )
            client.subscriptions.add(
                self.provider_subscription("claude-code", session_id)
            )
            projected = self.project_claude_session(
                {
                    "sessionId": session_id,
                    "cwd": str(cwd),
                    "classification": "managed",
                    "active": True,
                    "title": overlay["title"],
                    "model": model,
                    "permissionMode": permission_mode,
                    "messages": [],
                },
                True,
            )
            if message_type == "provider.session.start":
                await self.broadcast_claude_lifecycle(session_id, projected)
            return {
                "accepted": True,
                "session": projected,
                "turnId": run_id,
            }
        if message_type in {"provider.session.subscribe", "provider.session.unsubscribe"}:
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            session_id = required_text(payload, "sessionId", 160)
            subscription = self.provider_subscription(provider, session_id)
            subscribed = message_type.endswith("subscribe") and not message_type.endswith("unsubscribe")
            if subscribed:
                client.subscriptions.add(subscription)
                if provider == "codex":
                    # Codex event delivery predates provider-aware subscriptions
                    # and remains keyed by the raw thread ID. Keep both keys so
                    # provider-v1 clients receive the same live stream as legacy
                    # session.subscribe clients.
                    client.subscriptions.add(session_id)
                    await self.codex.subscribe_thread(session_id)
            else:
                client.subscriptions.discard(subscription)
                if provider == "codex":
                    client.subscriptions.discard(session_id)
            return {"provider": provider, "sessionId": session_id, "subscribed": subscribed}
        if message_type == "provider.session.delete":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider != "claude-code":
                raise CapabilityError("Use the compatible Codex session.delete operation")
            session_id = required_text(payload, "sessionId", 160)
            if payload.get("confirm") is not True:
                raise ValueError("permanent deletion requires confirm=true")
            repository_id = required_text(payload, "repositoryId", 500)
            cwd = self.resolve_repository(repository_id)
            await self.require_claude()
            assert self.claude is not None
            async with self.thread_lock(
                self.provider_subscription(provider, session_id)
            ):
                overlay = self.claude_session_overlays.get(session_id, {})
                if overlay.get("state") == "working" or overlay.get("activeTurnId"):
                    raise ValueError(
                        "Claude session is active; interrupt it before deletion"
                    )
                await self.claude.delete_session(session_id, cwd)
                self.claude_session_overlays.pop(session_id, None)
                self.session_activity_sources.pop((provider, session_id), None)
                self.claude_session_cwds.pop(session_id, None)
                self.claude_session_messages.pop(session_id, None)
                self.state.forget_session_settings(provider, session_id)
                await self.flush_session_timestamp_persistence()
                await asyncio.to_thread(
                    self.state.forget_session_timestamps, provider, session_id
                )
            subscription = self.provider_subscription(provider, session_id)
            for connected in self.clients:
                connected.subscriptions.discard(subscription)
            await self.broadcast_claude_lifecycle(session_id, action="removed")
            return {
                "provider": provider,
                "sessionId": session_id,
                "deleted": True,
            }
        if message_type == "provider.turn.interrupt":
            provider = self.required_provider(payload)
            self.require_provider_enabled(provider)
            if provider != "claude-code":
                raise CapabilityError("Use the compatible Codex turn.interrupt operation")
            session_id = required_text(payload, "sessionId", 160)
            overlay = self.claude_session_overlays.get(session_id)
            if not overlay:
                raise CapabilityError(
                    "External Claude sessions cannot be interrupted because Foreman is not live-attached"
                )
            if overlay.get("state") != "working" or not overlay.get("activeTurnId"):
                raise ValueError("Claude session does not have an active Foreman-owned query")
            await self.require_claude()
            assert self.claude is not None
            await self.claude.interrupt(session_id)
            return {"accepted": True, "provider": provider, "sessionId": session_id}
        if message_type == "service.status":
            return self.service_status()
        if message_type == "usage.status":
            if self.provider_enabled["codex"]:
                self.account_usage = await self.codex.account_rate_limits()
            return self.account_usage_projection()
        if message_type == "diagnostics.list":
            return {"events": self.diagnostics.entries(), "limit": 100}
        if message_type == "service.restart":
            if not self.remote_restart_enabled:
                raise ValueError("remote restart is disabled")
            if self.restart_scheduled:
                raise ValueError("restart is already scheduled")
            await self.require_restart_safe()
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
                "sessions": self.sort_session_projections(
                    [
                        self.projected_session(item)
                        for item in await self.codex.list_threads()
                    ]
                )
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
            model_id, effort = await self.route(payload)
            selected_access_level = await self.access_level(payload)
            thread = await self.codex.start_thread(
                str(repository),
                model_id=model_id,
                effort=effort,
                selected_access_level=selected_access_level,
            )
            self.remember_session_settings(
                "codex",
                thread["id"],
                {
                    "model": model_id,
                    "reasoningEffort": effort,
                    "accessLevel": selected_access_level,
                },
            )
            client.subscriptions.add(thread["id"])
            projected = self.projected_session(thread, True)
            await self.broadcast_lifecycle(thread["id"], "created", projected)
            return {"session": projected}
        if message_type == "session.resume":
            thread_id = required_text(payload, "sessionId", 100)
            thread = await self.codex.resume_thread(thread_id)
            return {"session": self.projected_session(thread, True)}
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
            self.session_activity_sources.pop(("codex", thread_id), None)
            self.state.forget_session_token_usage(thread_id)
            self.state.forget_session_settings("codex", thread_id)
            await self.flush_session_timestamp_persistence()
            await asyncio.to_thread(
                self.state.forget_session_timestamps, "codex", thread_id
            )
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
            self.session_activity_sources.pop(("codex", thread_id), None)
            self.state.forget_session_token_usage(thread_id)
            self.state.forget_session_settings("codex", thread_id)
            await self.flush_session_timestamp_persistence()
            await asyncio.to_thread(
                self.state.forget_session_timestamps, "codex", thread_id
            )
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
                await self.require_configurable_codex_session(thread_id)
                await self.codex.update_thread_settings(
                    thread_id,
                    model_id,
                    effort,
                    selected_access_level,
                )
                settings = self.remember_session_settings(
                    "codex",
                    thread_id,
                    {
                        "model": model_id,
                        "reasoningEffort": effort,
                        "accessLevel": selected_access_level,
                    },
                )
                projected = self.projected_session(
                    await self.codex.read_thread(thread_id), True
                )
            await self.broadcast_route_settings("codex", thread_id, settings)
            return {"updated": True, "session": projected}
        if message_type == "turn.prompt":
            thread_id = required_text(payload, "sessionId", 100)
            images = image_payloads(payload)
            text = message_text(payload, images)
            requested_model, requested_effort = await self.route(payload)
            requested_access_level = await self.access_level(payload)
            async with self.thread_lock(thread_id):
                await self.require_configurable_codex_session(thread_id)
                known = self.session_overlays.get(thread_id, {})
                model_id = (
                    known.get("model")
                    if isinstance(known.get("model"), str)
                    else requested_model
                )
                effort = (
                    known.get("reasoningEffort")
                    if isinstance(known.get("reasoningEffort"), str)
                    else requested_effort
                )
                selected_access_level = (
                    known.get("accessLevel")
                    if isinstance(known.get("accessLevel"), str)
                    else requested_access_level
                )
                result = await self.codex.prompt(
                    thread_id,
                    text,
                    images,
                    model_id,
                    effort,
                    selected_access_level,
                )
                self.session_overlays.setdefault(thread_id, {}).update(
                    {
                        "status": "working",
                        "activeTurnId": result["turn"]["id"],
                        "attention": False,
                    }
                )
                if any(
                    isinstance(value, str) and value
                    for value in (model_id, effort, selected_access_level)
                ):
                    self.remember_session_settings(
                        "codex",
                        thread_id,
                        {
                            "model": model_id,
                            "reasoningEffort": effort,
                            "accessLevel": selected_access_level,
                        },
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

    def claude_route(
        self,
        payload: dict[str, Any],
        session_id: str | None = None,
        complete: bool = True,
    ) -> dict[str, str]:
        model = optional_text(payload, "model", 100)
        permission_mode = optional_text(payload, "permissionMode", 100)
        known = (
            self.claude_session_overlays.get(session_id, {})
            if session_id is not None
            else {}
        )
        if complete:
            model = (
                known.get("model")
                if isinstance(known.get("model"), str)
                else model or "sonnet"
            )
            permission_mode = (
                known.get("permissionMode")
                if isinstance(known.get("permissionMode"), str)
                else permission_mode or "default"
            )
        if model is not None and model not in {
            item["id"] for item in SUPPORTED_MODELS
        }:
            raise ValueError("selected Claude model is unavailable")
        if (
            permission_mode is not None
            and permission_mode not in CLAUDE_PERMISSION_MODES
        ):
            raise ValueError("selected Claude permission mode is unavailable")
        return {
            key: value
            for key, value in {
                "model": model,
                "permissionMode": permission_mode,
            }.items()
            if isinstance(value, str) and value
        }

    @staticmethod
    def require_configurable_projection(projected: dict[str, Any]) -> None:
        if projected.get("status") in ("working", "waiting", "stopping") or projected.get(
            "state"
        ) == "working":
            raise ValueError(
                "session settings are available when this turn finishes"
            )

    async def require_configurable_codex_session(self, thread_id: str) -> None:
        self.require_configurable_projection(
            self.projected_session(await self.codex.read_thread(thread_id))
        )

    async def require_inactive_session(self, thread_id: str) -> None:
        projected = session(await self.codex.read_thread(thread_id))
        if projected["status"] in ("working", "waiting"):
            raise ValueError("session is active; interrupt it before archive or delete")

    async def require_restart_safe(self) -> None:
        if self.provider_enabled["codex"] and (
            self.codex.list_approvals() or self.codex.list_inputs()
        ):
            raise ValueError(
                "restart is unavailable while approval or input requests are pending"
            )
        if self.provider_enabled["codex"]:
            threads = await self.codex.list_threads()
            if any(
                self.projected_session(thread)["status"] in ("working", "waiting")
                for thread in threads
            ):
                raise ValueError(
                    "restart is unavailable while sessions are active or waiting for attention"
                )
        if self.provider_enabled["claude-code"] and any(
            overlay.get("state") == "working"
            or overlay.get("status") in ("working", "waiting")
            for overlay in self.claude_session_overlays.values()
        ):
            raise ValueError(
                "restart is unavailable while sessions are active or waiting for attention"
            )

    def thread_lock(self, thread_id: str) -> asyncio.Lock:
        return self.thread_locks.setdefault(thread_id, asyncio.Lock())

    def discard_subscriptions(self, thread_id: str) -> None:
        for connected in self.clients:
            connected.subscriptions.discard(thread_id)
            connected.subscriptions.discard(
                self.provider_subscription("codex", thread_id)
            )

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
        if not path.is_dir():
            raise ValueError("repository was not found")
        if path == self.repository_root:
            return path
        if not (path / ".git").exists():
            raise ValueError("repository was not found")
        return path

    def read_workspace_file(self, raw_path: str) -> dict[str, Any]:
        requested = Path(raw_path)
        if not requested.is_absolute():
            raise ValueError("workspace file path must be absolute")
        try:
            path = requested.resolve(strict=True)
            relative = path.relative_to(self.repository_root)
        except (OSError, ValueError) as error:
            raise ValueError(
                "workspace file is outside configured root or was not found"
            ) from error
        parts = relative.parts
        if not parts:
            raise ValueError("workspace file was not found")
        descriptors: set[int] = set()
        try:
            directory_fd = os.open(
                self.repository_root,
                os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
            )
            descriptors.add(directory_fd)
            for component in parts[:-1]:
                directory_fd = os.open(
                    component,
                    os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
                    dir_fd=directory_fd,
                )
                descriptors.add(directory_fd)
            file_fd = os.open(
                parts[-1],
                os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK,
                dir_fd=directory_fd,
            )
            descriptors.add(file_fd)
            file_stat = os.fstat(file_fd)
            if not stat.S_ISREG(file_stat.st_mode):
                raise ValueError("workspace file was not found")
            if file_stat.st_size > MAX_WORKSPACE_FILE_BYTES:
                raise ValueError("workspace file is larger than 1 MiB")
            chunks: list[bytes] = []
            remaining = MAX_WORKSPACE_FILE_BYTES + 1
            while remaining:
                chunk = os.read(file_fd, min(64 * 1024, remaining))
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            content = b"".join(chunks)
            if len(content) > MAX_WORKSPACE_FILE_BYTES:
                raise ValueError("workspace file is larger than 1 MiB")
        except OSError as error:
            raise ValueError(
                "workspace file is outside configured root or was not found"
            ) from error
        finally:
            for descriptor in descriptors:
                os.close(descriptor)
        if b"\x00" in content:
            raise ValueError("workspace file is not a text file")
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValueError("workspace file is not UTF-8 text") from error
        return {"path": str(path), "content": text}


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
        claude_factory=ClaudeCode,
        claude_node=args.node,
        claude_bridge=args.claude_bridge,
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


async def print_claude_status(args: argparse.Namespace) -> None:
    adapter = ClaudeCode(
        Path(args.repository_root),
        Path(args.state_directory) / "claude-code-sessions.json",
        node_executable=args.node,
        bridge_path=args.claude_bridge,
    )
    try:
        print(json.dumps(await adapter.start(), separators=(",", ":")))
    finally:
        await adapter.stop()


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
    parser.add_argument("--claude-status", action="store_true")
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
        "--node",
        default=os.environ.get("FOREMAN_NODE_EXECUTABLE", "node"),
    )
    parser.add_argument(
        "--claude-bridge",
        default=os.environ.get(
            "FOREMAN_CLAUDE_BRIDGE",
            str(Path(__file__).resolve().parent / "claude_bridge" / "bridge.mjs"),
        ),
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
    if args.claude_status:
        asyncio.run(print_claude_status(args))
        return
    asyncio.run(run_service(args))


if __name__ == "__main__":
    main()
