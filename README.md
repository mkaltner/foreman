# Foreman

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/foreman_logo.png" alt="Foreman logo" width="128">
  <br>
  <strong>Monitor. Orchestrate. Command.</strong>
</p>

Foreman is an early-alpha Linux user service with Android and responsive browser
clients for viewing and controlling local Codex sessions.

## Install

Clone Foreman and run the rootless, offline installer from the repository:

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
```

Then confirm the service and create a short-lived pairing code:

```sh
foreman status
foreman pair
foreman web
```

To update an existing installation:

```sh
cd foreman
git pull
./install.sh
```

Tagged Linux archives are available from [GitHub releases](https://github.com/mkaltner/foreman/releases)
as an alternative when a Git checkout is not convenient. Android is currently
installed by sideloading the signed release APK from the same release.

## Alpha status

Foreman is intentionally small and is ready for limited testing on trusted
networks. Breaking behavior and incompatible Codex changes may occur during the
alpha. Approval and structured-input requests must still be answered through
another Codex client.

The current alpha can:

- pair Android devices and browsers, each with a short-lived six-digit one-time
  code;
- list Git repositories below one configured root;
- list and read real local Codex threads, grouped by active and recent status;
- show user/assistant messages and compact command/tool activity;
- render common Markdown, including headings, lists, emphasis, links, and code;
- start or resume a thread, prompt it, steer an active turn, and interrupt it;
- choose Codex access, installed model, and supported reasoning effort per turn;
- attach up to four processed images to a prompt or steer;
- refresh the session list with a pull gesture, and archive or permanently delete
  inactive sessions with confirmation;
- share Codex's Desktop app-server when available so work and live status remain
  visible across clients;
- stream assistant deltas, tool activity, progress messages, and terminal status;
- optionally monitor active turns in an Android foreground service and notify
  when they finish or need attention;
- reconnect and refresh foregrounded sessions from Codex-authoritative state;
- reconnect browsers with fresh authoritative history without replaying
  prompts or controls;
- follow the system theme by default, with explicit light/dark modes and the
  same fixed accent palette across Android and web.

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

Foreman does not provide standalone shell, REST application, Git-operation,
approval, or structured-input endpoints. Codex may still run tools and modify
files according to the access level selected for a turn. The TCP and WebSocket
connections are authenticated but not encrypted; use them only on a trusted
private LAN or through a secure tunnel.

> [!CAUTION]
> Do not expose Foreman's TCP (`8765`) or web (`8766`) port directly to the public internet.
> Prefer a private overlay such as Tailscale or WireGuard, or a trusted LAN with
> firewall rules limited to your devices. Pairing uses a six-digit, one-time
> code that expires after ten minutes and throttles failed guesses per source
> address. Have the phone ready, run `foreman pair` only when needed, and finish
> pairing promptly—especially if the service is reachable beyond your LAN.

> [!NOTE]
> Foreman is alpha software built against Codex's evolving app-server interface.
> The current integration is verified with `codex-cli 0.145.0`; a future Codex
> update may require a matching Foreman update. Approval and structured-input
> requests are detected as waiting states but must still be answered in another
> Codex client.

## Linux

Requirements: Linux with user systemd, Python 3.10+, Git, and an authenticated
`codex` CLI. The payload includes its pinned `websockets` dependency, so the
installer doesn't need pip or Python venv support.

The service listens on raw TCP `0.0.0.0:8765` and HTTP/WebSocket
`0.0.0.0:8766`. Edit
`~/.config/foreman/foreman.env`, then run `foreman restart`, if the repository
root or listener should change.

## Web

Run `foreman pair`, open the URL printed by `foreman web`, and enter the host,
web port, pairing code, and a device name. The SPA supports sessions, live
conversation updates, prompt/steer/interrupt, dynamic access/model/reasoning
selection, processed image attachments, new sessions, archive/delete,
reconnect, and system/light/dark appearance with fixed accents.

The persistent browser device token is stored in `localStorage`, which is less
protected than Android Keystore. Foreman doesn't retain the one-time pairing
code or put tokens in URLs, cookies, or logs. Use **Disconnect and forget host**
before leaving a shared browser. Foreman doesn't terminate TLS; use a trusted
LAN, Tailscale/WireGuard, or a trusted reverse proxy with an explicit
`FOREMAN_WEB_ORIGINS` allowlist. The installed SPA is prebuilt, so runtime and
normal installation don't require Node.

## Android

Install the signed release APK from a tagged [GitHub release](https://github.com/mkaltner/foreman/releases).
For development, open [`android`](android) in a current Android Studio and build
the debug app. In Foreman enter the Linux host/IP, the output of `foreman pair`,
and a device name. A host without a port uses `8765`; run `foreman pair` again
for each additional device.

Enable **Notify for active turns** in the app settings to monitor turns that you
open or start. Android will ask for notification permission. While at least one
turn is active, Foreman shows a low-priority foreground-service notification and
keeps a separate authenticated connection to the Linux host. It posts an alert
when a turn completes, fails, is interrupted, or needs attention, then stops
monitoring that turn. This is on-demand rather than an always-running push
service, so it cannot discover turns started elsewhere after Foreman is fully
closed. Notification text intentionally omits session titles and transcript
content so private prompts do not appear on the lock screen.

Foreman attaches to Codex's shared Unix-socket app-server at
`$CODEX_HOME/app-server-control/app-server-control.sock`. That Desktop socket is
strictly attach-only: Foreman never removes, replaces, or launches a process on
it. If attachment is unavailable, Foreman launches an independent owned runtime
at `~/.local/state/foreman/codex-app-server.sock` and reports
`SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`. Set `FOREMAN_CODEX_SOCKET` to override
the attach target. Android still connects only to Foreman's authenticated TCP
service.

The compact row above the composer selects access level, model, and reasoning
effort. Access choices use Codex's installed permission profiles: ask the user,
use automatic approval review, or grant full access. Photo Picker images are
resized to a maximum 2048-pixel edge; each message accepts four images and at
most 8 MiB of encoded image data. Foreman detects approval/input waits but
cannot answer them yet; manual approvals remain available through another
client on the shared thread.

For local development, the installed-Codex proof and web checks are:

```sh
python3 scripts/codex_poc.py
python3 -m unittest discover -s tests -v
cd web && npm ci && npm test && npm run typecheck && npm run build
```

See [installation](docs/install.md), [architecture](docs/architecture.md), and
the [wire protocol](docs/protocol.md).

Version tags publish signed Android and Linux artifacts as described in the
[release guide](docs/releasing.md).
