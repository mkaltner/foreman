"""Signed, durable, externally activated Foreman server updates."""

from __future__ import annotations

import asyncio
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import ssl
import subprocess
import tarfile
import time
from typing import Any, Awaitable, Callable, Iterator, Mapping
from urllib.parse import quote, urlsplit
import urllib.request

from release_updates import GITHUB_RELEASE_URL_PREFIX, SemVer


UPDATE_SCHEMA = 1
SOURCE = "Official Foreman GitHub releases"
SOURCE_URL = "https://github.com/mkaltner/foreman/releases"
RELEASE_API_PREFIX = "https://api.github.com/repos/mkaltner/foreman/releases/tags/"
DOWNLOAD_PREFIX = "https://github.com/mkaltner/foreman/releases/download/"
FINAL_DOWNLOAD_HOSTS = {
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
}
MAX_MANIFEST_BYTES = 512 * 1024
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_SMALL_ASSET_BYTES = 64 * 1024
MAX_EXTRACTED_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_FILES = 20_000
TERMINAL_PHASES = {
    "succeeded",
    "rolledBack",
    "recoveryRequired",
    "blocked",
    "failed",
    "interrupted",
}
ACTIVATION_PHASES = {
    "activationScheduled",
    "activating",
    "restarting",
    "healthChecking",
    "rollingBack",
}
OPERATION_ID = re.compile(r"^fmu_[A-Za-z0-9_-]{16,80}$")
REQUEST_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
CERT_DIGEST = re.compile(r"^[0-9a-f]{64}$")
CHECKSUM_LINE = re.compile(r"^([0-9a-fA-F]{64}) [ *]([^/]+)$")

REQUIRED_ARCHIVE_FILES = {
    "install.sh",
    "release.properties",
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
    "web/dist/index.html",
}
INSTALL_MODULES = (
    "foreman_service.py",
    "codex.py",
    "approvals.py",
    "inputs.py",
    "protocol.py",
    "state.py",
    "diagnostics.py",
    "claude_code.py",
    "session_identity.py",
    "release_updates.py",
    "server_update.py",
    "update_cli.py",
)


