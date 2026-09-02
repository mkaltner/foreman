# Security overview

Foreman is a self-hosted control surface for coding agents running with access
to your development host. Its security model combines authenticated clients,
bounded protocol operations, provider-native authorization, and signed release
artifacts. It does not turn an untrusted network into a safe deployment.

## Deployment boundary

Foreman authenticates Android and web clients but does not terminate TLS. Run
it only on a trusted LAN or private overlay such as Tailscale or WireGuard, or
place the web listener behind a trusted HTTPS reverse proxy. Do not expose ports
`8765` or `8766` directly to the public internet.

When a reverse proxy changes the page origin, configure its exact HTTPS origin
in `FOREMAN_WEB_ORIGINS`. Foreman does not enable permissive wildcard CORS.

## Pairing and client tokens

Pairing codes expire after ten minutes, work once, and are throttled by source
address after failed guesses. Successful pairing creates a persistent device
token:

- the service stores only token hashes;
- Android protects its token with Android Keystore;
- browsers store tokens locally without equivalent Keystore protection;
- tokens are not placed in URLs, cookies, analytics, or normal application
  logs.

Paired-client listings expose a saved label, client type, pairing time, and live
connection state—not token values, hashes, pairing codes, or source addresses.
Any authenticated client can revoke another paired token, immediately removing
that client's access without changing sessions or repositories.

## Authorization and provider access

A paired client can control every enabled provider available on its host. Treat
the device, token, and network path as sensitive. Foreman does not expose a
general shell or Git-write API; it sends bounded provider operations and
validated answers to currently pending approvals or input requests.

Codex still executes tools and modifies files according to its selected access
profile. Claude executes tools according to its native permission mode. Foreman
does not translate unsupported Claude permission callbacks into Codex-style
approvals and never silently approves them.

Approval and structured-input endpoints accept only responses matching the
active runtime request. Safe activity rendering intentionally excludes hidden
reasoning and unrestricted tool output.

## Presence and notification privacy

Presence messages contain the local host identity and focused
provider/session pair needed to suppress duplicate alerts. Notifications use
generic attention text where commands, paths, or prompt content could otherwise
appear on a lock screen. Presence suppresses redundant delivery only while
another client visibly focuses the exact same session.

## Release and update trust

Release discovery alone never authorizes installation. Foreman stable releases
publish a Linux archive, Android APK, signed checksum manifest, detached
signature, and release certificate. Update paths pin the production signing
identity and verify the relevant archive or package before activation.

The Linux bootstrapper verifies the signing identity, manifest signature,
archive checksum, and safe extraction layout before invoking the release's
installer. See the [bootstrap installer trust model](bootstrap-installer.md).

Server updates accept no remote path, repository, URL, command, version, or
service name. They require an official newer compatible release, full-access
authorization, no active-work blockers, and a verified payload. An external
user-systemd helper owns activation, health checking, and rollback. See
[recoverable server updates](server-updates.md).

Android verifies the signed checksum, package name, APK signer, newer version
name, and increasing version code before handing the file to Android's system
installer. Final installation always requires operating-system confirmation.
See [Android APK self-update](android-apk-updates.md).

## State and operational data

Session transcripts remain provider/server data. Client-local preferences such
as saved hosts, grouping, pins, hidden IDs, appearance, and notification choices
are scoped locally and do not create a transcript cache. Bounded dashboard
snapshots and recent activity are operational views, not permanent audit logs.

For protocol and component boundaries, read the [architecture](architecture.md)
and [protocol v1](protocol.md).
