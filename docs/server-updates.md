# Recoverable server updates

This document is the architecture and security decision for Foreman server
updates. It is normative for `foreman update`, the web client, Android, the
release workflow, and the external updater helper.

## Trust and release discovery

Foreman has one stable update channel: published, non-prerelease releases in
`mkaltner/foreman`. Discovery uses fixed GitHub API paths and accepts only a
strict `v<SemVer>` tag with the exact bounded asset set defined by the release
workflow. Clients never provide a repository, URL, tag, version, path, command,
or service name.

GitHub release metadata is discovery input, not artifact provenance. A server
release is installable only when all of these checks pass:

1. the release exposes exactly one nonempty Linux archive, `SHA256SUMS`,
   `SHA256SUMS.sig`, and `foreman-release-cert.pem` with canonical download
   URLs for the discovered tag;
2. the certificate's DER SHA-256 fingerprint equals the trust anchor in the
   currently installed `release.properties`;
3. OpenSSL validates the detached SHA-256 signature over the exact
   `SHA256SUMS` bytes with that certificate;
4. the signed manifest contains exactly one checksum for the Linux and Android
   release artifacts, and the downloaded archive matches its checksum;
5. bounded, traversal-safe extraction finds every required server file and no
   link, device, absolute path, duplicate path, or oversized payload;
6. the staged `release.properties` identifies the discovered version, is an
   official release build, retains the trusted signing certificate, and uses a
   protocol version supported by the running updater.

Any missing, duplicate, malformed, untrusted, or mismatched input fails closed.
Existing releases without the signature and certificate assets remain visible
as historical releases but are not installable by this mechanism.

The Android application-signing key is reused as the release-manifest signing
key so the workflow does not introduce another long-lived secret. The workflow
exports only its public certificate. Key rotation is a two-release operation:
the last release signed by the old key must ship an updater that trusts both the
old and reviewed new certificate fingerprints; only a later release may switch
the signing key. A lost or compromised key requires manual recovery from a
locally verified checkout.

## Authorization and bounded protocol

`update.check` and `update.status` follow the existing authenticated read
convention. `update.start` requires an authenticated client whose paired-device
access is `full`; legacy paired devices are migrated to `full`. A local CLI uses
a mode-`0600` Unix control socket and the same protocol handlers and update
manager as remote clients. The control socket accepts only the bounded local
operations documented here.

`update.start` accepts only an optional opaque idempotency key matching the
bounded protocol identifier grammar. The server chooses the release and
operation ID. Error codes and messages are fixed, bounded, and safe to show.
No response, progress event, diagnostic, or durable public projection contains
tokens, environment variables, release API bodies, unrestricted logs, archive
contents, or filesystem paths.

## State machine and concurrency

One update operation moves monotonically through these durable phases:

```text
downloading -> verifying -> staging -> activationScheduled
  -> activating -> restarting -> healthChecking -> succeeded
                                            \-> rollingBack -> rolledBack
                                                            \-> recoveryRequired

downloading/verifying/staging -> blocked | failed | interrupted
```

The operation record is atomically replaced in the private Foreman state
directory after every transition. It contains a generated operation ID,
versions, safe release identity, phase, bounded progress, timestamps, and a
fixed result/error code. Records never contain credentials or user/session
content. On startup, abandoned pre-activation phases become `interrupted`;
activation phases remain owned by the external helper and are reported as such.
An operation in `recoveryRequired` must be recovered before a new update can be
started, which prevents pruning or overwriting its retained backup.

A filesystem advisory lock plus the single nonterminal durable operation
serializes CLI, web, and Android attempts. The lock covers only operation
creation and the final activation handoff; network transfer, verification, and
staging do not hold it, so another process can inspect or recover durable state.
A repeated idempotency key returns the same operation. Every activation gets a
fresh lock, authorization check, and safety check after download and staging,
so a revoked client or newly active session cannot be raced.

## Active-session safety

An update is unsafe while any provider reports working, waiting, or stopping
work; while an approval is pending; or while structured input is pending. The
check reports only blocker categories (`workingSession`, `waitingSession`,
`pendingApproval`, `pendingInput`) and counts. It never returns transcript,
command, path, or prompt content.

