#!/usr/bin/env python3
"""Foreman: a small authenticated TCP bridge to local Codex sessions."""

from __future__ import annotations

import argparse
import asyncio
from collections import deque
import os
import signal
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from codex import Codex, CodexError, normalize_event, session
from protocol import MAX_FRAME_BYTES, VERSION, ProtocolError, encode, error, read, response
from state import State


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
    writer: asyncio.StreamWriter
    peer: str
    authenticated: bool = False
    subscriptions: set[str] = field(default_factory=set)
    write_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def send(self, message: dict[str, Any]) -> None:
        async with self.write_lock:
            self.writer.write(encode(message))
            await self.writer.drain()


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
    ) -> None:
        self.host = host
        self.port = port
        self.repository_root = repository_root.resolve()
        self.state = state
        self.clients: set[Client] = set()
        self.pairing_limiter = PairingLimiter()
        self.codex = codex_factory(codex_executable, self.codex_event)
        self.server: asyncio.Server | None = None

    async def start(self) -> None:
        await self.codex.start()
        self.server = await asyncio.start_server(
            self.client_connected,
            self.host,
            self.port,
            limit=MAX_FRAME_BYTES + 1,
        )

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()
        for client in list(self.clients):
            client.writer.close()
        await self.codex.stop()

    async def codex_event(self, message: dict[str, Any]) -> None:
        thread_id, event = normalize_event(message)
        if not thread_id:
            return
        outgoing = {
            "version": VERSION,
            "type": "session.event",
            "payload": {"sessionId": thread_id, "event": event},
        }
        targets = [
            client
            for client in self.clients
            if client.authenticated and thread_id in client.subscriptions
        ]
        await asyncio.gather(
            *(client.send(outgoing) for client in targets), return_exceptions=True
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
                try:
                    result = await self.dispatch(client, request)
                    await client.send(response(request, result))
                except PermissionError as exc:
                    await client.send(error(request, "unauthorized", str(exc)))
                except (ValueError, CodexError) as exc:
                    await client.send(error(request, "requestFailed", str(exc)))
                except Exception as exc:
                    print(f"request error: {exc}", flush=True)
                    await client.send(error(request, "internalError", "request failed"))
        except ProtocolError as exc:
            try:
                await client.send(error(request, "protocolError", str(exc)))
            except Exception:
                pass
        finally:
            self.clients.discard(client)
            try:
                writer.close()
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass

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
                "capabilities": {
                    "steer": True,
                    "interrupt": True,
                    "archive": True,
                    "delete": True,
                    "approvals": False,
                    "structuredInput": False,
                },
            }
        if message_type == "pair":
            if not self.pairing_limiter.allowed(client.peer):
                raise PermissionError("too many pairing attempts; try again later")
            key = required_text(payload, "pairingKey", 100)
            name = required_text(payload, "deviceName", 80)
            token = self.state.pair(key, name)
            if not token:
                self.pairing_limiter.failed(client.peer)
                raise PermissionError("pairing key is invalid or expired")
            self.pairing_limiter.succeeded(client.peer)
            client.authenticated = True
            return {"deviceToken": token}
        if message_type == "authenticate":
            token = required_text(payload, "deviceToken", 200)
            client.authenticated = self.state.authenticate(token)
            if not client.authenticated:
                raise PermissionError("device token is invalid")
            return {"authenticated": True}
        if message_type == "ping":
            return {"time": int(time.time())}
        if not client.authenticated:
            raise PermissionError("authenticate first")

        if message_type == "repository.list":
            return {"repositories": await asyncio.to_thread(self.repositories)}
        if message_type == "session.list":
            return {
                "sessions": [session(item) for item in await self.codex.list_threads()]
            }
        if message_type == "session.read":
            thread_id = required_text(payload, "sessionId", 100)
            return {"session": session(await self.codex.read_thread(thread_id), True)}
        if message_type == "session.start":
            repository_id = required_text(payload, "repositoryId", 500)
            repository = self.resolve_repository(repository_id)
            thread = await self.codex.start_thread(str(repository))
            client.subscriptions.add(thread["id"])
            return {"session": session(thread)}
        if message_type == "session.resume":
            thread_id = required_text(payload, "sessionId", 100)
            thread = await self.codex.resume_thread(thread_id)
            return {"session": session(thread, True)}
        if message_type == "session.subscribe":
            thread_id = required_text(payload, "sessionId", 100)
            client.subscriptions.add(thread_id)
            return {"subscribed": True}
        if message_type == "session.archive":
            thread_id = required_text(payload, "sessionId", 100)
            await self.require_inactive_session(thread_id)
            await self.codex.archive_thread(thread_id)
            self.discard_subscriptions(thread_id)
            return {"archived": True}
        if message_type == "session.delete":
            thread_id = required_text(payload, "sessionId", 100)
            if payload.get("confirm") is not True:
                raise ValueError("permanent deletion requires confirm=true")
            await self.require_inactive_session(thread_id)
            await self.codex.delete_thread(thread_id)
            self.discard_subscriptions(thread_id)
            return {"deleted": True}
        if message_type == "turn.prompt":
            thread_id = required_text(payload, "sessionId", 100)
            text = required_text(payload, "text", 100_000)
            result = await self.codex.prompt(thread_id, text)
            client.subscriptions.add(thread_id)
            return {"accepted": True, "turnId": result["turn"]["id"]}
        if message_type == "turn.steer":
            thread_id = required_text(payload, "sessionId", 100)
            turn_id = required_text(payload, "turnId", 100)
            text = required_text(payload, "text", 100_000)
            result = await self.codex.steer(thread_id, turn_id, text)
            return {"accepted": True, "turnId": result["turnId"]}
        if message_type == "turn.interrupt":
            thread_id = required_text(payload, "sessionId", 100)
            turn_id = required_text(payload, "turnId", 100)
            await self.codex.interrupt(thread_id, turn_id)
            return {"accepted": True}
        raise ValueError(f"unknown message type: {message_type}")

    async def require_inactive_session(self, thread_id: str) -> None:
        projected = session(await self.codex.read_thread(thread_id))
        if projected["status"] in ("working", "waiting"):
            raise ValueError("session is active; interrupt it before archive or delete")

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
    )
    await app.start()
    sockets = ", ".join(str(sock.getsockname()) for sock in app.server.sockets or [])
    print(f"Foreman listening on {sockets}", flush=True)
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
    parser.add_argument("--host", default=os.environ.get("FOREMAN_HOST", "0.0.0.0"))
    parser.add_argument(
        "--port", type=int, default=int(os.environ.get("FOREMAN_PORT", "8765"))
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
    if args.create_pairing:
        key, expires_at = State(args.state_directory).create_pairing()
        print(f"Pairing key: {key}")
        print("Expires: 10 minutes")
        return
    asyncio.run(run_service(args))


if __name__ == "__main__":
    main()
