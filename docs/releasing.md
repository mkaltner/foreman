# Releases

Foreman releases are tag-triggered and must come from a reviewed, merged release
PR. Preparing a candidate branch or opening a draft PR must never create a tag
or GitHub release.

## Version sources

`release.properties` is the candidate manifest. `foremanVersion` must match the
v-prefixed release tag, `androidVersionCode` must be greater than every APK ever
published for `net.kaltner.foreman`, and `protocolVersion` changes only for an
intentional wire-compatibility change. The Linux status version, web package
version, Android build defaults, and all three protocol constants are checked by:

```sh
python3 scripts/verify_release.py --tag v0.1.0-alpha.6
```

Do not derive Android version code from a CI run number. Confirm the previous
APK with `aapt2 dump badging`; Android cannot install a lower code over a higher
one. The expected signing-certificate SHA-256 digest is public metadata in
`release.properties` and must match the previous published APK.

## Candidate gates

1. Start from current `main`, choose the next unused SemVer prerelease, and open
   `release/<version>` as a draft PR titled `Prepare <version>`.
2. Complete the matching `docs/acceptance-<version>.md`. Automated tests do not
   replace physical Android, real-browser, shared Desktop, fallback, HTTPS
   notification, upgrade, or rollback evidence.
3. Run Linux tests with system Python, web tests/typecheck/build with the pinned
   Node version, and Android test/lint/debug build with JDK 17. Confirm rebuilding
   the web client leaves `web/dist` unchanged.
4. Test an unprivileged fresh install, same-version reinstall, prior-release
   upgrade, injected activation failure, and previous-release rollback. Compare
   configuration and state bytes before/after and confirm paired clients still
   authenticate. Inject an obsolete installed file and confirm it is removed.
5. Build the signed release APK locally or in a non-publishing workflow. Verify
   its signature, certificate digest, version name, and monotonic version code;
   install that exact APK on the physical test device.
6. Build the Linux archive with the workflow exclusions. Inspect its complete
   file list, dependency licenses, committed web assets, absence of Python cache
   files, and extraction/install behavior. Verify every SHA-256 checksum.
7. Review the complete resolved web and Android dependency inventories and
   package every legally required license and notice. `THIRD_PARTY_NOTICES.md`
   is only a starting inventory; it is not a completed legal review.
8. Confirm the four Android signing secret names exist in repository Actions:
   `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEYSTORE_PASSWORD`,
   `ANDROID_SIGNING_KEY_ALIAS`, and `ANDROID_SIGNING_KEY_PASSWORD`. Never print
   secret values. Keep recovery material outside the repository with tested,
   access-controlled backups.
9. Require every PR check to pass and resolve every blocker before merging.

## Tagged workflow

`.github/workflows/release.yml` checks out the full annotated tag and confirms it
points at the tested commit. It validates release metadata, signing-secret
presence, Linux/web/Android tests, committed web assets, signed APK metadata and
certificate, the Linux archive, dependency-license marker, and checksums. Only
then does it call `gh release create`; prerelease tags are marked prereleases and
use `docs/releases/<version>.md` when present.

After the release PR is approved and merged, rerun the candidate checks on the
exact `main` commit. A maintainer may then create and push the annotated tag:

```sh
git switch main
git pull --ff-only
python3 scripts/verify_release.py --tag v0.1.0-alpha.6
git tag -a v0.1.0-alpha.6 -m "Foreman v0.1.0-alpha.6"
git push origin v0.1.0-alpha.6
```

Use `git tag -s` instead when the project has an established signing key and
verification policy. Never move or reuse a published tag. The manual workflow
input is only for an existing validated tag; it is not a way to release a branch
or commit SHA.

After the workflow succeeds, download all three release assets into an empty
directory, run `sha256sum --check SHA256SUMS`, repeat APK signature/version
inspection, list/extract the Linux archive, and perform one final install smoke
test. If any post-publication verification fails, preserve the tag and release
for audit, mark the release clearly, and publish a new higher prerelease after a
fix; do not replace artifacts in place.
