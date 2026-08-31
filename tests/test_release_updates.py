from __future__ import annotations

import asyncio
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from release_updates import (
    FRESH_SECONDS,
    GITHUB_API_HOST,
    GITHUB_RELEASES_ENDPOINT,
    GITHUB_RELEASES_PATH,
    MAX_RESPONSE_BYTES,
    HttpResult,
    ReleaseFetchError,
    ReleaseUpdateCache,
    SemVer,
    _default_fetch,
    parse_release_response,
)


def asset(name: str, size: int = 10) -> dict[str, object]:
    return {"name": name, "size": size, "browser_download_url": "https://example.invalid/ignored"}


def release(
    version: str,
    *,
    server: bool = True,
    android: bool = True,
    checksums: bool = True,
    draft: bool = False,
    prerelease: bool = False,
    assets: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    uploaded = [] if assets is None else list(assets)
    if assets is None:
        if server:
            uploaded.append(asset(f"foreman-linux-v{version}.tar.gz"))
        if android:
            uploaded.append(asset(f"foreman-v{version}.apk"))
        if checksums:
            uploaded.append(asset("SHA256SUMS"))
    return {
        "tag_name": f"v{version}",
        "name": f"Foreman {version}",
        "draft": draft,
        "prerelease": prerelease,
        "published_at": "2026-08-29T04:47:19Z",
        "html_url": "https://example.invalid/ignored",
        "tarball_url": "https://api.github.com/source-is-not-an-asset",
        "zipball_url": "https://api.github.com/source-is-not-an-asset",
        "assets": uploaded,
    }


def response(*releases: dict[str, object]) -> bytes:
    return json.dumps(list(releases)).encode()


class SemVerTest(unittest.TestCase):
    def test_stable_ordering_is_numeric_not_lexicographic(self) -> None:
        values = [SemVer.parse(value) for value in ("1.9.0", "1.10.0", "2.0.0")]
        self.assertEqual(sorted(values), values)
        self.assertGreater(SemVer.parse("999999999999999999.0.0"), SemVer.parse("2.0.0"))

    def test_prerelease_ordering_follows_semver(self) -> None:
        raw = [
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        ]
        parsed = [SemVer.parse(value) for value in raw]
        self.assertTrue(all(value is not None for value in parsed))
        self.assertEqual(sorted(parsed), parsed)
        self.assertEqual(SemVer.parse("1.0.0+one"), SemVer.parse("1.0.0+two"))

    def test_malformed_versions_are_rejected(self) -> None:
        for value in ("1", "1.0", "01.0.0", "1.0.0-alpha.01", "latest", "", "1.0.0-"):
            self.assertIsNone(SemVer.parse(value), value)


class ReleaseSelectionTest(unittest.TestCase):
    def test_drafts_prereleases_and_malformed_tags_do_not_enter_stable_channel(self) -> None:
        malformed = release("1.2.0")
        malformed["tag_name"] = "release-1.2.0"
        projected = parse_release_response(
            response(
                release("9.0.0", draft=True),
                release("2.0.0-alpha.1", prerelease=True),
                release("1.99.0-rc.1", prerelease=False),
                malformed,
                release("1.10.0"),
                release("1.9.0"),
            )
        )
        self.assertEqual(projected["server"]["supportedRelease"]["version"], "1.10.0")

    def test_component_selection_can_choose_different_complete_releases(self) -> None:
        projected = parse_release_response(
            response(
                release("1.3.0", server=False, android=True),
                release("1.2.0", server=True, android=False),
                release("1.1.0"),
            )
        )
        self.assertEqual(projected["android"]["supportedRelease"]["version"], "1.3.0")
        self.assertEqual(projected["server"]["supportedRelease"]["version"], "1.2.0")
        self.assertFalse(projected["server"]["newestRelease"]["artifactAvailable"])

    def test_missing_checksum_makes_component_unavailable(self) -> None:
        projected = parse_release_response(response(release("1.0.0", checksums=False)))
        self.assertIsNone(projected["server"]["supportedRelease"])
        self.assertFalse(projected["server"]["newestRelease"]["artifactAvailable"])

    def test_zero_byte_and_duplicate_assets_are_incomplete(self) -> None:
        for uploaded in (
            [asset("foreman-v1.0.0.apk", 0), asset("SHA256SUMS")],
            [asset("foreman-v1.0.0.apk"), asset("foreman-v1.0.0.apk"), asset("SHA256SUMS")],
            [asset("foreman-v1.0.0.apk"), asset("SHA256SUMS"), asset("SHA256SUMS", 0)],
        ):
            with self.subTest(uploaded=uploaded):
                projected = parse_release_response(response(release("1.0.0", assets=uploaded)))
                self.assertIsNone(projected["android"]["supportedRelease"])

    def test_source_archive_fields_are_not_treated_as_uploaded_assets(self) -> None:
        projected = parse_release_response(response(release("1.0.0", assets=[])))
        self.assertIsNone(projected["server"]["supportedRelease"])
        self.assertIsNone(projected["android"]["supportedRelease"])

    def test_newest_incomplete_release_retains_older_supported_release(self) -> None:
        projected = parse_release_response(response(release("1.0.2", assets=[]), release("1.0.0")))
        server = projected["server"]
        self.assertEqual(server["newestRelease"]["version"], "1.0.2")
        self.assertFalse(server["newestRelease"]["artifactAvailable"])
        self.assertEqual(server["supportedRelease"]["version"], "1.0.0")

    def test_response_and_remote_fields_are_bounded(self) -> None:
        with self.assertRaisesRegex(ReleaseFetchError, "response-too-large"):
            parse_release_response(b"[" + b" " * MAX_RESPONSE_BYTES + b"]")
        oversized = release("1.0.0")
        oversized["name"] = "x" * 161
        self.assertIsNone(parse_release_response(response(oversized))["server"]["newestRelease"])

    def test_release_note_url_is_constructed_from_fixed_repository(self) -> None:
        projected = parse_release_response(response(release("1.0.0")))
        self.assertEqual(
            projected["server"]["supportedRelease"]["releaseNotesUrl"],
            "https://github.com/mkaltner/foreman/releases/tag/v1.0.0",
        )
        self.assertEqual(
            GITHUB_RELEASES_ENDPOINT,
            "https://api.github.com/repos/mkaltner/foreman/releases?per_page=20",
        )


class CacheTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.now = 2_000_000_000.0

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def cache(self, fetcher) -> ReleaseUpdateCache:
        return ReleaseUpdateCache(self.directory, fetcher=fetcher, clock=lambda: self.now)

    async def test_missing_fresh_and_stale_cache_states(self) -> None:
        cache = self.cache(lambda _: HttpResult(200, response(release("1.0.0")), {"etag": '"one"'}))
        self.assertEqual(cache.snapshot()["refreshStatus"], "unavailable")
        await cache.refresh()
        self.assertFalse(cache.snapshot()["stale"])
        self.now += FRESH_SECONDS + 1
        self.assertTrue(cache.snapshot()["stale"])

    async def test_etag_and_304_renew_observation_without_replacing_projection(self) -> None:
        seen: list[str | None] = []
        replies = iter(
            [
                HttpResult(200, response(release("1.0.0")), {"etag": '"one"'}),
                HttpResult(304, b"", {"etag": '"one"'}),
            ]
        )
        cache = self.cache(lambda etag: (seen.append(etag), next(replies))[1])
        await cache.refresh()
        self.now += FRESH_SECONDS + 1
        await cache.refresh()
        self.assertEqual(seen, [None, '"one"'])
        self.assertEqual(cache.snapshot()["components"]["server"]["supportedRelease"]["version"], "1.0.0")
        self.assertFalse(cache.snapshot()["stale"])

    async def test_valid_cache_survives_offline_timeout_malformed_json_and_http_error(self) -> None:
        initial = self.cache(lambda _: HttpResult(200, response(release("1.0.0")), {}))
        await initial.refresh()
        original = initial.snapshot()["components"]
        failures = [
            lambda _: (_ for _ in ()).throw(ReleaseFetchError("offline")),
            lambda _: (_ for _ in ()).throw(ReleaseFetchError("timeout")),
            lambda _: HttpResult(200, b"not-json", {}),
            lambda _: HttpResult(500, b"error", {}),
        ]
        for fetcher in failures:
            self.now += FRESH_SECONDS + 1
            cached = self.cache(fetcher)
            await cached.refresh()
            self.assertEqual(cached.snapshot()["components"], original)
            self.assertTrue(cached.snapshot()["stale"])
            self.assertEqual(cached.snapshot()["refreshStatus"], "unavailable")

    async def test_rate_limit_is_bounded_and_not_retried_repeatedly(self) -> None:
        calls = 0

        def fetch(_: str | None) -> HttpResult:
            nonlocal calls
            calls += 1
            return HttpResult(403, b"", {"x-ratelimit-remaining": "0", "x-ratelimit-reset": str(int(self.now + 3600))})

        cache = self.cache(fetch)
        await cache.refresh()
        await cache.refresh()
        self.assertEqual(calls, 1)
        self.assertEqual(cache.snapshot()["unavailableReason"], "rate-limited")

        restored = self.cache(lambda _: (_ for _ in ()).throw(AssertionError("persisted rate limit should suppress refresh")))
        await restored.start()
        self.assertEqual(restored.snapshot()["unavailableReason"], "rate-limited")

    async def test_concurrent_refreshes_coalesce_and_manual_checks_throttle(self) -> None:
        calls = 0
        entered = threading.Event()
        release_fetch = threading.Event()

        def fetch(_: str | None) -> HttpResult:
            nonlocal calls
            calls += 1
            entered.set()
            release_fetch.wait(2)
            return HttpResult(200, response(release("1.0.0")), {})

        cache = self.cache(fetch)
        first = asyncio.create_task(cache.refresh(manual=True))
        await asyncio.to_thread(entered.wait, 1)
        second = asyncio.create_task(cache.refresh(manual=True))
        release_fetch.set()
        await asyncio.gather(first, second)
        await cache.refresh(manual=True)
        self.assertEqual(calls, 1)

    async def test_validated_cache_persists_across_restart(self) -> None:
        cache = self.cache(lambda _: HttpResult(200, response(release("1.4.0")), {"etag": '"persisted"'}))
        await cache.refresh()
        restored = self.cache(lambda _: (_ for _ in ()).throw(AssertionError("fresh cache should not fetch")))
        self.assertEqual(restored.snapshot()["components"]["android"]["supportedRelease"]["version"], "1.4.0")
        await restored.start()


class FixedTransportTest(unittest.TestCase):
    def test_default_fetch_uses_only_fixed_host_path_and_conditional_header(self) -> None:
        observed: dict[str, object] = {}

        class Response:
            status = 304

            def read(self, maximum: int) -> bytes:
                observed["maximum"] = maximum
                return b""

            def getheaders(self):
                return [("ETag", '"one"')]

        class Connection:
            sock = None

            def __init__(self, host: str, timeout: int) -> None:
                observed["host"] = host
                observed["timeout"] = timeout

            def request(self, method: str, path: str, headers: dict[str, str]) -> None:
                observed.update(method=method, path=path, headers=headers)

            def getresponse(self) -> Response:
                return Response()

            def close(self) -> None:
                pass

        with patch("release_updates.http.client.HTTPSConnection", Connection):
            result = _default_fetch('"one"')
        self.assertEqual(result.status, 304)
        self.assertEqual(observed["host"], GITHUB_API_HOST)
        self.assertEqual(observed["path"], GITHUB_RELEASES_PATH)
        self.assertEqual(observed["headers"]["If-None-Match"], '"one"')
        self.assertEqual(observed["maximum"], MAX_RESPONSE_BYTES + 1)

    def test_timeout_is_reduced_to_safe_reason(self) -> None:
        class Connection:
            def __init__(self, host: str, timeout: int) -> None:
                pass

            def request(self, method: str, path: str, headers: dict[str, str]) -> None:
                raise socket.timeout

            def close(self) -> None:
                pass

        with patch("release_updates.http.client.HTTPSConnection", Connection):
            with self.assertRaisesRegex(ReleaseFetchError, "timeout"):
                _default_fetch(None)


if __name__ == "__main__":
    unittest.main()
