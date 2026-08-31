# Secure Linux bootstrap installer

`scripts/install-foreman.sh` is the acquisition layer for installing Foreman on
a Linux host that does not already have Foreman's signed updater. It is
intended to stay small enough to inspect and has no staging, service-control,
health-check, or rollback implementation. After release verification it runs
the selected release's bundled `install.sh`, which remains authoritative for
all installation behavior.

## Selection and source

The bootstrapper accepts only the fixed `mkaltner/foreman` GitHub API and
download locations. With no argument it examines at most the newest 20
published releases and selects the greatest strict `vMAJOR.MINOR.PATCH` version
that is non-draft, non-prerelease, and complete. A newer draft, prerelease, or
incomplete release is skipped rather than weakening the stable channel. An
explicit `--version` must identify an exact complete stable release.

Complete means exactly one nonempty custom asset with each of these names and
no other custom asset:

```text
foreman-vMAJOR.MINOR.PATCH.apk
foreman-linux-vMAJOR.MINOR.PATCH.tar.gz
SHA256SUMS
SHA256SUMS.sig
foreman-release-cert.pem
```

Every metadata download URL must equal the canonical URL constructed from the
fixed repository, selected tag, and exact asset name. Redirects must remain
HTTPS and end at GitHub's API or established release-asset hosts. GitHub's
automatic repository archives and mutable branch contents are never release
payloads.

## Trust verification

GitHub metadata determines which candidate to inspect; it does not establish
artifact provenance. Before extracting or executing release content, the
bootstrapper:

1. converts the downloaded certificate to DER and requires SHA-256 fingerprint
   `80d479d1a8f9f038c6977a1cfb68a2b45c3117492c364620e48babebf1810ad3`;
2. uses that pinned certificate's public key to verify the detached OpenSSL
   SHA-256 signature over the exact `SHA256SUMS` bytes;
3. requires the signed manifest to contain exactly one checksum for the APK and
   versioned Linux archive, with no duplicate, renamed, path-containing, or
   extra entry;
4. computes and compares the Linux archive SHA-256;
5. validates every tar entry before extraction, rejecting absolute and
   noncanonical paths, traversal, duplicates, symbolic and hard links, devices,
   special entries, excessive file counts, and excessive expanded size;
6. requires the complete Foreman install layout and release metadata matching
   the selected version, official-release flag, and pinned signing identity.

Only then does it execute the verified bundled `install.sh`. Downloading a
certificate beside a signature is not trust: the fingerprint embedded in the
reviewed bootstrap script is the trust anchor. The raw bootstrap script itself
is delivered from the documented GitHub repository over HTTPS; users who want
to review that mutable entry point should use the download-then-inspect flow in
[`install.md`](install.md).

The Android application-signing identity is also the release-manifest signing
identity. Rotation therefore follows the two-release process in
[`server-updates.md`](server-updates.md): an already trusted release must first
ship reviewed trust for a new identity. A bootstrapper must not silently accept
a certificate supplied by a release or fetched from a second location.

## Failure and lifecycle behavior

The script requires Linux, `curl`, Python 3, OpenSSL, and Bash before contacting
the release API. Network errors, HTTP errors, rate limits, malformed API data,
untrusted redirects, incomplete releases, verification failures, and unsafe
archives terminate before `install.sh` runs. Provider and user-systemd checks
remain in the bundled installer, which performs them before replacing an
installation. This preserves #91 behavior: Codex-only, Claude-only, and
dual-provider hosts are supported; a host with neither provider fails with an
actionable message before installation mutation.

The bootstrapper sets a restrictive umask, creates a mode-`0700` directory
under `${TMPDIR:-/tmp}`, and removes it on normal exit, failure, `HUP`, `INT`, or
`TERM`. It never invokes `sudo`. Interrupted downloads and partial verification
state are disposable and are never reused.

Reinstall and update behavior is deliberately not reimplemented here. Running
the bootstrapper for the installed version delegates a safe same-version
reinstall to `install.sh`; selecting a newer or older explicit stable version
delegates the same staging, configuration/state preservation, systemd-user
activation, health check, and rollback boundaries to that versioned installer.
