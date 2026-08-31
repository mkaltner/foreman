"""Bounded, sanitized operational diagnostics for Foreman."""

from __future__ import annotations

from collections import deque
from datetime import datetime, timezone
import re
from typing import Any


DIAGNOSTIC_MESSAGES: dict[str, tuple[str, str]] = {
    "service.started": ("info", "Foreman service started"),
    "service.stopping": ("info", "Foreman service stopping"),
    "service.shutdown_timed_out": ("warning", "Foreman shutdown deadline reached"),
    "state.timestamp_persist_failed": (
        "warning",
        "Session timestamp persistence failed",
    ),
    "runtime.shared_attached": ("info", "Shared Desktop runtime attached"),
    "runtime.fallback_started": ("warning", "Foreman fallback runtime started"),
    "runtime.disconnected": ("warning", "Codex runtime disconnected"),
    "runtime.reconnected": ("info", "Codex runtime reconnected"),
    "client.connected": ("info", "Authenticated client connected"),
    "client.disconnected": ("info", "Authenticated client disconnected"),
    "pairing.created": ("info", "Pairing created"),
    "pairing.consumed": ("info", "Pairing consumed"),
    "token.revoked": ("warning", "Client token revoked"),
    "request.failed": ("warning", "Request category failed"),
    "update.started": ("info", "Server update started"),
    "update.verification_failed": ("warning", "Server update verification failed"),
    "update.activation_started": ("info", "Server update activation started"),
    "update.rolled_back": ("warning", "Server update rolled back"),
    "update.succeeded": ("info", "Server update succeeded"),
    "update.recovery_required": ("warning", "Server update requires recovery"),
    "protocol.incompatible": ("warning", "Protocol incompatibility detected"),
    "listeners.started": ("info", "Foreman listeners started"),
    "claude.available": ("info", "Optional Claude Code adapter available"),
    "claude.unavailable": ("info", "Optional Claude Code adapter unavailable"),
    "claude.query.started": ("info", "Claude Code query started"),
    "claude.query.completed": ("info", "Claude Code query completed"),
    "claude.query.failed": ("warning", "Claude Code query failed"),
    "claude.query.interrupted": ("info", "Claude Code query interrupted"),
    "claude.permission.requested": ("info", "Claude Code permission requested"),
    "claude.permission.denied": ("info", "Claude Code permission denied"),
}

REQUEST_CATEGORIES = {
    "access",
    "approval",
    "authentication",
    "client",
    "diagnostics",
    "input",
    "model",
    "protocol",
    "repository",
    "service",
    "session",
    "turn",
    "update",
    "unknown",
}

_SAFE_ID = re.compile(r"^(?:fmc|restart|fmu)_[A-Za-z0-9_-]{1,96}$")


def request_category(message_type: Any) -> str:
    """Map an untrusted operation name to one fixed, non-sensitive category."""
    if not isinstance(message_type, str):
        return "unknown"
    prefix = message_type.split(".", 1)[0]
    if prefix in REQUEST_CATEGORIES:
        return prefix
    if prefix in ("authenticate", "hello", "pair", "ping"):
        return "authentication" if prefix in ("authenticate", "pair") else "protocol"
    return "unknown"


class DiagnosticBuffer:
    """An in-memory ring that only accepts fixed messages and narrow safe IDs."""

    def __init__(self, maximum: int = 100) -> None:
        if maximum < 1 or maximum > 1_000:
            raise ValueError("diagnostic buffer size is out of range")
        self._entries: deque[dict[str, Any]] = deque(maxlen=maximum)

    def record(
        self,
        category: str,
        *,
        ids: dict[str, str] | None = None,
        request: str | None = None,
        timestamp: float | None = None,
    ) -> None:
        if category not in DIAGNOSTIC_MESSAGES:
            raise ValueError("diagnostic category is not allowed")
        severity, message = DIAGNOSTIC_MESSAGES[category]
        safe_ids: dict[str, str] = {}
        for key, value in (ids or {}).items():
            if key not in {"clientId", "operationId"} or not isinstance(value, str) or not _SAFE_ID.fullmatch(value):
                raise ValueError("diagnostic identifier is not safe")
            safe_ids[key] = value
        observed = (
            datetime.fromtimestamp(timestamp, timezone.utc)
            if timestamp is not None
            else datetime.now(timezone.utc)
        )
        entry: dict[str, Any] = {
            "timestamp": observed.isoformat(),
            "severity": severity,
            "category": category,
            "message": message,
        }
        if safe_ids:
            entry["ids"] = safe_ids
        if category == "request.failed":
            entry["requestCategory"] = request if request in REQUEST_CATEGORIES else "unknown"
        self._entries.append(entry)

    def entries(self) -> list[dict[str, Any]]:
        return [
            {**entry, **({"ids": dict(entry["ids"])} if "ids" in entry else {})}
            for entry in reversed(self._entries)
        ]

    def __len__(self) -> int:
        return len(self._entries)
