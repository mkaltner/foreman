# Foreman

Foreman is a small Linux user service and Android app for viewing and controlling
local Codex sessions over a direct TCP connection.

The current prototype can:

- pair one Android device with a short-lived one-time key;
- list Git repositories below one configured root;
- list and read real local Codex threads;
- show user/assistant messages and compact command/tool activity;
- start or resume a thread, prompt it, steer an active turn, and interrupt it;
- stream assistant deltas and terminal status;
- reconnect by reloading Codex-authoritative state.

It does not expose a shell, HTTP server, Git writes, approvals, or structured
input. The TCP connection is authenticated but not encrypted; use it only on a
trusted private LAN or through a secure tunnel.

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

For local development, the installed-Codex proof is:

```sh
python3 scripts/codex_poc.py
python3 -m unittest discover -s tests -v
```

See [installation](docs/install.md), [architecture](docs/architecture.md), and
the [wire protocol](docs/protocol.md).
