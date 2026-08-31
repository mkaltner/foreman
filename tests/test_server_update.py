from __future__ import annotations

import asyncio
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "linux"))

from server_update import (  # noqa: E402
    DOWNLOAD_PREFIX,
    OperationStore,
    RELEASE_API_PREFIX,
    ServerUpdateManager,
    UpdateFailure,
    public_operation,
    recover_latest,
    release_assets,
    run_external_helper,
    safe_extract,
    signed_checksums,
    verify_certificate_and_signature,
)
from foreman_updater import prepare_runtime  # noqa: E402
from state import State  # noqa: E402


TAG = "v1.0.3"
VERSION = "1.0.3"


def canonical(name: str) -> str:
    return f"{DOWNLOAD_PREFIX}{TAG}/{name}"


def release_manifest(*, missing: str | None = None, bad_url: bool = False) -> bytes:
    names = [
        f"foreman-{TAG}.apk",
        f"foreman-linux-{TAG}.tar.gz",
        "SHA256SUMS",
        "SHA256SUMS.sig",
        "foreman-release-cert.pem",
    ]
    return json.dumps({
        "tag_name": TAG,
        "draft": False,
        "prerelease": False,
        "assets": [
            {
                "name": name,
                "size": 10,
                "browser_download_url": "https://evil.invalid/payload" if bad_url and name.startswith("foreman-linux") else canonical(name),
            }
            for name in names if name != missing
        ],
    }).encode()


class FakeCache:
    def __init__(self, version: str | None = VERSION) -> None:
        self.version = version

    def snapshot(self):
        release = None if self.version is None else {
            "version": self.version,
            "tag": f"v{self.version}",
            "title": f"Foreman {self.version}",
            "publishedAt": "2026-08-31T00:00:00Z",
            "releaseNotesUrl": f"https://github.com/mkaltner/foreman/releases/tag/v{self.version}",
            "artifactAvailable": True,
        }
        return {"components": {"server": {"supportedRelease": release}}}

    async def refresh(self, *, manual=False):
        return self.snapshot()


class FakeFetcher:
    def __init__(self, values: dict[str, bytes]) -> None:
        self.values = values
        self.calls: list[str] = []

    def fetch(self, url: str, maximum: int) -> bytes:
        self.calls.append(url)
        value = self.values[url]
        if len(value) > maximum:
            raise UpdateFailure("downloadTooLarge", "too large")
        return value


def make_certificate(directory: Path) -> tuple[bytes, bytes, str, Path]:
    key = directory / "key.pem"
    cert = directory / "cert.pem"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=Foreman test", "-keyout", str(key), "-out", str(cert), "-days", "1"],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    der = ssl.PEM_cert_to_DER_cert(cert.read_text(encoding="ascii"))
    return key.read_bytes(), cert.read_bytes(), hashlib.sha256(der).hexdigest(), key


def make_archive(directory: Path) -> bytes:
    payload = directory / "payload"
    shutil.copytree(
        ROOT / "linux",
        payload / "linux",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "node_modules"),
    )
    shutil.copytree(ROOT / "web" / "dist", payload / "web" / "dist")
    shutil.copy2(ROOT / "install.sh", payload / "install.sh")
    (payload / "release.properties").write_text(
        "foremanVersion=1.0.3\nreleaseBuild=true\nandroidVersionCode=10\nprotocolVersion=1\n"
        "androidSigningCertificateSha256=placeholder\n",
        encoding="utf-8",
    )
    archive = directory / "release.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        for path in sorted(payload.rglob("*")):
            bundle.add(path, path.relative_to(payload).as_posix(), recursive=False)
    return archive.read_bytes()


