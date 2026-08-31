# Releases

Foreman releases are tag-triggered and must come from a reviewed, merged release
PR. Preparing a candidate branch or opening a draft PR must never create a tag
or GitHub release.

## Version sources

`release.properties` is the candidate manifest. `foremanVersion` must match the
v-prefixed release tag, `releaseBuild` must be `true` only on the exact reviewed
release commit, `androidVersionCode` must be greater than every APK ever
published for `net.kaltner.foreman`, and `protocolVersion` changes only for an
intentional wire-compatibility change. The Linux status version, web package
version, Android build defaults, and all three protocol constants are checked by:

```sh
python3 scripts/verify_release.py --tag v1.0.3
```

The web and Android builds embed `foremanVersion` directly from this manifest
for their offline About views. Android embeds the checked-out short commit by
default. Set `FOREMAN_BUILD_COMMIT` to the artifact's source commit when
building distributable clients; committed web assets leave it unset so their
rebuild stays deterministic. About labels clients from `releaseBuild=false`
manifests as development builds. A release-preparation PR sets the chosen
version and `releaseBuild=true`; development resumes in a follow-up reviewed PR.

Settings → About discovers only the stable channel: drafts, GitHub prereleases,
and SemVer prerelease tags are excluded from ordinary update offers. A component
release is supported only when its exact nonempty APK or Linux archive and one
nonempty `SHA256SUMS` are uploaded without duplicate names. Automatic GitHub
source archives do not count. If the newest stable release is incomplete, About
can point out the unavailable artifact while offering only the newest older
complete component release. Development, prerelease, malformed, and
newer-than-published builds never receive a downgrade recommendation.

This discovery path never downloads an artifact or modifies an installed
release. #58 owns shared server installation/restart behavior and #59 owns APK
download/package installation.

Do not derive Android version code from a CI run number. Confirm the previous
APK with `aapt2 dump badging`; Android cannot install a lower code over a higher
one. The expected signing-certificate SHA-256 digest is public metadata in
`release.properties` and must match the previous published APK.

## Candidate gates

1. Start from current `main`, choose the next unused SemVer prerelease, and open
   `release/<version>` as a draft PR titled `Prepare <version>`.
2. Complete the stable target's acceptance record (for example, every
   `v1.0.0-rc.*` candidate uses `docs/acceptance-v1.0.0.md`). Automated tests do
   not replace physical Android, real-browser, shared Desktop, fallback, HTTPS
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
certificate, the Linux archive, dependency-license marker, and checksums. It
then creates an unpublished draft, uploads the APK, Linux archive, and
`SHA256SUMS`, queries GitHub for the exact nonempty asset set, downloads those
assets into an empty directory, and repeats checksum, APK, and archive
verification. Only a fully verified draft is published. A failure after draft
creation leaves the release unpublished for inspection; it must never be
published or have assets replaced until the cause is resolved. Prerelease tags
are marked prereleases and use `docs/releases/<version>.md` when present.

`.github/workflows/release-asset-guard.yml` independently checks future
`release.published` events, including releases published manually rather than by
the tagged workflow. A release without the exact three custom assets or with
invalid checksums is immediately returned to draft. The guard is event-driven
and is not applied retroactively to releases that existed before it was merged.
GitHub's automatic source ZIP and TAR links are not custom release assets and do
not satisfy this check.

After the release PR is approved and merged, rerun the candidate checks on the
exact `main` commit. A maintainer may then create and push the annotated tag:

```sh
git switch main
git pull --ff-only
python3 scripts/verify_release.py --tag v1.0.3
git tag -a v1.0.3 -m "Foreman v1.0.3"
git push origin v1.0.3
```

Use `git tag -s` instead when the project has an established signing key and
verification policy. Never move or reuse a published tag. The manual workflow
input is only for an existing validated tag; it is not a way to release a branch
or commit SHA.

After the workflow succeeds, confirm the release page shows all three custom
assets in addition to GitHub's two source links. Download the custom assets into
an empty directory, run `sha256sum --check SHA256SUMS`, repeat APK
signature/version inspection, list/extract the Linux archive, and perform one
final install smoke test. If any post-publication verification fails, preserve
the tag and release for audit, mark the release clearly, and publish a new higher
prerelease after a fix; do not replace artifacts in place.
