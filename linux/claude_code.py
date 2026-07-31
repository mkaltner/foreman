"""Optional Linux lifecycle adapter for the Foreman Claude Code bridge."""

from __future__ import annotations

import asyncio
import inspect
import json
import os
from pathlib import Path
import re
import shutil
import sys
from typing import Any, Awaitable, Callable


MAX_BRIDGE_MESSAGE_BYTES = 256 * 1024
BRIDGE_PROTOCOL_VERSION = 1
RESTART_DELAYS = (0.1, 0.5, 1.0, 2.0, 5.0)
TERMINAL_EVENTS = {
    "query.completed",
    "query.failed",
    "query.interrupted",
}
SUPPORTED_MODELS = [
    {
        "id": "sonnet",
        "displayName": "Sonnet",
        "description": "Adapter-supported Claude Sonnet alias",
    },
    {
        "id": "haiku",
        "displayName": "Haiku",
        "description": "Adapter-supported Claude Haiku alias",
    },
]


class ClaudeCodeError(RuntimeError):
    pass


def _safe_error(value: object) -> str:
    text = str(value).replace("\n", " ").replace("\r", " ")[:400]
    text = re.sub(r"(?:sk-ant-|Bearer\s+)[A-Za-z0-9._-]+", "[credential]", text, flags=re.I)
    text = re.sub(
        r"(token|api[_-]?key|authorization)\s*[=:]\s*\S+",
        r"\1=[redacted]",
        text,
        flags=re.I,
    )
    return text or "Claude Code bridge failed"


def unavailable_status(limitation: str) -> dict[str, Any]:
    return {
        "provider": "claude-code",
        "installed": False,
        "cliVersion": None,
        "sdkVersion": None,
        "nodeVersion": None,
        "available": False,
        "permissionModes": [
            "default",
            "dontAsk",
            "acceptEdits",
            "plan",
            "auto",
            "bypassPermissions",
        ],
        "models": [dict(model) for model in SUPPORTED_MODELS],
        "modelSelection": True,
        "limitation": limitation,
        "capabilities": {
            "discover": False,
            "start": False,
            "resume": False,
            "stream": False,
            "delete": False,
            "interruptManaged": False,
            "liveAttachExternal": False,
            "approveExternal": False,
            "interruptExternal": False,
            "remoteControl": False,
        },
    }


