# Foreman

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/foreman_logo.png" alt="Foreman logo" width="128">
  <br>
  <strong>A fast, self-hosted control plane for Codex.</strong>
</p>

Foreman provides lightweight Android and web interfaces for monitoring and
controlling Codex sessions running on Linux hosts. It connects directly to the
local Codex app-server and is designed for fast, LAN-first operation.

## Why Foreman?

Foreman is for people who want a dedicated Codex monitoring interface, fast
local interaction, and direct control of an always-on Linux host. It provides
self-hosted Android and browser access, visibility across active and recent
sessions, and controls for prompting, steering, interrupting, model, reasoning
effort, and access level.

## Foreman and Codex Remote

OpenAI's [Codex Remote](https://learn.chatgpt.com/docs/remote-connections) is the
first-party option for controlling Codex from the ChatGPT mobile app. It is a
good fit for people who want remote control integrated with the broader ChatGPT
experience.

Foreman is aimed at users who prefer a dedicated, self-hosted, LAN-first
interface and want direct access to their own Linux Codex host. It is designed
for low-latency local use and does not attempt to replace every first-party
Remote capability.

## Install

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
```

Then verify the user service and create a short-lived pairing code:

```sh
foreman status
foreman pair
```

To update:

```sh
cd foreman
git pull
./install.sh
```

Foreman requires Linux with user systemd, Python 3.10+, Git, and an authenticated
`codex` CLI. Its pinned Python dependency is included, so installation does not
require pip, a Python virtual environment, root access, or network access.
Tagged Linux archives and signed Android APKs are available from
[GitHub releases](https://github.com/mkaltner/foreman/releases) as an alternative.

## Features

- Pair clients with a short-lived, six-digit, one-time code.
- Reconnect with persistent token authentication.
- Discover Git repositories and browse active and recent Codex sessions.
- List, read, start, resume, archive, and delete sessions.
- Prompt, steer, and interrupt active work.
- Stream live status, assistant deltas, tool activity, and progress updates.
- Select installed models, supported reasoning efforts, and Codex access levels.
- Attach up to four JPEG, PNG, or WebP images to a prompt or steer.
- Notify Android devices when monitored turns finish or need attention.
- Follow light, dark, or system themes with a configurable accent color.
- Use dedicated Android and responsive web clients.
- Install as a rootless user-level systemd service without pip or a Python venv.

## Android

Download and sideload the signed APK from the matching tagged
[GitHub release](https://github.com/mkaltner/foreman/releases). Run `foreman pair`
on the Linux host, then enter the host name or IP address, pairing code, and a
device name in the app. Port `8765` is used when no port is given. Run
`foreman pair` again for each additional device.

For development builds, open [`android`](android) in a current Android Studio.
Android protects the persistent device token with Android Keystore.

## Web

Open `http://HOST:8766` on a trusted network. Run `foreman pair`, enter the
six-digit code in the browser, and Foreman stores the resulting device token in
that browser profile for future connections. Current Chrome, Firefox, and Edge
releases are the target browsers.

Browser storage does not provide Android Keystore protection. Use a dedicated,
trusted browser profile and clear the Foreman site data to remove its token.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/pairing.png" alt="Pair Foreman with a Linux host" width="260"></td>
    <td align="center"><img src="docs/screenshots/session-list.png" alt="Browse active and recent Codex sessions" width="260"></td>
    <td align="center"><img src="docs/screenshots/live-session.png" alt="Monitor a working Codex session" width="260"></td>
  </tr>
  <tr>
    <td align="center">Pair with a Linux host</td>
    <td align="center">Browse live sessions</td>
    <td align="center">Monitor work in progress</td>
  </tr>
</table>

## Architecture

```text
Android ─┐
         ├─ Foreman service ── Codex app-server
Browser ─┘
```

- Android uses authenticated JSONL over TCP.
- Browser clients use HTTP for static assets and WebSocket for control.
- Foreman connects to Codex over a Unix-socket WebSocket.

## Security

> [!CAUTION]
> Foreman transport is authenticated but not necessarily encrypted. Use a
> trusted LAN, Tailscale, WireGuard, or a trusted reverse proxy that provides
> encryption. Do not expose Foreman ports directly to the public internet.

Pairing codes expire after ten minutes and are valid for one use. Failed guesses
are throttled per source address. The service stores only device-token hashes;
Android protects its token with Android Keystore, while browser tokens remain in
the local browser profile without equivalent Keystore protection.

A paired client can control Codex with the access level selected for a turn, so
treat its token and network access as sensitive. Foreman does not expose a
standalone shell, Git-write, approval, or structured-input endpoint, but Codex
can still run tools and modify files according to its selected access profile.

## Codex Desktop runtime

Codex Desktop's default control socket is attach-only. Foreman never removes or
replaces that socket and never launches a process on it. When shared attachment
is unavailable, Foreman uses its own fallback socket and reports
`SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`.

Fallback mode does not provide live co-presence with Codex Desktop. Stopping
Foreman closes its attachment or stops only the fallback process it owns; it
does not stop Desktop's Codex runtime.

## Known limitations

- Approval requests cannot yet be answered through Foreman.
- Structured user-input requests are not yet supported.
- Direct TCP and HTTP transport is not encrypted.
- Desktop live co-presence requires successful shared-socket attachment.
- Android distribution is currently sideloaded.
- Multi-host aggregation is not implemented.
- Web support remains experimental during the alpha.

## Alpha status

Foreman is usable but still evolving. Breaking protocol or configuration
changes may occur, Android is currently distributed by sideloading, and web
support remains experimental. Foreman is not production-ready. Issues and
feedback are welcome in the [GitHub issue tracker](https://github.com/mkaltner/foreman/issues).

## Documentation

- [Installation guide](docs/install.md)
- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [Release guide](docs/releasing.md)
