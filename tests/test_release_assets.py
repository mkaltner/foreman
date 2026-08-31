import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_release_assets import (
    expected_asset_names,
    verify_directory,
    verify_manifest,
)


TAG = "v1.2.3"
ROOT = Path(__file__).resolve().parents[1]


def manifest(*, missing: str | None = None, extra: bool = False, empty: str | None = None):
    assets = [
        {"name": name, "size": 0 if name == empty else index + 1}
        for index, name in enumerate(expected_asset_names(TAG))
        if name != missing
    ]
    if extra:
        assets.append({"name": "unexpected.zip", "size": 10})
    return {"assets": assets}


class ReleaseManifestTests(unittest.TestCase):
    def test_accepts_exact_nonempty_asset_set(self):
        self.assertEqual(verify_manifest(manifest(), TAG), [])

    def test_rejects_each_missing_asset(self):
        for name in expected_asset_names(TAG):
            with self.subTest(name=name):
                self.assertIn("missing release assets", "\n".join(verify_manifest(manifest(missing=name), TAG)))

    def test_rejects_empty_and_unexpected_assets(self):
        errors = verify_manifest(manifest(empty=f"foreman-{TAG}.apk", extra=True), TAG)
        self.assertIn("is empty", "\n".join(errors))
        self.assertIn("unexpected release assets", "\n".join(errors))

    def test_rejects_invalid_tag(self):
        with self.assertRaises(ValueError):
            expected_asset_names("latest")


class ReleaseDirectoryTests(unittest.TestCase):
    def create_release(self, directory: Path) -> None:
        payloads = {
            f"foreman-{TAG}.apk": b"signed apk",
            f"foreman-linux-{TAG}.tar.gz": b"linux archive",
        }
        checksum_lines = []
        for name, content in payloads.items():
            (directory / name).write_bytes(content)
            checksum_lines.append(f"{hashlib.sha256(content).hexdigest()}  {name}")
        (directory / "SHA256SUMS").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
        (directory / "SHA256SUMS.sig").write_bytes(b"detached signature")
        (directory / "foreman-release-cert.pem").write_text("public certificate\n", encoding="utf-8")

    def test_accepts_downloaded_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_release(directory)
            self.assertEqual(verify_directory(directory, TAG), [])

    def test_rejects_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_release(directory)
            (directory / f"foreman-{TAG}.apk").write_bytes(b"tampered")
            self.assertIn("checksum mismatch", "\n".join(verify_directory(directory, TAG)))

    def test_rejects_missing_zero_byte_and_unexpected_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_release(directory)
            (directory / f"foreman-{TAG}.apk").unlink()
            (directory / f"foreman-linux-{TAG}.tar.gz").write_bytes(b"")
            (directory / "extra.txt").write_text("extra", encoding="utf-8")
            errors = "\n".join(verify_directory(directory, TAG))
            self.assertIn("missing release files", errors)
            self.assertIn("is empty", errors)
            self.assertIn("unexpected release files", errors)

    def test_rejects_incomplete_checksum_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.create_release(directory)
            (directory / "SHA256SUMS").write_text(
                f"{'0' * 64}  foreman-{TAG}.apk\n",
                encoding="utf-8",
            )
            errors = "\n".join(verify_directory(directory, TAG))
            self.assertIn("missing checksums", errors)
            self.assertIn("checksum mismatch", errors)

    def test_cli_manifest_fixture_is_json_serializable(self):
        self.assertIsInstance(json.dumps(manifest()), str)


class ReleaseWorkflowTests(unittest.TestCase):
    def test_draft_manifest_uses_authenticated_release_api_url(self):
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        upload_step = workflow.split("- name: Upload and verify draft release assets", 1)[1]
        upload_step = upload_step.split("- name: Publish verified GitHub release", 1)[0]

        self.assertIn('gh release view "$RELEASE_TAG" --json apiUrl', upload_step)
        self.assertIn('gh api "${release_api_url#https://api.github.com/}"', upload_step)
        self.assertNotIn("releases/tags/$RELEASE_TAG", upload_step)


if __name__ == "__main__":
    unittest.main()
