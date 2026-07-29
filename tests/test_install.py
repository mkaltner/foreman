from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux" / "vendor"))

from websockets.asyncio.server import unix_serve  # noqa: E402


class DesktopServer:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.server: Any | None = None
        self.methods: list[str] = []

    async def start(self) -> None:
        self.path.parent.mkdir(parents=True)
        self.server = await unix_serve(self.handle, str(self.path))

    async def handle(self, websocket: Any) -> None:
        async for raw in websocket:
            message = json.loads(raw)
            method = message.get("method", "")
            self.methods.append(method)
            if "id" not in message:
                continue
            thread = {
                "id": "installed-thread",
                "cwd": "/projects/example",
                "preview": "Installed service",
                "name": None,
                "status": {"type": "idle"},
                "updatedAt": 100,
                "recencyAt": 100,
                "turns": [],
            }
            if method == "initialize":
                result = {"userAgent": "fake-desktop"}
            elif method == "thread/list":
                result = {"data": [thread], "nextCursor": None}
            elif method == "thread/resume":
                result = {
                    "thread": thread,
                    "model": "model-test",
                    "reasoningEffort": "high",
                }
            else:
                result = {}
            await websocket.send(json.dumps({"id": message["id"], "result": result}))

    async def stop(self) -> None:
        if self.server:
            self.server.close(close_connections=True)
            await self.server.wait_closed()