class ProvenanceTests(unittest.TestCase):
    def test_release_manifest_requires_exact_assets_and_canonical_urls(self) -> None:
        self.assertEqual(len(release_assets(release_manifest(), TAG)), 5)
        with self.assertRaisesRegex(UpdateFailure, "missing"):
            release_assets(release_manifest(missing="SHA256SUMS.sig"), TAG)
        with self.assertRaisesRegex(UpdateFailure, "untrusted"):
            release_assets(release_manifest(bad_url=True), TAG)
        unexpected = json.loads(release_manifest())
        unexpected["assets"].append({
            "name": "install.sh", "size": 10,
            "browser_download_url": canonical("install.sh"),
        })
        with self.assertRaisesRegex(UpdateFailure, "unexpected"):
            release_assets(json.dumps(unexpected).encode(), TAG)

    def test_pinned_certificate_signature_and_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, cert_bytes, fingerprint, key = make_certificate(root)
            manifest = root / "SHA256SUMS"
            manifest.write_bytes(b"0" * 64 + b"  foreman-v1.0.3.apk\n" + b"1" * 64 + b"  foreman-linux-v1.0.3.tar.gz\n")
            certificate = root / "release.pem"
            certificate.write_bytes(cert_bytes)
            signature = root / "SHA256SUMS.sig"
            subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(signature), str(manifest)], check=True)
            verify_certificate_and_signature(certificate, signature, manifest, fingerprint)
            manifest.write_bytes(manifest.read_bytes() + b"tampered")
            with self.assertRaisesRegex(UpdateFailure, "signature"):
                verify_certificate_and_signature(certificate, signature, manifest, fingerprint)
            with self.assertRaisesRegex(UpdateFailure, "not trusted"):
                verify_certificate_and_signature(certificate, signature, manifest, "0" * 64)

    def test_signed_manifest_rejects_missing_duplicate_and_unexpected_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "SHA256SUMS"
            path.write_text(f"{'0' * 64}  foreman-{TAG}.apk\n", encoding="ascii")
            with self.assertRaises(UpdateFailure):
                signed_checksums(path, TAG)
            path.write_text(
                f"{'0' * 64}  foreman-{TAG}.apk\n{'1' * 64}  foreman-linux-{TAG}.tar.gz\n{'2' * 64}  extra\n",
                encoding="ascii",
            )
            with self.assertRaises(UpdateFailure):
                signed_checksums(path, TAG)

    def test_extraction_rejects_traversal_and_links(self) -> None:
        for name, kind in (("../escape", "file"), ("linux/link", "link")):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = root / "bad.tar.gz"
                with tarfile.open(archive, "w:gz") as bundle:
                    info = tarfile.TarInfo(name)
                    if kind == "link":
                        info.type = tarfile.SYMTYPE
                        info.linkname = "/etc/passwd"
                        bundle.addfile(info)
                    else:
                        info.size = 1
                        bundle.addfile(info, io.BytesIO(b"x"))
                with self.assertRaisesRegex(UpdateFailure, "unsafe"):
                    safe_extract(archive, root / "out")


class ManagerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        key_bytes, cert_bytes, self.fingerprint, key = make_certificate(self.root)
        del key_bytes
        archive = make_archive(self.root)
        payload_root = self.root / "payload"
        metadata = payload_root / "release.properties"
        metadata.write_text(metadata.read_text().replace("placeholder", self.fingerprint), encoding="utf-8")
        # Rebuild after inserting the pinned trust anchor.
        archive = make_archive_with_payload(self.root, payload_root)
        sums = (
            f"{'0' * 64}  foreman-{TAG}.apk\n"
            f"{hashlib.sha256(archive).hexdigest()}  foreman-linux-{TAG}.tar.gz\n"
        ).encode()
        manifest_path = self.root / "sums"
        manifest_path.write_bytes(sums)
        signature_path = self.root / "sig"
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(signature_path), str(manifest_path)], check=True)
        self.values = {
            RELEASE_API_PREFIX + TAG: release_manifest(),
            canonical(f"foreman-linux-{TAG}.tar.gz"): archive,
            canonical("SHA256SUMS"): sums,
            canonical("SHA256SUMS.sig"): signature_path.read_bytes(),
            canonical("foreman-release-cert.pem"): cert_bytes,
        }
        self.blockers: list[dict[str, object]] = []
        self.helper_calls: list[str] = []

    async def asyncTearDown(self) -> None:
        self.temporary.cleanup()

    def manager(self, cache: FakeCache | None = None, fetcher: FakeFetcher | None = None) -> ServerUpdateManager:
        async def safety():
            return list(self.blockers)

        async def helper(operation_id: str):
            self.helper_calls.append(operation_id)
            return 0

        return ServerUpdateManager(
            state_directory=self.root / "state",
            install_directory=self.root / "install",
            launcher_file=self.root / "bin/foreman",
            unit_file=self.root / "unit/foreman.service",
            helper_file=self.root / "libexec/foreman-updater",
            current_version="1.0.2",
            release_build=True,
            protocol_version=1,
            trust_fingerprint=self.fingerprint,
            release_cache=cache or FakeCache(),
            safety_check=safety,
            fetcher=fetcher or FakeFetcher(dict(self.values)),
            helper_runner=helper,
        )

    async def test_no_update_downgrade_and_active_categories(self) -> None:
        current = self.manager(FakeCache("1.0.2"))
        self.assertFalse((await current.check())["updateAvailable"])
        downgrade = self.manager(FakeCache("1.0.1"))
        self.assertFalse((await downgrade.check())["updateAvailable"])
        with self.assertRaisesRegex(UpdateFailure, "already current"):
            await downgrade.start()
        recovering = self.manager()
        recovering.store.write({
            "id": "fmu_1234567890abcdef", "phase": "recoveryRequired",
            "createdAt": "2026-08-31T00:00:00Z",
        })
        with self.assertRaisesRegex(UpdateFailure, "recover"):
            await recovering.start("new-update")
        shutil.rmtree(self.root / "state", ignore_errors=True)
        self.blockers = [{"category": "pendingInput", "count": 1}]
        with self.assertRaisesRegex(UpdateFailure, "blocked"):
            await self.manager().start()

    async def test_valid_update_is_verified_staged_and_scheduled(self) -> None:
        manager = self.manager()
        started = await manager.start("web_request")
        await manager.task
        operation = manager.store.read(started["id"])
        self.assertEqual(operation["phase"], "activationScheduled")
        self.assertEqual(self.helper_calls, [started["id"]])
        self.assertTrue((manager.store.operation_directory(started["id"]) / "staged-install/foreman_service.py").is_file())

    async def test_missing_corrupt_and_untrusted_inputs_fail_closed(self) -> None:
        cases: list[tuple[str, dict[str, bytes], str]] = []
        missing = dict(self.values)
        missing[RELEASE_API_PREFIX + TAG] = release_manifest(missing="SHA256SUMS.sig")
        cases.append(("missing", missing, "missingAsset"))
        corrupted = dict(self.values)
        corrupted[canonical(f"foreman-linux-{TAG}.tar.gz")] += b"corrupt"
        cases.append(("checksum", corrupted, "verificationFailed"))
        untrusted = dict(self.values)
        untrusted[RELEASE_API_PREFIX + TAG] = release_manifest(bad_url=True)
        cases.append(("source", untrusted, "untrustedSource"))
        for label, values, code in cases:
            with self.subTest(label=label):
                manager = self.manager(fetcher=FakeFetcher(values))
                started = await manager.start(label)
                await manager.task
                operation = manager.store.read(started["id"])
                self.assertEqual(operation["phase"], "failed")
                self.assertEqual(operation["resultCode"], code)
                # Permit the next subtest to create its own latest operation.
                shutil.rmtree(self.root / "state", ignore_errors=True)

    async def test_race_concurrency_idempotency_and_durable_recovery(self) -> None:
        calls = 0

        async def safety():
            nonlocal calls
            calls += 1
            return [] if calls == 1 else [{"category": "workingSession", "count": 1}]

        manager = self.manager()
        manager.safety_check = safety
        first = await manager.start("same")
        duplicate = await manager.start("same")
        self.assertEqual(first["id"], duplicate["id"])
        with self.assertRaisesRegex(UpdateFailure, "already running"):
            await manager.start("other")
        await manager.task
        self.assertEqual(manager.store.read(first["id"])["phase"], "blocked")
        reloaded = OperationStore(self.root / "state")
        self.assertEqual(reloaded.read(first["id"])["phase"], "blocked")

    async def test_remote_authorization_is_rechecked_before_activation(self) -> None:
        checked: list[str] = []

        async def authorization(principal: str) -> bool:
            checked.append(principal)
            return False

        manager = self.manager()
        manager.authorization_check = authorization
        started = await manager.start("remote", "device:fmc_revoked")
        self.assertNotIn("authorizationPrincipal", started)
        self.assertNotIn("requestId", started)
        await manager.task
        operation = manager.store.read(started["id"])
        self.assertEqual(checked, ["device:fmc_revoked"])
        self.assertEqual(operation["phase"], "failed")
        self.assertEqual(operation["resultCode"], "authorizationRevoked")
        self.assertEqual(self.helper_calls, [])

    async def test_interruption_during_download_is_recoverable(self) -> None:
        entered = threading.Event()
        release = threading.Event()

        class BlockingFetcher(FakeFetcher):
            def fetch(inner_self, url, maximum):
                if url.startswith(RELEASE_API_PREFIX):
                    entered.set()
                    release.wait(5)
                return super().fetch(url, maximum)

        manager = self.manager(fetcher=BlockingFetcher(dict(self.values)))
        started = await manager.start("cancel")
        for _ in range(100):
            if entered.is_set():
                break
            await asyncio.sleep(0.02)
        self.assertTrue(entered.is_set())
        # Downloading must not monopolize the cross-process coordination lock.
        with manager.store.lock():
            pass
        stopping = asyncio.create_task(manager.stop())
        release.set()
        await stopping
        self.assertEqual(manager.store.read(started["id"])["phase"], "interrupted")

    async def test_interruption_during_staging_is_recoverable(self) -> None:
        entered = threading.Event()
        release = threading.Event()
        original = safe_extract

        def blocking_extract(archive: Path, destination: Path) -> None:
            entered.set()
            release.wait(5)
            original(archive, destination)

        manager = self.manager()
        with patch("server_update.safe_extract", side_effect=blocking_extract):
            started = await manager.start("stage-cancel")
            for _ in range(100):
                if entered.is_set():
                    break
                await asyncio.sleep(0.02)
            self.assertTrue(entered.is_set())
            stopping = asyncio.create_task(manager.stop())
            release.set()
            await stopping
        self.assertEqual(manager.store.read(started["id"])["phase"], "interrupted")

    async def test_helper_terminal_outcomes_are_not_overwritten(self) -> None:
        for phase, result in (("rolledBack", 1), ("recoveryRequired", 3)):
            with self.subTest(phase=phase):
                manager = self.manager()

                async def helper(operation_id: str, outcome=phase, code=result) -> int:
                    manager.store.transition(
                        operation_id, outcome, progress=100,
                        resultCode="rollbackSucceeded" if outcome == "rolledBack" else "rollbackFailed",
                        message="bounded helper outcome",
                    )
                    return code

                manager.helper_runner = helper
                started = await manager.start(f"outcome-{phase}")
                await manager.task
                self.assertEqual(manager.store.read(started["id"])["phase"], phase)
                shutil.rmtree(self.root / "state", ignore_errors=True)

    async def test_transient_helper_restarts_only_after_unexpected_failure(self) -> None:
        manager = self.manager()

        class Process:
            async def wait(self) -> int:
                return 0

        with patch("asyncio.create_subprocess_exec", return_value=Process()) as spawn:
            self.assertEqual(await manager._systemd_run_helper("fmu_" + "c" * 32), 0)
        arguments = spawn.call_args.args
        self.assertIn("--property=Restart=on-failure", arguments)
        self.assertIn("--property=RestartSec=1s", arguments)
        self.assertIn("--property=RestartPreventExitStatus=1 2 3 4", arguments)

    async def test_final_safety_check_linearizes_with_new_work(self) -> None:
        activation_lock = asyncio.Lock()
        entered = asyncio.Event()
        release = asyncio.Event()
        checks = 0

        async def safety():
            nonlocal checks
            checks += 1
            if checks == 2:
                entered.set()
                await release.wait()
            return []

        manager = self.manager()
        manager.activation_lock = activation_lock
        manager.safety_check = safety
        started = await manager.start("safety-linearization")
        await entered.wait()
        observed: list[str] = []

        async def begin_work() -> None:
            async with activation_lock:
                operation = manager.store.read(started["id"])
                observed.append(operation["phase"])

        work = asyncio.create_task(begin_work())
        await asyncio.sleep(0)
        self.assertFalse(work.done())
        release.set()
        await manager.task
        await work
        self.assertEqual(observed, ["activationScheduled"])


