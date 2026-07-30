"""Bounded, schema-aware projection and validation for Codex approvals."""

from __future__ import annotations

import copy
import json
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

MAX_TEXT = 4_096
MAX_COMMAND = 16_384
MAX_COLLECTION = 100
MAX_DIFF_SUMMARY = 2_000

COMMAND_METHOD = "item/commandExecution/requestApproval"
FILE_METHOD = "item/fileChange/requestApproval"
PERMISSION_METHOD = "item/permissions/requestApproval"
USER_INPUT_METHOD = "item/tool/requestUserInput"
MCP_ELICITATION_METHOD = "mcpServer/elicitation/request"
APPROVAL_METHODS = {
    COMMAND_METHOD,
    FILE_METHOD,
    PERMISSION_METHOD,
    USER_INPUT_METHOD,
    MCP_ELICITATION_METHOD,
}
MAX_DECISION_BYTES = 64 * 1024

LEGACY_COMMAND_DECISIONS = ["accept", "acceptForSession", "decline", "cancel"]
LEGACY_FILE_DECISIONS = ["accept", "acceptForSession", "decline", "cancel"]
DECISION_LABELS = {
    "accept": "Allow once",
    "acceptForSession": "Allow for session",
    "acceptWithExecpolicyAmendment": "Allow matching commands",
    "applyNetworkPolicyAmendment": "Apply network rule",
    "decline": "Decline",
    "cancel": "Cancel turn",
    "grant": "Grant selected",
    "deny": "Deny all",
}


class ApprovalError(ValueError):
    """A safe error that can be returned to an authenticated Foreman client."""


def bounded_text(value: Any, limit: int = MAX_TEXT) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.replace("\x00", "")
    return value[:limit]


def bounded_list(value: Any, limit: int = MAX_COLLECTION) -> list[Any]:
    return value[:limit] if isinstance(value, list) else []


