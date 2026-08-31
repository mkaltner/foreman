"""Bounded, cached discovery of official Foreman GitHub releases."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import socket
import time
from typing import Any, Callable, Mapping


GITHUB_API_HOST = "api.github.com"
GITHUB_RELEASES_PATH = "/repos/mkaltner/foreman/releases?per_page=20"
GITHUB_RELEASES_ENDPOINT = f"https://{GITHUB_API_HOST}{GITHUB_RELEASES_PATH}"
GITHUB_RELEASE_URL_PREFIX = "https://github.com/mkaltner/foreman/releases/tag/"
MAX_RELEASES = 20
MAX_RESPONSE_BYTES = 512 * 1024
MAX_ASSETS_PER_RELEASE = 50
MAX_TAG_BYTES = 80
MAX_TITLE_BYTES = 160
MAX_ETAG_BYTES = 256
FRESH_SECONDS = 6 * 60 * 60
FAILURE_RETRY_SECONDS = 15 * 60
MANUAL_REFRESH_SECONDS = 30
MAX_RATE_LIMIT_DELAY_SECONDS = 24 * 60 * 60
CACHE_SCHEMA = 1
COMPONENT_ASSET = {
    "server": "foreman-linux-v{version}.tar.gz",
    "android": "foreman-v{version}.apk",
}

_SEMVER = re.compile(
    r"^(?P<major>0|[1-9]\d*)\."
    r"(?P<minor>0|[1-9]\d*)\."
    r"(?P<patch>0|[1-9]\d*)"
    r"(?:-(?P<prerelease>(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+(?P<build>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


@dataclass(frozen=True)
class SemVer:
    major: int
    minor: int
    patch: int
    prerelease: tuple[str, ...] = ()

    @classmethod
    def parse(cls, value: str, *, allow_v: bool = False) -> "SemVer | None":
        if not isinstance(value, str) or len(value.encode("utf-8")) > MAX_TAG_BYTES:
            return None
        candidate = value[1:] if allow_v and value.startswith("v") else value
        match = _SEMVER.fullmatch(candidate)
        if match is None:
            return None
        return cls(
            int(match.group("major")),
            int(match.group("minor")),
            int(match.group("patch")),
            tuple((match.group("prerelease") or "").split("."))
            if match.group("prerelease")
            else (),
        )

    def _compare(self, other: "SemVer") -> int:
        core = (self.major, self.minor, self.patch)
        other_core = (other.major, other.minor, other.patch)
        if core != other_core:
            return -1 if core < other_core else 1
        if not self.prerelease or not other.prerelease:
            if self.prerelease == other.prerelease:
                return 0
            return -1 if self.prerelease else 1
        for left, right in zip(self.prerelease, other.prerelease):
            if left == right:
                continue
            left_numeric = left.isdigit()
            right_numeric = right.isdigit()
            if left_numeric and right_numeric:
                return -1 if int(left) < int(right) else 1
            if left_numeric != right_numeric:
                return -1 if left_numeric else 1
            return -1 if left < right else 1
        if len(self.prerelease) == len(other.prerelease):
            return 0
        return -1 if len(self.prerelease) < len(other.prerelease) else 1

    def __lt__(self, other: "SemVer") -> bool:
        return self._compare(other) < 0

    def __eq__(self, other: object) -> bool:
        return isinstance(other, SemVer) and self._compare(other) == 0


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: bytes
    headers: Mapping[str, str]


class ReleaseFetchError(RuntimeError):
    def __init__(self, reason: str, retry_at: int | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.retry_at = retry_at


def _bounded_text(value: Any, maximum: int, *, allow_empty: bool = False) -> str | None:
    if not isinstance(value, str):
        return None
    if (not value and not allow_empty) or len(value.encode("utf-8")) > maximum:
        return None
    return value


def _published_timestamp(value: Any) -> str | None:
    text = _bounded_text(value, 40)
    if text is None:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _release_projection(release: dict[str, Any], artifact_available: bool) -> dict[str, Any]:
    return {
        "version": release["version"],
        "tag": release["tag"],
        "title": release["title"],
        "publishedAt": release["publishedAt"],
        "releaseNotesUrl": GITHUB_RELEASE_URL_PREFIX + release["tag"],
        "artifactAvailable": artifact_available,
    }


def parse_release_response(body: bytes) -> dict[str, dict[str, Any]]:
    """Validate the bounded GitHub response and select stable component releases.

    Drafts are ignored. GitHub-prerelease releases and SemVer prerelease tags are
    intentionally excluded from the stable update channel. Uploaded assets only
    count when the exact name occurs once and its size is positive.
    """
    if len(body) > MAX_RESPONSE_BYTES:
        raise ReleaseFetchError("response-too-large")
    try:
        decoded = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseFetchError("malformed-response") from error
    if not isinstance(decoded, list):
        raise ReleaseFetchError("malformed-response")

    releases: list[dict[str, Any]] = []
    for raw in decoded[:MAX_RELEASES]:
        if not isinstance(raw, dict) or raw.get("draft") is True:
            continue
        if raw.get("draft") is not False or raw.get("prerelease") is not False:
            continue
        tag = _bounded_text(raw.get("tag_name"), MAX_TAG_BYTES)
        version = SemVer.parse(tag or "", allow_v=True)
        if tag is None or not tag.startswith("v") or version is None or version.prerelease:
            continue
        raw_title = raw.get("name")
        title = (
            tag
            if raw_title is None
            else _bounded_text(raw_title, MAX_TITLE_BYTES, allow_empty=True)
        )
        published_at = _published_timestamp(raw.get("published_at"))
        assets = raw.get("assets")
        if title is None or published_at is None or not isinstance(assets, list):
            continue
        asset_counts: dict[str, int] = {}
        invalid_assets: set[str] = set()
        for asset in assets[: MAX_ASSETS_PER_RELEASE + 1]:
            if not isinstance(asset, dict):
                continue
            name = _bounded_text(asset.get("name"), 128)
            size = asset.get("size")
            if name is None:
                continue
            asset_counts[name] = asset_counts.get(name, 0) + 1
            if (
                isinstance(size, bool)
                or not isinstance(size, int)
                or size <= 0
                or size > 2**40
            ):
                invalid_assets.add(name)
        if len(assets) > MAX_ASSETS_PER_RELEASE:
            asset_counts = {}
        else:
            for name in invalid_assets:
                asset_counts[name] = 0
        releases.append(
            {
                "semver": version,
                "version": tag[1:],
                "tag": tag,
                "title": title or tag,
                "publishedAt": published_at,
                "assets": asset_counts,
            }
        )

    releases.sort(key=lambda item: item["semver"], reverse=True)
    components: dict[str, dict[str, Any]] = {}
    for component, pattern in COMPONENT_ASSET.items():
        supported: dict[str, Any] | None = None
        newest: dict[str, Any] | None = None
        for release in releases:
            artifact = pattern.format(version=release["version"])
            complete = (
                release["assets"].get(artifact) == 1
                and release["assets"].get("SHA256SUMS") == 1
            )
            if newest is None:
                newest = _release_projection(release, complete)
            if complete and supported is None:
                supported = _release_projection(release, True)
        components[component] = {
            "supportedRelease": supported,
            "newestRelease": newest,
        }
    return components


def _default_fetch(etag: str | None) -> HttpResult:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "Foreman-release-discovery",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if etag:
        headers["If-None-Match"] = etag
    connection = http.client.HTTPSConnection(GITHUB_API_HOST, timeout=3)
    try:
        connection.request("GET", GITHUB_RELEASES_PATH, headers=headers)
        response = connection.getresponse()
        if connection.sock is not None:
            connection.sock.settimeout(5)
        body = response.read(MAX_RESPONSE_BYTES + 1)
        return HttpResult(response.status, body, {key.lower(): value for key, value in response.getheaders()})
    except (TimeoutError, socket.timeout) as error:
        raise ReleaseFetchError("timeout") from error
    except OSError as error:
        raise ReleaseFetchError("offline") from error
    finally:
        connection.close()


class ReleaseUpdateCache:
    def __init__(
        self,
        directory: Path,
        *,
        fetcher: Callable[[str | None], HttpResult] = _default_fetch,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.path = directory / "release-updates.json"
        self.retry_path = directory / "release-updates-retry.json"
        self.fetcher = fetcher
        self.clock = clock
        self._cache = self._load()
        self._refresh_task: asyncio.Task[dict[str, Any]] | None = None
        self._lock = asyncio.Lock()
        self._last_manual_at = 0.0
        persisted_retry = self._load_retry() if self._cache is None else 0
        self._last_error: str | None = self._cache.get("lastError") if self._cache else (
            "rate-limited" if persisted_retry > int(self.clock()) else None
        )
        self._retry_at = self._cache.get("retryAt", 0) if self._cache else persisted_retry

    def _load_retry(self) -> int:
        try:
            raw = self.retry_path.read_bytes()
            if len(raw) > 1024:
                return 0
            value = json.loads(raw)
            if not isinstance(value, dict):
                return 0
            retry_at = value.get("retryAt")
            if (
                value.get("schema") != CACHE_SCHEMA
                or value.get("reason") != "rate-limited"
                or isinstance(retry_at, bool)
                or not isinstance(retry_at, int)
                or retry_at <= 0
            ):
                return 0
            return retry_at
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return 0

    def _load(self) -> dict[str, Any] | None:
        try:
            raw = self.path.read_bytes()
            if len(raw) > 32 * 1024:
                return None
            value = json.loads(raw)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return None
        return self._validated_cache(value)

    @staticmethod
    def _validated_release(value: Any, *, artifact_must_be_available: bool = False) -> dict[str, Any] | None:
        if value is None:
            return None
        if not isinstance(value, dict):
            raise ValueError
        version = _bounded_text(value.get("version"), MAX_TAG_BYTES)
        tag = _bounded_text(value.get("tag"), MAX_TAG_BYTES)
        title = _bounded_text(value.get("title"), MAX_TITLE_BYTES)
        published_at = _published_timestamp(value.get("publishedAt"))
        url = _bounded_text(value.get("releaseNotesUrl"), 240)
        available = value.get("artifactAvailable")
        if (
            version is None
            or tag != f"v{version}"
            or SemVer.parse(version) is None
            or title is None
            or published_at is None
            or url != GITHUB_RELEASE_URL_PREFIX + tag
            or not isinstance(available, bool)
            or (artifact_must_be_available and not available)
        ):
            raise ValueError
        return {
            "version": version,
            "tag": tag,
            "title": title,
            "publishedAt": published_at,
            "releaseNotesUrl": url,
            "artifactAvailable": available,
        }

    @classmethod
    def _validated_cache(cls, value: Any) -> dict[str, Any] | None:
        try:
            if not isinstance(value, dict) or value.get("schema") != CACHE_SCHEMA:
                return None
            observed_at = value.get("observedAt")
            retry_at = value.get("retryAt", 0)
            last_error = value.get("lastError")
            etag = value.get("etag")
            components = value.get("components")
            if (
                isinstance(observed_at, bool)
                or not isinstance(observed_at, int)
                or observed_at <= 0
                or isinstance(retry_at, bool)
                or not isinstance(retry_at, int)
                or retry_at < 0
                or last_error not in (
                    None,
                    "offline",
                    "timeout",
                    "response-too-large",
                    "malformed-response",
                    "http-error",
                    "rate-limited",
                    "unavailable",
                )
                or (etag is not None and _bounded_text(etag, MAX_ETAG_BYTES) is None)
                or not isinstance(components, dict)
                or set(components) != set(COMPONENT_ASSET)
            ):
                return None
            validated_components = {}
            for component in COMPONENT_ASSET:
                projected = components.get(component)
                if not isinstance(projected, dict):
                    return None
                validated_components[component] = {
                    "supportedRelease": cls._validated_release(
                        projected.get("supportedRelease"), artifact_must_be_available=True
                    ),
                    "newestRelease": cls._validated_release(projected.get("newestRelease")),
                }
            return {
                "schema": CACHE_SCHEMA,
                "observedAt": observed_at,
                "retryAt": retry_at,
                "lastError": last_error,
                "etag": etag,
                "components": validated_components,
            }
        except ValueError:
            return None

    def _persist(self) -> None:
        if self._cache is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self._cache, separators=(",", ":")) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, self.path)

    def _persist_retry(self) -> None:
        self.retry_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.retry_path.with_suffix(".tmp")
        temporary.write_text(
            json.dumps(
                {"schema": CACHE_SCHEMA, "reason": "rate-limited", "retryAt": self._retry_at},
                separators=(",", ":"),
            ) + "\n",
            encoding="utf-8",
        )
        os.chmod(temporary, 0o600)
        os.replace(temporary, self.retry_path)

    def _clear_retry(self) -> None:
        try:
            self.retry_path.unlink()
        except OSError:
            pass

    def snapshot(self) -> dict[str, Any]:
        now = int(self.clock())
        refreshing = self._refresh_task is not None and not self._refresh_task.done()
        if self._cache is None:
            return {
                "observedAt": None,
                "stale": True,
                "refreshStatus": "checking" if refreshing else "unavailable",
                "components": {
                    component: {"supportedRelease": None, "newestRelease": None}
                    for component in COMPONENT_ASSET
                },
                **({"unavailableReason": self._last_error} if self._last_error else {}),
            }
        stale = now - self._cache["observedAt"] >= FRESH_SECONDS or self._last_error is not None
        return {
            "observedAt": datetime.fromtimestamp(self._cache["observedAt"], timezone.utc).isoformat().replace("+00:00", "Z"),
            "stale": stale,
            "refreshStatus": "checking" if refreshing else "unavailable" if self._last_error else "idle",
            "components": self._cache["components"],
            **({"unavailableReason": self._last_error} if self._last_error else {}),
        }

    async def start(self) -> None:
        # Startup remains non-blocking; callers receive the validated cache immediately.
        await self._schedule(manual=False, wait=False)

    async def stop(self) -> None:
        task = self._refresh_task
        if task is not None and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def refresh(self, *, manual: bool = False) -> dict[str, Any]:
        return await self._schedule(manual=manual, wait=True)

    async def _schedule(self, *, manual: bool, wait: bool) -> dict[str, Any]:
        now = self.clock()
        async with self._lock:
            if self._refresh_task is not None and not self._refresh_task.done():
                task = self._refresh_task
            else:
                if manual:
                    if now - self._last_manual_at < MANUAL_REFRESH_SECONDS:
                        return self.snapshot()
                    self._last_manual_at = now
                elif self._cache is not None and now - self._cache["observedAt"] < FRESH_SECONDS:
                    return self.snapshot()
                if now < self._retry_at:
                    return self.snapshot()
                task = asyncio.create_task(self._perform_refresh())
                self._refresh_task = task
        if wait:
            await task
        return self.snapshot()

    async def _perform_refresh(self) -> dict[str, Any]:
        etag = self._cache.get("etag") if self._cache else None
        try:
            result = await asyncio.to_thread(self.fetcher, etag)
            now = int(self.clock())
            if len(result.body) > MAX_RESPONSE_BYTES:
                raise ReleaseFetchError("response-too-large")
            headers = {str(key).lower(): str(value) for key, value in result.headers.items()}
            if result.status == 304:
                if self._cache is None:
                    raise ReleaseFetchError("malformed-response")
                self._cache["observedAt"] = now
                self._cache["retryAt"] = 0
                self._cache["lastError"] = None
                self._retry_at = 0
                self._last_error = None
                self._clear_retry()
                self._persist()
                return self.snapshot()
            if result.status != 200:
                if result.status in (403, 429):
                    remaining = headers.get("x-ratelimit-remaining")
                    if result.status == 429 or remaining == "0":
                        retry_at = self._rate_limit_retry_at(headers, now)
                        raise ReleaseFetchError("rate-limited", retry_at)
                raise ReleaseFetchError("http-error")
            components = parse_release_response(result.body)
            response_etag = headers.get("etag")
            if response_etag is not None and _bounded_text(response_etag, MAX_ETAG_BYTES) is None:
                response_etag = None
            self._cache = {
                "schema": CACHE_SCHEMA,
                "observedAt": now,
                "retryAt": 0,
                "lastError": None,
                "etag": response_etag,
                "components": components,
            }
            self._last_error = None
            self._retry_at = 0
            self._clear_retry()
            self._persist()
        except ReleaseFetchError as error:
            self._last_error = error.reason
            self._retry_at = error.retry_at or int(self.clock()) + FAILURE_RETRY_SECONDS
            if self._cache is not None:
                self._cache["retryAt"] = self._retry_at
                self._cache["lastError"] = error.reason
                self._persist()
            elif error.reason == "rate-limited":
                self._persist_retry()
        except Exception:
            self._last_error = "unavailable"
            self._retry_at = int(self.clock()) + FAILURE_RETRY_SECONDS
            if self._cache is not None:
                self._cache["retryAt"] = self._retry_at
                self._cache["lastError"] = self._last_error
                self._persist()
        return self.snapshot()

    @staticmethod
    def _rate_limit_retry_at(headers: Mapping[str, str], now: int) -> int:
        candidates: list[int] = []
        for key in ("retry-after", "x-ratelimit-reset"):
            try:
                value = int(headers.get(key, ""))
            except ValueError:
                continue
            candidates.append(now + value if key == "retry-after" else value)
        retry_at = max(candidates, default=now + FAILURE_RETRY_SECONDS)
        return min(max(retry_at, now + MANUAL_REFRESH_SECONDS), now + MAX_RATE_LIMIT_DELAY_SECONDS)