class UpdateFailure(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def utc_now(clock: Callable[[], float] = time.time) -> str:
    return datetime.fromtimestamp(clock(), timezone.utc).isoformat().replace("+00:00", "Z")


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key, value = stripped.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def public_operation(value: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if value is None:
        return None
    allowed = {
        "id",
        "phase",
        "currentVersion",
        "targetVersion",
        "source",
        "sourceUrl",
        "releaseNotesUrl",
        "progress",
        "createdAt",
        "updatedAt",
        "completedAt",
        "resultCode",
        "message",
        "recoveryCommand",
    }
    return {key: value[key] for key in allowed if key in value}


class OperationStore:
    """Private atomic operation records plus a cross-process update lock."""

    def __init__(self, state_directory: Path, clock: Callable[[], float] = time.time) -> None:
        self.root = state_directory / "updates"
        self.records = self.root / "operations"
        self.latest_path = self.root / "latest"
        self.lock_path = self.root / "update.lock"
        self.clock = clock

    def _prepare(self) -> None:
        self.records.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        os.chmod(self.records, 0o700)

    def operation_path(self, operation_id: str) -> Path:
        if not OPERATION_ID.fullmatch(operation_id):
            raise UpdateFailure("invalidOperation", "The update operation ID is invalid.")
        return self.records / f"{operation_id}.json"

    def read(self, operation_id: str | None = None) -> dict[str, Any] | None:
        try:
            if operation_id is None:
                operation_id = self.latest_path.read_text(encoding="ascii").strip()
            path = self.operation_path(operation_id)
            raw = path.read_bytes()
            if len(raw) > 32 * 1024:
                return None
            value = json.loads(raw)
            if not isinstance(value, dict) or value.get("schema") != UPDATE_SCHEMA:
                return None
            if value.get("id") != operation_id or value.get("phase") not in {
                "downloading", "verifying", "staging",
                "activationScheduled", "activating", "restarting",
                "healthChecking", "rollingBack", *TERMINAL_PHASES,
            }:
                return None
            return value
        except (OSError, UnicodeError, json.JSONDecodeError, UpdateFailure):
            return None

    def write(self, operation: dict[str, Any]) -> dict[str, Any]:
        self._prepare()
        operation = dict(operation)
        operation["schema"] = UPDATE_SCHEMA
        operation["updatedAt"] = utc_now(self.clock)
        path = self.operation_path(operation["id"])
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(operation, separators=(",", ":")) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        latest_tmp = self.latest_path.with_suffix(".tmp")
        latest_tmp.write_text(operation["id"] + "\n", encoding="ascii")
        os.chmod(latest_tmp, 0o600)
        os.replace(latest_tmp, self.latest_path)
        self._prune()
        return operation

    def transition(self, operation_id: str, phase: str, **updates: Any) -> dict[str, Any]:
        operation = self.read(operation_id)
        if operation is None:
            raise UpdateFailure("operationUnavailable", "The update operation is unavailable.")
        operation.update(updates)
        operation["phase"] = phase
        if phase in TERMINAL_PHASES:
            operation["completedAt"] = utc_now(self.clock)
        return self.write(operation)

    @contextmanager
    def lock(self, *, blocking: bool = False) -> Iterator[None]:
        self._prepare()
        with self.lock_path.open("a+", encoding="utf-8") as handle:
            os.chmod(self.lock_path, 0o600)
            flags = fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB)
            try:
                fcntl.flock(handle, flags)
            except BlockingIOError as error:
                raise UpdateFailure("updateConcurrent", "Another update operation is already running.") from error
            yield

    def operation_directory(self, operation_id: str) -> Path:
        self.operation_path(operation_id)
        return self.root / operation_id

    def recover_interrupted(self) -> None:
        operation = self.read()
        if operation is None or operation.get("phase") in TERMINAL_PHASES | ACTIVATION_PHASES:
            return
        self.transition(
            operation["id"],
            "interrupted",
            resultCode="interrupted",
            message="The update was interrupted before activation. Start it again when the host is safe.",
        )

    def _prune(self) -> None:
        records = sorted(self.records.glob("fmu_*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
        for stale in records[20:]:
            try:
                operation_id = stale.stem
                stale.unlink()
                shutil.rmtree(self.root / operation_id, ignore_errors=True)
            except OSError:
                pass


class UrlFetcher:
    def fetch(self, url: str, maximum: int) -> bytes:
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json" if url.startswith(RELEASE_API_PREFIX) else "application/octet-stream",
                "User-Agent": "Foreman-server-updater",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                final = urlsplit(response.geturl())
                if final.scheme != "https" or final.hostname not in FINAL_DOWNLOAD_HOSTS | {"api.github.com"}:
                    raise UpdateFailure("untrustedSource", "The release download redirected to an untrusted source.")
                content_length = response.headers.get("Content-Length")
                if content_length and int(content_length) > maximum:
                    raise UpdateFailure("downloadTooLarge", "A release asset exceeds the allowed size.")
                body = response.read(maximum + 1)
        except UpdateFailure:
            raise
        except (OSError, ValueError, TimeoutError, socket.timeout) as error:
            raise UpdateFailure("downloadFailed", "The official release could not be downloaded.") from error
        if len(body) > maximum:
            raise UpdateFailure("downloadTooLarge", "A release asset exceeds the allowed size.")
        return body


def release_assets(body: bytes, tag: str) -> dict[str, tuple[str, int]]:
    try:
        release = json.loads(body)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise UpdateFailure("untrustedSource", "The release metadata is invalid.") from error
    if (
        not isinstance(release, dict)
        or release.get("tag_name") != tag
        or release.get("draft") is not False
        or release.get("prerelease") is not False
    ):
        raise UpdateFailure("untrustedSource", "The release metadata is not an installable stable release.")
    expected = {
        f"foreman-{tag}.apk",
        f"foreman-linux-{tag}.tar.gz",
        "SHA256SUMS",
        "SHA256SUMS.sig",
        "foreman-release-cert.pem",
    }
    raw_assets = release.get("assets")
    if not isinstance(raw_assets, list) or len(raw_assets) > 50:
        raise UpdateFailure("missingAsset", "The release is missing required verified assets.")
    found: dict[str, tuple[str, int]] = {}
    for raw in raw_assets:
        if not isinstance(raw, dict):
            raise UpdateFailure("untrustedSource", "The release asset metadata is invalid.")
        name, url, size = raw.get("name"), raw.get("browser_download_url"), raw.get("size")
        if name not in expected:
            raise UpdateFailure("untrustedSource", "The release contains an unexpected custom asset.")
        if name in found or not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise UpdateFailure("missingAsset", "The release has duplicate or empty required assets.")
        canonical = f"{DOWNLOAD_PREFIX}{quote(tag, safe='')}/{quote(name, safe='')}"
        if url != canonical:
            raise UpdateFailure("untrustedSource", "A release asset has an untrusted download location.")
        found[name] = (url, size)
    if set(found) != expected:
        raise UpdateFailure("missingAsset", "The release is missing required verified assets.")
    if found[f"foreman-linux-{tag}.tar.gz"][1] > MAX_ARCHIVE_BYTES:
        raise UpdateFailure("downloadTooLarge", "The Linux release archive exceeds the allowed size.")
    for name in ("SHA256SUMS", "SHA256SUMS.sig", "foreman-release-cert.pem"):
        if found[name][1] > MAX_SMALL_ASSET_BYTES:
            raise UpdateFailure("downloadTooLarge", "A release verification asset exceeds the allowed size.")
    return found


def verify_certificate_and_signature(
    certificate: Path,
    signature: Path,
    manifest: Path,
    expected_fingerprint: str,
) -> None:
    if not CERT_DIGEST.fullmatch(expected_fingerprint):
        raise UpdateFailure("verificationFailed", "The installed release trust anchor is invalid.")
    try:
        pem = certificate.read_text(encoding="ascii")
        der = ssl.PEM_cert_to_DER_cert(pem)
    except (OSError, UnicodeError, ValueError) as error:
        raise UpdateFailure("verificationFailed", "The release signing certificate is invalid.") from error
    if hashlib.sha256(der).hexdigest() != expected_fingerprint:
        raise UpdateFailure("verificationFailed", "The release signing certificate is not trusted.")
    public_key = certificate.with_name("release-public-key.pem")
    try:
        extracted = subprocess.run(
            ["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10,
        )
        if extracted.returncode != 0 or not extracted.stdout:
            raise UpdateFailure("verificationFailed", "The release signing certificate is invalid.")
        public_key.write_bytes(extracted.stdout)
        os.chmod(public_key, 0o600)
        verified = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(public_key), "-signature", str(signature), str(manifest)],
            check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
        )
        if verified.returncode != 0:
            raise UpdateFailure("verificationFailed", "The release manifest signature is invalid.")
    except FileNotFoundError as error:
        raise UpdateFailure("verificationUnavailable", "OpenSSL is required to verify Foreman releases.") from error
    except subprocess.TimeoutExpired as error:
        raise UpdateFailure("verificationFailed", "Release signature verification timed out.") from error
    finally:
        try:
            public_key.unlink()
        except OSError:
            pass


def signed_checksums(manifest: Path, tag: str) -> dict[str, str]:
    try:
        lines = manifest.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError) as error:
        raise UpdateFailure("verificationFailed", "The signed checksum manifest is invalid.") from error
    expected = {f"foreman-{tag}.apk", f"foreman-linux-{tag}.tar.gz"}
    checksums: dict[str, str] = {}
    for line in lines:
        match = CHECKSUM_LINE.fullmatch(line)
        if match is None or match.group(2) in checksums:
            raise UpdateFailure("verificationFailed", "The signed checksum manifest is invalid.")
        checksums[match.group(2)] = match.group(1).lower()
    if set(checksums) != expected:
        raise UpdateFailure("verificationFailed", "The signed checksum manifest has an unexpected asset set.")
    return checksums


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, mode=0o700)
    total = 0
    names: set[str] = set()
    try:
        with tarfile.open(archive, "r:gz") as bundle:
            members = bundle.getmembers()
            if len(members) > MAX_ARCHIVE_FILES:
                raise UpdateFailure("verificationFailed", "The release archive contains too many files.")
            for member in members:
                pure = PurePosixPath(member.name)
                canonical_name = pure.as_posix()
                canonical_entry = (
                    member.name in {canonical_name, canonical_name + "/"}
                    if member.isdir()
                    else member.name == canonical_name
                )
                if (
                    not member.name
                    or pure.is_absolute()
                    or ".." in pure.parts
                    or not canonical_entry
                    or canonical_name in names
                    or member.issym()
                    or member.islnk()
                    or member.isdev()
                    or not (member.isfile() or member.isdir())
                ):
                    raise UpdateFailure("verificationFailed", "The release archive contains an unsafe entry.")
                names.add(canonical_name)
                if member.isfile():
                    total += member.size
                    if member.size < 0 or total > MAX_EXTRACTED_BYTES:
                        raise UpdateFailure("verificationFailed", "The release archive exceeds the extraction limit.")
            missing = sorted(REQUIRED_ARCHIVE_FILES - names)
            if missing:
                raise UpdateFailure("missingAsset", "The Linux archive is missing required Foreman files.")
            # Every member is validated above; avoid version-dependent tarfile
            # extraction-filter availability on supported Python 3.10+ hosts.
            bundle.extractall(destination, members=members)
    except UpdateFailure:
        raise
    except (OSError, tarfile.TarError) as error:
        raise UpdateFailure("verificationFailed", "The Linux release archive is invalid.") from error