Safety is checked before accepting the operation and immediately before the
external helper is scheduled. Remote initiation authorization is checked at
that server boundary and once more by the helper, from durable paired-device
state, immediately before the first replacement rename. The final server safety
check and the transition to `activationScheduled` share an in-process gate with
all operations that can start a Codex or Claude turn. Once activation is
scheduled, those operations fail with `updateActivating`; consequently a turn
cannot begin in the safety-check-to-helper window. A blocker at either safety
boundary refuses or ends the operation as `blocked`; revoked authorization
fails without activation. Foreman never interrupts work to make an update
proceed. Runtime continuity is not claimed: all sessions must be inactive.

## Staging, activation, and restart ownership

Downloads and extraction use mode-`0700` operation directories and bounded
file sizes. The server constructs an installed-layout directory beside the
current installation, validates imports and `--help`, and never runs release
shell fragments or `install.sh`.

The service then asks `systemd-run --user` to start the fixed external helper
installed at `~/.local/libexec/foreman-updater`. The helper, not the Foreman
process being replaced, owns activation, `daemon-reload`, restart, health
checking, cleanup, and rollback. Its command has fixed argument positions and
locally derived paths; no remote value becomes a command or path. The helper is
in a separate transient unit so restarting `foreman.service` cannot kill it.
Before activation, its bounded Python runtime is copied into the private
operation directory. The transient unit restarts after an unexpected process
failure, imports that retained runtime even when the installation directory is
between atomic renames, and treats any durable in-activation phase as a request
to roll back instead of trying to continue an ambiguous activation.
For a remote initiator, the helper holds the same paired-device state lock used
by revocation from its final full-access decision through the first activation
rename. Authorization and revocation therefore have one observable ordering:
a completed revocation always prevents an activation that has not yet begun.

The installer also enables `foreman-update-recovery.service`, a fixed oneshot
user unit started with the user's default target. On every boot it inspects only
the bounded latest operation record and exits without creating files unless the
phase is an activation phase. For an interrupted activation it loads the
operation-private helper runtime—even if the application directory is absent—
and invokes the same helper state machine. The recovery unit and
`foreman.service` have no ordering dependency: it can stop a concurrently
started Foreman, restore or finish the payload, restart it, and perform the same
version/protocol health check without a systemd ordering cycle.

Activation replaces the application directory, launcher, unit, and helper at
their existing transactional boundaries. Configuration and durable state are
outside those boundaries and are never copied, rewritten, or deleted. The
previous payload is retained as a bounded operation-specific backup until
success.

## Health checking, rollback, and recovery

After restart the helper polls the loopback `/health` endpoint with bounded
backoff and requires the target Foreman version and protocol. Success commits
the update and removes downloaded, staged, and backup payloads while retaining
the bounded operation record.

Activation or health failure triggers rollback: stop only `foreman.service`,
restore the previous payload, launcher, unit, and helper, reload the user unit,
restart it, and require the previous version to become healthy. A successful
rollback ends as `rolledBack` and explains that the old release is running. If
rollback or its health check fails, the record becomes `recoveryRequired` and
clients show `foreman update --recover` plus the manual recovery procedure
below. The rollback is restartable from its durable phase and retained backup,
including an interruption between the old-install and new-install directory
renames and a full host reboot. A recovery command only acts on the latest
recorded backup and never accepts a path.

Manual recovery, from a local terminal:

1. Run `foreman update --recover` and then `foreman status`.
2. If the helper cannot recover, inspect the operation with
   `foreman update --status`; do not delete its backup.
3. Download the named release or previous release on a trusted machine, verify
   its signed manifest as documented in `docs/releasing.md`, copy the verified
   archive locally, and run its `install.sh` from a terminal.
4. If service activation still fails, use `journalctl --user -u
   foreman.service`; never paste tokens or `foreman.env` into an issue.

## Client continuity

The operation ID and result survive the expected disconnect. Web and Android
store only the operation ID in host-scoped client storage, use their existing
bounded reconnect loops, retain the current route/screen and remembered open
session, and query `update.status` after authentication. Switching or forgetting
a host cannot expose or reuse another host's operation ID. Duplicate buttons
are disabled while an operation is nonterminal.

An updater may install only a strictly newer stable server release using the
same protocol version. Downgrades, same-version replacement, prereleases,
development targets, and protocol changes are rejected. A future protocol
migration requires a separately reviewed compatibility design and an updater
capable of understanding both versions.

## Auditability

Fixed diagnostic categories record discovery, start, verification failure,
activation, rollback, and completion. Durable records contain only safe fields.
Tests inject fake release endpoints, temporary installation roots, and mocked
service control. Automated tests never update the live development service.