def make_archive_with_payload(directory: Path, payload: Path) -> bytes:
    archive = directory / "release-final.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        for path in sorted(payload.rglob("*")):
            bundle.add(path, path.relative_to(payload).as_posix(), recursive=False)
    return archive.read_bytes()


class HelperTests(unittest.TestCase):
    def prepare(self, root: Path) -> tuple[OperationStore, str, dict[str, Path]]:
        state = root / "state"
        store = OperationStore(state)
        operation_id = "fmu_" + "a" * 32
        store.write({
            "id": operation_id, "schema": 1, "phase": "activationScheduled",
            "currentVersion": "1.0.2", "targetVersion": "1.0.3",
            "createdAt": "2026-01-01T00:00:00Z",
            "protocolVersion": 1, "healthPort": 9999,
            "authorizationPrincipal": "local",
        })
        operation_dir = store.operation_directory(operation_id)
        staged = operation_dir / "staged-install"
        staged.mkdir(parents=True)
        (staged / "release.properties").write_text("protocolVersion=1\n", encoding="utf-8")
        (staged / "payload").write_text("new", encoding="utf-8")
        for name in ("staged-launcher", "staged-unit", "staged-helper"):
            (operation_dir / name).write_text("new", encoding="utf-8")
        paths = {
            "install": root / "install",
            "launcher": root / "bin/foreman",
            "unit": root / "unit/foreman.service",
            "helper": root / "libexec/foreman-updater",
        }
        paths["install"].mkdir()
        (paths["install"] / "payload").write_text("old", encoding="utf-8")
        for key in ("launcher", "unit", "helper"):
            paths[key].parent.mkdir(parents=True, exist_ok=True)
            paths[key].write_text("old", encoding="utf-8")
        return store, operation_id, paths

    def test_external_helper_completes_activation_after_service_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            with patch("server_update._systemctl"), patch("server_update._health", return_value=True):
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            self.assertEqual(result, 0)
            self.assertEqual(store.read(operation_id)["phase"], "succeeded")
            self.assertEqual((paths["install"] / "payload").read_text(), "new")
            self.assertEqual(paths["launcher"].read_text(), "new")
            self.assertFalse((store.operation_directory(operation_id) / "backup").exists())

    def test_interrupted_activation_is_restarted_as_rollback(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            operation_dir = store.operation_directory(operation_id)
            backup = operation_dir / "backup"
            backup.mkdir(parents=True)
            for destination, name in (
                (paths["launcher"], "launcher"),
                (paths["unit"], "unit"),
                (paths["helper"], "helper"),
            ):
                shutil.copy2(destination, backup / name)
            store.transition(
                operation_id, "activating", activationPrepared=True,
                launcherExisted=True, unitExisted=True, helperExisted=True,
            )
            # Simulate the helper dying after the first atomic directory rename.
            os.replace(paths["install"], backup / "install")
            self.assertFalse(paths["install"].exists())
            with patch("server_update._systemctl"), patch("server_update._health", return_value=True):
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            self.assertEqual(result, 1)
            self.assertEqual(store.read(operation_id)["phase"], "rolledBack")
            self.assertEqual((paths["install"] / "payload").read_text(), "old")

    def test_helper_rechecks_remote_authorization_at_activation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            store.transition(operation_id, "activationScheduled", authorizationPrincipal="device:fmc_revoked")
            (root / "state/state.json").write_text('{"devices":[]}\n', encoding="utf-8")
            with patch("server_update._systemctl") as systemctl:
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            self.assertEqual(result, 4)
            self.assertEqual(store.read(operation_id)["resultCode"], "authorizationRevoked")
            self.assertEqual((paths["install"] / "payload").read_text(), "old")
            systemctl.assert_not_called()

    def test_revocation_linearizes_after_first_activation_rename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            state = State(root / "state")
            state.path.write_text(
                '{"devices":[{"id":"fmc_authorized","access":"full","digest":"unused"}]}\n',
                encoding="utf-8",
            )
            store.transition(
                operation_id, "activationScheduled",
                authorizationPrincipal="device:fmc_authorized",
            )
            original_replace = os.replace
            revocation_started = threading.Event()
            revocation_finished = threading.Event()
            blocked_at_rename: list[bool] = []
            revocation: threading.Thread | None = None

            def replace(source, destination):
                nonlocal revocation
                if Path(source) == paths["install"]:
                    def revoke() -> None:
                        revocation_started.set()
                        state.revoke_device("fmc_authorized")
                        revocation_finished.set()

                    revocation = threading.Thread(target=revoke)
                    revocation.start()
                    self.assertTrue(revocation_started.wait(1))
                    revocation.join(0.2)
                    blocked_at_rename.append(revocation.is_alive())
                return original_replace(source, destination)

            with (
                patch("server_update.os.replace", side_effect=replace),
                patch("server_update._systemctl"),
                patch("server_update._health", return_value=True),
            ):
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            assert revocation is not None
            revocation.join(1)
            self.assertEqual(result, 0)
            self.assertEqual(blocked_at_rename, [True])
            self.assertTrue(revocation_finished.is_set())
            self.assertEqual(state.list_devices(), [])

    def test_helper_runtime_survives_install_directory_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            install = root / "install"
            install.mkdir()
            for name in ("server_update.py", "release_updates.py", "state.py"):
                shutil.copy2(ROOT / "linux" / name, install / name)
            operation_id = "fmu_" + "b" * 32
            runtime = prepare_runtime(root / "state", operation_id, install)
            shutil.rmtree(install)
            self.assertEqual(prepare_runtime(root / "state", operation_id, install), runtime)
            self.assertTrue(all((runtime / name).is_file() for name in ("server_update.py", "release_updates.py", "state.py")))

    def test_failed_health_check_rolls_back_and_preserves_external_state(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            config = root / "config/foreman.env"
            config.parent.mkdir()
            config.write_text("TOKEN=never-log-this\n", encoding="utf-8")
            paired = root / "state/state.json"
            paired.write_text('{"devices":[{"id":"fmc_keep"}]}\n', encoding="utf-8")
            expected_config, expected_state = config.read_bytes(), paired.read_bytes()
            with patch("server_update._systemctl"), patch("server_update._health", side_effect=[False, True]):
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            self.assertEqual(result, 1)
            self.assertEqual((paths["install"] / "payload").read_text(), "old")
            self.assertEqual(store.read(operation_id)["phase"], "rolledBack")
            self.assertFalse((store.operation_directory(operation_id) / "backup").exists())
            self.assertEqual(config.read_bytes(), expected_config)
            self.assertEqual(paired.read_bytes(), expected_state)

    def test_failed_rollback_retains_backup_and_safe_recovery_instruction(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store, operation_id, paths = self.prepare(root)
            with patch("server_update._systemctl"), patch("server_update._health", return_value=False):
                result = run_external_helper(
                    operation_id=operation_id, state_directory=root / "state",
                    install_directory=paths["install"], launcher_file=paths["launcher"],
                    unit_file=paths["unit"], helper_file=paths["helper"], health_port=9999,
                )
            self.assertEqual(result, 3)
            operation = store.read(operation_id)
            self.assertEqual(operation["phase"], "recoveryRequired")
            self.assertEqual(operation["recoveryCommand"], "foreman update --recover")
            self.assertTrue((store.operation_directory(operation_id) / "backup/install").is_dir())
            self.assertNotIn(str(root), json.dumps(public_operation(operation)))

            with patch("server_update._systemctl"), patch("server_update._health", return_value=True):
                recovered = recover_latest(
                    state_directory=root / "state", install_directory=paths["install"],
                    launcher_file=paths["launcher"], unit_file=paths["unit"],
                    helper_file=paths["helper"],
                )
            self.assertEqual(recovered, 0)
            self.assertEqual(store.read(operation_id)["phase"], "rolledBack")
            self.assertEqual((paths["install"] / "payload").read_text(), "old")
            self.assertFalse((store.operation_directory(operation_id) / "backup").exists())


if __name__ == "__main__":
    unittest.main()