def build_install_layout(extracted: Path, staged_install: Path, target_version: str, protocol_version: int, trust: str) -> None:
    metadata = properties(extracted / "release.properties")
    if (
        metadata.get("foremanVersion") != target_version
        or metadata.get("releaseBuild") != "true"
        or metadata.get("protocolVersion") != str(protocol_version)
        or metadata.get("androidSigningCertificateSha256") != trust
    ):
        raise UpdateFailure("incompatibleRelease", "The release metadata is incompatible with this installation.")
    staged_install.mkdir(parents=True, mode=0o700)
    for name in INSTALL_MODULES:
        shutil.copy2(extracted / "linux" / name, staged_install / name)
    for directory in ("vendor", "claude_bridge"):
        shutil.copytree(extracted / "linux" / directory, staged_install / directory)
    shutil.copytree(extracted / "web" / "dist", staged_install / "web")
    shutil.copy2(extracted / "release.properties", staged_install / "release.properties")
    subprocess.run(
        ["python3", "-m", "compileall", "-q", str(staged_install)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60,
    )
    subprocess.run(
        ["python3", str(staged_install / "foreman_service.py"), "--help"],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
    )


class ServerUpdateManager:
    def __init__(
        self,
        *,
        state_directory: Path,
        install_directory: Path,
        launcher_file: Path,
        unit_file: Path,
        helper_file: Path,
        current_version: str,
        release_build: bool,
        protocol_version: int,
        trust_fingerprint: str,
        release_cache: Any,
        safety_check: Callable[[], Awaitable[list[dict[str, Any]]]],
        authorization_check: Callable[[str], Awaitable[bool]] | None = None,
        fetcher: Any | None = None,
        helper_runner: Callable[[str], Awaitable[int]] | None = None,
        event_callback: Callable[[dict[str, Any]], Awaitable[None]] | None = None,
        activation_lock: asyncio.Lock | None = None,
        health_port: int = 8766,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.store = OperationStore(state_directory, clock)
        self.install_directory = install_directory
        self.launcher_file = launcher_file
        self.unit_file = unit_file
        self.helper_file = helper_file
        self.current_version = current_version
        self.release_build = release_build
        self.protocol_version = protocol_version
        self.trust_fingerprint = trust_fingerprint
        self.release_cache = release_cache
        self.safety_check = safety_check
        self.authorization_check = authorization_check
        self.fetcher = fetcher or UrlFetcher()
        self.external_helper_required = helper_runner is None
        self.helper_runner = helper_runner or self._systemd_run_helper
        self.event_callback = event_callback
        self.activation_lock = activation_lock or asyncio.Lock()
        self.health_port = health_port
        self.clock = clock
        self.task: asyncio.Task[None] | None = None
        self._async_lock = asyncio.Lock()
        self.store.recover_interrupted()

    async def check(self, *, refresh: bool = True) -> dict[str, Any]:
        snapshot = await self.release_cache.refresh(manual=True) if refresh else self.release_cache.snapshot()
        target = snapshot.get("components", {}).get("server", {}).get("supportedRelease")
        candidate = target if isinstance(target, dict) else None
        installed = SemVer.parse(self.current_version)
        target_semver = SemVer.parse(candidate.get("version", "")) if candidate else None
        update_available = bool(
            self.release_build and installed and target_semver and installed < target_semver
        )
        blockers = await self.safety_check()
        return {
            "currentVersion": self.current_version,
            "releaseBuild": self.release_build,
            "source": SOURCE,
            "sourceUrl": SOURCE_URL,
            "updateAvailable": update_available,
            "target": candidate,
            "blockers": blockers,
            "operation": public_operation(self.store.read()),
        }

    def status(self, operation_id: str | None = None) -> dict[str, Any]:
        return {"operation": public_operation(self.store.read(operation_id))}

    def _existing_operation(self, request_id: str | None) -> dict[str, Any] | None:
        latest = self.store.read()
        if latest and request_id and latest.get("requestId") == request_id:
            return latest
        if latest and latest.get("phase") == "recoveryRequired":
            raise UpdateFailure(
                "updateRecoveryRequired",
                "Recover the previous update with `foreman update --recover` before starting another update.",
            )
        if latest and latest.get("phase") not in TERMINAL_PHASES:
            raise UpdateFailure("updateConcurrent", "Another update operation is already running.")
        return None

    async def start(
        self,
        request_id: str | None = None,
        authorization_principal: str = "local",
    ) -> dict[str, Any]:
        if request_id is not None and not REQUEST_ID.fullmatch(request_id):
            raise UpdateFailure("invalidRequest", "The update request ID is invalid.")
        if authorization_principal != "local" and not authorization_principal.startswith("device:"):
            raise UpdateFailure("authorizationRequired", "Update authorization is invalid.")
        async with self._async_lock:
            with self.store.lock():
                existing = self._existing_operation(request_id)
            if existing is not None:
                return public_operation(existing) or {}
            check = await self.check(refresh=True)
            if check["blockers"]:
                raise UpdateFailure("updateBlocked", "The update is blocked by active or waiting work.")
            target = check.get("target")
            if not check.get("updateAvailable") or not isinstance(target, dict):
                raise UpdateFailure(
                    "alreadyCurrent" if self.release_build else "developmentBuild",
                    "Foreman is already current." if self.release_build else "Development builds cannot use automatic server updates.",
                )
            if self.external_helper_required and (
                not self.helper_file.is_file()
                or not os.access(self.helper_file, os.X_OK)
                or shutil.which("systemd-run") is None
                or shutil.which("openssl") is None
            ):
                raise UpdateFailure(
                    "updateUnavailable",
                    "The installed external updater or a required verification tool is unavailable. Reinstall Foreman from a verified release archive.",
                )
            with self.store.lock():
                existing = self._existing_operation(request_id)
                if existing is not None:
                    return public_operation(existing) or {}
                operation_id = "fmu_" + os.urandom(18).hex()
                operation = self.store.write({
                    "schema": UPDATE_SCHEMA,
                    "id": operation_id,
                    "requestId": request_id,
                    "authorizationPrincipal": authorization_principal,
                    "phase": "downloading",
                    "currentVersion": self.current_version,
                    "targetVersion": target["version"],
                    "targetTag": target["tag"],
                    "protocolVersion": self.protocol_version,
                    "healthPort": self.health_port,
                    "source": SOURCE,
                    "sourceUrl": SOURCE_URL,
                    "releaseNotesUrl": target["releaseNotesUrl"],
                    "progress": 5,
                    "createdAt": utc_now(self.clock),
                    "message": "Downloading the verified Foreman release.",
                })
            await self._publish(operation)
            self.task = asyncio.create_task(self._prepare(operation_id))
            return public_operation(operation) or {}

    async def stop(self) -> None:
        if self.task is not None and not self.task.done():
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)

    async def _prepare(self, operation_id: str) -> None:
        try:
            operation = self.store.read(operation_id)
            if operation is None:
                return
            tag = operation["targetTag"]
            operation_dir = self.store.operation_directory(operation_id)
            shutil.rmtree(operation_dir, ignore_errors=True)
            downloads = operation_dir / "downloads"
            extracted = operation_dir / "extracted"
            downloads.mkdir(parents=True, mode=0o700)
            manifest_body = await asyncio.to_thread(
                self.fetcher.fetch,
                RELEASE_API_PREFIX + quote(tag, safe=""),
                MAX_MANIFEST_BYTES,
            )
            assets = release_assets(manifest_body, tag)
            wanted = {
                f"foreman-linux-{tag}.tar.gz": MAX_ARCHIVE_BYTES,
                "SHA256SUMS": MAX_SMALL_ASSET_BYTES,
                "SHA256SUMS.sig": MAX_SMALL_ASSET_BYTES,
                "foreman-release-cert.pem": MAX_SMALL_ASSET_BYTES,
            }
            for index, (name, maximum) in enumerate(wanted.items(), 1):
                body = await asyncio.to_thread(self.fetcher.fetch, assets[name][0], maximum)
                path = downloads / name
                path.write_bytes(body)
                os.chmod(path, 0o600)
                await self._transition(operation_id, "downloading", progress=5 + index * 10, message="Downloading verified release assets.")
            await self._transition(operation_id, "verifying", progress=50, message="Verifying release provenance and checksums.")
            await asyncio.to_thread(
                verify_certificate_and_signature,
                downloads / "foreman-release-cert.pem",
                downloads / "SHA256SUMS.sig",
                downloads / "SHA256SUMS",
                self.trust_fingerprint,
            )
            checksums = signed_checksums(downloads / "SHA256SUMS", tag)
            archive = downloads / f"foreman-linux-{tag}.tar.gz"
            if sha256_file(archive) != checksums[archive.name]:
                raise UpdateFailure("verificationFailed", "The Linux release archive checksum does not match.")
            await self._transition(operation_id, "staging", progress=65, message="Building and validating the replacement payload.")
            await asyncio.to_thread(safe_extract, archive, extracted)
            staged_install = operation_dir / "staged-install"
            await asyncio.to_thread(
                build_install_layout,
                extracted,
                staged_install,
                operation["targetVersion"],
                self.protocol_version,
                self.trust_fingerprint,
            )
            shutil.copy2(extracted / "linux" / "foreman", operation_dir / "staged-launcher")
            shutil.copy2(extracted / "linux" / "foreman.service", operation_dir / "staged-unit")
            shutil.copy2(extracted / "linux" / "foreman_updater.py", operation_dir / "staged-helper")
            os.chmod(operation_dir / "staged-launcher", 0o755)
            os.chmod(operation_dir / "staged-helper", 0o755)

            # The filesystem lock is intentionally acquired only for the final
            # coordination boundary; downloads and staging never block recovery.
            final_phase = "activationScheduled"
            final_updates: dict[str, Any] = {
                "progress": 80,
                "message": "The external updater is taking ownership of activation and restart.",
            }
            async with self.activation_lock:
                operation = self.store.read(operation_id)
                if operation is None or operation.get("phase") != "staging":
                    raise UpdateFailure("operationUnavailable", "The update operation changed during staging.")
                principal = operation.get("authorizationPrincipal")
                if (
                    not isinstance(principal, str)
                    or (
                        principal != "local"
                        and (
                            self.authorization_check is None
                            or not await self.authorization_check(principal)
                        )
                    )
                ):
                    shutil.rmtree(operation_dir, ignore_errors=True)
                    final_phase = "failed"
                    final_updates = {
                        "progress": 75,
                        "resultCode": "authorizationRevoked",
                        "message": "The initiating client's full-access authorization is no longer valid. Nothing was replaced.",
                    }
                else:
                    blockers = await self.safety_check()
                    if blockers:
                        shutil.rmtree(operation_dir, ignore_errors=True)
                        final_phase = "blocked"
                        final_updates = {
                            "progress": 75,
                            "resultCode": "activeWork",
                            "message": "Work became active before activation. Nothing was replaced; start the update again when it finishes.",
                        }
                with self.store.lock():
                    current = self.store.read(operation_id)
                    if current is None or current.get("phase") != "staging":
                        raise UpdateFailure(
                            "operationUnavailable",
                            "The update operation changed during staging.",
                        )
                    finalized = self.store.transition(
                        operation_id, final_phase, **final_updates,
                    )
            await self._publish(finalized)
            if final_phase != "activationScheduled":
                return
            scheduled = finalized
            result = await self.helper_runner(operation_id)
            current = self.store.read(operation_id)
            if current is not None and current.get("phase") != scheduled.get("phase"):
                await self._publish(current)
            if result != 0 and (
                current is None or current.get("phase") not in TERMINAL_PHASES
            ):
                await self._transition(
                    operation_id, "failed", resultCode="helperStartFailed",
                    message="The external updater could not be started. Nothing was replaced.",
                )
        except asyncio.CancelledError:
            current = self.store.read(operation_id)
            if current and current.get("phase") not in TERMINAL_PHASES | ACTIVATION_PHASES:
                await self._transition(
                    operation_id, "interrupted", resultCode="interrupted",
                    message="The update was interrupted before activation. Nothing was replaced.",
                )
            raise
        except UpdateFailure as error:
            await self._transition(
                operation_id, "failed", resultCode=error.code, message=error.message,
            )
        except (OSError, subprocess.SubprocessError):
            await self._transition(
                operation_id, "failed", resultCode="stagingFailed",
                message="The replacement payload could not be staged. Nothing was replaced.",
            )

    async def _transition(self, operation_id: str, phase: str, **updates: Any) -> dict[str, Any]:
        operation = self.store.transition(operation_id, phase, **updates)
        await self._publish(operation)
        return operation

    async def _publish(self, operation: dict[str, Any]) -> None:
        if self.event_callback is not None:
            await self.event_callback(public_operation(operation) or {})

    async def _systemd_run_helper(self, operation_id: str) -> int:
        process = await asyncio.create_subprocess_exec(
            "systemd-run", "--user", "--collect", "--quiet",
            "--service-type=exec",
            "--property=Restart=on-failure",
            "--property=RestartSec=1s",
            "--property=RestartPreventExitStatus=1 2 3 4",
            f"--unit=foreman-update-{operation_id}",
            str(self.helper_file),
            "--operation", operation_id,
            "--state-directory", str(self.store.root.parent),
            "--install-directory", str(self.install_directory),
            "--launcher-file", str(self.launcher_file),
            "--unit-file", str(self.unit_file),
            "--helper-file", str(self.helper_file),
            "--health-port", str(self.health_port),
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
        return await process.wait()


def _replace_file(source: Path, destination: Path) -> None:
    temporary = destination.with_name(destination.name + ".update-tmp")
    shutil.copy2(source, temporary)
    os.replace(temporary, destination)


def _systemctl(*arguments: str) -> None:
    result = subprocess.run(
        ["systemctl", "--user", *arguments], check=False,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        timeout=30,
    )
    if result.returncode != 0:
        raise UpdateFailure("serviceControlFailed", "The Foreman service could not be restarted.")


def _health(port: int, version: str, protocol_version: int, timeout_seconds: int = 45) -> bool:
    deadline = time.monotonic() + timeout_seconds
    delay = 0.25
    while time.monotonic() < deadline:
        connection: http.client.HTTPConnection | None = None
        try:
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            connection.request("GET", "/health")
            response = connection.getresponse()
            body = response.read(16 * 1024)
            value = json.loads(body)
            if (
                response.status == 200
                and value.get("foremanVersion") == version
                and value.get("protocolVersion") == protocol_version
            ):
                return True
        except (OSError, ValueError, json.JSONDecodeError, http.client.HTTPException):
            pass
        finally:
            if connection is not None:
                connection.close()
        time.sleep(delay)
        delay = min(delay * 1.5, 2.0)
    return False


@contextmanager
def _helper_authorization_guard(
    state_directory: Path, operation: Mapping[str, Any]
) -> Iterator[bool]:
    principal = operation.get("authorizationPrincipal")
    if principal == "local":
        yield True
        return
    if not isinstance(principal, str) or not principal.startswith("device:"):
        yield False
        return
    device_id = principal.removeprefix("device:")
    from state import State

    with State(state_directory).full_access_guard(device_id) as authorized:
        yield authorized


def _rollback_external_update(
    *,
    store: OperationStore,
    operation_id: str,
    install_directory: Path,
    launcher_file: Path,
    unit_file: Path,
    helper_file: Path,
    health_port: int,
) -> int:
    operation_dir = store.operation_directory(operation_id)
    backup = operation_dir / "backup"
    failed_install = operation_dir / "failed-install"
    try:
        with store.lock(blocking=True):
            operation = store.read(operation_id)
            if operation is None:
                return 2
            store.transition(
                operation_id, "rollingBack", progress=94,
                message="The update failed; restoring the previous Foreman payload.",
            )
            _systemctl("stop", "foreman.service")
            if (backup / "install").is_dir():
                if install_directory.exists():
                    shutil.rmtree(failed_install, ignore_errors=True)
                    os.replace(install_directory, failed_install)
                restore_install = operation_dir / "restore-install"
                shutil.rmtree(restore_install, ignore_errors=True)
                shutil.copytree(backup / "install", restore_install)
                os.replace(restore_install, install_directory)
            elif not install_directory.is_dir():
                raise UpdateFailure("rollbackPayloadMissing", "The previous Foreman payload is unavailable.")

            prepared = operation.get("activationPrepared") is True
            for destination, saved, existed_key in (
                (launcher_file, backup / "launcher", "launcherExisted"),
                (unit_file, backup / "unit", "unitExisted"),
                (helper_file, backup / "helper", "helperExisted"),
            ):
                if saved.is_file():
                    shutil.copy2(saved, destination)
                elif prepared and operation.get(existed_key) is False:
                    try:
                        destination.unlink()
                    except FileNotFoundError:
                        pass
                try:
                    destination.with_name(destination.name + ".update-tmp").unlink()
                except FileNotFoundError:
                    pass
            _systemctl("daemon-reload")
            _systemctl("restart", "foreman.service")
            protocol_version = int(operation["protocolVersion"])
            if not _health(health_port, operation["currentVersion"], protocol_version):
                raise UpdateFailure("rollbackHealthFailed", "The restored service did not pass its health check.")
            store.transition(
                operation_id, "rolledBack", progress=100, resultCode="rollbackSucceeded",
                message="The update failed and the previous Foreman release was restored successfully.",
            )
            shutil.rmtree(operation_dir, ignore_errors=True)
            return 1
    except Exception:
        store.transition(
            operation_id, "recoveryRequired", progress=100, resultCode="rollbackFailed",
            message="Automatic rollback failed. Run the local recovery command before deleting update data.",
            recoveryCommand="foreman update --recover",
        )
        return 3


def run_external_helper(
    *,
    operation_id: str,
    state_directory: Path,
    install_directory: Path,
    launcher_file: Path,
    unit_file: Path,
    helper_file: Path,
    health_port: int,
) -> int:
    store = OperationStore(state_directory)
    operation = store.read(operation_id)
    if operation is None or operation.get("phase") not in ACTIVATION_PHASES:
        return 2
    try:
        recorded_health_port = int(operation["healthPort"])
    except (KeyError, TypeError, ValueError):
        return 2
    if not 1 <= recorded_health_port <= 65535:
        return 2
    health_port = recorded_health_port
    if operation.get("phase") != "activationScheduled":
        return _rollback_external_update(
            store=store, operation_id=operation_id,
            install_directory=install_directory, launcher_file=launcher_file,
            unit_file=unit_file, helper_file=helper_file, health_port=health_port,
        )
    operation_dir = store.operation_directory(operation_id)
    backup = operation_dir / "backup"
    try:
        with store.lock(blocking=True):
            operation = store.read(operation_id)
            if operation is None or operation.get("phase") != "activationScheduled":
                return 2
            store.transition(operation_id, "activating", progress=84, message="Activating the staged Foreman payload.")
            backup.mkdir(parents=True, mode=0o700)
            existence: dict[str, bool] = {}
            for destination, saved in (
                (launcher_file, backup / "launcher"),
                (unit_file, backup / "unit"),
                (helper_file, backup / "helper"),
            ):
                existence[saved.name + "Existed"] = destination.exists()
                if destination.exists():
                    shutil.copy2(destination, saved)
            store.transition(
                operation_id, "activating", progress=84,
                message="Activating the staged Foreman payload.",
                activationPrepared=True,
                launcherExisted=existence["launcherExisted"],
                unitExisted=existence["unitExisted"],
                helperExisted=existence["helperExisted"],
            )
            with _helper_authorization_guard(state_directory, operation) as authorized:
                if not authorized:
                    store.transition(
                        operation_id, "failed", progress=100, resultCode="authorizationRevoked",
                        message="Update authorization was revoked before activation. Nothing was replaced.",
                    )
                    shutil.rmtree(operation_dir, ignore_errors=True)
                    return 4
                # Keep the same device-state lock through the activation
                # directory renames, so authorization and revocation have one
                # ordering even if the prior install is unexpectedly absent.
                if install_directory.exists():
                    os.replace(install_directory, backup / "install")
                os.replace(operation_dir / "staged-install", install_directory)
            _replace_file(operation_dir / "staged-launcher", launcher_file)
            _replace_file(operation_dir / "staged-unit", unit_file)
            _replace_file(operation_dir / "staged-helper", helper_file)
            store.transition(operation_id, "restarting", progress=88, message="Restarting Foreman with the staged payload.")
            _systemctl("daemon-reload")
            _systemctl("restart", "foreman.service")
            store.transition(operation_id, "healthChecking", progress=92, message="Checking the restarted Foreman version and protocol.")
            if not _health(health_port, operation["targetVersion"], int(operation["protocolVersion"])):
                raise UpdateFailure("healthCheckFailed", "The updated service did not pass its health check.")
            store.transition(
                operation_id, "succeeded", progress=100, resultCode="updated",
                message=f"Foreman {operation['targetVersion']} is installed and healthy.",
            )
            shutil.rmtree(operation_dir, ignore_errors=True)
            return 0
    except Exception:
        return _rollback_external_update(
            store=store, operation_id=operation_id,
            install_directory=install_directory, launcher_file=launcher_file,
            unit_file=unit_file, helper_file=helper_file, health_port=health_port,
        )


def recover_latest(
    *, state_directory: Path, install_directory: Path, launcher_file: Path,
    unit_file: Path, helper_file: Path,
) -> int:
    store = OperationStore(state_directory)
    operation = store.read()
    if operation is None:
        raise UpdateFailure("recoveryUnavailable", "No update operation currently requires recovery.")
    if operation.get("phase") in ACTIVATION_PHASES:
        try:
            health_port = int(operation["healthPort"])
        except (KeyError, TypeError, ValueError) as error:
            raise UpdateFailure(
                "recoveryUnavailable", "The interrupted update has invalid recovery metadata."
            ) from error
        if not 1 <= health_port <= 65535:
            raise UpdateFailure(
                "recoveryUnavailable", "The interrupted update has invalid recovery metadata."
            )
        result = _rollback_external_update(
            store=store, operation_id=operation["id"],
            install_directory=install_directory, launcher_file=launcher_file,
            unit_file=unit_file, helper_file=helper_file, health_port=health_port,
        )
        return 0 if result == 1 else result
    if operation.get("phase") != "recoveryRequired":
        raise UpdateFailure("recoveryUnavailable", "No update operation currently requires recovery.")
    operation_dir = store.operation_directory(operation["id"])
    backup = operation_dir / "backup"
    if not (backup / "install").exists():
        raise UpdateFailure("recoveryUnavailable", "The retained recovery payload is unavailable.")
    with store.lock(blocking=False):
        _systemctl("stop", "foreman.service")
        if install_directory.exists():
            failed = operation_dir / "failed-recovery-install"
            if failed.exists():
                shutil.rmtree(failed)
            os.replace(install_directory, failed)
        restore_install = operation_dir / "manual-restore-install"
        shutil.rmtree(restore_install, ignore_errors=True)
        shutil.copytree(backup / "install", restore_install)
        os.replace(restore_install, install_directory)
        for destination, saved in (
            (launcher_file, backup / "launcher"),
            (unit_file, backup / "unit"),
            (helper_file, backup / "helper"),
        ):
            if saved.exists():
                shutil.copy2(saved, destination)
        _systemctl("daemon-reload")
        _systemctl("restart", "foreman.service")
        try:
            protocol_version = int(operation.get("protocolVersion", 0))
            health_port = int(operation.get("healthPort", 8766))
        except (TypeError, ValueError):
            protocol_version = 0
            health_port = 8766
        if (
            protocol_version <= 0
            or not (1 <= health_port <= 65535)
            or not _health(health_port, str(operation.get("currentVersion", "")), protocol_version)
        ):
            store.transition(
                operation["id"], "recoveryRequired", progress=100,
                resultCode="manualRecoveryHealthFailed",
                message="The previous payload was restored but did not pass its health check. Inspect `systemctl --user status foreman.service` before retrying recovery.",
                recoveryCommand="foreman update --recover",
            )
            raise UpdateFailure(
                "recoveryHealthFailed",
                "The restored Foreman service did not pass its health check. Inspect `systemctl --user status foreman.service`.",
            )
        store.transition(
            operation["id"], "rolledBack", progress=100, resultCode="manualRecoverySucceeded",
            message="The retained previous Foreman payload was restored. Check service status.",
        )
        shutil.rmtree(operation_dir, ignore_errors=True)
    return 0
