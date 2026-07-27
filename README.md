# Foreman

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/foreman_logo.png" alt="Foreman logo" width="128">
  <br>
  <strong>Monitor. Orchestrate. Command.</strong>
</p>

Foreman is a small Linux user service and Android app for viewing and controlling
local Codex sessions over a direct TCP connection.

The current prototype can:

- pair one Android device with a short-lived one-time key;
- list Git repositories below one configured root;
- list and read real local Codex threads;
- show user/assistant messages and compact command/tool activity;
- start or resume a thread, prompt it, steer an active turn, and interrupt it;
- stream assistant deltas and terminal status;
- optionally monitor active turns in an Android foreground service and notify
  when they finish or need attention;
- reconnect by reloading Codex-authoritative state.

It does not expose a shell, HTTP server, Git writes, approvals, or structured
input. The TCP connection is authenticated but not encrypted; use it only on a
trusted private LAN or through a secure tunnel.

> [!CAUTION]
> Do not expose Foreman's TCP port (`8765`) directly to the public internet.
> Prefer a private overlay such as Tailscale or WireGuard, or a trusted LAN with
> firewall rules limited to your devices. Pairing uses a six-digit, one-time
> code that expires after ten minutes and throttles failed guesses per source
> address. Have the phone ready, run `foreman pair` only when needed, and finish
> pairing promptly—especially if the service is reachable beyond your LAN.

## Linux

Requirements: Linux with user systemd, Python 3.10+, Git, and an authenticated
`codex` CLI.

```sh
./install.sh
foreman status
foreman pair
```

The service listens on `0.0.0.0:8765`. Edit
`~/.config/foreman/foreman.env`, then run `foreman restart`, if the repository
root or listener should change.

## Android

Open [`android`](android) in current Android Studio, build the debug app, and
install it on the phone. In Foreman enter the Linux host/IP, the output of
`foreman pair`, and a device name. A host without a port uses `8765`.

Enable **Notify for active turns** in the app settings to monitor turns that you
open or start. Android will ask for notification permission. While at least one
turn is active, Foreman shows a low-priority foreground-service notification and
keeps a separate authenticated connection to the Linux host. It posts an alert
when a turn completes, fails, is interrupted, or needs attention, then stops
monitoring that turn. This is on-demand rather than an always-running push
service, so it cannot discover turns started elsewhere after Foreman is fully
closed. Notification text intentionally omits session titles and transcript
content so private prompts do not appear on the lock screen.

For local development, the installed-Codex proof is:

```sh
python3 scripts/codex_poc.py
python3 -m unittest discover -s tests -v
```

See [installation](docs/install.md), [architecture](docs/architecture.md), and
the [wire protocol](docs/protocol.md).

Version tags publish signed Android and Linux artifacts as described in the
[release guide](docs/releasing.md).
