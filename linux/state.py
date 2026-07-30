"""Minimal file-backed pairing and device-token state."""

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
