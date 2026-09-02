# Foreman

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/foreman_logo.png" alt="Foreman logo" width="128">
  <br>
  <strong>A fast, self-hosted control plane for Codex and Claude Code.</strong>
</p>

Foreman gives Android and web clients direct control of coding-agent sessions
running on your own Linux host. It connects to the local Codex app-server and
uses the official Claude Agent SDK through a bounded host-side bridge. The
result is a dedicated, low-latency, LAN-first interface for monitoring work,
answering requests, and continuing sessions away from the terminal.

> A Foreman host needs an authenticated Codex or Claude Code CLI. Claude Code
> additionally requires Node.js 20 or newer and the pinned Agent SDK.

See the [documentation](docs/README.md), [product roadmap](ROADMAP.md), and
[latest release](https://github.com/mkaltner/foreman/releases/latest).

## Why Foreman?

Foreman is for people who want a dedicated, self-hosted interface to one or
more always-on Linux coding hosts. It provides:

- a unified view of active, waiting, failed, recent, and archived sessions;
- live prompts, steering, interrupts, approvals, and structured input;
- provider-aware Codex and Claude Code configuration and controls;
- responsive web and native Android clients with durable multi-host pairing;
- context and account-usage meters, search, grouping, pins, and themes;
- cross-client presence and privacy-safe background notifications;
- signed, recoverable Linux and Android update flows.

[Codex Remote](https://learn.chatgpt.com/docs/remote-connections) is the
first-party choice for controlling Codex through ChatGPT. Foreman is an
independent option for users who prefer a dedicated, self-hosted, LAN-first
control surface with direct access to their own hosts.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/pairing.png" alt="Pair Foreman with a Linux host" width="260"></td>
    <td align="center"><img src="docs/screenshots/session-list.png" alt="Browse active and recent coding-agent sessions" width="260"></td>
    <td align="center"><img src="docs/screenshots/live-session.png" alt="Monitor work in progress" width="260"></td>
  </tr>
  <tr>
    <td align="center">Pair with a Linux host</td>
    <td align="center">Browse live sessions</td>
    <td align="center">Monitor work in progress</td>
  </tr>
</table>

## Quick start

Install and authenticate at least one supported provider CLI: Codex (`codex`)
or Claude Code (`claude`). Then install the newest complete Foreman release:

```sh
curl -fsSL https://raw.githubusercontent.com/mkaltner/foreman/main/scripts/install-foreman.sh | sh
```

The rootless bootstrapper verifies the pinned release identity, signed checksum
manifest, Linux archive checksum, and archive layout before running the
release's installer. To inspect it first or install from a checkout, follow the
[installation guide](docs/install.md).

Verify the service, create a one-time pairing code, and print the browser URL:

```sh
foreman status
foreman pair
foreman web
```

Foreman runs as an enabled systemd user service. On a headless or SSH-managed
host, enable lingering so the user service starts at boot and survives the last
logout. Enabling the unit alone does not keep the user service manager alive.

```sh
sudo loginctl enable-linger "$USER"
loginctl show-user "$USER" -p Linger
```

The second command should report `Linger=yes`. This one-time host setting may
require administrator privileges; the Foreman installer itself never invokes
`sudo`.

Download the signed Android APK from the
[latest release](https://github.com/mkaltner/foreman/releases/latest), sideload
it, and pair it with the same short-lived code. The bundled web client normally
runs at `http://HOST:8766`; Android connects to port `8765` by default.

## How it works

```text
Android ── authenticated JSONL/TCP :8765 ─┐
                                          ├─ Foreman service ─┬─ Codex app-server
Browser ── HTTP + authenticated WS :8766 ─┘                   └─ Claude Agent SDK bridge
```

Both clients use the same protocol and provider-aware session model. Foreman
serves the web application and Android transport from one `foreman.service`;
there is no separate web server to manage. See the
[architecture](docs/architecture.md), [protocol](docs/protocol.md), and
[user guide](docs/user-guide.md) for details.

## Security

> [!CAUTION]
> Foreman authenticates its clients but does not terminate TLS. Keep it on a
> trusted LAN or private overlay such as Tailscale or WireGuard, or place the
> web listener behind a trusted HTTPS reverse proxy. Never expose its ports
> directly to the public internet.

Pairing codes are short-lived and single-use. The service stores token hashes,
Android protects its token with Android Keystore, and browser tokens remain in
browser-local storage. A paired client can control every enabled provider on
its host, so treat both network access and client devices as sensitive.

Linux and Android updates require signed official artifacts and explicit
confirmation. Read the [security overview](docs/security.md) for the complete
deployment boundary and links to the bootstrap, server-update, and APK trust
models.

## Project status

Foreman is stable on protocol v1. The 1.x compatibility contract preserves
documented wire behavior and user state across supported upgrades. Android is
currently distributed by sideloading, and some provider capabilities differ;
the clients expose only operations supported by the active provider and host.

- [Latest release](https://github.com/mkaltner/foreman/releases/latest)
- [Compatibility policy](docs/compatibility.md)
- [Known limitations](docs/user-guide.md#known-limitations)
- [Issue tracker](https://github.com/mkaltner/foreman/issues)
- [Product roadmap](ROADMAP.md)

## Documentation

Start with the [documentation index](docs/README.md). It links installation,
client usage, architecture, integrations, security, updates, compatibility,
release engineering, and historical acceptance records.

- [Documentation](docs/README.md)
- [License](LICENSE)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
