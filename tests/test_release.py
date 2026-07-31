from __future__ import annotations

import unittest

from scripts.verify_apk_certificate import certificate_sha256


EXPECTED = "80d479d1a8f9f038c6977a1cfb68a2b45c3117492c364620e48babebf1810ad3"
UNRELATED = "0123456789abcdef" * 4


class ApkCertificateTests(unittest.TestCase):
    def test_extracts_standard_apksigner_label(self) -> None:
        output = f"Signer #1 certificate SHA-256 digest: {EXPECTED}\n"

        self.assertEqual(certificate_sha256(output), EXPECTED)

    def test_accepts_alternate_label_and_uppercase_digest(self) -> None:
        output = f"SHA256 certificate fingerprint = {EXPECTED.upper()}\n"

        self.assertEqual(certificate_sha256(output), EXPECTED)

    def test_ignores_preceding_unrelated_sha256_metadata(self) -> None:
        output = (
            f"Signer #1 public key SHA-256 digest: {UNRELATED}\n"
            f"Certificate transparency metadata: {UNRELATED}\n"
            f"SHA-256 digest of signer #1 certificate: {EXPECTED}\n"
        )

        self.assertEqual(certificate_sha256(output), EXPECTED)

    def test_rejects_unscoped_hex_digest(self) -> None:
        with self.assertRaisesRegex(ValueError, "no certificate SHA-256 fingerprint"):
            certificate_sha256(f"Build metadata: {UNRELATED}\n")

    def test_rejects_ambiguous_certificate_digests(self) -> None:
        output = (
            f"Signer #1 certificate SHA-256 digest: {EXPECTED}\n"
            f"Signer #2 certificate SHA256 fingerprint: {UNRELATED}\n"
        )

        with self.assertRaisesRegex(ValueError, "multiple certificate SHA-256 fingerprints"):
            certificate_sha256(output)


if __name__ == "__main__":
    unittest.main()
