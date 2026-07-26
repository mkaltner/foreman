"""Foreman's tiny versioned newline-delimited JSON protocol."""

from __future__ import annotations

import asyncio
import json
from typing import Any

VERSION = 1
MAX_FRAME_BYTES = 1024 * 1024


class ProtocolError(ValueError):
    pass


def encode(message: dict[str, Any]) -> bytes:
    data = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode()
    if len(data) > MAX_FRAME_BYTES:
        raise ProtocolError("frame is too large")
    return data + b"\n"


def decode(data: bytes) -> dict[str, Any]:
    if not data.endswith(b"\n"):
        raise ProtocolError("incomplete frame")
    if len(data) - 1 > MAX_FRAME_BYTES:
        raise ProtocolError("frame is too large")
    try:
        value = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProtocolError("invalid JSON") from error
    if not isinstance(value, dict):
        raise ProtocolError("frame must be a JSON object")
    if value.get("version") != VERSION:
        raise ProtocolError("unsupported protocol version")
    if not isinstance(value.get("type"), str):
        raise ProtocolError("message type is required")
    return value


async def read(reader: asyncio.StreamReader) -> dict[str, Any] | None:
    try:
        data = await reader.readline()
    except ValueError as error:
        raise ProtocolError("frame is too large") from error
    if not data:
        return None
    return decode(data)


def response(request: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "version": VERSION,
        "id": request.get("id"),
        "type": f"{request['type']}.result",
        "payload": payload,
    }


def error(
    request: dict[str, Any] | None, code: str, message: str
) -> dict[str, Any]:
    return {
        "version": VERSION,
        "id": request.get("id") if request else None,
        "type": "error",
        "payload": {"code": code, "message": message},
    }
