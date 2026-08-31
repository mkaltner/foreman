# Android APK self-update

This document is the architecture and security decision for updating Foreman's
sideloaded Android application. It is normative for the Android About flow,
release discovery, release publication, signing-key continuity, and physical
device acceptance. Android app replacement is separate from the connected
Foreman server updater in [`server-updates.md`](server-updates.md).

## Trust model and provenance

Foreman has one stable release channel: published, non-prerelease releases in
`mkaltner/foreman`. The Android app starts from the validated Android component
target projected by the existing release-discovery contract, then fetches that
exact tag from the fixed GitHub Releases API. Neither a connected server nor a
user can supply a repository, tag, URL, filename, package name, certificate, or
version to the installer.

GitHub metadata and HTTPS are discovery and transport controls, not sufficient
artifact provenance. Every API and download request uses server-authenticated
HTTPS, bounded redirects, timeouts, response sizes, and only the fixed GitHub
and GitHub release-asset hosts. An installable release must then pass all of
these checks:

1. Its tag is exactly `v<available-version>` and it remains a published stable
   release.
2. Its custom assets are exactly one nonempty `foreman-v<version>.apk`, one
   `foreman-linux-v<version>.tar.gz`, `SHA256SUMS`, `SHA256SUMS.sig`, and
   `foreman-release-cert.pem`. Duplicate, missing, empty, unexpected,
   oversized, ambiguously named, or noncanonical assets fail closed.
3. The public certificate's DER SHA-256 fingerprint equals both the trust
   anchor embedded from the installed release's `release.properties` and the
   current installed Android package signer. This prevents a modified app from
   silently inheriting official update authority.
4. That certificate verifies the detached RSA/SHA-256 signature over the exact
   `SHA256SUMS` bytes. The signed manifest must contain exactly one checksum for
   the Android APK and Linux archive and no other entry.
5. The completed APK matches the signed APK checksum.
6. Android package inspection recognizes a valid, signed APK for
   `net.kaltner.foreman` with exactly one current signer matching the same
   trusted certificate.
7. The APK `versionName` equals the selected release, its version name is
   strictly newer by SemVer, and its `versionCode` is strictly greater than the
   installed code.

Any checksum, detached signature, APK signature, package identity, version,
certificate, or release-metadata mismatch deletes the invalid operation files
and requires a new download. Same-version replacement, downgrades,
prereleases, development targets, unsigned APKs, and signing-key changes are
never offered. Existing historical releases without the signed five-asset
contract remain manually downloadable but are not self-installable.

The Android application key remains the release-manifest signing key, matching
the server-update trust contract. Key rotation therefore remains a reviewed
two-release migration: an old-key release must explicitly introduce the new
trust anchor and signing-lineage policy before a later APK can use it. The
current updater does not guess across a key change.

## Download storage, bounds, and recovery

One app-global operation is stored under the app-private
`files/android-updates/apk_<random>/` directory. It is deliberately not scoped
to a connected host because the Android package is one application shared by
all saved hosts. The durable operation record contains only the target release,
canonical public asset metadata, phase, bounded progress, public checksums,
version code, timestamps, and safe result text. It contains no host token,
pairing data, prompt, transcript, filesystem location outside the app sandbox,
or GitHub response body.

The APK is capped at 200 MiB, the Linux asset metadata at 300 MiB, each signing
asset at 64 KiB, and the release API response at 512 KiB. At most one update and
one installer launch can be claimed at a time. Downloads use `.part` files,
HTTP range requests, and an ETag sidecar. An interrupted process restores as
`Interrupted`; an explicit retry revalidates the release and resumes retained
partial bytes. A changed or mixed payload still fails the signed checksum and
is removed. Completed metadata files are reused only at their declared size
and reverified before use.

User cancellation stops the job and deletes its files. Verification rejection
also deletes every operation payload. Unreferenced operation directories older
than seven days are pruned only within Foreman's dedicated update directory.
A verified APK and its small operation record survive activity recreation and
process relaunch so returning from settings or rotating the device never
causes another download.

The durable phases are:

```text
discovering -> downloading -> verifying -> ready
     |              |             |          |
     +----------> interrupted ----+          +-> explainingPermission
     |                 |                         -> awaitingPermission -> ready
     |                 +-> retry -> discovering
     +-------------------------------> failed

ready -> awaitingInstaller -> completed
  ^              |
  +---- canceled/retry result
```

