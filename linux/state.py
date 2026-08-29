"""Minimal file-backed pairing, device-token, and bounded session state."""

from __future__ import annotations

import base64
import fcntl
import hashlib
import hmac
import json
import os
import secrets
import time
from pathlib import Path
from typing import Any


def _token(prefix: str, byte_count: int = 24) -> str:
    value = base64.urlsafe_b64encode(secrets.token_bytes(byte_count)).decode().rstrip("=")
    return prefix + value


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class State:
    def __init__(self, directory: str | Path) -> None:
        self.directory = Path(directory).expanduser()
        self.path = self.directory / "state.json"
        self.lock_path = self.directory / "state.lock"

    def _locked(self, update) -> Any:
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.lock_path.open("a+", encoding="utf-8") as lock:
            os.chmod(self.lock_path, 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX)
            data = {"pairings": [], "devices": []}
            if self.path.exists():
                try:
                    loaded = json.loads(self.path.read_text(encoding="utf-8"))
                    if isinstance(loaded, dict):
                        data.update(loaded)
                except (OSError, json.JSONDecodeError):
                    pass
            result = update(data)
            temporary = self.path.with_suffix(".tmp")
            temporary.write_text(
                json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8"
            )
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.path)
            return result

    def create_pairing(self, lifetime_seconds: int = 600) -> tuple[str, int]:
        expires_at = int(time.time()) + lifetime_seconds

        def update(data: dict[str, Any]) -> str:
            now = int(time.time())
            data["pairings"] = [
                item for item in data.get("pairings", []) if item.get("expiresAt", 0) > now
            ]
            existing = {item.get("digest") for item in data["pairings"]}
            while True:
                key = f"{secrets.randbelow(1_000_000):06d}"
                if _digest(key) not in existing:
                    break
            data["pairings"].append(
                {"digest": _digest(key), "expiresAt": expires_at}
            )
            return key

        key = self._locked(update)
        return key, expires_at

    def session_token_usage(self) -> dict[str, dict[str, Any]]:
        def update(data: dict[str, Any]) -> dict[str, dict[str, Any]]:
            stored = data.get("sessionTokenUsage")
            if not isinstance(stored, dict):
                data["sessionTokenUsage"] = {}
                return {}
            valid = [
                (thread_id, item)
                for thread_id, item in stored.items()
                if isinstance(thread_id, str)
                and len(thread_id) <= 100
                and isinstance(item, dict)
                and isinstance(item.get("usage"), dict)
            ]

            def updated_at(pair: tuple[str, dict[str, Any]]) -> float:
                value = pair[1].get("updatedAt", 0)
                return (
                    value
                    if isinstance(value, (int, float))
                    and not isinstance(value, bool)
                    else 0
                )

            valid.sort(key=updated_at, reverse=True)
            bounded = dict(valid[:500])
            data["sessionTokenUsage"] = bounded
            return {
                thread_id: item["usage"] for thread_id, item in bounded.items()
            }

        return self._locked(update)

    def remember_session_token_usage(
        self, thread_id: str, usage: dict[str, Any]
    ) -> None:
        if not thread_id or len(thread_id) > 100:
            return

        def update(data: dict[str, Any]) -> None:
            stored = data.setdefault("sessionTokenUsage", {})
            if not isinstance(stored, dict):
                stored = {}
                data["sessionTokenUsage"] = stored
            stored[thread_id] = {"usage": usage, "updatedAt": int(time.time())}
            if len(stored) > 500:
                def updated_at(key: str) -> float:
                    item = stored.get(key)
                    value = item.get("updatedAt", 0) if isinstance(item, dict) else 0
                    return (
                        value
                        if isinstance(value, (int, float))
                        and not isinstance(value, bool)
                        else 0
                    )

                oldest = sorted(
                    stored,
                    key=updated_at,
                )[: len(stored) - 500]
                for key in oldest:
                    stored.pop(key, None)

        self._locked(update)

    def forget_session_token_usage(self, thread_id: str) -> None:
        if not thread_id or len(thread_id) > 100:
            return

        def update(data: dict[str, Any]) -> None:
            stored = data.get("sessionTokenUsage")
            if isinstance(stored, dict):
                stored.pop(thread_id, None)

        self._locked(update)

    def session_settings(self, provider: str) -> dict[str, dict[str, Any]]:
        if provider not in {"codex", "claude-code"}:
            return {}

        def update(data: dict[str, Any]) -> dict[str, dict[str, Any]]:
            stored = data.get("sessionSettings")
            by_provider = stored.get(provider) if isinstance(stored, dict) else None
            if not isinstance(by_provider, dict):
                if not isinstance(stored, dict):
                    stored = {}
                    data["sessionSettings"] = stored
                stored[provider] = {}
                return {}
            valid_fields = (
                {"model", "reasoningEffort", "accessLevel"}
                if provider == "codex"
                else {"model", "permissionMode"}
            )
            valid: list[tuple[str, dict[str, Any]]] = []
            for session_id, item in by_provider.items():
                if (
                    not isinstance(session_id, str)
                    or not session_id
                    or len(session_id) > 160
                    or not isinstance(item, dict)
                ):
                    continue
                settings = {
                    key: value
                    for key, value in item.items()
                    if key in valid_fields and isinstance(value, str) and value
                }
                revision = item.get("settingsRevision")
                updated_at = item.get("updatedAt")
                if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
                    revision = 1
                settings["settingsRevision"] = revision
                settings["updatedAt"] = (
                    updated_at
                    if isinstance(updated_at, (int, float)) and not isinstance(updated_at, bool)
                    else 0
                )
                valid.append((session_id, settings))
            valid.sort(key=lambda pair: pair[1]["updatedAt"], reverse=True)
            bounded = dict(valid[:500])
            data["sessionSettings"][provider] = bounded
            return {
                session_id: {
                    key: value for key, value in item.items() if key != "updatedAt"
                }
                for session_id, item in bounded.items()
            }

        return self._locked(update)

    def remember_session_settings(
        self, provider: str, session_id: str, settings: dict[str, Any]
    ) -> dict[str, Any]:
        if provider not in {"codex", "claude-code"}:
            raise ValueError("provider is unsupported")
        if not session_id or len(session_id) > 160:
            raise ValueError("session ID is invalid")
        valid_fields = (
            {"model", "reasoningEffort", "accessLevel"}
            if provider == "codex"
            else {"model", "permissionMode"}
        )
        incoming = {
            key: value
            for key, value in settings.items()
            if key in valid_fields and isinstance(value, str) and value
        }

        def update(data: dict[str, Any]) -> dict[str, Any]:
            stored = data.setdefault("sessionSettings", {})
            if not isinstance(stored, dict):
                stored = {}
                data["sessionSettings"] = stored
            by_provider = stored.setdefault(provider, {})
            if not isinstance(by_provider, dict):
                by_provider = {}
                stored[provider] = by_provider
            previous = by_provider.get(session_id)
            if not isinstance(previous, dict):
                previous = {}
            merged = {
                key: value
                for key, value in previous.items()
                if key in valid_fields and isinstance(value, str) and value
            }
            changed = any(merged.get(key) != value for key, value in incoming.items())
            merged.update(incoming)
            previous_revision = previous.get("settingsRevision", 0)
            if not isinstance(previous_revision, int) or isinstance(previous_revision, bool):
                previous_revision = 0
            revision = max(1, previous_revision + (1 if changed else 0))
            stored_item = {
                **merged,
                "settingsRevision": revision,
                "updatedAt": int(time.time()),
            }
            by_provider[session_id] = stored_item
            if len(by_provider) > 500:
                oldest = sorted(
                    by_provider,
                    key=lambda key: by_provider[key].get("updatedAt", 0)
                    if isinstance(by_provider.get(key), dict)
                    else 0,
                )[: len(by_provider) - 500]
                for key in oldest:
                    by_provider.pop(key, None)
            return {key: value for key, value in stored_item.items() if key != "updatedAt"}

        return self._locked(update)

    def forget_session_settings(self, provider: str, session_id: str) -> None:
        if provider not in {"codex", "claude-code"} or not session_id:
            return

        def update(data: dict[str, Any]) -> None:
            stored = data.get("sessionSettings")
            by_provider = stored.get(provider) if isinstance(stored, dict) else None
            if isinstance(by_provider, dict):
                by_provider.pop(session_id, None)

        self._locked(update)

    def provider_account_usage(self, provider: str) -> dict[str, Any] | None:
        if provider not in {"codex", "claude-code"}:
            return None

        def update(data: dict[str, Any]) -> dict[str, Any] | None:
            stored = data.get("providerAccountUsage")
            value = stored.get(provider) if isinstance(stored, dict) else None
            return value if isinstance(value, dict) else None

        return self._locked(update)

    def provider_enabled(self, provider: str) -> bool:
        if provider not in {"codex", "claude-code"}:
            return False

        def update(data: dict[str, Any]) -> bool:
            stored = data.get("providerEnabled")
            if not isinstance(stored, dict):
                data["providerEnabled"] = {}
                return True
            value = stored.get(provider)
            return value if isinstance(value, bool) else True

        return self._locked(update)

    def set_provider_enabled(self, provider: str, enabled: bool) -> None:
        if provider not in {"codex", "claude-code"}:
            return

        def update(data: dict[str, Any]) -> None:
            stored = data.setdefault("providerEnabled", {})
            if not isinstance(stored, dict):
                stored = {}
                data["providerEnabled"] = stored
            stored[provider] = enabled

        self._locked(update)

    def remember_provider_account_usage(
        self, provider: str, usage: dict[str, Any]
    ) -> None:
        if provider not in {"codex", "claude-code"}:
            return

        def update(data: dict[str, Any]) -> None:
            stored = data.setdefault("providerAccountUsage", {})
            if not isinstance(stored, dict):
                stored = {}
                data["providerAccountUsage"] = stored
            stored[provider] = usage

        self._locked(update)

    def active_pairing_count(self) -> int:
        def update(data: dict[str, Any]) -> int:
            now = int(time.time())
            data["pairings"] = [
                item
                for item in data.get("pairings", [])
                if item.get("expiresAt", 0) > now
            ]
            return len(data["pairings"])

        return self._locked(update)

    def pair(
        self, key: str, device_name: str, device_type: str = "unknown"
    ) -> str | None:
        key_digest = _digest(key)
        device_token = _token("fmt_", 32)

        def update(data: dict[str, Any]) -> str | None:
            now = int(time.time())
            pairings = data.get("pairings", [])
            match = next(
                (
                    item
                    for item in pairings
                    if item.get("expiresAt", 0) > now
                    and hmac.compare_digest(item.get("digest", ""), key_digest)
                ),
                None,
            )
            data["pairings"] = [
                item
                for item in pairings
                if item is not match and item.get("expiresAt", 0) > now
            ]
            if match is None:
                return None
            data.setdefault("devices", []).append(
                {
                    "id": _token("fmc_", 12),
                    "digest": _digest(device_token),
                    "name": device_name[:80],
                    "type": device_type if device_type in ("browser", "android") else "unknown",
                    "createdAt": now,
                }
            )
            return device_token

        return self._locked(update)

    def authenticate(self, token: str) -> bool:
        return self.authenticate_device(token) is not None

    def authenticate_device(self, token: str) -> dict[str, Any] | None:
        candidate = _digest(token)

        def update(data: dict[str, Any]) -> dict[str, Any] | None:
            item = next(
                (
                    device
                    for device in data.get("devices", [])
                    if hmac.compare_digest(device.get("digest", ""), candidate)
                ),
                None,
            )
            if item is None:
                return None
            self._ensure_device_id(item)
            return self._public_device(item)

        return self._locked(update)

    def list_devices(self) -> list[dict[str, Any]]:
        def update(data: dict[str, Any]) -> list[dict[str, Any]]:
            devices = data.get("devices", [])
            for item in devices:
                self._ensure_device_id(item)
            return [self._public_device(item) for item in devices]

        return self._locked(update)

    def revoke_device(self, device_id: str) -> bool:
        def update(data: dict[str, Any]) -> bool:
            devices = data.get("devices", [])
            for item in devices:
                self._ensure_device_id(item)
            remaining = [item for item in devices if item.get("id") != device_id]
            data["devices"] = remaining
            return len(remaining) != len(devices)

        return bool(self._locked(update))

    @staticmethod
    def _ensure_device_id(item: dict[str, Any]) -> None:
        if not isinstance(item.get("id"), str) or not item["id"]:
            item["id"] = _token("fmc_", 12)

    @staticmethod
    def _public_device(item: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": item["id"],
            "name": item.get("name") if isinstance(item.get("name"), str) else "Foreman client",
            "type": item.get("type") if item.get("type") in ("browser", "android") else "unknown",
            "createdAt": item.get("createdAt") if isinstance(item.get("createdAt"), int) else None,
        }