def decision_type(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and len(value) == 1:
        key = next(iter(value))
        return key if isinstance(key, str) else None
    return None


def approval_key(request_id: Any) -> str:
    # JSON-RPC request IDs may be strings or integers; preserve their type.
    return json.dumps(request_id, separators=(",", ":"), sort_keys=True)


def bounded_approval_params(method: str, value: Any) -> dict[str, Any]:
    """Copy only fields Foreman needs, with bounded collections and strings."""
    if not isinstance(value, dict):
        return {}
    result: dict[str, Any] = {}
    for key in ("threadId", "turnId", "itemId", "approvalId", "environmentId"):
        if text := bounded_text(value.get(key), 500):
            result[key] = text
    started = value.get("startedAtMs")
    if isinstance(started, int) and not isinstance(started, bool):
        result["startedAtMs"] = started
    if text := bounded_text(value.get("reason")):
        result["reason"] = text
    if method == COMMAND_METHOD:
        for key, limit in (("command", MAX_COMMAND), ("cwd", MAX_TEXT)):
            if text := bounded_text(value.get(key), limit):
                result[key] = text
        actions = []
        for raw in bounded_list(value.get("commandActions"), 20):
            if not isinstance(raw, dict):
                continue
            action = {
                key: bounded_text(item, 1_000)
                for key, item in raw.items()
                if key in {"type", "command", "name", "path", "query"}
                and isinstance(item, str)
            }
            if action:
                actions.append(action)
        if actions:
            result["commandActions"] = actions
        decisions = []
        for decision in bounded_list(value.get("availableDecisions"), 20):
            try:
                size = len(json.dumps(decision, separators=(",", ":")).encode())
            except (TypeError, ValueError):
                continue
            if size <= MAX_DECISION_BYTES and decision_type(decision) in DECISION_LABELS:
                decisions.append(copy.deepcopy(decision))
        if isinstance(value.get("availableDecisions"), list):
            result["availableDecisions"] = decisions
        result["additionalPermissions"] = normalize_permissions(value.get("additionalPermissions"))
        context = value.get("networkApprovalContext")
        if isinstance(context, dict):
            result["networkApprovalContext"] = {
                "host": bounded_text(context.get("host"), 500),
                "protocol": bounded_text(context.get("protocol"), 40),
            }
    elif method == FILE_METHOD:
        if text := bounded_text(value.get("grantRoot")):
            result["grantRoot"] = text
        decisions = []
        for decision in bounded_list(value.get("availableDecisions"), 20):
            if decision_type(decision) in DECISION_LABELS:
                decisions.append(copy.deepcopy(decision))
        if isinstance(value.get("availableDecisions"), list):
            result["availableDecisions"] = decisions
    elif method == PERMISSION_METHOD:
        if text := bounded_text(value.get("cwd")):
            result["cwd"] = text
        result["permissions"] = normalize_permissions(value.get("permissions"))
    return result


def _safe_path(value: Any) -> str | None:
    return bounded_text(value, MAX_TEXT)


def _safe_fs_path(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    kind = value.get("type")
    if kind == "path" and (path := _safe_path(value.get("path"))):
        return {"type": "path", "path": path}
    if kind == "glob_pattern" and (pattern := bounded_text(value.get("pattern"))):
        return {"type": "glob_pattern", "pattern": pattern}
    if kind == "special" and isinstance(value.get("value"), dict):
        special = value["value"]
        result: dict[str, Any] = {"kind": bounded_text(special.get("kind"), 80) or "unknown"}
        for key in ("path", "subpath"):
            if text := _safe_path(special.get(key)):
                result[key] = text
        return {"type": "special", "value": result}
    return None


def normalize_permissions(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, Any] = {}
    filesystem = value.get("fileSystem")
    if isinstance(filesystem, dict):
        projected: dict[str, Any] = {}
        for key in ("read", "write"):
            paths = [path for raw in bounded_list(filesystem.get(key)) if (path := _safe_path(raw))]
            if paths:
                projected[key] = paths
        entries = []
        for raw in bounded_list(filesystem.get("entries")):
            if not isinstance(raw, dict) or raw.get("access") not in ("read", "write", "deny"):
                continue
            path = _safe_fs_path(raw.get("path"))
            if path:
                entries.append({"access": raw["access"], "path": path})
        if entries:
            projected["entries"] = entries
        depth = filesystem.get("globScanMaxDepth")
        if isinstance(depth, int) and not isinstance(depth, bool) and depth > 0:
            projected["globScanMaxDepth"] = depth
        if projected:
            result["fileSystem"] = projected
    network = value.get("network")
    if isinstance(network, dict) and isinstance(network.get("enabled"), bool):
        result["network"] = {"enabled": network["enabled"]}
    return result


def _permission_subset(requested: Any, granted: Any) -> bool:
    """Structural subset check that never treats omitted values as grants."""
    if isinstance(granted, dict):
        if not isinstance(requested, dict):
            return False
        return all(key in requested and _permission_subset(requested[key], value) for key, value in granted.items())
    if isinstance(granted, list):
        if not isinstance(requested, list):
            return False
        requested_encodings = {json.dumps(item, sort_keys=True, separators=(",", ":")) for item in requested}
        return all(json.dumps(item, sort_keys=True, separators=(",", ":")) in requested_encodings for item in granted)
    return granted == requested


def _decision_projection(raw: Any, index: int) -> dict[str, Any] | None:
    kind = decision_type(raw)
    if kind not in DECISION_LABELS:
        return None
    result: dict[str, Any] = {
        "type": kind,
        "label": DECISION_LABELS[kind],
        "optionId": f"decision-{index}",
    }
    if kind == "acceptWithExecpolicyAmendment" and isinstance(raw, dict):
        amendment = raw.get(kind, {}).get("execpolicy_amendment")
        if isinstance(amendment, list):
            result["amendment"] = [bounded_text(part, 500) for part in amendment[:20] if isinstance(part, str)]
    if kind == "applyNetworkPolicyAmendment" and isinstance(raw, dict):
        amendment = raw.get(kind, {}).get("network_policy_amendment")
        if isinstance(amendment, dict):
            result["networkAmendment"] = {
                "host": bounded_text(amendment.get("host"), 500),
                "action": amendment.get("action") if amendment.get("action") in ("allow", "deny") else None,
            }
    return result


def _file_changes(item: Any) -> list[dict[str, Any]]:
    if not isinstance(item, dict) or item.get("type") != "fileChange":
        return []
    result = []
    for raw in bounded_list(item.get("changes")):
        if not isinstance(raw, dict) or not (path := _safe_path(raw.get("path"))):
            continue
        change: dict[str, Any] = {"path": path, "kind": bounded_text(raw.get("kind"), 80) or "update"}
        diff = bounded_text(raw.get("diff"), MAX_DIFF_SUMMARY)
        if diff:
            lines = diff.splitlines()
            change["summary"] = {
                "addedLines": sum(1 for line in lines if line.startswith("+") and not line.startswith("+++")),
                "removedLines": sum(1 for line in lines if line.startswith("-") and not line.startswith("---")),
            }
        result.append(change)
    return result


@dataclass
class PendingApproval:
    upstream_id: Any
    method: str
    params: dict[str, Any]
    item: dict[str, Any] | None = None
    connection: Any = field(default=None, repr=False)
    id: str = field(default_factory=lambda: f"apr_{uuid.uuid4().hex}")
    created_at: float = field(default_factory=time.time)
    status: str = "pending"
    resolution: str | None = None
    lock: Any = field(default=None, repr=False)

    def __post_init__(self) -> None:
        # Avoid importing asyncio until objects are made inside a running loop.
        import asyncio

        self.lock = asyncio.Lock()

    @property
    def thread_id(self) -> str:
        return self.params.get("threadId", "")

    @property
    def turn_id(self) -> str:
        return self.params.get("turnId", "")

    @property
    def item_id(self) -> str:
        return self.params.get("itemId", "")

    @property
    def request_type(self) -> str:
        return {
            COMMAND_METHOD: "command",
            FILE_METHOD: "fileChange",
            PERMISSION_METHOD: "permission",
            USER_INPUT_METHOD: "unsupportedInput",
            MCP_ELICITATION_METHOD: "unsupportedForm",
        }[self.method]

    def decisions(self) -> tuple[list[dict[str, Any]], list[Any]]:
        if self.method in (COMMAND_METHOD, FILE_METHOD):
            supplied = self.params.get("availableDecisions")
            raw = supplied if isinstance(supplied, list) else (
                LEGACY_COMMAND_DECISIONS if self.method == COMMAND_METHOD else LEGACY_FILE_DECISIONS
            )
            upstream: list[Any] = []
            projected: list[dict[str, Any]] = []
            for value in raw[:20]:
                if (option := _decision_projection(value, len(upstream))) is not None:
                    upstream.append(copy.deepcopy(value))
                    option["optionId"] = f"decision-{len(upstream) - 1}"
                    projected.append(option)
            return projected, upstream
        if self.method == PERMISSION_METHOD:
            return [
                {"type": "grant", "label": "Grant selected", "scopes": ["turn", "session"]},
                {"type": "deny", "label": "Deny all"},
            ], []
        if self.method == MCP_ELICITATION_METHOD:
            return [
                {"type": "decline", "label": "Decline"},
                {"type": "cancel", "label": "Cancel turn"},
            ], []
        return [], []

    def projection(self) -> dict[str, Any]:
        decisions, _ = self.decisions()
        params = self.params
        result: dict[str, Any] = {
            "id": self.id,
            "sessionId": self.thread_id,
            "turnId": self.turn_id or None,
            "itemId": self.item_id or None,
            "type": self.request_type,
            "createdAt": int(self.created_at),
            "startedAt": params.get("startedAtMs") if isinstance(params.get("startedAtMs"), int) else None,
            "reason": bounded_text(params.get("reason")),
            "status": self.status,
            "resolution": self.resolution,
            "availableDecisions": decisions,
        }
        if self.method == COMMAND_METHOD:
            result.update({
                "title": "Approval required",
                "command": bounded_text(params.get("command"), MAX_COMMAND),
                "cwd": _safe_path(params.get("cwd")),
                "commandActions": [
                    {key: bounded_text(value, 1_000) for key, value in raw.items() if key in {"type", "command", "name", "path", "query"} and isinstance(value, str)}
                    for raw in bounded_list(params.get("commandActions"), 20) if isinstance(raw, dict)
                ],
                "requestedPermissions": normalize_permissions(params.get("additionalPermissions")),
            })
            context = params.get("networkApprovalContext")
            if isinstance(context, dict):
                result["networkContext"] = {
                    "host": bounded_text(context.get("host"), 500),
                    "protocol": bounded_text(context.get("protocol"), 40),
                }
        elif self.method == FILE_METHOD:
            changes = _file_changes(self.item)
            result.update({
                "title": "File changes require approval",
                "fileChanges": changes,
                "fileCount": len(changes),
                "grantRoot": _safe_path(params.get("grantRoot")),
            })
        elif self.method == PERMISSION_METHOD:
            result.update({
                "title": "Permissions requested",
                "cwd": _safe_path(params.get("cwd")),
                "requestedPermissions": normalize_permissions(params.get("permissions")),
                "availableScopes": ["turn", "session"],
            })
        else:
            result.update({
                "title": "User input required",
                "unsupportedMessage": "This request type is not yet supported in Foreman.",
            })
        return result

    def response_result(self, response: Any) -> tuple[dict[str, Any], str]:
        if not isinstance(response, dict):
            raise ApprovalError("decision must be an object")
        kind = response.get("type")
        if self.method in (COMMAND_METHOD, FILE_METHOD):
            projected, upstream = self.decisions()
            option_id = response.get("optionId")
            matches = [
                (option, raw) for option, raw in zip(projected, upstream)
                if option["type"] == kind and (option_id is None or option["optionId"] == option_id)
            ]
            if len(matches) != 1:
                raise ApprovalError("decision is not available for this approval")
            return {"decision": copy.deepcopy(matches[0][1])}, str(kind)
        if self.method == PERMISSION_METHOD:
            if kind == "deny":
                return {"permissions": {}, "scope": "turn"}, "deny"
            if kind != "grant":
                raise ApprovalError("decision is not available for this approval")
            scope = response.get("scope", "turn")
            if scope not in ("turn", "session"):
                raise ApprovalError("permission scope is unavailable")
            granted = normalize_permissions(response.get("permissions"))
            requested = normalize_permissions(self.params.get("permissions"))
            if not granted:
                raise ApprovalError("select at least one requested permission or deny all")
            if not _permission_subset(requested, granted):
                raise ApprovalError("granted permissions must be a subset of the request")
            return {"permissions": granted, "scope": scope}, "grant"
        if self.method == MCP_ELICITATION_METHOD and kind in ("decline", "cancel"):
            return {"action": kind, "content": None}, str(kind)
        raise ApprovalError("this request cannot be answered in Foreman")
