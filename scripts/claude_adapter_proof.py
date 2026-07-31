#!/usr/bin/env python3
"""Opt-in authenticated proof for the production Claude Code adapter."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid


ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))

from claude_code import ClaudeCode, ClaudeCodeError  # noqa: E402


async def proof() -> dict[str, object]:
    claude = shutil.which("claude")
    if claude is None:
        raise RuntimeError("native claude executable is required")
    with tempfile.TemporaryDirectory(prefix="foreman-claude-production-proof-") as directory:
        root = Path(directory)
        repository = root / "repo"
        repository.mkdir()
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        (repository / "FOREMAN_PROOF.txt").write_text("FOREMAN_PRODUCTION_MARKER\n", encoding="utf-8")
        state_path = root / "claude-code-sessions.json"
        events: list[dict[str, object]] = []

        def event(message: dict[str, object]) -> None:
            events.append(message)

        def adapter() -> ClaudeCode:
            return ClaudeCode(
                root,
                state_path,
                on_event=event,
                bridge_path=ROOT / "linux" / "claude_bridge" / "bridge.mjs",
                env={**os.environ, "FOREMAN_CLAUDE_EXECUTABLE": claude},
            )

        async def wait_for(predicate, timeout: float = 180):
            async with asyncio.timeout(timeout):
                while True:
                    match = predicate()
                    if match:
                        return match
                    await asyncio.sleep(0.02)

        async def finish(session_id: str, start_index: int, allow: bool = False) -> dict[str, object]:
            answered: set[str] = set()
            async with asyncio.timeout(180):
                while True:
                    for request_id, pending in list(current.pending_approvals.items()):
                        if request_id not in answered and pending.get("sessionId") == session_id:
                            await current.answer_approval(request_id, allow=allow)
                            answered.add(request_id)
                    terminal = next(
                        (
                            item
                            for item in events[start_index:]
                            if item.get("sessionId") == session_id
                            and item.get("kind")
                            in ("query.completed", "query.failed", "query.interrupted")
                        ),
                        None,
                    )
                    if terminal:
                        return terminal
                    await asyncio.sleep(0.02)

        results: dict[str, object] = {}
        current = adapter()
        external_process: subprocess.Popen[bytes] | None = None
        try:
            status = await current.start()
            if not status.get("available"):
                raise RuntimeError(str(status.get("limitation")))
            results["detection"] = {
                "cliVersion": status.get("cliVersion"),
                "sdkVersion": status.get("sdkVersion"),
            }

            index = len(events)
            read_run = await current.start_session(
                repository,
                "Use Read to read FOREMAN_PROOF.txt, then reply with exactly FOREMAN_PRODUCTION_MARKER.",
                model="sonnet",
                permission_mode="default",
            )
            read_terminal = await finish(read_run["sessionId"], index, allow=True)
            read_events = events[index:]
            if read_terminal["kind"] != "query.completed":
                raise RuntimeError("Read proof did not complete")
            if not any(item.get("kind") == "assistant.delta" for item in read_events):
                raise RuntimeError("assistant partial text was not observed")
            if "FOREMAN_PRODUCTION_MARKER" not in "".join(
                str(item.get("text", "")) for item in read_events if item.get("kind") == "assistant.delta"
            ):
                raise RuntimeError("assistant partial text did not contain the proof marker")
            if not any(item.get("kind") == "tool" and item.get("name") == "Read" for item in read_events):
                raise RuntimeError("safe Read activity was not observed")
            results["startAndRead"] = read_run["sessionId"]
            results["partialStreaming"] = True

            index = len(events)
            bash_run = await current.start_session(
                repository,
                "Use Bash to run exactly `pwd`, then reply with exactly BASH_OK.",
                model="sonnet",
                permission_mode="default",
            )
            bash_terminal = await finish(bash_run["sessionId"], index, allow=True)
            bash_events = events[index:]
            if bash_terminal["kind"] != "query.completed":
                raise RuntimeError("Bash proof did not complete")
            if not any(item.get("kind") == "tool" and item.get("name") == "Bash" for item in bash_events):
                raise RuntimeError("safe Bash activity was not observed")
            if not any(item.get("kind") == "tool" and item.get("status") == "completed" for item in bash_events):
                raise RuntimeError("safe Bash result was not observed")
            results["safeBash"] = True

            for model in ("sonnet", "haiku"):
                index = len(events)
                selected = await current.start_session(
                    repository,
                    f"Reply with exactly {model.upper()}_OK. Do not use tools.",
                    model=model,
                    permission_mode="dontAsk",
                )
                terminal = await finish(selected["sessionId"], index)
                started = next(
                    item
                    for item in events[index:]
                    if item.get("kind") == "query.started" and item.get("sessionId") == selected["sessionId"]
                )
                if terminal["kind"] != "query.completed" or model not in str(started.get("model", "")):
                    raise RuntimeError(f"{model} model selection proof failed")
                results[model] = started.get("model")

            index = len(events)
            interrupted = await current.start_session(
                repository,
                "Use Bash to run exactly `sleep 20`, then reply with INTERRUPT_MISSED.",
                model="sonnet",
                permission_mode="bypassPermissions",
            )
            await wait_for(
                lambda: next(
                    (
                        item
                        for item in events[index:]
                        if item.get("sessionId") == interrupted["sessionId"]
                        and item.get("kind") == "tool"
                        and item.get("name") == "Bash"
                    ),
                    None,
                )
            )
            await current.interrupt(interrupted["sessionId"])
            interrupted_terminal = await finish(interrupted["sessionId"], index)
            if interrupted_terminal["kind"] != "query.interrupted":
                raise RuntimeError("interrupt proof failed")
            resumed = await current.resume_session(
                interrupted["sessionId"],
                repository,
                "Reply with exactly INTERRUPT_RESUME_OK. Do not use tools.",
                model="sonnet",
                permission_mode="dontAsk",
            )
            await finish(resumed["sessionId"], len(events) - 1)
            if resumed["sessionId"] != interrupted["sessionId"]:
                raise RuntimeError("post-interrupt resume changed the session ID")
            results["interruptAndResume"] = interrupted["sessionId"]

            index = len(events)
            denial = await current.start_session(
                repository,
                "Use Bash to run exactly `touch DONT_ASK_SHOULD_NOT_EXIST`. Do not use another tool.",
                model="haiku",
                permission_mode="dontAsk",
            )
            await finish(denial["sessionId"], index)
            if (repository / "DONT_ASK_SHOULD_NOT_EXIST").exists():
                raise RuntimeError("dontAsk allowed the denied write")
            if not any(
                item.get("kind") == "permission.denied" and item.get("sessionId") == denial["sessionId"]
                for item in events[index:]
            ):
                raise RuntimeError("dontAsk denial event was not observed")
            results["dontAskDeniedWrite"] = True

            await current.stop()
            current = adapter()
            await current.start()
            index = len(events)
            after_restart = await current.resume_session(
                read_run["sessionId"],
                repository,
                "Reply with the exact marker you read earlier. Do not use tools.",
                model="sonnet",
                permission_mode="dontAsk",
            )
            await finish(after_restart["sessionId"], index)
            if after_restart["sessionId"] != read_run["sessionId"]:
                raise RuntimeError("adapter restart changed the session ID")
            if "FOREMAN_PRODUCTION_MARKER" not in "".join(
                str(item.get("text", "")) for item in events[index:] if item.get("kind") == "assistant.delta"
            ):
                raise RuntimeError("adapter restart did not restore Claude session context")
            results["restartResume"] = after_restart["sessionId"]

            external_id = str(uuid.uuid4())
            external_process = subprocess.Popen(
                [
                    claude,
                    "-p",
                    "--session-id",
                    external_id,
                    "--model",
                    "haiku",
                    "--dangerously-skip-permissions",
                    "Remember EXTERNAL_CONTEXT_731. Use Bash to run exactly `sleep 5`, then reply EXTERNAL_DONE.",
                ],
                cwd=repository,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            async with asyncio.timeout(30):
                while True:
                    discovered = await current.discover(repository)
                    match = next((item for item in discovered if item["sessionId"] == external_id), None)
                    if match:
                        break
                    await asyncio.sleep(0.25)
            if match["classification"] != "resumable" or match["active"]:
                raise RuntimeError("external session was falsely reported live")
            try:
                await current.attach_external(external_id)
                raise RuntimeError("external live attachment unexpectedly succeeded")
            except ClaudeCodeError as error:
                if "not supported" not in str(error):
                    raise
            await asyncio.to_thread(external_process.wait, 60)
            if external_process.returncode != 0:
                raise RuntimeError("external Claude CLI proof process failed")
            external_process = None
            index = len(events)
            external_resume = await current.resume_session(
                external_id,
                repository,
                "What exact EXTERNAL_CONTEXT token did I give you? Reply with only that token.",
                model="haiku",
                permission_mode="dontAsk",
            )
            await finish(external_resume["sessionId"], index)
            if "EXTERNAL_CONTEXT_731" not in "".join(
                str(item.get("text", "")) for item in events[index:] if item.get("kind") == "assistant.delta"
            ):
                raise RuntimeError("external exact-ID resume did not restore CLI session context")
            results["externalDiscoverResume"] = external_resume["sessionId"]
            results["externalLiveAttachRejected"] = True
            results["mappingFields"] = sorted(
                json.loads(state_path.read_text(encoding="utf-8"))["sessions"][0]
            )
            return results
        finally:
            if external_process is not None and external_process.poll() is None:
                external_process.terminate()
                try:
                    await asyncio.to_thread(external_process.wait, 5)
                except subprocess.TimeoutExpired:
                    external_process.kill()
                    await asyncio.to_thread(external_process.wait)
            await current.stop()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--acknowledge-live-costs",
        action="store_true",
        help="required: runs authenticated Claude requests in disposable repositories",
    )
    args = parser.parse_args()
    if not args.acknowledge_live_costs:
        parser.error("--acknowledge-live-costs is required")
    print(json.dumps(asyncio.run(proof()), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