Only download/verification phases can be canceled as downloads. A canceled
system installation returns to `ready` and reuses the verified APK. An
installer handoff left pending across recreation is not launched twice; the
user can explicitly reopen it.

## Permission and system installer

Foreman requests `REQUEST_INSTALL_PACKAGES` because it is distributed outside
Google Play. On Android 8 and later, the app checks `canRequestPackageInstalls`
only after an APK is fully verified and the user chooses **Open Android
installer**. If permission is absent, Foreman first explains what **Install
unknown apps** permits, that the APK is already verified, and that Android will
still require explicit confirmation. Only **Continue to settings** opens the
per-app Android settings screen.

Returning with permission granted resumes the same durable operation and opens
the installer once. Denial or backing out returns to `ready` without another
download or repeated dialog. Android versions without the per-app permission
go directly to the system installer. The APK is shared through a nonexported
`FileProvider` with a one-intent read grant. Foreman never uses PackageInstaller
session APIs, device-owner privileges, accessibility automation, root, or any
other silent-install mechanism, and never describes installation as automatic.

Android's package installer owns final identity checks, user confirmation,
replacement, cancellation, and platform error presentation. Foreman treats a
canceled result as retryable. If Android accepts replacement, the old process
may disappear before it can report success; the next launch compares the
installed version to the durable target, records completion, and removes the
APK.

## App continuity and foreground reconciliation

Package replacement retains the application ID, signing certificate, app data,
Keystore keys, saved hosts, host-scoped preferences, notification preferences,
and pending safe update metadata. Those stores are outside the APK file and are
not rewritten by the updater. Normal `MainActivity.onResume` handling remains
the authoritative foreground boundary: it acknowledges attention, reconciles
badges and notification permission, publishes current presence, reconnects the
selected host, and then reconciles the installed Android update version. The
self-update flow creates no update-available notification and no duplicate
foreground-service notification.

An Android app update never changes, restarts, or uploads anything to the
connected Foreman server. A server update never downloads or launches an APK.
About shows the installed and available version for both components and labels
whether the available release applies to the server, Android app, or both.
Only Android exposes APK download and installer actions; web retains the shared
release terminology and discovery information.

## Physical-device acceptance checklist

Use an official release-signed current APK and a distinct higher
release-signed test APK. Do not create, move, or publish a release tag merely to
run this checklist.

- [ ] From a narrow-screen Android 6/7 device or emulator, confirm the verified
  APK goes directly to Android's installer and all controls remain reachable
  with large text.
- [ ] On Android 8+, deny **Install unknown apps** after reading Foreman's
  explanation; confirm About returns to **Verified and ready**, no installer
  opens, and no file is downloaded again.
- [ ] Grant the permission on a second attempt; confirm the pending verified
  APK resumes into exactly one Android installer screen with no duplicate
  explanation or download.
- [ ] Cancel Android's installer, return to Foreman, and reopen it; confirm the
  verified APK is reused and only one installer is visible.
- [ ] Interrupt the APK download by disabling networking or force-closing the
  app; relaunch, retry, confirm partial recovery, and verify normal Foreman use
  remained responsive throughout.
- [ ] Serve or locally inject an empty APK, duplicate/misnamed asset, changed
  checksum manifest, bad detached signature, wrong signing certificate,
  unsigned APK, mismatched package/version, same version, and lower version
  code; confirm each fails before installer handoff and invalid files are
  removed.
- [ ] Complete replacement with the official signing identity. Relaunch and
  confirm the installed/available versions, saved hosts, encrypted pairing,
  theme, notification preferences, remembered session, and server/app update
  labels are preserved.
- [ ] With attention and monitoring state present before replacement, confirm
  the first foreground reconciliation clears stale badges/attention once,
  restores intended monitoring only, and creates no duplicate notification.
- [ ] Repeat rotation/activity recreation while ready, on the permission
  screen, and after installer launch; confirm there are no duplicate downloads,
  permission dialogs, or installer launches.

Automated JVM tests cover the deterministic selection, trust, version, state,
permission, concurrency, and restoration policies with fakes or fixed
cryptographic fixtures. They cannot prove OEM installer UI, Android background
restrictions, package replacement, signing-lineage behavior on a physical
device, or OS notification/badge delivery; record those results separately in
the release acceptance evidence.
