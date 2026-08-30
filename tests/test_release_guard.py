import hashlib
import json
import subprocess
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest import mock

from scripts.guard_published_release import guard_release, main


TAG = "v1.2.3"
REPOSITORY = "mkaltner/foreman"
RELEASE_ID = "123"


def release_assets():
    return [
        {"name": f"foreman-{TAG}.apk", "size": 10},
        {"name": f"foreman-linux-{TAG}.tar.gz", "size": 20},
        {"name": "SHA256SUMS", "size": 30},
    ]


class FakeGitHub:
    def __init__(self, assets=None, *, tamper=False):
        self.assets = release_assets() if assets is None else assets
        self.tamper = tamper
        self.returned_to_draft = False
        self.calls = []

    def __call__(self, arguments, **kwargs):
        self.calls.append(list(arguments))
        if arguments[:3] == ["gh", "api", "--method"]:
            self.returned_to_draft = True
            return subprocess.CompletedProcess(arguments, 0, stdout="{}", stderr="")
        if arguments[:2] == ["gh", "api"]:
            return subprocess.CompletedProcess(
                arguments,
                0,
                stdout=json.dumps({"assets": self.assets}),
                stderr="",
            )
        if arguments[:3] == ["gh", "release", "download"]:
            destination = Path(arguments[arguments.index("--dir") + 1])
            payloads = {
                f"foreman-{TAG}.apk": b"signed apk",
                f"foreman-linux-{TAG}.tar.gz": b"linux archive",
            }
            checksums = []
            for name, content in payloads.items():
                destination.joinpath(name).write_bytes(content)
                checksums.append(f"{hashlib.sha256(content).hexdigest()}  {name}")
            destination.joinpath("SHA256SUMS").write_text(
                "\n".join(checksums) + "\n",
                encoding="utf-8",
            )
            if self.tamper:
                destination.joinpath(f"foreman-{TAG}.apk").write_bytes(b"tampered")
            return subprocess.CompletedProcess(arguments, 0, stdout="", stderr="")
        raise AssertionError(f"unexpected command: {arguments}")


class PublishedReleaseGuardTests(unittest.TestCase):
    def test_complete_release_remains_published(self):
        github = FakeGitHub()
        self.assertEqual(guard_release(REPOSITORY, RELEASE_ID, TAG, github), [])
        self.assertFalse(github.returned_to_draft)

    def test_missing_asset_returns_release_to_draft_without_download(self):
        github = FakeGitHub(assets=release_assets()[:-1])
        errors = guard_release(REPOSITORY, RELEASE_ID, TAG, github)
        self.assertIn("missing release assets", "\n".join(errors))
        self.assertTrue(github.returned_to_draft)
        self.assertFalse(any(call[:3] == ["gh", "release", "download"] for call in github.calls))

    def test_bad_download_checksum_returns_release_to_draft(self):
        github = FakeGitHub(tamper=True)
        errors = guard_release(REPOSITORY, RELEASE_ID, TAG, github)
        self.assertIn("checksum mismatch", "\n".join(errors))
        self.assertTrue(github.returned_to_draft)

    def test_api_failure_attempts_to_return_release_to_draft(self):
        runner = mock.Mock(
            side_effect=[
                subprocess.CalledProcessError(1, ["gh", "api"]),
                subprocess.CompletedProcess(["gh", "api"], 0, stdout="{}", stderr=""),
            ]
        )
        errors = guard_release(REPOSITORY, RELEASE_ID, TAG, runner)
        self.assertIn("release asset validation failed", "\n".join(errors))
        self.assertEqual(runner.call_count, 2)

    @mock.patch("scripts.guard_published_release.guard_release")
    @mock.patch("sys.argv", ["guard_published_release.py", "--repository", REPOSITORY, "--release-id", RELEASE_ID, "--tag", TAG])
    def test_cli_does_not_claim_failed_draft_transition_succeeded(self, guard):
        guard.return_value = [
            "missing release assets: SHA256SUMS",
            "failed to return incomplete release to draft: API rejected update",
        ]
        with self.assertRaises(SystemExit) as raised, redirect_stderr(StringIO()):
            main()
        self.assertIn("could not be returned to draft", str(raised.exception))
        self.assertNotIn("has been returned to draft", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
