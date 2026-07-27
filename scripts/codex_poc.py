#!/usr/bin/env python3
"""Opt-in proof against the installed Codex Unix-socket app-server."""

from __future__ import annotations

import argparse
import asyncio
import base64
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).parents[1] / "linux"))

from codex import Codex, resolve_socket_path, user_input  # noqa: E402

ONE_PIXEL_PNG = base64.b64encode(
    base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
        "+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
).decode()


async def wait_for(
    events: asyncio.Queue[dict[str, Any]],
    method: str,
    turn_id: str,
    verbose: bool,
) -> tuple[dict[str, Any], str]:
    assistant: list[str] = []
    while True:
        event = await asyncio.wait_for(events.get(), timeout=120)
        event_method = event.get("method")
        params = event.get("params", {})
        if verbose:
            print(f"event {event_method}")
        if (
            event_method == "item/agentMessage/delta"
            and params.get("turnId") == turn_id
        ):
            assistant.append(params.get("delta", ""))
        if event_method == method:
            event_turn = params.get("turn", {}).get("id") or params.get("turnId")
            if event_turn == turn_id:
                return event, "".join(assistant)


async def proof(args: argparse.Namespace) -> None:
    events: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

    async def on_event(message: dict[str, Any]) -> None:
        await events.put(message)

    adapter = Codex(args.codex, on_event, args.socket)
    await adapter.start()
    try:
        print(
            "connected: "
            f"{adapter.socket_path} ({'launched' if adapter.process else 'attached'})"
        )
        models = await adapter.list_models(refresh=True)
        print(f"models: {len(models)}")
        threads = await adapter.list_threads()
        print(f"existing threads: {len(threads)} returned")

        with tempfile.TemporaryDirectory(prefix="foreman-poc-") as repository:
            subprocess.run(["git", "init", "-q", repository], check=True)
            started = await adapter.start_thread(repository, ephemeral=True)
            thread_id = started["id"]
            empty = await adapter.read_thread(thread_id)
            if empty.get("turns"):
                raise RuntimeError("new thread unexpectedly contained turns")
            print(f"empty thread opened: {thread_id}")

            selected = next((item for item in models if item["isDefault"]), None)
            selected = selected or (models[0] if models else None)
            model_id = selected["id"] if selected else None
            effort = selected["defaultReasoningEffort"] if selected else None
            inputs = user_input(
                "Reply with exactly FOREMAN_POC_OK. Do not use tools.",
                [],
            )
            params: dict[str, Any] = {"threadId": thread_id, "input": inputs}
            if model_id:
                params["model"] = model_id
            if effort:
                params["effort"] = effort
            turn_id = (await adapter.request("turn/start", params))["turn"]["id"]
            completed, assistant = await wait_for(
                events,
                "turn/completed",
                turn_id,
                args.verbose_events,
            )
            status = completed["params"]["turn"]["status"]
            if status != "completed" or "FOREMAN_POC_OK" not in assistant:
                raise RuntimeError("text prompt result was unexpected")
            print(f"text prompt completed: {status}")

            if args.with_image:
                image_turn = (
                    await adapter.request(
                        "turn/start",
                        {
                            "threadId": thread_id,
                            "input": user_input(
                                "Reply with exactly FOREMAN_IMAGE_OK.",
                                [
                                    {
                                        "mimeType": "image/png",
                                        "data": ONE_PIXEL_PNG,
                                    }
                                ],
                            ),
                            **({"model": model_id} if model_id else {}),
                            **({"effort": effort} if effort else {}),
                        },
                    )
                )["turn"]["id"]
                image_completed, image_assistant = await wait_for(
                    events,
                    "turn/completed",
                    image_turn,
                    args.verbose_events,
                )
                if (
                    image_completed["params"]["turn"]["status"] != "completed"
                    or "FOREMAN_IMAGE_OK" not in image_assistant
                ):
                    raise RuntimeError("image prompt result was unexpected")
                print("image prompt completed: completed")
    finally:
        await adapter.stop()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--codex",
        default=os.environ.get("FOREMAN_CODEX_EXECUTABLE") or shutil.which("codex"),
    )
    parser.add_argument(
        "--socket",
        type=Path,
        default=resolve_socket_path(),
        help="Unix control socket to attach to or launch",
    )
    parser.add_argument("--with-image", action="store_true")
    parser.add_argument("--verbose-events", action="store_true")
    args = parser.parse_args()
    if not args.codex:
        parser.error("codex executable not found")
    asyncio.run(proof(args))
    print("FOREMAN_CODEX_POC_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
