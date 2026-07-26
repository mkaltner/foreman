# Releases

Pushing a semantic version tag such as `v0.0.1` runs the tagged-release
workflow. It tests both projects, builds a signed Android release APK, packages
the Linux service, and creates a GitHub release containing both files and their
SHA-256 checksums.

The signing keystore and recovery secrets generated for this repository are at
`~/foreman-release.jks` and `~/foreman-release-secrets.txt`. Keep secure backups
of both. Losing the signing key prevents future APKs from upgrading existing
installations.

Create and push a release tag after the release changes reach `main`:

```bash
git tag v0.0.1
git push origin v0.0.1
```

The same workflow can be started manually for an existing tag from the Actions
tab. It deliberately fails before publishing if any signing secret is absent.
