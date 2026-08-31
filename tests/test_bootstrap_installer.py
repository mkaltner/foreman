from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts/install-foreman.sh"
API = "https://api.github.com/repos/mkaltner/foreman/releases"
DOWNLOAD = "https://github.com/mkaltner/foreman/releases/download"
TAG = "v1.2.3"

REQUIRED_FILES = {
    "install.sh",
    "release.properties",
    "requirements.txt",
    "linux/foreman",
    "linux/foreman.service",
    "linux/foreman-update-recovery.service",
    "linux/foreman_service.py",
    "linux/codex.py",
    "linux/approvals.py",
    "linux/inputs.py",
    "linux/protocol.py",
    "linux/state.py",
    "linux/diagnostics.py",
    "linux/claude_code.py",
    "linux/session_identity.py",
    "linux/release_updates.py",
    "linux/server_update.py",
    "linux/update_cli.py",
    "linux/foreman_updater.py",
    "linux/claude_bridge/bridge.mjs",
    "linux/claude_bridge/package.json",
    "linux/claude_bridge/package-lock.json",
    "linux/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json",
    "web/dist/index.html",
}


def make_certificate(directory: Path, name: str) -> tuple[Path, Path, str]:
    key = directory / f"{name}-key.pem"
    certificate = directory / f"{name}-cert.pem"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-subj",
            f"/CN={name}",
            "-keyout",
            str(key),
            "-out",
            str(certificate),
            "-days",
            "1",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    der = ssl.PEM_cert_to_DER_cert(certificate.read_text(encoding="ascii"))
    return key, certificate, hashlib.sha256(der).hexdigest()


class BootstrapInstallerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="foreman-bootstrap-test-")
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.temp_root = self.root / "tmp"
        self.temp_root.mkdir()
        self.fake_bin = self.root / "bin"
        self.fake_bin.mkdir()
        self.key, self.certificate, self.fingerprint = make_certificate(self.root, "Foreman test")
        _, self.other_certificate, _ = make_certificate(self.root, "Untrusted test")
        self.script = self.root / "install-foreman.sh"
        source = SCRIPT.read_text(encoding="utf-8")
        source = source.replace(
            "80d479d1a8f9f038c6977a1cfb68a2b45c3117492c364620e48babebf1810ad3",
            self.fingerprint,
        )
        self.script.write_text(source, encoding="utf-8")
        self.script.chmod(0o755)
        self.install_log = self.root / "install.log"
        self.curl_log = self.root / "curl.log"
        self._write_fake_curl()
        self.fixture = self._make_release_fixture(TAG)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_fake_curl(self) -> None:
        curl = self.fake_bin / "curl"
        curl.write_text(
            "#!/usr/bin/python3\n"
            "import json, os, pathlib, shutil, signal, sys, time\n"
            "args = sys.argv[1:]\n"
            "def value(name):\n"
            "    return args[args.index(name) + 1]\n"
            "url = args[-1]\n"
            "entry = json.loads(os.environ['FOREMAN_TEST_CURL_MAP'])[url]\n"
            "with open(os.environ['FOREMAN_TEST_CURL_LOG'], 'a', encoding='utf-8') as log:\n"
            "    log.write(url + '\\n')\n"
            "if entry.get('signal'):\n"
            "    os.kill(os.getppid(), signal.SIGTERM)\n"
            "    time.sleep(0.1)\n"
            "    raise SystemExit(143)\n"
            "if entry.get('exit') is not None:\n"
            "    raise SystemExit(entry['exit'])\n"
            "destination = pathlib.Path(value('--output'))\n"
            "if entry.get('path'):\n"
            "    shutil.copyfile(entry['path'], destination)\n"
            "else:\n"
            "    destination.write_bytes(entry.get('body', '').encode())\n"
            "print(entry.get('status', 200))\n"
            "print(entry.get('effective', url))\n",
            encoding="utf-8",
        )
        curl.chmod(0o755)

    def _make_release_fixture(
        self,
        tag: str,
        *,
        unsafe: tuple[str, str] | None = None,
        archive_bytes: bytes | None = None,
        manifest_digest: str | None = None,
    ) -> dict[str, Path | bytes | dict[str, object]]:
        fixture_root = self.root / ("fixture-" + tag.replace(".", "-"))
        counter = 1
        while fixture_root.exists():
            counter += 1
            fixture_root = self.root / ("fixture-" + tag.replace(".", "-") + f"-{counter}")
        payload = fixture_root / "payload"
        payload.mkdir(parents=True)
        for name in REQUIRED_FILES:
            path = payload / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture {name}\n", encoding="utf-8")
        (payload / "release.properties").write_text(
            f"foremanVersion={tag[1:]}\n"
            "releaseBuild=true\n"
            "androidVersionCode=1\n"
            "protocolVersion=1\n"
            f"androidSigningCertificateSha256={self.fingerprint}\n",
            encoding="utf-8",
        )
        (payload / "install.sh").write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "codex_path=\"$(command -v codex 2>/dev/null || true)\"\n"
            "claude_path=\"$(command -v claude 2>/dev/null || true)\"\n"
            "if [[ -z \"$codex_path\" && -z \"$claude_path\" ]]; then\n"
            "  echo 'at least one supported provider CLI is required' >&2\n"
            "  exit 2\n"
            "fi\n"
            "printf '%s|%s\\n' \"${codex_path:+codex}\" \"${claude_path:+claude}\" >> \"$FOREMAN_TEST_INSTALL_LOG\"\n"
            "mkdir -p \"$HOME/.local/share/foreman\"\n"
            "touch \"$HOME/.local/share/foreman/installed-by-fixture\"\n",
            encoding="utf-8",
        )
        (payload / "install.sh").chmod(0o755)
        archive = fixture_root / f"foreman-linux-{tag}.tar.gz"
        if archive_bytes is None:
            with tarfile.open(archive, "w:gz") as bundle:
                for path in sorted(payload.rglob("*")):
                    bundle.add(path, path.relative_to(payload).as_posix(), recursive=False)
                if unsafe is not None:
                    name, kind = unsafe
                    info = tarfile.TarInfo(name)
                    if kind == "symlink":
                        info.type = tarfile.SYMTYPE
                        info.linkname = "/etc/passwd"
                    elif kind == "hardlink":
                        info.type = tarfile.LNKTYPE
                        info.linkname = "install.sh"
                    elif kind == "device":
                        info.type = tarfile.CHRTYPE
                    else:
                        info.size = 1
                        bundle.addfile(info, io.BytesIO(b"x"))
                        info = None
                    if info is not None:
                        bundle.addfile(info)
        else:
            archive.parent.mkdir(parents=True, exist_ok=True)
            archive.write_bytes(archive_bytes)
        digest = manifest_digest or hashlib.sha256(archive.read_bytes()).hexdigest()
        manifest = fixture_root / "SHA256SUMS"
        manifest.write_text(
            f"{'0' * 64}  foreman-{tag}.apk\n{digest}  foreman-linux-{tag}.tar.gz\n",
            encoding="ascii",
        )
        signature = fixture_root / "SHA256SUMS.sig"
        subprocess.run(
            [
                "openssl",
                "dgst",
                "-sha256",
                "-sign",
                str(self.key),
                "-out",
                str(signature),
                str(manifest),
            ],
            check=True,
        )
        assets = []
        sizes = {
            f"foreman-{tag}.apk": 10,
            f"foreman-linux-{tag}.tar.gz": archive.stat().st_size,
            "SHA256SUMS": manifest.stat().st_size,
            "SHA256SUMS.sig": signature.stat().st_size,
            "foreman-release-cert.pem": self.certificate.stat().st_size,
        }
        for name, size in sizes.items():
            assets.append(
                {
                    "name": name,
                    "size": size,
                    "browser_download_url": f"{DOWNLOAD}/{tag}/{name}",
                }
            )
        release = {
            "tag_name": tag,
            "draft": False,
            "prerelease": False,
            "assets": assets,
        }
        return {
            "archive": archive,
            "manifest": manifest,
            "signature": signature,
            "certificate": self.certificate,
            "release": release,
        }

    def _mapping(
        self,
        metadata: object,
        fixture: dict[str, Path | bytes | dict[str, object]] | None = None,
        *,
        explicit: bool = False,
    ) -> dict[str, dict[str, object]]:
        fixture = fixture or self.fixture
        tag = str(fixture["release"]["tag_name"])  # type: ignore[index]
        metadata_file = self.root / f"metadata-{len(list(self.root.glob('metadata-*')))}.json"
        metadata_file.write_text(json.dumps(metadata), encoding="utf-8")
        metadata_url = f"{API}/tags/{tag}" if explicit else f"{API}?per_page=20"
        mapping: dict[str, dict[str, object]] = {metadata_url: {"path": str(metadata_file)}}
        for name, key in (
            ("SHA256SUMS", "manifest"),
            ("SHA256SUMS.sig", "signature"),
            ("foreman-release-cert.pem", "certificate"),
            (f"foreman-linux-{tag}.tar.gz", "archive"),
        ):
            mapping[f"{DOWNLOAD}/{tag}/{name}"] = {"path": str(fixture[key])}
        return mapping

    def _environment(
        self,
        mapping: dict[str, dict[str, object]],
        providers: tuple[str, ...] = ("codex",),
    ) -> dict[str, str]:
        for provider in ("codex", "claude"):
            path = self.fake_bin / provider
            if path.exists():
                path.unlink()
            if provider in providers:
                path.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
                path.chmod(0o755)
        return {
            **os.environ,
            "HOME": str(self.home),
            "TMPDIR": str(self.temp_root),
            "PATH": f"{self.fake_bin}:/usr/bin:/bin",
            "FOREMAN_TEST_CURL_MAP": json.dumps(mapping),
            "FOREMAN_TEST_CURL_LOG": str(self.curl_log),
            "FOREMAN_TEST_INSTALL_LOG": str(self.install_log),
        }

    def _run(
        self,
        mapping: dict[str, dict[str, object]],
        *,
        arguments: tuple[str, ...] = (),
        providers: tuple[str, ...] = ("codex",),
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["sh", str(self.script), *arguments],
            env=self._environment(mapping, providers),
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )

    def assert_temporary_directory_clean(self) -> None:
        self.assertEqual(list(self.temp_root.iterdir()), [])

    def test_latest_stable_skips_drafts_prereleases_and_incomplete_releases(self) -> None:
        valid = self.fixture["release"]
        draft = {**valid, "tag_name": "v9.0.0", "draft": True}
        prerelease = {**valid, "tag_name": "v8.0.0", "prerelease": True}
        incomplete = {**valid, "tag_name": "v7.0.0", "assets": []}
        result = self._run(self._mapping([draft, prerelease, incomplete, valid]))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"Resolved Foreman {TAG} from Official Foreman GitHub releases", result.stdout)
        self.assertIn("foreman pair", result.stdout)
        self.assertEqual(self.install_log.read_text(encoding="utf-8"), "codex|\n")
        self.assert_temporary_directory_clean()

    def test_latest_complete_stable_resolution_uses_numeric_version_order(self) -> None:
        older = self._make_release_fixture("v1.9.0")
        newest = self._make_release_fixture("v1.10.0")
        mapping = self._mapping([older["release"], newest["release"]], newest)
        result = self._run(mapping)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Resolved Foreman v1.10.0", result.stdout)
        self.assertIn(
            f"{DOWNLOAD}/v1.10.0/foreman-linux-v1.10.0.tar.gz",
            self.curl_log.read_text(encoding="utf-8"),
        )

    def test_explicit_version_uses_exact_stable_release(self) -> None:
        result = self._run(
            self._mapping(self.fixture["release"], explicit=True),
            arguments=("--version", TAG),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"{API}/tags/{TAG}", self.curl_log.read_text(encoding="utf-8"))

    def test_explicit_draft_and_prerelease_are_rejected_before_assets(self) -> None:
        for field in ("draft", "prerelease"):
            with self.subTest(field=field):
                release = {**self.fixture["release"], field: True}
                mapping = self._mapping(release, explicit=True)
                result = self._run(mapping, arguments=("--version", TAG))
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.install_log.exists())
                self.assertEqual(self.curl_log.read_text(encoding="utf-8").splitlines(), [f"{API}/tags/{TAG}"])
                self.curl_log.unlink()
                self.assert_temporary_directory_clean()

    def test_incomplete_duplicate_renamed_and_unexpected_asset_sets_fail_closed(self) -> None:
        base_assets = list(self.fixture["release"]["assets"])  # type: ignore[index]
        cases = {
            "missing": base_assets[:-1],
            "duplicate": base_assets + [base_assets[0]],
            "renamed": [{**base_assets[0], "name": "renamed.apk"}, *base_assets[1:]],
            "unexpected": [*base_assets, {"name": "install.sh", "size": 1, "browser_download_url": f"{DOWNLOAD}/{TAG}/install.sh"}],
        }
        for label, assets in cases.items():
            with self.subTest(label=label):
                release = {**self.fixture["release"], "assets": assets}
                result = self._run(self._mapping([release]))
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.install_log.exists())
                if self.curl_log.exists():
                    self.curl_log.unlink()
                self.assert_temporary_directory_clean()

    def test_malformed_api_network_rate_limit_and_untrusted_redirect_fail_cleanly(self) -> None:
        cases = (
            ({f"{API}?per_page=20": {"body": "not-json"}}, "complete stable"),
            ({f"{API}?per_page=20": {"exit": 7}}, "download failed"),
            ({f"{API}?per_page=20": {"status": 403, "body": "rate limited"}}, "rate limit"),
            ({f"{API}?per_page=20": {"body": "[]", "effective": "https://evil.invalid/releases"}}, "untrusted URL"),
        )
        for mapping, message in cases:
            with self.subTest(message=message):
                result = self._run(mapping)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.install_log.exists())
                if self.curl_log.exists():
                    self.curl_log.unlink()
                self.assert_temporary_directory_clean()

    def test_pinned_certificate_mismatch_bad_signature_and_bad_checksum_fail_before_install(self) -> None:
        mismatch = self._mapping([self.fixture["release"]])
        mismatch[f"{DOWNLOAD}/{TAG}/foreman-release-cert.pem"] = {"path": str(self.other_certificate)}

        bad_signature_file = self.root / "bad-signature"
        bad_signature_file.write_bytes(b"not a signature")
        bad_signature = self._mapping([self.fixture["release"]])
        bad_signature[f"{DOWNLOAD}/{TAG}/SHA256SUMS.sig"] = {"path": str(bad_signature_file)}

        checksum_fixture = self._make_release_fixture(TAG, manifest_digest="f" * 64)
        bad_checksum = self._mapping([checksum_fixture["release"]], checksum_fixture)

        for mapping, message in (
            (mismatch, "pinned identity"),
            (bad_signature, "signature verification"),
            (bad_checksum, "checksum verification"),
        ):
            with self.subTest(message=message):
                result = self._run(mapping)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.install_log.exists())
                if self.curl_log.exists():
                    self.curl_log.unlink()
                self.assert_temporary_directory_clean()

    def test_unsafe_archive_entries_are_rejected_before_install(self) -> None:
        cases = (
            ("/absolute", "file"),
            ("../traversal", "file"),
            ("linux/symlink", "symlink"),
            ("linux/hardlink", "hardlink"),
            ("linux/device", "device"),
        )
        for unsafe in cases:
            with self.subTest(unsafe=unsafe):
                fixture = self._make_release_fixture(TAG, unsafe=unsafe)
                result = self._run(self._mapping([fixture["release"]], fixture))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unsafe or incomplete", result.stderr)
                self.assertFalse(self.install_log.exists())
                if self.curl_log.exists():
                    self.curl_log.unlink()
                self.assert_temporary_directory_clean()

    def test_provider_matrix_and_same_version_reinstall_delegate_to_bundled_installer(self) -> None:
        mapping = self._mapping([self.fixture["release"]])
        for providers in (("codex",), ("claude",), ("codex", "claude")):
            with self.subTest(providers=providers):
                shutil.rmtree(self.home / ".local", ignore_errors=True)
                if self.install_log.exists():
                    self.install_log.unlink()
                first = self._run(mapping, providers=providers)
                second = self._run(mapping, providers=providers)
                self.assertEqual(first.returncode, 0, first.stderr)
                self.assertEqual(second.returncode, 0, second.stderr)
                self.assertEqual(len(self.install_log.read_text(encoding="utf-8").splitlines()), 2)
                self.assertTrue((self.home / ".local/share/foreman/installed-by-fixture").is_file())
        shutil.rmtree(self.home / ".local", ignore_errors=True)
        self.install_log.unlink()
        neither = self._run(mapping, providers=())
        self.assertNotEqual(neither.returncode, 0)
        self.assertIn("supported provider", neither.stderr)
        self.assertFalse((self.home / ".local").exists())

    def test_signal_interruption_cleans_private_temporary_directory(self) -> None:
        mapping = {f"{API}?per_page=20": {"signal": True}}
        result = self._run(mapping)
        self.assertEqual(result.returncode, 143)
        self.assertFalse(self.install_log.exists())
        self.assert_temporary_directory_clean()

    def test_script_pins_release_identity_and_never_uses_sudo(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        release_properties = dict(
            line.split("=", 1)
            for line in (ROOT / "release.properties").read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )
        self.assertIn(f'TRUSTED_CERT_SHA256="{release_properties["androidSigningCertificateSha256"]}"', source)
        self.assertNotIn("sudo", source)
        self.assertTrue(SCRIPT.stat().st_mode & 0o100)


if __name__ == "__main__":
    unittest.main()