class ClaudeCode:
    """One optional Node bridge process per Foreman service."""

    def __init__(
        self,
        repository_root: str | Path,
        state_path: str | Path,
        on_event: Callable[[dict[str, Any]], Awaitable[None] | None] | None = None,
        node_executable: str = "node",
        bridge_path: str | Path | None = None,
        env: dict[str, str] | None = None,
        restart_delays: tuple[float, ...] = RESTART_DELAYS,
        query_timeout: float = 30,
    ) -> None:
        if query_timeout <= 0:
            raise ValueError("query_timeout must be positive")
        self.repository_root = Path(repository_root).expanduser().resolve()
        self.state_path = Path(state_path).expanduser().resolve()
        self.on_event = on_event or (lambda _event: None)
        self.node_executable = node_executable
        self.bridge_path = Path(bridge_path or Path(__file__).parent / "claude_bridge" / "bridge.mjs").resolve()
        self.env = dict(env or os.environ)
        self.restart_delays = restart_delays
        self.query_timeout = query_timeout
        self.process: asyncio.subprocess.Process | None = None
        self.reader_task: asyncio.Task[None] | None = None
        self.stderr_task: asyncio.Task[None] | None = None
        self.restart_task: asyncio.Task[None] | None = None
        self.pending: dict[str, asyncio.Future[Any]] = {}
        self.pending_approvals: dict[str, dict[str, Any]] = {}
        self.request_number = 0
        self.spawn_lock = asyncio.Lock()
        self.write_lock = asyncio.Lock()
        self.stopping = False
        self.desired = False
        self.restart_attempt = 0
        self.last_stderr: str | None = None
        self.runtime_status = unavailable_status("Claude Code support has not been started.")

    async def start(self) -> dict[str, Any]:
        self.desired = True
        self.stopping = False
        if sys.platform != "linux":
            self.runtime_status = unavailable_status("The Foreman Claude Code adapter is Linux-only.")
            return self.runtime_status
        claude_executable = self.env.get("FOREMAN_CLAUDE_EXECUTABLE", "claude")
        if shutil.which(claude_executable, path=self.env.get("PATH")) is None:
            self.runtime_status = unavailable_status("The native claude executable is unavailable.")
            return self.runtime_status
        executable = shutil.which(self.node_executable, path=self.env.get("PATH"))
        if executable is None:
            self.runtime_status = unavailable_status("Node.js 20 or newer is required for optional Claude Code support.")
            return self.runtime_status
        if not self.bridge_path.is_file():
            self.runtime_status = unavailable_status("The Claude Code bridge entry point is missing.")
            return self.runtime_status
        try:
            await self._spawn(executable)
            self.runtime_status = await self._request("status", timeout=15)
            self.restart_attempt = 0
        except Exception as error:
            self.runtime_status = unavailable_status(_safe_error(error))
            await self._terminate_process()
            if self.desired and not self.stopping and self.restart_attempt < len(self.restart_delays):
                delay = self.restart_delays[self.restart_attempt]
                self.restart_attempt += 1
                self.restart_task = asyncio.create_task(self._restart_after(delay))
        return self.runtime_status

    async def stop(self) -> None:
        self.desired = False
        self.stopping = True
        if self.restart_task:
            self.restart_task.cancel()
            await asyncio.gather(self.restart_task, return_exceptions=True)
            self.restart_task = None
        if self.process and self.process.returncode is None:
            try:
                await self._request("shutdown", timeout=3, ensure=False)
                await asyncio.wait_for(self.process.wait(), timeout=3)
            except (ClaudeCodeError, TimeoutError, BrokenPipeError, ConnectionError):
                await self._terminate_process()
        await self._terminate_process()
        self.pending_approvals.clear()

    async def status(self) -> dict[str, Any]:
        if self.process and self.process.returncode is None:
            try:
                self.runtime_status = await self._request("status", timeout=15)
            except ClaudeCodeError as error:
                self.runtime_status = unavailable_status(_safe_error(error))
        return self.runtime_status

    async def discover(self, cwd: str | Path) -> list[dict[str, Any]]:
        directory = self._cwd(cwd)
        return await self._request("discover", {"cwd": str(directory)}, timeout=30)

    async def read_session(
        self, session_id: str, cwd: str | Path
    ) -> dict[str, Any]:
        directory = self._cwd(cwd)
        return await self._request(
            "read",
            {"sessionId": session_id, "cwd": str(directory)},
            timeout=30,
        )

    async def start_session(
        self,
        cwd: str | Path,
        prompt: str,
        model: str | None = None,
        permission_mode: str = "default",
    ) -> dict[str, str]:
        params = self._query_params(cwd, prompt, model, permission_mode)
        return await self._request("start", params, timeout=self.query_timeout)

    async def resume_session(
        self,
        session_id: str,
        cwd: str | Path,
        prompt: str,
        model: str | None = None,
        permission_mode: str = "default",
    ) -> dict[str, str]:
        params = self._query_params(cwd, prompt, model, permission_mode)
        params["sessionId"] = session_id
        return await self._request("resume", params, timeout=self.query_timeout)

    async def interrupt(self, session_id: str) -> dict[str, Any]:
        return await self._request("interrupt", {"sessionId": session_id}, timeout=15)

    async def delete_session(
        self, session_id: str, cwd: str | Path
    ) -> dict[str, Any]:
        directory = self._cwd(cwd)
        return await self._request(
            "delete",
            {"sessionId": session_id, "cwd": str(directory)},
            timeout=30,
        )

    async def answer_approval(self, request_id: str, allow: bool) -> dict[str, Any]:
        result = await self._request(
            "approval",
            {"requestId": request_id, "decision": "allow" if allow else "deny"},
            timeout=15,
        )
        self.pending_approvals.pop(request_id, None)
        return result

    async def attach_external(self, session_id: str) -> None:
        await self._request("attachExternal", {"sessionId": session_id}, timeout=15)

    def _query_params(
        self,
        cwd: str | Path,
        prompt: str,
        model: str | None,
        permission_mode: str,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "cwd": str(self._cwd(cwd)),
            "prompt": prompt,
            "permissionMode": permission_mode,
        }
        if model is not None:
            params["model"] = model
        return params

    def _cwd(self, value: str | Path) -> Path:
        try:
            directory = Path(value).expanduser().resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise ClaudeCodeError("Claude working directory is unavailable") from error
        if not directory.is_dir() or not directory.is_relative_to(self.repository_root):
            raise ClaudeCodeError("Claude working directory must be inside the configured repository root")
        return directory

    async def _spawn(self, executable: str | None = None) -> None:
        async with self.spawn_lock:
            if self.process and self.process.returncode is None:
                return
            executable = executable or shutil.which(self.node_executable, path=self.env.get("PATH"))
            if executable is None:
                raise ClaudeCodeError("Node.js 20 or newer is required for optional Claude Code support")
            self.state_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            self.process = await asyncio.create_subprocess_exec(
                executable,
                str(self.bridge_path),
                "--state",
                str(self.state_path),
                cwd=str(self.bridge_path.parent),
                env=self.env,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                limit=MAX_BRIDGE_MESSAGE_BYTES + 1,
            )
            self.reader_task = asyncio.create_task(self._read_stdout(self.process))
            self.stderr_task = asyncio.create_task(self._read_stderr(self.process))
            handshake = await self._request(
                "handshake",
                {"protocol": BRIDGE_PROTOCOL_VERSION},
                timeout=10,
                ensure=False,
            )
            if handshake.get("protocol") != BRIDGE_PROTOCOL_VERSION:
                raise ClaudeCodeError("Claude bridge protocol mismatch")

    async def _request(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        timeout: float = 15,
        ensure: bool = True,
    ) -> Any:
        if ensure and (self.stopping or not self.desired):
            raise ClaudeCodeError("Claude bridge is not started")
        if ensure and (self.process is None or self.process.returncode is not None):
            await self._spawn()
        process = self.process
        if process is None or process.returncode is not None or process.stdin is None:
            raise ClaudeCodeError("Claude bridge is unavailable")
        self.request_number += 1
        request_id = f"python-{self.request_number}"
        message = {"id": request_id, "method": method, "params": params or {}}
        encoded = (json.dumps(message, separators=(",", ":")) + "\n").encode()
        if len(encoded) > MAX_BRIDGE_MESSAGE_BYTES:
            raise ClaudeCodeError("Claude bridge request is too large")
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            async with self.write_lock:
                process.stdin.write(encoded)
                await process.stdin.drain()
            return await asyncio.wait_for(future, timeout=timeout)
        except TimeoutError as error:
            if method in ("start", "resume"):
                try:
                    await self._request(
                        "cancelRequest",
                        {"requestId": request_id},
                        timeout=5,
                        ensure=False,
                    )
                except (ClaudeCodeError, TimeoutError, BrokenPipeError, ConnectionError):
                    await self._terminate_process()
                    for pending in list(self.pending.values()):
                        if not pending.done():
                            pending.set_exception(
                                ClaudeCodeError("Claude bridge was stopped after cancellation failed")
                            )
                    self.pending.clear()
                    self.pending_approvals.clear()
                    self.runtime_status = unavailable_status(
                        "Claude bridge was stopped after a query cancellation failure"
                    )
                    if (
                        self.desired
                        and not self.stopping
                        and self.restart_attempt < len(self.restart_delays)
                    ):
                        delay = self.restart_delays[self.restart_attempt]
                        self.restart_attempt += 1
                        self.restart_task = asyncio.create_task(
                            self._restart_after(delay)
                        )
            raise ClaudeCodeError(f"Claude bridge {method} request timed out") from error
        finally:
            self.pending.pop(request_id, None)

    async def _read_stdout(self, process: asyncio.subprocess.Process) -> None:
        assert process.stdout is not None
        failure: Exception | None = None
        try:
            while True:
                line = await process.stdout.readline()
                if not line:
                    break
                if len(line) > MAX_BRIDGE_MESSAGE_BYTES:
                    raise ClaudeCodeError("Claude bridge response exceeded the message limit")
                try:
                    message = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ClaudeCodeError("Claude bridge returned malformed JSON") from error
                if message.get("type") == "event" and isinstance(message.get("event"), dict):
                    await self._event(message["event"])
                    continue
                request_id = message.get("id")
                future = self.pending.get(request_id)
                if future is None or future.done():
                    continue
                if message.get("type") == "response":
                    future.set_result(message.get("result"))
                else:
                    detail = (message.get("error") or {}).get("message", "Claude bridge request failed")
                    future.set_exception(ClaudeCodeError(_safe_error(detail)))
        except Exception as error:
            failure = error
        finally:
            await self._process_lost(process, failure)

    async def _read_stderr(self, process: asyncio.subprocess.Process) -> None:
        assert process.stderr is not None
        try:
            while True:
                line = await process.stderr.readline()
                if not line:
                    break
                self.last_stderr = _safe_error(line.decode("utf-8", errors="replace"))
        except (ValueError, asyncio.LimitOverrunError):
            self.last_stderr = "Claude bridge stderr exceeded the message limit"

    async def _event(self, event: dict[str, Any]) -> None:
        kind = event.get("kind")
        request_id = event.get("requestId")
        if kind == "permission.requested" and isinstance(request_id, str):
            self.pending_approvals[request_id] = event
        if kind in TERMINAL_EVENTS:
            run_id = event.get("runId")
            self.pending_approvals = {
                key: item
                for key, item in self.pending_approvals.items()
                if item.get("runId") != run_id
            }
        result = self.on_event(event)
        if inspect.isawaitable(result):
            await result

    async def _process_lost(
        self,
        process: asyncio.subprocess.Process,
        failure: Exception | None,
    ) -> None:
        if self.process is not process:
            return
        detail = _safe_error(failure or self.last_stderr or "Claude bridge stopped")
        for future in list(self.pending.values()):
            if not future.done():
                future.set_exception(ClaudeCodeError(detail))
        self.pending.clear()
        self.pending_approvals.clear()
        self.process = None
        previous = self.runtime_status
        self.runtime_status = unavailable_status(detail)
        if previous.get("installed"):
            for key in ("installed", "cliVersion", "sdkVersion", "nodeVersion", "executable"):
                if key in previous:
                    self.runtime_status[key] = previous[key]
        await self._notify_status()
        if self.desired and not self.stopping and self.restart_attempt < len(self.restart_delays):
            delay = self.restart_delays[self.restart_attempt]
            self.restart_attempt += 1
            if self.restart_task is None or self.restart_task.done():
                self.restart_task = asyncio.create_task(self._restart_after(delay))

    async def _restart_after(self, delay: float) -> None:
        await asyncio.sleep(delay)
        if not self.desired or self.stopping:
            return
        try:
            await self._spawn()
            self.runtime_status = await self._request("status", timeout=15)
            self.restart_attempt = 0
            await self._notify_status()
        except Exception as error:
            self.runtime_status = unavailable_status(_safe_error(error))
            await self._terminate_process()
            if self.desired and not self.stopping and self.restart_attempt < len(self.restart_delays):
                next_delay = self.restart_delays[self.restart_attempt]
                self.restart_attempt += 1
                self.restart_task = asyncio.create_task(self._restart_after(next_delay))

    async def _notify_status(self) -> None:
        result = self.on_event(
            {
                "provider": "claude-code",
                "kind": "provider.status",
                "available": self.runtime_status.get("available") is True,
            }
        )
        if inspect.isawaitable(result):
            await result

    async def _terminate_process(self) -> None:
        process = self.process
        self.process = None
        current = asyncio.current_task()
        tasks = [task for task in (self.reader_task, self.stderr_task) if task and task is not current]
        self.reader_task = None
        self.stderr_task = None
        if process and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=2)
            except TimeoutError:
                process.kill()
                await process.wait()
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
