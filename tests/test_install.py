from __future__ import annotations

import asyncio
import json
import os
import shutil
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
        self,
        directory: str,
        active: bool = True,
        providers: tuple[str, ...] = ("codex",),
        create_config: bool = True,
    ) -> tuple[Path, dict[str, str], Path, Path]:
        home = Path(directory)
        home.mkdir(parents=True, exist_ok=True)
        fake_bin = home / "fake-bin"
        fake_bin.mkdir()
        python_log = home / "python.log"
        systemctl_log = home / "systemctl.log"
        forbidden_tool_log = home / "forbidden-tool.log"
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
        for name in ("sudo", "node", "npm", "java", "javac", "gradle"):
            unavailable = fake_bin / name
            unavailable.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$0 $*\" >> \"$FOREMAN_FORBIDDEN_TOOL_LOG\"\n"
                "exit 97\n",
                encoding="utf-8",
            )
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
        if "codex" in providers:
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
        claude = fake_bin / "claude"
        if "claude-code" in providers:
            claude.write_text(
                "#!/bin/sh\n"
                "[ \"${1:-}\" = --version ] && printf '%s\\n' '2.1.220'\n"
                "exit 0\n",
                encoding="utf-8",
            )
            claude.chmod(0o755)
            node = fake_bin / "node"
            node.write_text(
                "#!/bin/sh\n"
                "[ \"${1:-}\" = --version ] && printf '%s\\n' 'v20.19.0'\n"
                "exit 0\n",
                encoding="utf-8",
            )
            node.chmod(0o755)
            npm = fake_bin / "npm"
            npm.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$*\" >> \"$FOREMAN_NPM_LOG\"\n"
                "mkdir -p node_modules/@anthropic-ai/claude-agent-sdk\n"
                "printf '%s\\n' '{\"name\":\"@anthropic-ai/claude-agent-sdk\",\"version\":\"0.3.220\"}' > node_modules/@anthropic-ai/claude-agent-sdk/package.json\n"
                "exit 0\n",
                encoding="utf-8",
            )
            npm.chmod(0o755)
        environment = {
            **{key: value for key, value in os.environ.items() if not key.startswith("FOREMAN_")},
            "HOME": str(home),
            "PATH": f"{fake_bin}:/usr/bin:/bin",
            "FOREMAN_PYTHON_LOG": str(python_log),
            "FOREMAN_SYSTEMCTL_LOG": str(systemctl_log),
            "FOREMAN_SYSTEMCTL_ACTIVE": "1" if active else "0",
            "FOREMAN_FORBIDDEN_TOOL_LOG": str(forbidden_tool_log),
            "FOREMAN_NPM_LOG": str(home / "npm.log"),
            "CODEX_HOME": str(home / ".codex"),
        }
        config = home / ".config" / "foreman" / "foreman.env"
        repository = home / "projects" / "example"
        repository.mkdir(parents=True)
        if create_config:
            config.parent.mkdir(parents=True)
            provider_config = ""
            if "codex" in providers:
                provider_config += f"FOREMAN_CODEX_EXECUTABLE={codex}\n"
            if "claude-code" in providers:
                provider_config += f"FOREMAN_CLAUDE_EXECUTABLE={claude}\n"
                provider_config += f"FOREMAN_NODE_EXECUTABLE={fake_bin / 'node'}\n"
            config.write_text(
                "FOREMAN_HOST=127.0.0.1\n"
                "FOREMAN_PORT=0\n"
                "FOREMAN_WEB_HOST=127.0.0.1\n"
                "FOREMAN_WEB_PORT=0\n"
                f"FOREMAN_REPOSITORY_ROOT={home / 'projects'}\n"
                + provider_config,
                encoding="utf-8",
            )
        return home, environment, python_log, systemctl_log

    def copy_install_payload(self, destination: Path, packaged_sdk: bool = False) -> Path:
        destination.mkdir()
        shutil.copy2(ROOT / "install.sh", destination / "install.sh")
        shutil.copy2(ROOT / "requirements.txt", destination / "requirements.txt")
        shutil.copy2(ROOT / "release.properties", destination / "release.properties")
        shutil.copytree(
            ROOT / "linux",
            destination / "linux",
            ignore=shutil.ignore_patterns("node_modules", "__pycache__", "*.pyc"),
        )
        shutil.copytree(ROOT / "web/dist", destination / "web/dist")
        if packaged_sdk:
            sdk = (
                destination
                / "linux/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
            )
            sdk.parent.mkdir(parents=True)
            sdk.write_text(
                '{"name":"@anthropic-ai/claude-agent-sdk","version":"0.3.220"}\n',
                encoding="utf-8",
            )
        return destination / "install.sh"

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

    async def test_fresh_install_supports_each_provider_matrix(self) -> None:
        for providers in (("codex",), ("claude-code",), ("codex", "claude-code")):
            with self.subTest(providers=providers), tempfile.TemporaryDirectory() as directory:
                script = ROOT / "install.sh"
                if "claude-code" in providers:
                    script = self.copy_install_payload(Path(directory) / "source-payload")
                home, environment, _, _ = self.prepare_home(
                    directory,
                    providers=providers,
                    create_config=False,
                )

                installed = await self.run_command([str(script)], environment)

                self.assertEqual(installed.returncode, 0, installed.stderr)
                config = (
                    home / ".config/foreman/foreman.env"
                ).read_text(encoding="utf-8")
                codex = home / "fake-bin/codex"
                claude = home / "fake-bin/claude"
                self.assertEqual(
                    f"FOREMAN_CODEX_EXECUTABLE={codex}" in config,
                    "codex" in providers,
                )
                self.assertEqual(
                    f"FOREMAN_CLAUDE_EXECUTABLE={claude}" in config,
                    "claude-code" in providers,
                )
                self.assertNotIn("FOREMAN_CODEX_EXECUTABLE=\n", config)
                if "claude-code" in providers:
                    self.assertIn(
                        "ci --omit=dev --ignore-scripts",
                        (home / "npm.log").read_text(encoding="utf-8"),
                    )
                    self.assertTrue(
                        (
                            home
                            / ".local/share/foreman/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
                        ).is_file()
                    )
                else:
                    self.assertFalse((home / "npm.log").exists())

    async def test_neither_provider_fails_before_installation_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home, environment, _, _ = self.prepare_home(
                directory,
                providers=(),
                create_config=False,
            )

            installed = await self.run_command([str(ROOT / "install.sh")], environment)

            self.assertNotEqual(installed.returncode, 0)
            self.assertIn("Codex (codex) or Claude Code (claude)", installed.stderr)
            self.assertFalse((home / ".config/foreman").exists())
            self.assertFalse((home / ".local").exists())
            self.assertFalse((home / "systemctl.log").exists())

    async def test_claude_only_missing_runtime_fails_actionably(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = self.copy_install_payload(Path(directory) / "source-payload")
            home, environment, _, _ = self.prepare_home(
                directory,
                providers=("claude-code",),
                create_config=False,
            )
            (home / "fake-bin/node").unlink()

            missing_node = await self.run_command([str(script)], environment)

            self.assertNotEqual(missing_node.returncode, 0)
            self.assertIn("Install Node.js 20 or newer", missing_node.stderr)
            self.assertFalse((home / ".config/foreman").exists())
            self.assertFalse((home / ".local").exists())

        with tempfile.TemporaryDirectory() as directory:
            script = self.copy_install_payload(Path(directory) / "source-payload")
            home, environment, _, _ = self.prepare_home(
                directory,
                providers=("claude-code",),
                create_config=False,
            )
            npm = home / "fake-bin/npm"
            npm.write_text("#!/bin/sh\nexit 73\n", encoding="utf-8")
            npm.chmod(0o755)

            missing_sdk = await self.run_command([str(script)], environment)

            self.assertNotEqual(missing_sdk.returncode, 0)
            self.assertIn("pinned Claude Agent SDK could not be prepared", missing_sdk.stderr)
            self.assertIn("npm ci --omit=dev --ignore-scripts", missing_sdk.stderr)
            self.assertFalse((home / ".config/foreman/foreman.env").exists())
            self.assertFalse((home / ".local/share/foreman").exists())

        with tempfile.TemporaryDirectory() as directory:
            script = self.copy_install_payload(Path(directory) / "source-payload")
            home, environment, _, _ = self.prepare_home(
                directory,
                providers=("codex", "claude-code"),
                create_config=False,
            )
            npm = home / "fake-bin/npm"
            npm.write_text(
                "#!/bin/sh\n"
                "mkdir -p node_modules/@anthropic-ai/claude-agent-sdk\n"
                "printf '%s\\n' '{\"version\":\"0.3.220\"}' > node_modules/@anthropic-ai/claude-agent-sdk/package.json\n"
                "exit 73\n",
                encoding="utf-8",
            )
            npm.chmod(0o755)

            codex_fallback = await self.run_command([str(script)], environment)

            self.assertEqual(codex_fallback.returncode, 0, codex_fallback.stderr)
            self.assertIn("Codex installation will continue", codex_fallback.stderr)
            self.assertFalse(
                (
                    home
                    / ".local/share/foreman/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
                ).exists()
            )

    async def test_release_payload_uses_packaged_claude_sdk_without_npm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "release-payload"
            script = self.copy_install_payload(payload, packaged_sdk=True)
            home, environment, _, _ = self.prepare_home(
                str(root / "home"),
                providers=("claude-code",),
                create_config=False,
            )

            installed = await self.run_command([str(script)], environment)

            self.assertEqual(installed.returncode, 0, installed.stderr)
            self.assertFalse((home / "npm.log").exists())
            self.assertTrue(
                (
                    home
                    / ".local/share/foreman/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
                ).is_file()
            )

    async def test_reinstall_uses_valid_configured_executable_outside_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home, environment, _, _ = self.prepare_home(directory)
            configured = home / "provider-bin/codex-custom"
            configured.parent.mkdir()
            (home / "fake-bin/codex").replace(configured)
            config = home / ".config/foreman/foreman.env"
            config.write_text(
                config.read_text(encoding="utf-8").replace(
                    str(home / "fake-bin/codex"), str(configured)
                ),
                encoding="utf-8",
            )
            expected = config.read_bytes()

            installed = await self.run_command([str(ROOT / "install.sh")], environment)

            self.assertEqual(installed.returncode, 0, installed.stderr)
            self.assertEqual(config.read_bytes(), expected)

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
            recovery_unit = (
                home / ".config" / "systemd" / "user"
                / "foreman-update-recovery.service"
            )
            self.assertFalse((install_dir / "venv").exists())
            self.assertIn(
                "ExecStart=/usr/bin/env python3 ",
                unit.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "--resume-latest",
                recovery_unit.read_text(encoding="utf-8"),
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
            self.assertTrue((install_dir / "diagnostics.py").is_file())
            self.assertTrue((install_dir / "claude_code.py").is_file())
            self.assertTrue((install_dir / "session_identity.py").is_file())
            self.assertTrue((install_dir / "release_updates.py").is_file())
            self.assertTrue((install_dir / "server_update.py").is_file())
            self.assertTrue((install_dir / "update_cli.py").is_file())
            self.assertTrue((home / ".local/libexec/foreman-updater").is_file())
            self.assertIn(
                "--user enable foreman-update-recovery.service",
                systemctl_log.read_text(encoding="utf-8"),
            )
            self.assertTrue((install_dir / "claude_bridge/bridge.mjs").is_file())
            self.assertTrue((install_dir / "claude_bridge/package-lock.json").is_file())
            self.assertFalse((install_dir / "claude_bridge/bridge.test.mjs").exists())
            self.assertFalse((install_dir / "claude_bridge/test_fake_sdk.mjs").exists())
            self.assertEqual(
                (
                    install_dir
                    / "claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
                ).is_file(),
                (
                    ROOT
                    / "linux/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json"
                ).is_file(),
            )
            self.assertEqual(
                (ROOT / "release.properties").read_text(encoding="utf-8"),
                (install_dir / "release.properties").read_text(encoding="utf-8"),
            )

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
                    line = await asyncio.wait_for(process.stdout.readline(), 10)
                    if not line:
                        stderr = (await process.stderr.read()).decode()
                        self.fail(f"installed service exited during startup: {stderr}")
                    output.append(line.decode())
                self.assertIn("SHARED_DESKTOP_LIVE_STATUS_AVAILABLE", "".join(output))
                self.assertIn("Foreman listening", "".join(output))
                self.assertIn("Foreman web listening", "".join(output))
                self.assertIn("initialize", server.methods)
                self.assertIn("thread/list", server.methods)
                self.assertIn("thread/resume", server.methods)
            finally:
                if process.returncode is None:
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
            self.assertFalse((home / "forbidden-tool.log").exists())

            web_url = await self.run_command([str(launcher), "web"], environment)
            self.assertEqual(web_url.returncode, 0, web_url.stderr)
            self.assertEqual(web_url.stdout.strip(), "http://127.0.0.1:0")

    async def test_reinstall_preserves_config_and_clients_and_removes_old_payload(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home, environment, _, _ = self.prepare_home(directory)
            config = home / ".config" / "foreman" / "foreman.env"
            config.write_text(
                config.read_text(encoding="utf-8") + "FOREMAN_REMOTE_RESTART=1\n",
                encoding="utf-8",
            )
            state_dir = home / ".local" / "state" / "foreman"
            state_dir.mkdir(parents=True)
            state = state_dir / "state.json"
            state.write_text(
                '{"pairings":[],"devices":[{"id":"fmc_old","digest":"abc",'
                '"name":"Prior phone","type":"android","createdAt":1}]}\n',
                encoding="utf-8",
            )
            expected_config = config.read_bytes()
            expected_state = state.read_bytes()

            first = await self.run_command([str(ROOT / "install.sh")], environment)
            self.assertEqual(first.returncode, 0, first.stderr)
            install_dir = home / ".local" / "share" / "foreman"
            obsolete = install_dir / "removed-after-prior-alpha.txt"
            obsolete.write_text("old payload\n", encoding="utf-8")
            (install_dir / "foreman_service.py").write_text(
                "prior alpha payload\n", encoding="utf-8"
            )

            upgraded = await self.run_command([str(ROOT / "install.sh")], environment)
            self.assertEqual(upgraded.returncode, 0, upgraded.stderr)
            self.assertEqual(config.read_bytes(), expected_config)
            self.assertEqual(state.read_bytes(), expected_state)
            self.assertFalse(obsolete.exists())
            self.assertIn(
                "small authenticated TCP bridge",
                (install_dir / "foreman_service.py").read_text(encoding="utf-8"),
            )

    async def test_claude_only_reinstall_preserves_paths_and_provider_choice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = self.copy_install_payload(Path(directory) / "source-payload")
            home, environment, _, _ = self.prepare_home(
                directory,
                providers=("claude-code",),
            )
            config = home / ".config/foreman/foreman.env"
            state = home / ".local/state/foreman/state.json"
            state.parent.mkdir(parents=True)
            state.write_text(
                '{"pairings":[],"devices":[],"providerEnabled":'
                '{"codex":false,"claude-code":true}}\n',
                encoding="utf-8",
            )
            expected_config = config.read_bytes()
            expected_state = state.read_bytes()

            first = await self.run_command([str(script)], environment)
            npm = home / "fake-bin/npm"
            npm.write_text("#!/bin/sh\nexit 74\n", encoding="utf-8")
            npm.chmod(0o755)
            second = await self.run_command([str(script)], environment)

            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(config.read_bytes(), expected_config)
            self.assertEqual(state.read_bytes(), expected_state)
            self.assertEqual(
                (home / "npm.log").read_text(encoding="utf-8").count(
                    "ci --omit=dev --ignore-scripts"
                ),
                1,
            )

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
            recovery_unit = (
                home / ".config" / "systemd" / "user"
                / "foreman-update-recovery.service"
            )
            recovery_unit.write_text("old recovery unit\n", encoding="utf-8")
            helper = home / ".local" / "libexec" / "foreman-updater"
            helper.parent.mkdir(parents=True)
            helper.write_text("old helper\n", encoding="utf-8")
            state = home / ".local" / "state" / "foreman" / "state.json"
            state.parent.mkdir(parents=True)
            state.write_text('{"devices":[{"id":"fmc_old"}]}\n', encoding="utf-8")
            config = home / ".config" / "foreman" / "foreman.env"
            expected_config = config.read_bytes()
            expected_state = state.read_bytes()

            installed = await self.run_command(
                [str(ROOT / "install.sh")],
                environment,
            )

            self.assertNotEqual(installed.returncode, 0)
            self.assertEqual(old_service.read_text(encoding="utf-8"), "old service\n")
            self.assertTrue(obsolete_venv.is_dir())
            self.assertEqual(launcher.read_text(encoding="utf-8"), "old launcher\n")
            self.assertEqual(unit.read_text(encoding="utf-8"), "old unit\n")
            self.assertEqual(
                recovery_unit.read_text(encoding="utf-8"),
                "old recovery unit\n",
            )
            self.assertEqual(helper.read_text(encoding="utf-8"), "old helper\n")
            self.assertEqual(config.read_bytes(), expected_config)
            self.assertEqual(state.read_bytes(), expected_state)