class InstallerTests(unittest.IsolatedAsyncioTestCase):
    def prepare_home(
        self, directory: str, active: bool = True
    ) -> tuple[Path, dict[str, str], Path, Path]:
        home = Path(directory)
        fake_bin = home / "fake-bin"
        fake_bin.mkdir()
        python_log = home / "python.log"
        systemctl_log = home / "systemctl.log"
        base_python = Path(sys._base_executable)
        python = fake_bin / "python3"
        python.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> \"$FOREMAN_PYTHON_LOG\"\n"
            "if [ \"${1:-}\" = -m ]; then\n"
            "  case \"${2:-}\" in venv|pip|ensurepip) exit 97;; esac\n"
            "fi\n"
            f'exec "{base_python}" "$@"\n',
            encoding="utf-8",
        )
        python.chmod(0o755)
        for name in ("pip", "pip3"):
            unavailable = fake_bin / name
            unavailable.write_text("#!/bin/sh\nexit 97\n", encoding="utf-8")
            unavailable.chmod(0o755)
        systemctl = fake_bin / "systemctl"
        systemctl.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> \"$FOREMAN_SYSTEMCTL_LOG\"\n"
            "for argument in \"$@\"; do\n"
            "  if [ \"$argument\" = is-active ]; then\n"
            "    [ \"$FOREMAN_SYSTEMCTL_ACTIVE\" = 1 ]\n"
            "    exit\n"
            "  fi\n"
            "done\n"
            "exit 0\n",
            encoding="utf-8",
        )
        systemctl.chmod(0o755)
        codex = fake_bin / "codex"
        codex.write_text(
            "#!/bin/sh\n"
            "if [ \"${2:-}\" = generate-json-schema ]; then\n"
            "  for output; do :; done\n"
            "  mkdir -p \"$output\"\n"
            "  printf '%s\\n' '{\"oneOf\":[]}' > \"$output/ClientRequest.json\"\n"
            "  exit 0\n"
            "fi\n"
            "exit 1\n",
            encoding="utf-8",
        )
        codex.chmod(0o755)
        environment = {
            **os.environ,
            "HOME": str(home),
            "PATH": f"{fake_bin}:/usr/bin:/bin",
            "FOREMAN_PYTHON_LOG": str(python_log),
            "FOREMAN_SYSTEMCTL_LOG": str(systemctl_log),
            "FOREMAN_SYSTEMCTL_ACTIVE": "1" if active else "0",
            "CODEX_HOME": str(home / ".codex"),
        }
        config = home / ".config" / "foreman" / "foreman.env"
        config.parent.mkdir(parents=True)
        repository = home / "projects" / "example"
        repository.mkdir(parents=True)
        config.write_text(
            "FOREMAN_HOST=127.0.0.1\n"
            "FOREMAN_PORT=0\n"
            "FOREMAN_WEB_HOST=127.0.0.1\n"
            "FOREMAN_WEB_PORT=0\n"
            f"FOREMAN_REPOSITORY_ROOT={home / 'projects'}\n"
            f"FOREMAN_CODEX_EXECUTABLE={codex}\n",
            encoding="utf-8",
        )
        return home, environment, python_log, systemctl_log

    async def run_command(
        self, command: list[str], environment: dict[str, str]
    ) -> subprocess.CompletedProcess[str]:
        return await asyncio.to_thread(
            subprocess.run,
            command,
            cwd=ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

    async def test_offline_install_pair_and_shared_socket_use_system_python(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home, environment, python_log, systemctl_log = self.prepare_home(
                directory
            )
            obsolete_venv = home / ".local" / "share" / "foreman" / "venv"
            obsolete_venv.mkdir(parents=True)
            (obsolete_venv / "old-runtime").write_text("old", encoding="utf-8")

            installed = await self.run_command(
                [str(ROOT / "install.sh")],
                environment,
            )
            self.assertEqual(installed.returncode, 0, installed.stderr)

            install_dir = home / ".local" / "share" / "foreman"
            launcher = home / ".local" / "bin" / "foreman"
            unit = home / ".config" / "systemd" / "user" / "foreman.service"
            self.assertFalse((install_dir / "venv").exists())
            self.assertIn(
                "ExecStart=/usr/bin/env python3 ",
                unit.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "exec /usr/bin/env python3",
                launcher.read_text(encoding="utf-8"),
            )
            self.assertTrue(
                (
                    install_dir
                    / "vendor/websockets-16.1.1.dist-info/licenses/LICENSE"
                ).is_file()
            )
            self.assertIn("<div id=\"root\"></div>", (install_dir / "web/index.html").read_text())
            self.assertTrue(any((install_dir / "web/assets").iterdir()))

            paired = await self.run_command(
                [str(launcher), "pair"],
                environment,
            )
            self.assertEqual(paired.returncode, 0, paired.stderr)
            self.assertIn("Pairing key:", paired.stdout)

            imported = await self.run_command(
                [
                    "python3",
                    "-c",
                    "import pathlib,sys; "
                    f"sys.path.insert(0,{str(install_dir)!r}); "
                    "import codex,websockets; "
                    "assert websockets.__version__=='16.1.1'; "
                    f"assert pathlib.Path(websockets.__file__).is_relative_to(pathlib.Path({str(install_dir / 'vendor')!r}))",
                ],
                environment,
            )
            self.assertEqual(imported.returncode, 0, imported.stderr)

            server = DesktopServer(
                home / ".codex/app-server-control/app-server-control.sock"
            )
            await server.start()
            process = await asyncio.create_subprocess_exec(
                "/usr/bin/env",
                "python3",
                str(install_dir / "foreman_service.py"),
                env=environment,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                output = []
                for _ in range(3):
                    output.append(
                        (await asyncio.wait_for(process.stdout.readline(), 10)).decode()
                    )
                self.assertIn("SHARED_DESKTOP_LIVE_STATUS_AVAILABLE", "".join(output))
                self.assertIn("Foreman listening", "".join(output))
                self.assertIn("Foreman web listening", "".join(output))
                self.assertIn("initialize", server.methods)
                self.assertIn("thread/list", server.methods)
                self.assertIn("thread/resume", server.methods)
            finally:
                process.terminate()
                await asyncio.wait_for(process.wait(), 10)
                await server.stop()

            invocations = python_log.read_text(encoding="utf-8")
            self.assertNotRegex(invocations, r"-m (venv|pip|ensurepip)")
            self.assertIn(
                f"{install_dir}/foreman_service.py --create-pairing", invocations
            )
            self.assertIn(f"{install_dir}/foreman_service.py", invocations)
            service_calls = systemctl_log.read_text(encoding="utf-8")
            self.assertIn("--user restart foreman.service", service_calls)

    async def test_failed_activation_rolls_back_the_existing_install(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home, environment, _, _ = self.prepare_home(directory, active=False)
            install_dir = home / ".local" / "share" / "foreman"
            obsolete_venv = install_dir / "venv"
            obsolete_venv.mkdir(parents=True)
            old_service = install_dir / "foreman_service.py"
            old_service.write_text("old service\n", encoding="utf-8")
            launcher = home / ".local" / "bin" / "foreman"
            launcher.parent.mkdir(parents=True)
            launcher.write_text("old launcher\n", encoding="utf-8")
            unit = home / ".config" / "systemd" / "user" / "foreman.service"
            unit.parent.mkdir(parents=True)
            unit.write_text("old unit\n", encoding="utf-8")

            installed = await self.run_command(
                [str(ROOT / "install.sh")],
                environment,
            )

            self.assertNotEqual(installed.returncode, 0)
            self.assertEqual(old_service.read_text(encoding="utf-8"), "old service\n")
            self.assertTrue(obsolete_venv.is_dir())
            self.assertEqual(launcher.read_text(encoding="utf-8"), "old launcher\n")
            self.assertEqual(unit.read_text(encoding="utf-8"), "old unit\n")
